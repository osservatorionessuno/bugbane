package org.osservatorionessuno.bugbane.share

import org.osservatorionessuno.qf.AcquisitionRunner
import org.osservatorionessuno.qf.crypto.AgeExporter
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
 */
object AcquisitionExport {
    // 2^15 scrypt
    private const val SCRYPT_LOG_N = 15

    private fun recipient(passphrase: String) =
        ScryptRecipient(passphrase.toByteArray(), logN = SCRYPT_LOG_N)

    /** Stream [archive] re-wrapped to [passphrase] into [out]. */
    fun writeTo(archive: File, passphrase: String, out: OutputStream) {
        val vault = AcquisitionRunner.acquisitionKeyVault()
        archive.inputStream().use { src -> AgeExporter.export(src, vault, recipient(passphrase), out) }
    }

    /** Exact byte length [writeTo] would produce for [archive], without copying the payload. */
    fun size(archive: File, passphrase: String): Long {
        val vault = AcquisitionRunner.acquisitionKeyVault()
        return archive.inputStream().use { src ->
            AgeExporter.exportedSize(src, vault, recipient(passphrase), archive.length())
        }
    }
}
