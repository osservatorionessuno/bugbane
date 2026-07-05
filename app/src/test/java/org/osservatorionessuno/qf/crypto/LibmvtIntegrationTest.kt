package org.osservatorionessuno.qf.crypto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.osservatorionessuno.libmvt.android.ArtifactInput
import org.osservatorionessuno.libmvt.android.ForensicRunner
import org.osservatorionessuno.libmvt.common.Artifact
import org.osservatorionessuno.libmvt.common.Indicators
import org.osservatorionessuno.libmvt.common.StringResolver
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * End-to-end: the **real libMVT** analyzes artifacts read straight out of the
 * encrypted archive in a single decrypt+unzip streaming pass
 * ([EncryptedArchive.forEachEntry]), with no plaintext written to disk.
 *
 * For each analyzable artifact the result must match libMVT reading the same
 * bytes from a plain stream — proving the encrypted archive is a transparent
 * input to MVT (no libMVT change needed).
 */
class LibmvtIntegrationTest {

    private val resolver = object : StringResolver {
        override fun get(key: String): String = key
    }

    private fun runner(): ForensicRunner =
        ForensicRunner(resolver).apply { setIndicators(Indicators()) }

    private fun detections(a: Artifact?): List<String> =
        a?.detected?.map { "${it.level}|${it.title}|${it.context}" } ?: emptyList()

    @Test
    fun `libMVT analyzes artifacts directly from the encrypted archive`() {
        val artifacts = linkedMapOf(
            "getprop.txt" to (
                "[ro.build.version.sdk]: [33]\n" +
                    "[ro.product.model]: [Pixel 7]\n" +
                    "[ro.debuggable]: [0]\n"
                ).toByteArray(),
            "settings_secure.txt" to "adb_enabled=1\ninstall_non_market_apps=0\n".toByteArray(),
            // present in the archive but not an MVT artifact -> skipped by analysis
            "acquisition.json" to """{"streaming_mode":true}""".toByteArray(),
        )

        val vault = InMemoryKeyVault()
        val archive = ByteArrayOutputStream().also { out ->
            EncryptedArchive.write(out, vault, artifacts.map { (n, b) -> Entry(n) { ByteArrayInputStream(b) } })
        }.toByteArray()

        var sawGetprop = false
        EncryptedArchive.forEachEntry(ByteArrayRandomAccess(archive), vault) { name, _, stream ->
            if (name !in ForensicRunner.MODULES_MAP.keys) return@forEachEntry

            // analyze straight from the encrypted archive's stream
            val viaEncrypted = runCatching { runner().streamFileAnalysis(ArtifactInput(name, stream)) }
            // reference: same parser, same bytes, plaintext stream
            val viaPlain = runCatching {
                runner().streamFileAnalysis(ArtifactInput(name, ByteArrayInputStream(artifacts[name])))
            }

            assertEquals(viaPlain.isSuccess, viaEncrypted.isSuccess, "outcome parity for $name")
            if (viaPlain.isSuccess) {
                assertNotNull(viaEncrypted.getOrNull(), "encrypted analysis returned null for $name")
                assertEquals(
                    detections(viaPlain.getOrNull()),
                    detections(viaEncrypted.getOrNull()),
                    "detection parity for $name",
                )
            }
            if (name == "getprop.txt") sawGetprop = true
        }

        assertTrue(sawGetprop, "expected getprop.txt to be analyzed from the encrypted archive")
    }
}
