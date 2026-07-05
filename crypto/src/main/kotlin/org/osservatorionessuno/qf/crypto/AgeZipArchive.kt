package org.osservatorionessuno.qf.crypto

import org.osservatorionessuno.qf.crypto.age.Age
import org.osservatorionessuno.qf.crypto.age.AgePayload
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
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
class AgeZipArchiveWriter(out: OutputStream, vault: KeyVault) : Closeable {
    private val ageOut: OutputStream = Age.encryptingStream(listOf(KeyVaultRecipient(vault)), out)
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
 *  - acquire: [write] / [AgeZipArchiveWriter] stream entries straight to ciphertext;
 *    plaintext never hits disk.
 *  - analyze: [forEachEntry] decrypts (one 64 KiB chunk at a time, via
 *    [AgePayload]) and unzips in a single pass — bounded memory regardless of
 *    archive size.
 *
 * A decrypted export opens with stock tools: `age -d acquisition.age > a.zip`.
 */
object AgeZipArchiveReader {

    /** Stream [entries] into an encrypted archive written to [out]. */
    fun write(out: OutputStream, vault: KeyVault, entries: Iterable<Entry>) {
        AgeZipArchiveWriter(out, vault).use { writer ->
            for (e in entries) {
                writer.putEntry(e.name, e.modifiedTime).use { sink -> e.open().use { it.copyTo(sink, 64 * 1024) } }
            }
        }
    }

    /** Decrypt + unzip [file] in one bounded-memory pass, invoking [action] per entry. */
    fun forEachEntry(file: File, vault: KeyVault, action: (name: String, modifiedTime: Long?, data: InputStream) -> Unit) =
        FileRandomAccess(file).use { forEachEntry(it, vault, action) }

    /**
     * Decrypt + unzip [data] in one streaming pass. The supplied per-entry stream
     * is valid only for the duration of the call and must not be closed by the
     * callee (it is the live ZIP stream).
     */
    fun forEachEntry(data: RandomAccessData, vault: KeyVault, action: (name: String, modifiedTime: Long?, data: InputStream) -> Unit) {
        val payload = AgePayload.open(data, listOf(KeyVaultIdentity(vault)))
        ZipInputStream(payload.stream()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                action(entry.name, entry.time.takeIf { it >= 0 }, CloseShield(zin))
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
    }

    /** Wraps the shared ZipInputStream so a callee's `use {}` won't close it. */
    private class CloseShield(private val s: InputStream) : InputStream() {
        override fun read(): Int = s.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = s.read(b, off, len)
        override fun close() { /* no-op: the ZipInputStream outlives one entry */ }
    }
}
