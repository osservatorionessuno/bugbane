package org.osservatorionessuno.qf.crypto.age

import java.io.OutputStream

/**
 * Minimal age v1 implementation on a single backend (BouncyCastle).
 *
 * Covers exactly what bugbane needs: STREAM payload + header + X25519/scrypt and
 * custom (KeyVault) recipients. Decryption is provided by [AgePayload] (random
 * access + sequential), so there is no whole-ciphertext buffering.
 */
object Age {
    /**
     * Returns an [OutputStream] that age-encrypts everything written to it into
     * [out] (header + nonce written eagerly, payload STREAM-encrypted on the fly).
     * Push-based: e.g. `ZipOutputStream(Age.encryptingStream(recipients, file))` —
     * no background thread or pipe needed.
     *
     * [onFileKey] receives a copy of the freshly generated file key, for callers
     * that legitimately hold the plaintext anyway and want to keep (bounded)
     * decryption ability without unlocking an identity — see FileKeyIdentity.
     * The receiver owns the copy and should zero it when done.
     */
    fun encryptingStream(
        recipients: List<AgeRecipient>,
        out: OutputStream,
        onFileKey: ((ByteArray) -> Unit)? = null,
    ): OutputStream {
        require(recipients.isNotEmpty()) { "no recipients" }
        // spec "Payload": file = header ++ nonce ++ STREAM[HKDF[nonce, "payload"](file key)](plaintext).
        // The 16-byte file key (random) is wrapped into each recipient stanza; the random
        // 16-byte nonce salts the per-file STREAM key.
        val fileKey = AgePrimitives.randomBytes(AgeFormat.FILE_KEY_SIZE)
        val stanzas = recipients.flatMap { it.wrap(fileKey) }
        AgeFormat.write(out, stanzas, fileKey)
        onFileKey?.invoke(fileKey.copyOf())
        val nonce = AgePrimitives.randomBytes(AgeFormat.PAYLOAD_NONCE_SIZE)
        out.write(nonce)
        val streamKey = AgePrimitives.hkdf(nonce, fileKey, PAYLOAD_INFO, 32)
        return AgeEncryptingOutputStream(streamKey, out)
    }

    internal val PAYLOAD_INFO = "payload".toByteArray()
}

// spec "Payload" / the STREAM construction: ChaCha20-Poly1305 nonce is an 11-byte
// big-endian chunk counter in bytes [0..10], with byte[11] = 0x01 only for the final chunk.
internal fun streamNonce(counter: Long, last: Boolean): ByteArray {
    val nonce = ByteArray(12)
    var v = counter
    for (i in 10 downTo 0) { nonce[i] = (v and 0xFF).toByte(); v = v ushr 8 }
    if (last) nonce[11] = 1
    return nonce
}

/**
 * Push-based STREAM encryptor. Uses age's one-chunk lookahead: a full 64 KiB
 * buffer is only emitted (as non-final) once more data proves it isn't the last
 * chunk; [close] flushes the remainder as the final chunk. This makes the
 * exact-multiple-of-64-KiB case emit a *final full chunk* rather than an illegal
 * empty trailing chunk.
 */
internal class AgeEncryptingOutputStream(
    private val streamKey: ByteArray,
    private val dst: OutputStream,
) : OutputStream() {
    private val buf = ByteArray(CHUNK)
    private var bufLen = 0
    private var counter = 0L
    private var closed = false

    override fun write(i: Int) {
        if (bufLen == CHUNK) flush(false)
        buf[bufLen++] = i.toByte()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        var o = off
        var l = len
        while (l > 0) {
            if (bufLen == CHUNK) flush(false)
            val n = minOf(l, CHUNK - bufLen)
            System.arraycopy(b, o, buf, bufLen, n)
            bufLen += n; o += n; l -= n
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        flush(true)
        dst.close()
    }

    private fun flush(last: Boolean) {
        dst.write(AgePrimitives.chachaSeal(streamKey, streamNonce(counter, last), buf, 0, bufLen))
        bufLen = 0
        counter++
    }

    companion object { const val CHUNK = 65536 } // spec: STREAM chunks are 64 KiB of plaintext
}
