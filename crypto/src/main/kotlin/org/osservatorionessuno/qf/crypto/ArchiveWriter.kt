package org.osservatorionessuno.qf.crypto

import org.osservatorionessuno.qf.crypto.age.Age
import java.io.Closeable
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Push-style writer for the streaming encrypted archive:
 *
 * ```
 *  entries → ZIP (streaming, BEST_SPEED) → age (ChaCha20-Poly1305 STREAM) → out
 * ```
 *
 * Because our age encryptor ([Age.encryptingStream]) is a real push
 * `OutputStream`, the whole chain is a plain stack of streams — no pipe and no
 * background thread (unlike kage, whose encrypt is pull-only). The decrypted
 * payload is a standard ZIP (`age -d … | unzip`).
 *
 * Entries are written one at a time: close the stream from [putEntry] before the
 * next.
 */
class ArchiveWriter(out: OutputStream, vault: KeyVault) : Closeable {
    private val ageOut: OutputStream = Age.encryptingStream(listOf(KeyVaultRecipient(vault)), out)
    private val zip = ZipOutputStream(ageOut).apply { setLevel(Deflater.BEST_SPEED) }
    private var entryOpen = false

    fun putEntry(name: String, modifiedTime: Long? = null): OutputStream {
        check(!entryOpen) { "previous entry not closed" }
        zip.putNextEntry(ZipEntry(name).apply { if (modifiedTime != null) time = modifiedTime })
        entryOpen = true
        return EntryStream()
    }

    private inner class EntryStream : OutputStream() {
        override fun write(b: Int) = zip.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = zip.write(b, off, len)
        override fun close() {
            if (!entryOpen) return
            zip.closeEntry()
            entryOpen = false
        }
    }

    override fun close() {
        // Finishes the ZIP, then closes the age stream (flushes the final STREAM
        // chunk) which closes the underlying output.
        zip.close()
    }
}
