package org.osservatorionessuno.qf.crypto

import org.apache.commons.compress.archivers.zip.ZipFile
import org.osservatorionessuno.qf.crypto.age.AgeIdentity
import org.osservatorionessuno.qf.crypto.age.AgePayload
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel

internal interface RandomAccessData : Closeable {
    val size: Long
    fun readAt(pos: Long, dst: ByteArray, off: Int, len: Int): Int
}

internal class FileRandomAccess(file: File) : RandomAccessData {
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
 */
class SeekableArchive(private val file: File, identities: List<AgeIdentity>) : Closeable {
    constructor(file: File, vault: KeyVault) : this(file, listOf(KeyVaultIdentity(vault)))

    private val access = FileRandomAccess(file)
    private val zip = ZipFile(AgePayloadChannel(AgePayload.open(access, identities)))

    fun names(): Set<String> {
        val out = LinkedHashSet<String>()
        val e = zip.entries
        while (e.hasMoreElements()) out.add(e.nextElement().name)
        return out
    }

    fun open(name: String): InputStream {
        val entry = zip.getEntry(name) ?: throw NoSuchElementException(name)
        return zip.getInputStream(entry)
    }

    override fun close() {
        zip.close()
        access.close()
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
