package org.osservatorionessuno.qf

import com.google.protobuf.CodedOutputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import org.osservatorionessuno.qf.modules.Packages
import org.osservatorionessuno.libmvt.android.parsers.CertificateParser

object ArtifactProtobuf {
    fun writeDelimitedStringRecord(output: OutputStream, value: String): Unit {
        val record = ByteArrayOutputStream()
        val codedRecord = CodedOutputStream.newInstance(record)
        codedRecord.writeString(1, value)
        codedRecord.flush()
        writeDelimited(output, record.toByteArray())
    }

    fun writeDelimitedFileRecord(
        output: OutputStream,
        path: String,
        mtime: Double?,
        mode: String?,
        size: Long?,
        user: String?,
        group: String?,
    ): Unit {
        val record = ByteArrayOutputStream()
        val codedRecord = CodedOutputStream.newInstance(record)
        codedRecord.writeString(1, path)
        if (mtime != null) codedRecord.writeDouble(2, mtime)
        if (!mode.isNullOrBlank()) codedRecord.writeString(3, mode)
        if (size != null) codedRecord.writeInt64(4, size)
        if (!user.isNullOrBlank()) codedRecord.writeString(5, user)
        if (!group.isNullOrBlank()) codedRecord.writeString(6, group)
        codedRecord.flush()
        writeDelimited(output, record.toByteArray())
    }

    fun writeDelimitedPackageRecord(output: OutputStream, packageRecord: Packages.Package): Unit {
        val record = ByteArrayOutputStream()
        val codedRecord = CodedOutputStream.newInstance(record)
        codedRecord.writeString(1, packageRecord.name)
        if (packageRecord.installer.isNotBlank()) codedRecord.writeString(2, packageRecord.installer)
        codedRecord.writeInt32(3, packageRecord.uid)
        codedRecord.writeBool(4, packageRecord.disabled)
        codedRecord.writeBool(5, packageRecord.system)
        codedRecord.writeBool(6, packageRecord.thirdParty)
        for (file in packageRecord.files) {
            codedRecord.writeByteArray(7, encodePackageFileRecord(file))
        }
        codedRecord.flush()
        writeDelimited(output, record.toByteArray())
    }

    private fun encodePackageFileRecord(file: Packages.PackageFile): ByteArray {
        val record = ByteArrayOutputStream()
        val codedRecord = CodedOutputStream.newInstance(record)
        codedRecord.writeString(1, file.path)
        if (file.localName.isNotBlank()) codedRecord.writeString(2, file.localName)
        if (file.md5.isNotBlank()) codedRecord.writeString(3, file.md5)
        if (file.sha1.isNotBlank()) codedRecord.writeString(4, file.sha1)
        if (file.sha256.isNotBlank()) codedRecord.writeString(5, file.sha256)
        if (file.sha512.isNotBlank()) codedRecord.writeString(6, file.sha512)
        if (file.suspicious) codedRecord.writeBool(7, true)
        for (certificate in file.certificates) {
            codedRecord.writeByteArray(8, encodePackageCertificateRecord(certificate))
        }
        for (infile in file.infiles) {
            codedRecord.writeString(9, infile)
        }
        codedRecord.flush()
        return record.toByteArray()
    }

    private fun encodePackageCertificateRecord(certificate: CertificateParser.CertificateInfo): ByteArray {
        val record = ByteArrayOutputStream()
        val codedRecord = CodedOutputStream.newInstance(record)
        codedRecord.writeString(1, certificate.checksums.md5)
        codedRecord.writeString(2, certificate.checksums.sha1)
        codedRecord.writeString(3, certificate.checksums.sha256)
        codedRecord.writeString(4, certificate.notBefore.toString())
        codedRecord.writeString(5, certificate.notAfter.toString())
        codedRecord.writeString(6, certificate.issuer)
        codedRecord.writeString(7, certificate.subject)
        codedRecord.writeString(8, certificate.algorithm)
        codedRecord.writeString(9, certificate.serialNumber)
        codedRecord.flush()
        return record.toByteArray()
    }

    private fun writeDelimited(output: OutputStream, record: ByteArray): Unit {
        val codedOutput = CodedOutputStream.newInstance(output)
        codedOutput.writeUInt32NoTag(record.size)
        codedOutput.writeRawBytes(record)
        codedOutput.flush()
    }
}
