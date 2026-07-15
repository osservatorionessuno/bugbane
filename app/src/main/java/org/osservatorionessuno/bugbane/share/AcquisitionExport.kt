package org.osservatorionessuno.bugbane.share

import org.osservatorionessuno.qf.crypto.AcquisitionIdentityVault
import org.osservatorionessuno.qf.crypto.AgeExporter
import org.osservatorionessuno.qf.crypto.age.AgeIdentity
import org.osservatorionessuno.qf.crypto.age.ScryptRecipient
import java.io.File
import java.io.OutputStream

/**
 * File name presented for an exported/shared acquisition. The `.zip.age` suffix
 * reflects the payload: an age file whose plaintext is a ZIP (`age -d … | unzip`).
 */
const val EXPORT_FILE_NAME: String = "acquisition.zip.age"

/**
 * Passphrase re-wrap of an at-rest acquisition archive: rewrites the ~200-byte
 * age header to a scrypt recipient and copies the encrypted payload verbatim.
 * Shared by the file export ([writeTo] into the SAF destination) and the share
 * provider (streams [writeTo], answers a SIZE query with [size]).
 *
 * Callers supply the unlocked acquisition [AgeIdentity] — obtaining it is what
 * triggers the biometric/passphrase gate (see [AcquisitionIdentityVault]).
 */
object AcquisitionExport {
    // 2^15 scrypt
    private const val SCRYPT_LOG_N = 15

    private fun recipient(passphrase: String) =
        ScryptRecipient(passphrase.toByteArray(), logN = SCRYPT_LOG_N)

    private fun identities(identity: AgeIdentity): List<AgeIdentity> =
        listOf(identity) + AcquisitionIdentityVault.legacyIdentities()

    /** Stream [archive] re-wrapped to [passphrase] into [out]. */
    fun writeTo(archive: File, identity: AgeIdentity, passphrase: String, out: OutputStream) {
        archive.inputStream().use { src ->
            AgeExporter.export(src, identities(identity), recipient(passphrase), out)
        }
    }

    /** Exact byte length [writeTo] would produce for [archive], without copying the payload. */
    fun size(archive: File, identity: AgeIdentity, passphrase: String): Long {
        return archive.inputStream().use { src ->
            AgeExporter.exportedSize(src, identities(identity), recipient(passphrase), archive.length())
        }
    }
}
