package org.osservatorionessuno.libmvt.android.parsers

import org.osservatorionessuno.libmvt.common.Utils
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.security.GeneralSecurityException
import java.security.spec.KeySpec
import java.util.Arrays
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min
import kotlin.text.Charsets.UTF_8

/** Utilities to parse Android backup (.ab) files and extract SMS/MMS messages. */
@Suppress("DEPRECATION")
object BackupParser {

    // ---------- Exceptions ----------
    open class AndroidBackupParsingException @JvmOverloads constructor(
        msg: String,
        t: Throwable? = null
    ) : Exception(msg, t)

    class InvalidBackupPassword : AndroidBackupParsingException("Invalid backup password")

    // ---------- Public API ----------

    /** Parse an .ab file and return the inner TAR bytes (decrypted & optionally decompressed). */
    @Throws(AndroidBackupParsingException::class)
    @JvmStatic
    fun parseBackupFile(data: ByteArray, password: String?): ByteArray {
        val `in` = ByteArrayInputStream(data)

        val magic = String(readLineBytes(`in`), UTF_8)
        if (magic != "ANDROID BACKUP") {
            throw AndroidBackupParsingException("Invalid file header")
        }

        val version = String(readLineBytes(`in`), UTF_8).trim().toInt()
        val compressed = String(readLineBytes(`in`), UTF_8).trim() == "1"
        val encryption = String(readLineBytes(`in`), UTF_8).trim()

        var rest = readToEnd(`in`)
        if (encryption != "none") {
            rest = decryptBackupData(rest, password, encryption, version)
        }
        if (compressed) {
            try {
                InflaterInputStream(ByteArrayInputStream(rest)).use { inf ->
                    rest = readToEnd(inf)
                }
            } catch (ex: IOException) {
                throw AndroidBackupParsingException("Impossible to decompress the backup file", ex)
            }
        }
        return rest
    }

    /** Scan a TAR blob for telephony SMS/MMS backup JSON chunks and return parsed records. */
    @Throws(IOException::class)
    @JvmStatic
    fun parseTarForSms(tarData: ByteArray): List<Map<String, Any?>> {
        val res = ArrayList<Map<String, Any?>>()
        ByteArrayInputStream(tarData).use { bin ->
            val tar = SimpleTarReader(bin)
            var entry: TarEntry?
            while (tar.nextEntry().also { entry = it } != null) {
                val name = entry!!.name
                if (name.startsWith("apps/com.android.providers.telephony/d_f/") &&
                    (name.endsWith("_sms_backup") || name.endsWith("_mms_backup"))
                ) {
                    val content = tar.readEntryContent(entry!!)
                    res.addAll(parseSmsFile(content))
                } else {
                    // Skip content for non-matching entries
                    tar.skipEntryContent(entry!!)
                }
            }
        }
        return res
    }

    /** Parse a single compressed (deflate) JSON file containing SMS/MMS records. */
    @Throws(IOException::class)
    @JvmStatic
    fun parseSmsFile(data: ByteArray): List<Map<String, Any?>> {
        InflaterInputStream(ByteArrayInputStream(data)).use { inf ->
            val jsonBytes = readToEnd(inf)
            val text = String(jsonBytes, UTF_8)
            val arr = JSONArray(text)
            val res = ArrayList<Map<String, Any?>>()
            val urlRx = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE)

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val r = jsonToMutableMap(obj)

                // Normalize MMS body to "body"
                if (r.containsKey("mms_body")) {
                    r["body"] = r.remove("mms_body")
                }

                val body = r["body"] as? String
                if (body != null) {
                    val m: Matcher = urlRx.matcher(body)
                    val links = ArrayList<String>()
                    while (m.find()) links.add(m.group())
                    if (links.isNotEmpty() || body.trim().isEmpty()) {
                        r["links"] = links
                    }
                }

                val date = r["date"]?.toString()?.toLongOrNull() ?: 0L
                r["isodate"] = Utils.toIso(date)

                val sent = r["date_sent"]?.toString()?.toLongOrNull() ?: 0L
                r["direction"] = if (sent > 0) "sent" else "received"

                res.add(r)
            }
            return res
        }
    }

    // ---------- Crypto helpers for .ab decryption ----------

    @Throws(AndroidBackupParsingException::class)
    private fun decryptBackupData(enc: ByteArray, password: String?, algo: String, version: Int): ByteArray {
        if (algo != "AES-256") throw AndroidBackupParsingException("Encryption algorithm not implemented")
        if (password == null) throw InvalidBackupPassword()

        val `in` = ByteArrayInputStream(enc)
        var userSalt = String(readLineBytes(`in`), UTF_8).toByteArray(UTF_8)
        var checksumSalt = String(readLineBytes(`in`), UTF_8).toByteArray(UTF_8)
        val rounds = String(readLineBytes(`in`), UTF_8).trim().toInt()
        var userIv = String(readLineBytes(`in`), UTF_8).toByteArray(UTF_8)
        var masterKeyBlob = String(readLineBytes(`in`), UTF_8).toByteArray(UTF_8)
        val encrypted = readToEnd(`in`)

        userSalt = hexToBytes(String(userSalt, UTF_8))
        checksumSalt = hexToBytes(String(checksumSalt, UTF_8))
        userIv = hexToBytes(String(userIv, UTF_8))
        masterKeyBlob = hexToBytes(String(masterKeyBlob, UTF_8))

        val (masterKey, masterIv) = decryptMasterKey(password, userSalt, userIv, rounds, masterKeyBlob, version, checksumSalt)
        try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(masterKey, "AES"), IvParameterSpec(masterIv))
            return cipher.doFinal(encrypted)
        } catch (ex: GeneralSecurityException) {
            throw AndroidBackupParsingException("Failed to decrypt", ex)
        }
    }

    @Throws(AndroidBackupParsingException::class)
    private fun decryptMasterKey(
        password: String,
        userSalt: ByteArray,
        userIv: ByteArray,
        rounds: Int,
        masterBlob: ByteArray,
        version: Int,
        checksumSalt: ByteArray
    ): Pair<ByteArray, ByteArray> {
        try {
            val kf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            var spec: KeySpec = PBEKeySpec(password.toCharArray(), userSalt, rounds, 256)
            val key = kf.generateSecret(spec).encoded

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(userIv))
            val decrypted = cipher.doFinal(masterBlob)

            val `in` = ByteArrayInputStream(decrypted)
            val ivLen = `in`.read()
            val masterIv = readExact(`in`, ivLen)
            val keyLen = `in`.read()
            val masterKey = readExact(`in`, keyLen)
            val checksumLen = `in`.read()
            val checksum = readExact(`in`, checksumLen)

            val hmacMk = if (version > 1) toUtf8Bytes(masterKey) else masterKey
            spec = PBEKeySpec(String(hmacMk, UTF_8).toCharArray(), checksumSalt, rounds, 256)
            val calcChecksum = kf.generateSecret(spec).encoded
            if (!Arrays.equals(calcChecksum, checksum)) throw InvalidBackupPassword()

            return Pair(masterKey, masterIv)
        } catch (ex: GeneralSecurityException) {
            throw AndroidBackupParsingException("Failed to decrypt", ex)
        }
    }

    // Behavior preserved from your original
    private fun toUtf8Bytes(input: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        for (b in input) {
            val ub = b.toInt() and 0xff
            if (ub < 0x80) {
                bos.write(ub)
            } else {
                bos.write(0xef or (ub shr 12))
                bos.write(0xbc or ((ub shr 6) and 0x3f))
                bos.write(0x80 or (ub and 0x3f))
            }
        }
        return bos.toByteArray()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val out = ByteArray(len / 2)
        var i = 0
        var j = 0
        while (i < len) {
            val hi = Character.digit(hex[i], 16)
            val lo = Character.digit(hex[i + 1], 16)
            out[j++] = ((hi shl 4) + lo).toByte()
            i += 2
        }
        return out
    }

    // ---------- Minimal JSON helper ----------

    private fun jsonToMutableMap(obj: JSONObject): MutableMap<String, Any?> {
        val map = HashMap<String, Any?>()
        val it = obj.keys()
        while (it.hasNext()) {
            val k = it.next()
            val v = obj.get(k)
            map[k] = when (v) {
                is JSONObject -> jsonToMutableMap(v)
                is JSONArray  -> jsonArrayToList(v)
                JSONObject.NULL -> null
                else -> v
            }
        }
        return map
    }

    private fun jsonArrayToList(arr: JSONArray): List<Any?> {
        val list = ArrayList<Any?>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.get(i)
            list.add(
                when (v) {
                    is JSONObject -> jsonToMutableMap(v)
                    is JSONArray  -> jsonArrayToList(v)
                    JSONObject.NULL -> null
                    else -> v
                }
            )
        }
        return list
    }

    // ---------- Tiny TAR reader (USTAR + GNU longname) ----------

    private const val BLOCK = 512

    private data class TarHeader(
        val name: String,
        val size: Long,
        val typeFlag: Char,
        val isEmpty: Boolean
    )

    data class TarEntry(
        var name: String,
        val size: Long,
        val typeFlag: Char
    )

    private class SimpleTarReader(private val input: InputStream) {
        private var pendingLongName: String? = null

        fun nextEntry(): TarEntry? {
            while (true) {
                val hdr = readHeader() ?: return null
                if (hdr.isEmpty) return null

                var name = hdr.name
                if (pendingLongName != null) {
                    name = pendingLongName!!
                    pendingLongName = null
                }

                // GNU long name entry ('L'): read content as filename for the *next* header.
                if (hdr.typeFlag == 'L') {
                    val data = readContent(hdr.size)
                    val s = String(data, UTF_8).trimEnd('\u0000')
                    pendingLongName = s
                    alignToBlock(hdr.size)
                    // Continue loop to read the real next header
                    continue
                }

                val entry = TarEntry(name = name, size = hdr.size, typeFlag = hdr.typeFlag)

                // For non-regular files, we still need to skip their content (if any).
                // We'll let caller decide whether to read/skip.
                return entry
            }
        }

        fun readEntryContent(entry: TarEntry): ByteArray {
            val data = readContent(entry.size)
            alignToBlock(entry.size)
            return data
        }

        fun skipEntryContent(entry: TarEntry) {
            skipFully(entry.size)
            alignToBlock(entry.size)
        }

        private fun readHeader(): TarHeader? {
            val block = ByteArray(BLOCK)
            val n = readFully(block, 0, BLOCK)
            if (n == 0) return null // EOF
            if (n < BLOCK) throw IOException("Short TAR header")

            // All-zero block indicates end of archive
            var allZero = true
            for (b in block) if (b.toInt() != 0) { allZero = false; break }
            if (allZero) return TarHeader("", 0, '0', true)

            val rawName = extractString(block, 0, 100)
            val sizeOct = extractString(block, 124, 12).trim()
            val type = (block[156].toInt() and 0xff).toChar()
            val prefix = extractString(block, 345, 155)

            val name = if (prefix.isNotEmpty()) "$prefix/$rawName" else rawName
            val size = parseOctal(sizeOct)

            // Advance to next step; content will be handled by caller.
            return TarHeader(name = name, size = size, typeFlag = type, isEmpty = false)
        }

        private fun parseOctal(s: String): Long {
            var sum = 0L
            for (ch in s) {
                if (ch < '0' || ch > '7') break
                sum = (sum shl 3) + (ch.code - '0'.code)
            }
            return sum
        }

        private fun extractString(buf: ByteArray, off: Int, len: Int): String {
            var end = off + len
            // Stop at first NUL
            for (i in off until off + len) {
                if (buf[i].toInt() == 0) { end = i; break }
            }
            return String(buf, off, end - off, Charsets.US_ASCII).trim()
        }

        private fun readContent(size: Long): ByteArray {
            if (size > Int.MAX_VALUE) throw IOException("Entry too large")
            val out = ByteArray(size.toInt())
            readFully(out, 0, out.size)
            return out
        }

        private fun skipFully(size: Long) {
            var rem = size
            val buf = ByteArray(8192)
            while (rem > 0) {
                val toRead = if (rem > buf.size) buf.size else rem.toInt()
                val n = input.read(buf, 0, toRead)
                if (n < 0) throw IOException("Unexpected EOF while skipping")
                rem -= n
            }
        }

        private fun alignToBlock(size: Long) {
            val pad = ((BLOCK - (size % BLOCK)) % BLOCK).toInt()
            if (pad > 0) {
                val skip = ByteArray(pad)
                readFully(skip, 0, pad)
            }
        }

        private fun readFully(dst: ByteArray, off: Int, len: Int): Int {
            var read = 0
            var o = off
            var l = len
            while (read < len) {
                val n = input.read(dst, o, l)
                if (n < 0) break
                read += n
                o += n
                l -= n
            }
            if (read == 0 && len > 0) return 0
            if (read < len) throw IOException("Unexpected EOF")
            return read
        }
    }

    // ---------- Small I/O helpers (Android-safe, no Java 9+ APIs) ----------

    /** Reads a line (without trailing '\n'). */
    private fun readLineBytes(`in`: InputStream): ByteArray {
        val bos = ByteArrayOutputStream()
        while (true) {
            val b = `in`.read()
            if (b == -1 || b == '\n'.code) break
            bos.write(b)
        }
        return bos.toByteArray()
    }

    /** Read the rest of an InputStream fully into a ByteArray. */
    private fun readToEnd(`in`: InputStream): ByteArray {
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = `in`.read(buf)
            if (n == -1) break
            bos.write(buf, 0, n)
        }
        return bos.toByteArray()
    }

    /** Read exactly n bytes from the stream (throws if not enough). */
    @Throws(IOException::class)
    private fun readExact(`in`: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = `in`.read(out, read, n - read)
            if (r < 0) throw IOException("Unexpected EOF")
            read += r
        }
        return out
    }
}
