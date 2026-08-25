package org.osservatorionessuno.qf.crypto

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.osservatorionessuno.libmvt.android.ForensicRunner
import org.osservatorionessuno.libmvt.android.parsers.CertificateParser
import org.osservatorionessuno.libmvt.common.Artifact
import org.osservatorionessuno.libmvt.common.GroupedDetection
import org.osservatorionessuno.libmvt.common.Indicators
import org.osservatorionessuno.libmvt.common.ReopenableInput
import org.osservatorionessuno.libmvt.common.StringResolver
import org.osservatorionessuno.qf.ArtifactProtobuf
import org.osservatorionessuno.qf.crypto.age.X25519Identity
import org.osservatorionessuno.qf.modules.Packages
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Date
import java.util.LinkedHashMap

/**
 * Plant a known-bad entry per artifact module, write it with bugbane's own writer,
 * age-encrypt, decrypt and analyze with the real libMVT: the planted value must
 * surface as a detection. Also the parity guard for the artifact-format migration.
 */
class PlantedDetectionIntegrationTest {

    private val resolver = object : StringResolver {
        override fun get(name: String): String = name
    }

    private val hash = "deadbeef".repeat(8) // 64-hex

    // Encrypt [bytes] as [name], then decrypt + analyze it.
    private fun analyze(name: String, bytes: ByteArray, indicators: Indicators = Indicators()): Map<String, Artifact> {
        val id = X25519Identity.generate()
        val archive = File.createTempFile("planted-", ".age").apply { deleteOnExit() }
        ByteArrayOutputStream().also { out ->
            AgeZipArchiveWriter(out, listOf(id.recipient())).use { writer ->
                writer.putEntry(name).use { sink -> ByteArrayInputStream(bytes).use { it.copyTo(sink) } }
            }
            archive.writeBytes(out.toByteArray())
        }
        val result = LinkedHashMap<String, Artifact>()
        AgeZipArchiveReader.forEachEntry(archive, listOf(id)) { entryName, _, open ->
            if (!ForensicRunner.isAnalyzable(entryName)) return@forEachEntry
            val runner = ForensicRunner(resolver).apply { setIndicators(indicators) }
            result.putAll(runner.streamFileAnalysis(ReopenableInput.of(entryName) { open() }))
        }
        return result
    }

    // Every detection as "id|value|file", for substring assertions.
    private fun detectionBlob(artifacts: Map<String, Artifact>): String =
        GroupedDetection.fromArtifacts(artifacts).flatMap { group ->
            group.detections.map { "${group.id}|${it.value}|${it.file ?: ""}" }
        }.joinToString("\n")

    // Indicators loaded from a temp iocs.json of STIX key -> value.
    private fun indicatorsOf(vararg pairs: Pair<String, String>): Indicators {
        val entry = JSONObject()
        for ((key, value) in pairs) entry.put(key, JSONArray().put(value))
        val root = JSONObject().put("indicators", JSONArray().put(entry))
        val dir = Files.createTempDirectory("iocs").toFile().apply { deleteOnExit() }
        File(dir, "iocs.json").writeText(root.toString())
        return Indicators().apply { loadFromDirectory(dir) }
    }

    private fun bytes(write: (ByteArrayOutputStream) -> Unit): ByteArray =
        ByteArrayOutputStream().also(write).toByteArray()

    private fun packageFile(sha256: String = "", certs: List<CertificateParser.CertificateInfo> = emptyList()) =
        Packages.PackageFile("/data/app/x/base.apk", "", "", "", sha256, "", false, certs, emptyList())

    private fun cert(sha256: String) = CertificateParser.CertificateInfo(
        "CN=Evil", "CN=Evil", Date(0), Date(0), "SHA256withRSA", 3, "1", false,
        CertificateParser.Checksum("", "", sha256),
    )

    private fun pkg(name: String = "com.example.app", file: Packages.PackageFile? = null) =
        Packages.Package(name = name, files = listOfNotNull(file))

    @Test
    fun `files - suspicious tmp path is detected`() {
        val artifact = bytes {
            ArtifactProtobuf.writeDelimitedFileRecord(it, "/data/local/tmp/evil.sh", null, "755", 128L, null, null)
        }
        val blob = detectionBlob(analyze("files.pb", artifact))
        assertTrue(blob.contains("/data/local/tmp/evil.sh"), "expected suspicious-path detection; got:\n$blob")
    }

    @Test
    fun `mounts - system partition mounted read-write is detected`() {
        val artifact = bytes {
            ArtifactProtobuf.writeDelimitedStringRecord(it, "/dev/block/dm-0 on /system type ext4 (rw,relatime)")
        }
        val blob = detectionBlob(analyze("mounts.pb", artifact))
        assertTrue(blob.contains("/system"), "expected system-mount detection; got:\n$blob")
    }

    @Test
    fun `root_binaries - known su binary is detected`() {
        val artifact = bytes { ArtifactProtobuf.writeDelimitedStringRecord(it, "/system/xbin/su") }
        val blob = detectionBlob(analyze("root_binaries.pb", artifact))
        assertTrue(blob.contains("/system/xbin/su"), "expected root-binary detection; got:\n$blob")
    }

    @Test
    fun `packages - app id IOC is detected`() {
        val artifact = bytes { ArtifactProtobuf.writeDelimitedPackageRecord(it, pkg(name = "com.evil.spyware")) }
        val blob = detectionBlob(analyze("packages.pb", artifact, indicatorsOf("app:id" to "com.evil.spyware")))
        assertTrue(blob.contains("com.evil.spyware"), "expected app:id IOC match; got:\n$blob")
    }

    @Test
    fun `packages - apk file hash IOC is detected`() {
        val artifact = bytes { ArtifactProtobuf.writeDelimitedPackageRecord(it, pkg(file = packageFile(sha256 = hash))) }
        val blob = detectionBlob(analyze("packages.pb", artifact, indicatorsOf("file:hashes.sha256" to hash)))
        assertTrue(blob.contains(hash), "expected apk file-hash IOC match; got:\n$blob")
    }

    @Test
    fun `packages - signing certificate hash IOC is detected`() {
        val artifact = bytes {
            ArtifactProtobuf.writeDelimitedPackageRecord(it, pkg(file = packageFile(certs = listOf(cert(hash)))))
        }
        val blob = detectionBlob(analyze("packages.pb", artifact, indicatorsOf("app:cert.sha256" to hash)))
        assertTrue(blob.contains(hash), "expected signing-cert IOC match; got:\n$blob")
    }
}
