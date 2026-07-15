package org.osservatorionessuno.qf.crypto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.osservatorionessuno.libmvt.android.ForensicRunner
import org.osservatorionessuno.libmvt.common.Artifact
import org.osservatorionessuno.libmvt.common.GroupedDetection
import org.osservatorionessuno.libmvt.common.Indicators
import org.osservatorionessuno.libmvt.common.ReopenableInput
import org.osservatorionessuno.libmvt.common.StringResolver
import org.osservatorionessuno.qf.crypto.age.X25519Identity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.LinkedHashMap

/**
 * End-to-end: the **real libMVT** analyzes artifacts read straight out of the
 * encrypted archive in a single decrypt+unzip streaming pass
 * ([AgeZipArchiveReader.forEachEntry]), with no plaintext written to disk.
 *
 * For each analyzable artifact the result must match libMVT reading the same
 * bytes from a plain stream — proving the encrypted archive is a transparent
 * input to MVT (no libMVT change needed).
 */
class LibmvtIntegrationTest {

    private val resolver = object : StringResolver {
        override fun get(name: String): String = name
    }

    private fun runner(): ForensicRunner =
        ForensicRunner(resolver).apply { setIndicators(Indicators()) }

    private fun groupedKeys(artifacts: Map<String, Artifact>): List<String> =
        GroupedDetection.fromArtifacts(artifacts).flatMap { group ->
            group.detections.map { entry ->
                "${group.id}|${entry.value}|${entry.file ?: ""}"
            }
        }.sorted()

    @Test
    fun `libMVT analyzes artifacts directly from the encrypted archive`() {
        val artifactBytes = linkedMapOf(
            "getprop.txt" to (
                "[ro.build.version.sdk]: [33]\n" +
                    "[ro.product.model]: [Pixel 7]\n" +
                    "[ro.debuggable]: [0]\n"
                ).toByteArray(),
            "settings_secure.txt" to "adb_enabled=1\ninstall_non_market_apps=0\n".toByteArray(),
            // present in the archive but not an MVT artifact -> skipped by analysis
            "acquisition.json" to """{"streaming_mode":true}""".toByteArray(),
        )

        val id = X25519Identity.generate()
        val archiveFile = File.createTempFile("libmvt-test-", ".age").also { file ->
            ByteArrayOutputStream().also { out ->
                AgeZipArchiveWriter(out, listOf(id.recipient())).use { writer ->
                    for ((n, b) in artifactBytes) {
                        writer.putEntry(n).use { sink ->
                            ByteArrayInputStream(b).use { it.copyTo(sink) }
                        }
                    }
                }
                file.writeBytes(out.toByteArray())
            }
            file.deleteOnExit()
        }

        val viaEncrypted = LinkedHashMap<String, Artifact>()
        var sawGetprop = false

        AgeZipArchiveReader.forEachEntry(archiveFile, listOf(id)) { name, _, open ->
                if (ForensicRunner.findModuleIndices(name).isEmpty()) return@forEachEntry
                val reopenable = ReopenableInput.of(name) { open() }
                runner().streamFileAnalysis(reopenable)?.let { viaEncrypted[name] = it }
                if (name == "getprop.txt") sawGetprop = true
        }

        val viaPlain = LinkedHashMap<String, Artifact>()
        for (name in artifactBytes.keys) {
            if (ForensicRunner.findModuleIndices(name).isEmpty()) continue
            val reopenable = ReopenableInput.of(name) { ByteArrayInputStream(artifactBytes[name]) }
            runner().streamFileAnalysis(reopenable)?.let { viaPlain[name] = it }
        }

        assertTrue(sawGetprop, "expected getprop.txt to be analyzed from the encrypted archive")
        assertNotNull(viaEncrypted["getprop.txt"], "encrypted analysis returned null for getprop.txt")
        assertEquals(
            groupedKeys(viaPlain),
            groupedKeys(viaEncrypted),
            "grouped detection parity",
        )
    }
}
