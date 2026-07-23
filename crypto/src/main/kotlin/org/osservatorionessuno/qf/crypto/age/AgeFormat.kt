package org.osservatorionessuno.qf.crypto.age

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64

/** Thrown on any malformed/oversized age input. Never an unchecked panic. */
class AgeFormatException(message: String) : Exception(message)

/** One age recipient stanza: `-> TYPE args...` + a base64 body. */
class AgeStanza(val type: String, val args: List<String>, val body: ByteArray)

/** Wraps the file key to one or more stanzas (X25519, scrypt, a custom KeyVault, …). */
interface AgeRecipient {
    fun wrap(fileKey: ByteArray): List<AgeStanza>
}

/** Unwraps the file key from a stanza set; returns null if none match this identity. */
interface AgeIdentity {
    fun unwrap(stanzas: List<AgeStanza>): ByteArray?
}

/** An [AgeIdentity] holding secret material that can (and should) be zeroed after use. */
interface DestroyableAgeIdentity : AgeIdentity {
    /** Zero the secret when the action using this identity ends; unusable afterwards. */
    fun destroy()
}

internal class ParsedHeader(val stanzas: List<AgeStanza>, val mac: ByteArray, val headerBytes: Long)

/**
 * age v1 header (de)serialization + MAC. Implements the "Header" section of the
 * spec (https://github.com/C2SP/C2SP/blob/main/age.md). Hand-written but tiny and
 * bounded: parsing enforces hard limits so a hostile file can't OOM or throw an
 * undeclared exception, and [verifyMac] is a real constant-time HMAC check.
 */
internal object AgeFormat {
    const val VERSION = "age-encryption.org/v1"        // spec: header first line
    const val FILE_KEY_SIZE = 16                        // spec: the file key is 16 bytes
    const val PAYLOAD_NONCE_SIZE = 16                   // spec "Payload": 16-byte nonce before the STREAM

    // Defensive bounds for untrusted input.
    private const val MAX_LINE = 4096
    private const val MAX_STANZAS = 64
    private const val MAX_STANZA_BODY_B64 = 1 shl 20 // 1 MiB of base64 per stanza

    // spec: all base64 is canonical RFC 4648 (standard alphabet) without padding.
    private val b64e = Base64.getEncoder().withoutPadding()
    private val b64d = Base64.getDecoder()

    private fun b64decode(s: String): ByteArray =
        try { b64d.decode(s + "=".repeat((4 - s.length % 4) % 4)) }
        catch (e: IllegalArgumentException) { throw AgeFormatException("invalid base64") }

    /** Parse the header, leaving [input] positioned at the first payload byte (the nonce). */
    fun parse(input: InputStream): ParsedHeader {
        var count = 0L
        fun line(): String {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) { if (sb.isEmpty()) throw AgeFormatException("truncated header"); break }
                count++
                if (b == '\n'.code) break
                sb.append(b.toChar())
                if (sb.length > MAX_LINE) throw AgeFormatException("header line too long")
            }
            return sb.toString()
        }

        if (line() != VERSION) throw AgeFormatException("not an age v1 file")
        val stanzas = ArrayList<AgeStanza>()
        var l = line()
        // spec "Recipient stanzas": each is "-> " type SP-separated-args, then a
        // base64 body wrapped at 64 columns and terminated by a short (<64) line.
        while (l.startsWith("-> ")) {
            if (stanzas.size >= MAX_STANZAS) throw AgeFormatException("too many stanzas")
            val parts = l.removePrefix("-> ").split(" ")
            val type = parts[0]
            if (type.isEmpty()) throw AgeFormatException("empty stanza type")
            val args = parts.drop(1)
            val b64 = StringBuilder()
            while (true) {
                val bl = line()
                if (bl.length > 64) throw AgeFormatException("stanza body line too long")
                b64.append(bl)
                if (b64.length > MAX_STANZA_BODY_B64) throw AgeFormatException("stanza body too large")
                if (bl.length < 64) break // a short (possibly empty) line terminates the body
            }
            stanzas.add(AgeStanza(type, args, b64decode(b64.toString())))
            l = line()
        }
        if (!l.startsWith("---")) throw AgeFormatException("missing header footer")
        val macB64 = l.removePrefix("---").trim()
        if (macB64.isEmpty()) throw AgeFormatException("empty header MAC")
        return ParsedHeader(stanzas, b64decode(macB64), count)
    }

    /** Canonical header bytes up to and including "---" — exactly what the MAC covers. */
    private fun headerWithoutMac(stanzas: List<AgeStanza>): ByteArray {
        val sb = StringBuilder()
        sb.append(VERSION).append('\n')
        for (s in stanzas) {
            sb.append("-> ").append(s.type)
            if (s.args.isNotEmpty()) sb.append(' ').append(s.args.joinToString(" "))
            sb.append('\n')
            val body = b64e.encodeToString(s.body)
            var i = 0
            while (i < body.length) {
                val end = minOf(i + 64, body.length)
                sb.append(body, i, end).append('\n')
                i = end
            }
            if (body.length % 64 == 0) sb.append('\n') // terminating short/empty line
        }
        sb.append("---")
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    // spec "Header": HMAC-SHA-256(key = HKDF[<empty>, "header"](file key)) over the
    // header up to and including the "---" footer mark.
    fun mac(fileKey: ByteArray, stanzas: List<AgeStanza>): ByteArray =
        AgePrimitives.hmacSha256(
            AgePrimitives.hkdf(null, fileKey, "header".toByteArray(), 32),
            headerWithoutMac(stanzas),
        )

    fun verifyMac(fileKey: ByteArray, header: ParsedHeader) {
        if (!constantTimeEquals(header.mac, mac(fileKey, header.stanzas))) {
            throw AgeFormatException("header MAC verification failed")
        }
    }

    /** Write a full header for [fileKey]. spec footer: "--- " + base64(header MAC). */
    fun write(out: OutputStream, stanzas: List<AgeStanza>, fileKey: ByteArray) {
        out.write(headerWithoutMac(stanzas))
        out.write(' '.code)
        out.write(b64e.encodeToString(mac(fileKey, stanzas)).toByteArray(Charsets.US_ASCII))
        out.write('\n'.code)
    }

    fun encodeArg(bytes: ByteArray): String = b64e.encodeToString(bytes)
    fun decodeArg(arg: String): ByteArray = b64decode(arg)
    fun serializeHeader(stanzas: List<AgeStanza>, fileKey: ByteArray): ByteArray =
        ByteArrayOutputStream().also { write(it, stanzas, fileKey) }.toByteArray()

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].toInt() xor b[i].toInt())
        return r == 0
    }
}
