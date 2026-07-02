package org.osservatorionessuno.qf.crypto

import org.osservatorionessuno.qf.crypto.age.AgeFormat
import org.osservatorionessuno.qf.crypto.age.AgeRecipient
import java.io.InputStream
import java.io.OutputStream

/**
 * Verbatim re-wrap export.
 *
 * Reads an at-rest bugbane archive, unwraps the age file key via the [KeyVault]
 * (verifying the at-rest header MAC), re-wraps that same file key to [recipient]
 * (e.g. an analyst's passphrase or X25519 key), and writes a standard age file
 * whose **payload is copied byte-for-byte**. The bulk ciphertext is never
 * decrypted or re-encrypted — only the file key is re-wrapped and a fresh header
 * MAC computed.
 */
object AgeExporter {
    fun export(atRest: InputStream, vault: KeyVault, recipient: AgeRecipient, out: OutputStream) {
        val header = AgeFormat.parse(atRest)            // atRest is now positioned at the payload
        val fileKey = KeyVaultIdentity(vault).unwrap(header.stanzas)
            ?: throw IllegalStateException("no bugbane recipient stanza in archive")
        AgeFormat.verifyMac(fileKey, header)
        AgeFormat.write(out, recipient.wrap(fileKey), fileKey)
        atRest.copyTo(out)                              // payload, verbatim
    }
}
