package org.osservatorionessuno.qf.crypto

import org.apache.commons.compress.archivers.zip.ZipFile
import org.osservatorionessuno.qf.crypto.age.AgePayload
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel

/** Random-access byte source (memory or file) backing a [SeekableArchive]. */
interface RandomAccessData : Closeable {
    val size: Long
    /** Read up to [len] bytes at absolute [pos]; returns bytes read, or -1 at EOF. */
    fun readAt(pos: Long, dst: ByteArray, off: Int, len: Int): Int
}

class ByteArrayRandomAccess(private val data: ByteArray) : RandomAccessData {
    override val size: Long get() = data.size.toLong()
    override fun readAt(pos: Long, dst: ByteArray, off: Int, len: Int): Int {
        val p = pos.toInt()
        val n = minOf(len, data.size - p)
        if (n <= 0) return -1
        System.arraycopy(data, p, dst, off, n)
        return n
    }
    override fun close() {}
}

class FileRandomAccess(file: File) : RandomAccessData {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long get() = raf.length()
    @Synchronized
    override fun readAt(pos: Long, dst: ByteArray, off: Int, len: Int): Int {
        raf.seek(pos)
        return raf.read(dst, off, len)
    }
    override fun close() = raf.close()
}

/**
 * Random-access reader over a bugbane archive: decrypt and inflate a **single**
 * artifact straight from the encrypted envelope, without touching the rest and
 * without writing any plaintext to disk.
 *
 * The decrypted age payload (the ZIP bytes) is exposed as a [SeekableByteChannel]
 * via [AgePayload]; Apache Commons Compress'[ZipFile] does the central-directory
 * parsing, ZIP64 handling and inflation. Only the chunks spanning the requested
 * entry are decrypted.
 */
class SeekableArchive(private val data: RandomAccessData, vault: KeyVault) : Closeable {
    private val zip = ZipFile(AgePayloadChannel(AgePayload.open(data, listOf(KeyVaultIdentity(vault)))))

    fun names(): Set<String> {
        val out = LinkedHashSet<String>()
        val e = zip.entries
        while (e.hasMoreElements()) out.add(e.nextElement().name)
        return out
    }

    fun read(name: String): ByteArray = open(name).use { it.readBytes() }

    fun open(name: String): InputStream {
        val entry = zip.getEntry(name) ?: throw NoSuchElementException(name)
        return zip.getInputStream(entry)
    }

    override fun close() {
        zip.close()
        data.close()
    }
}

/** [SeekableByteChannel] exposing the decrypted age payload (the ZIP bytes) on demand. */
private class AgePayloadChannel(private val payload: AgePayload) : SeekableByteChannel {
    private var pos = 0L
    override fun read(dst: ByteBuffer): Int {
        if (pos >= payload.length) return -1
        val n = minOf(dst.remaining().toLong(), payload.length - pos).toInt()
        if (n <= 0) return 0
        dst.put(payload.read(pos, n))
        pos += n
        return n
    }
    override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()
    override fun position(): Long = pos
    override fun position(newPosition: Long): SeekableByteChannel { pos = newPosition; return this }
    override fun size(): Long = payload.length
    override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()
    override fun isOpen(): Boolean = true
    override fun close() {}
}
