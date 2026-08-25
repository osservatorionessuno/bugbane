package org.osservatorionessuno.qf

import java.io.Closeable
import java.io.OutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.osservatorionessuno.libmvt.android.parsers.CertificateParser
import org.osservatorionessuno.qf.modules.Packages

// Writes acquisition artifacts in androidqf shape so the decrypted export is consumable by
// libMVT and upstream MVT with no conversion. files.json streams one JSON object per line
// (dumps are huge); packages/mounts/root_binaries are streamed JSON arrays. Field names match MVT.
object ArtifactJson {

    // One files.json record per line. MVT reads mode and the three times unguarded
    // (check_indicators + timeline), so always emit them, defaulting to 0/"" when unknown.
    fun file(
        output: OutputStream,
        path: String,
        accessTime: Double?,
        changedTime: Double?,
        modifiedTime: Double?,
        mode: String?,
        size: Long?,
        context: String?,
        user: String?,
        group: String?,
    ) {
        val o = JSONObject()
        o.put("path", path)
        o.put("access_time", accessTime ?: 0.0)
        o.put("changed_time", changedTime ?: 0.0)
        o.put("modified_time", modifiedTime ?: 0.0)
        o.put("mode", mode ?: "")
        o.put("context", context ?: "")
        size?.let { o.put("size", it) }
        if (!user.isNullOrBlank()) o.put("user", user)
        if (!group.isNullOrBlank()) o.put("group", group)
        output.write(o.toString().toByteArray(Charsets.UTF_8))
        output.write('\n'.code)
    }

    // Streams `[e0,e1,...]`; close() (via use{}) always terminates the array, even on error.
    class Array(private val out: OutputStream) : Closeable {
        private var first = true

        init { out.write('['.code) }

        private fun element(json: String) {
            if (first) first = false else out.write(','.code)
            out.write(json.toByteArray(Charsets.UTF_8))
        }

        // Bare string element (mounts, root_binaries).
        fun string(value: String) = element(JSONObject.quote(value))

        fun pkg(pkg: Packages.Package) = element(pkgObject(pkg).toString())

        override fun close() = out.write(']'.code)
    }

    private fun pkgObject(pkg: Packages.Package): JSONObject {
        val o = JSONObject()
        o.put("name", pkg.name)
        o.put("installer", pkg.installer) // MVT reads installer unguarded; always emit.
        o.put("uid", pkg.uid)
        o.put("disabled", pkg.disabled)
        o.put("system", pkg.system)
        o.put("third_party", pkg.thirdParty)
        if (pkg.files.isNotEmpty()) o.put("files", JSONArray().apply { pkg.files.forEach { put(fileObject(it)) } })
        return o
    }

    private fun fileObject(f: Packages.PackageFile): JSONObject {
        val o = JSONObject()
        o.put("path", f.path)
        if (f.localName.isNotBlank()) o.put("local_name", f.localName)
        if (f.md5.isNotBlank()) o.put("md5", f.md5)
        if (f.sha1.isNotBlank()) o.put("sha1", f.sha1)
        if (f.sha256.isNotBlank()) o.put("sha256", f.sha256)
        if (f.sha512.isNotBlank()) o.put("sha512", f.sha512)
        if (f.suspicious) o.put("suspicious", true)
        if (f.certificates.isNotEmpty()) {
            o.put("certificates", JSONArray().apply { f.certificates.forEach { put(certObject(it)) } })
            // MVT reads a single certificate{Md5,Sha1,Sha256}; mirror the primary cert.
            val c = f.certificates[0].checksums
            o.put("certificate", JSONObject().put("Md5", c.md5).put("Sha1", c.sha1).put("Sha256", c.sha256))
        }
        if (f.infiles.isNotEmpty()) o.put("infiles", JSONArray(f.infiles))
        return o
    }

    private fun certObject(c: CertificateParser.CertificateInfo): JSONObject = JSONObject().apply {
        put("md5", c.checksums.md5)
        put("sha1", c.checksums.sha1)
        put("sha256", c.checksums.sha256)
        put("valid_from", c.notBefore.toString())
        put("valid_to", c.notAfter.toString())
        put("issuer", c.issuer)
        put("subject", c.subject)
        put("signature_algorithm", c.algorithm)
        put("serial_number", c.serialNumber)
    }
}
