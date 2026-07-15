package org.osservatorionessuno.qf.crypto

import org.osservatorionessuno.qf.crypto.age.Age
import org.osservatorionessuno.qf.crypto.age.AgeIdentity
import org.osservatorionessuno.qf.crypto.age.AgeRecipient
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.IOException

/** One streamed artifact: a name (relative path), optional mtime, and a lazy source of bytes. */
class Entry(val name: String, val modifiedTime: Long? = null, val open: () -> InputStream)

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
class AgeZipArchiveWriter(
    out: OutputStream,
    recipients: List<AgeRecipient>,
    onFileKey: ((ByteArray) -> Unit)? = null,
) : Closeable {
    constructor(out: OutputStream, vault: KeyVault) : this(out, listOf(KeyVaultRecipient(vault)))

    private val ageOut: OutputStream = Age.encryptingStream(recipients, out, onFileKey)
    private val zip = ZipOutputStream(ageOut).apply { setLevel(Deflater.BEST_SPEED) }
    private var entryOpen = false

    fun putEntry(name: String, modifiedTime: Long? = null): OutputStream {
        // TODO: handle case where previous modules didn't close the entry
        check(!entryOpen) { "previous entry not closed" }
        zip.putNextEntry(ZipEntry(name).apply { if (modifiedTime != null) time = modifiedTime })
        entryOpen = true
        return EntryStream()
    }

    private inner class EntryStream : OutputStream() {
        @Throws(IOException::class)
        override fun write(b: Int) = zip.write(b)
        @Throws(IOException::class)
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

/**
 * Streaming encrypted acquisition archive — a **standard ZIP inside age**.
 *
 * [forEachEntry] decrypts via [SeekableArchive] and supplies a reopenable stream
 * per entry ([open]); bounded memory regardless of archive size.
 *
 * A decrypted export opens with stock tools: `age -d acquisition.age > a.zip`.
 */
object AgeZipArchiveReader {

    /** Decrypt + unzip [file] with a single [vault] identity (see the identities overload). */
    fun forEachEntry(
        file: File,
        vault: KeyVault,
        action: (name: String, modifiedTime: Long?, open: () -> InputStream) -> Unit,
    ) = forEachEntry(file, listOf(KeyVaultIdentity(vault)), action)

    /** Decrypt + unzip [file] with the given [identities] (tried in order), invoking [action] per entry. */
    fun forEachEntry(
        file: File,
        identities: List<AgeIdentity>,
        action: (name: String, modifiedTime: Long?, open: () -> InputStream) -> Unit,
    ) {
        SeekableArchive(file, identities).use { seekable ->
            for (name in seekable.names()) {
                action(name, null) { seekable.open(name) }
            }
        }
    }
}
