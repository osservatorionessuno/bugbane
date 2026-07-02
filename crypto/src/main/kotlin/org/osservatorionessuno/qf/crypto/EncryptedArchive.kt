package org.osservatorionessuno.qf.crypto

import org.osservatorionessuno.qf.crypto.age.AgePayload
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream

/** One streamed artifact: a name (relative path), optional mtime, and a lazy source of bytes. */
class Entry(val name: String, val modifiedTime: Long? = null, val open: () -> InputStream)

/**
 * Streaming encrypted acquisition archive — a **standard ZIP inside age**.
 *
 *  - acquire: [write] / [ArchiveWriter] stream entries straight to ciphertext;
 *    plaintext never hits disk.
 *  - analyze: [forEachEntry] decrypts (one 64 KiB chunk at a time, via
 *    [AgePayload]) and unzips in a single pass — bounded memory regardless of
 *    archive size.
 *
 * A decrypted export opens with stock tools: `age -d acquisition.age > a.zip`.
 */
object EncryptedArchive {

    /** Stream [entries] into an encrypted archive written to [out]. */
    fun write(out: OutputStream, vault: KeyVault, entries: Iterable<Entry>) {
        ArchiveWriter(out, vault).use { writer ->
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
