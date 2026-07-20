package org.osservatorionessuno.qf.crypto

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets

/**
 * Key material with a deterministic wipe, stored off the managed heap.
 *
 * The bytes live in a direct [ByteBuffer] — native memory the garbage collector
 * never moves or copies — so the secret's storage produces no GC-evacuation
 * duplicates, and [close] zeroes it in place. Use it in a `use { }` block so the
 * wipe runs even on an exception.
 *
 * Reach the raw bytes only through [withBytes], which materializes a **transient**
 * heap copy for the duration of the block and zeroes it on the way out. That copy
 * is the irreducible cost of feeding a `byte[]`-based primitive (BouncyCastle,
 * `SecretKeySpec`); keep the block tight and never let the array escape it:
 *
 * ```
 * secret.withBytes { key -> AgePrimitives.chachaOpen(key, nonce, body) }
 * ```
 *
 * What [close] cannot reach — copies the runtime made elsewhere (a `String`, a
 * retained array, the IME's own buffers) — stays out of scope; the guard against
 * those is not creating them (see THREAT_MODELING.md and the lint rules). Only
 * hardware-Keystore keys never become a JVM variable at all.
 *
 * Construction takes ownership of [source] and zeroes it once copied off-heap.
 */
class Secret(source: ByteArray) : AutoCloseable {
    val size: Int = source.size
    private val buf: ByteBuffer = ByteBuffer.allocateDirect(size)
    private var closed = false

    init {
        for (i in 0 until size) buf.put(i, source[i])
        source.fill(0)
    }

    /**
     * Run [block] with a transient heap copy of the bytes, zeroed when it returns.
     * Do not retain the array past the block (use [copyBytes] to hand ownership out).
     */
    fun <T> withBytes(block: (ByteArray) -> T): T {
        check(!closed) { "secret already wiped" }
        val tmp = read()
        return try {
            block(tmp)
        } finally {
            tmp.fill(0)
        }
    }

    /** A detached heap copy for an API that takes ownership of its input; the caller wipes it. */
    fun copyBytes(): ByteArray {
        check(!closed) { "secret already wiped" }
        return read()
    }

    override fun close() {
        if (closed) return
        closed = true
        for (i in 0 until size) buf.put(i, 0.toByte())
    }

    private fun read(): ByteArray {
        val out = ByteArray(size)
        for (i in 0 until size) out[i] = buf.get(i)
        return out
    }

    companion object {
        /**
         * UTF-8 encoding of [chars] as a [Secret], clearing every intermediate.
         * [chars] itself is zeroed — pass a password's [CharArray], never its `String`.
         */
        fun ofChars(chars: CharArray): Secret {
            val bb = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars))
            val tmp = ByteArray(bb.remaining())
            bb.get(tmp)
            return try {
                Secret(tmp) // copies off-heap and zeroes tmp
            } finally {
                chars.fill(0.toChar())
                if (bb.hasArray()) bb.array().fill(0)
            }
        }
    }
}
