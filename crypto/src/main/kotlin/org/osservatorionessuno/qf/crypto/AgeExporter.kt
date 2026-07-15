package org.osservatorionessuno.qf.crypto

import org.osservatorionessuno.qf.crypto.age.AgeFormat
import org.osservatorionessuno.qf.crypto.age.AgeIdentity
import org.osservatorionessuno.qf.crypto.age.AgeRecipient
import org.osservatorionessuno.qf.crypto.age.AgeStanza
import java.io.InputStream
import java.io.OutputStream

/**
 * Verbatim re-wrap export.
 *
 * Reads an at-rest bugbane archive, unwraps the age file key via one of
 * [identities] (verifying the at-rest header MAC), re-wraps that same file key
 * to [recipient] (e.g. an analyst's passphrase or X25519 key), and writes a
 * standard age file whose **payload is copied byte-for-byte**. The bulk
 * ciphertext is never decrypted or re-encrypted — only the file key is
 * re-wrapped and a fresh header MAC computed.
 */
object AgeExporter {
    fun export(atRest: InputStream, identities: List<AgeIdentity>, recipient: AgeRecipient, out: OutputStream) {
        val header = AgeFormat.parse(atRest)            // atRest is now positioned at the payload
        val fileKey = unwrap(identities, header.stanzas)
        AgeFormat.verifyMac(fileKey, header)
        AgeFormat.write(out, recipient.wrap(fileKey), fileKey)
        atRest.copyTo(out)                              // payload, verbatim
    }

    /**
     * Exact byte length [export] would produce for an at-rest archive of
     * [atRestTotalSize] bytes re-wrapped to [recipient] — without decrypting or
     * copying the payload. Since the payload is copied verbatim, the result is
     * (new header) + (payload = total − old header). Useful to answer an
     * `OpenableColumns.SIZE` query when streaming the export to a share target.
     */
    fun exportedSize(atRest: InputStream, identities: List<AgeIdentity>, recipient: AgeRecipient, atRestTotalSize: Long): Long {
        val header = AgeFormat.parse(atRest)
        val fileKey = unwrap(identities, header.stanzas)
        val newHeaderSize = AgeFormat.serializeHeader(recipient.wrap(fileKey), fileKey).size.toLong()
        return newHeaderSize + (atRestTotalSize - header.headerBytes)
    }

    private fun unwrap(identities: List<AgeIdentity>, stanzas: List<AgeStanza>): ByteArray =
        identities.firstNotNullOfOrNull { it.unwrap(stanzas) }
            ?: throw IllegalStateException("no usable recipient stanza in archive")
}
