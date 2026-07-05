package org.osservatorionessuno.qf.crypto.age

import org.osservatorionessuno.qf.crypto.RandomAccessData
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Decrypted view of an age payload: random-access [read] (for ZIP seek) and
 * [stream] (for the memory-bounded sequential read). One 64 KiB chunk is cached.
 *
 * [open] parses the header, unwraps the file key with the given identities, and
 * **verifies the header MAC** before exposing any plaintext.
 */
class AgePayload private constructor(
    private val data: RandomAccessData,
    private val streamKey: ByteArray,
    private val cipherStart: Long,
    private val numChunks: Long,
    private val lastCipherLen: Int,
    val length: Long,
) {
    private var cachedIdx = -1L
    private var cached: ByteArray? = null

    /** Read [len] decrypted bytes at plaintext offset [off]. */
    fun read(off: Long, len: Int): ByteArray {
        val out = ByteArray(len)
        var done = 0
        var p = off
        while (done < len) {
            val chunk = chunk(p / PLAIN)
            val within = (p % PLAIN).toInt()
            val n = minOf(len - done, chunk.size - within)
            System.arraycopy(chunk, within, out, done, n)
            done += n; p += n
        }
        return out
    }

    /** Stream [len] decrypted bytes at [off], decrypting chunks on demand. */
    fun stream(off: Long = 0, len: Long = length): InputStream = object : InputStream() {
        private var p = off
        private val end = off + len
        override fun read(): Int {
            val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
        }
        override fun read(b: ByteArray, o: Int, l: Int): Int {
            if (p >= end) return -1
            val chunk = chunk(p / PLAIN)
            val within = (p % PLAIN).toInt()
            val n = minOf(l, minOf((end - p).toInt(), chunk.size - within))
            System.arraycopy(chunk, within, b, o, n); p += n; return n
        }
    }

    private fun chunk(idx: Long): ByteArray {
        cached?.let { if (idx == cachedIdx) return it }
        val last = idx == numChunks - 1
        val clen = if (last) lastCipherLen else CIPHER_I
        val ct = ByteArray(clen)
        readFully(cipherStart + idx * CIPHER, ct)
        // spec STREAM: chunk idx is decrypted under the counter nonce; the final chunk
        // carries the last-flag, so a truncated payload fails to authenticate.
        val pt = AgePrimitives.chachaOpen(streamKey, streamNonce(idx, last), ct, 0, clen)
        cached = pt; cachedIdx = idx
        return pt
    }

    private fun readFully(pos: Long, dst: ByteArray) {
        var r = 0
        while (r < dst.size) {
            val n = data.readAt(pos + r, dst, r, dst.size - r)
            if (n < 0) break
            r += n
        }
    }

    companion object {
        private const val PLAIN = 65536L
        private const val CIPHER = 65552L
        private const val CIPHER_I = 65552
        private const val TAG = 16

        fun open(data: RandomAccessData, identities: List<AgeIdentity>): AgePayload {
            val head = ByteArray(minOf(data.size, 65536L).toInt())
            var r = 0
            while (r < head.size) { val n = data.readAt(r.toLong(), head, r, head.size - r); if (n < 0) break; r += n }
            val header = AgeFormat.parse(ByteArrayInputStream(head, 0, r))
            val fileKey = identities.firstNotNullOfOrNull { it.unwrap(header.stanzas) }
                ?: throw AgeFormatException("no matching identity for this file")
            AgeFormat.verifyMac(fileKey, header)

            val bodyStart = header.headerBytes
            val nonce = ByteArray(AgeFormat.PAYLOAD_NONCE_SIZE)
            var k = 0
            while (k < nonce.size) { val n = data.readAt(bodyStart + k, nonce, k, nonce.size - k); if (n < 0) break; k += n }
            // spec "Payload": streamKey = HKDF[nonce, "payload"](file key)
            val streamKey = AgePrimitives.hkdf(nonce, fileKey, Age.PAYLOAD_INFO, 32)

            val cipherStart = bodyStart + AgeFormat.PAYLOAD_NONCE_SIZE
            val cipherLen = data.size - cipherStart
            val numChunks: Long
            val lastCipherLen: Int
            if (cipherLen % CIPHER == 0L) {
                numChunks = cipherLen / CIPHER; lastCipherLen = CIPHER_I
            } else {
                numChunks = cipherLen / CIPHER + 1; lastCipherLen = (cipherLen % CIPHER).toInt()
            }
            val length = (numChunks - 1) * PLAIN + (lastCipherLen - TAG)
            return AgePayload(data, streamKey, cipherStart, numChunks, lastCipherLen, length)
        }
    }
}
