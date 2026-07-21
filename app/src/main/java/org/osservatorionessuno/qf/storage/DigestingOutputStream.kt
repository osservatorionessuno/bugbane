package org.osservatorionessuno.qf.storage

import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Streams bytes to [delegate] while computing a SHA-256 digest.
 * When [onClosed] is set, it receives the hex digest after [delegate] is closed.
 */
class DigestingOutputStream(
    private val delegate: OutputStream,
    private val guard: FreeSpaceGuard? = null,
    private val onClosed: ((sha256Hex: String) -> Unit)? = null,
) : OutputStream() {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var closed = false

    var bytesWritten: Long = 0
        private set

    @Throws(IOException::class)
    override fun write(b: Int) {
        guard?.record(1)
        digest.update(b.toByte())
        delegate.write(b)
        bytesWritten++
    }

    @Throws(IOException::class)
    override fun write(b: ByteArray, off: Int, len: Int) {
        guard?.record(len)
        digest.update(b, off, len)
        delegate.write(b, off, len)
        bytesWritten += len
    }

    @Throws(IOException::class)
    override fun flush() = delegate.flush()

    @Throws(IOException::class)
    override fun close() {
        if (closed) return
        closed = true
        delegate.close()
        onClosed?.invoke(digest.digest().joinToString("") { "%02x".format(it) })
    }
}
