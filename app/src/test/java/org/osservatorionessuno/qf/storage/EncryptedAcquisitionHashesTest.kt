package org.osservatorionessuno.qf.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.osservatorionessuno.qf.crypto.InMemoryKeyVault
import java.nio.file.Files
import java.security.MessageDigest

private fun sha256Hex(data: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

class ArtifactHashesTest {

    @Test
    fun `parse matches androidqf hashes csv format`() {
        val hashA = "a7adc0aeb573a9c881d7379f87444359afc4bf2515a0378de89e3d26e3473d8b"
        val hashB = "7fec02dc1108aa9b2a53337c11c7f2980947da6e294c35f1e49dddb5d5fa5c65"
        val content = "getprop.txt,$hashA\n\"logs/anr,with,commas.txt\",$hashB\n"

        val parsed = ArtifactHashes.parse(content)
        assertEquals(2, parsed.size)
        assertEquals("getprop.txt" to hashA, parsed[0])
        assertEquals("logs/anr,with,commas.txt" to hashB, parsed[1])
    }

    @Test
    fun `formatLine quotes paths that need it`() {
        val hash = "a".repeat(64)
        assertEquals("getprop.txt,$hash\n", ArtifactHashes.formatLine("getprop.txt", hash))
        assertEquals(
            "\"logs/anr,trace.txt\",$hash\n",
            ArtifactHashes.formatLine("logs/anr,trace.txt", hash),
        )
    }

    @Test
    fun `quoted quotes and newlines in paths round-trip through format and parse`() {
        val hash = "a".repeat(64)
        for (path in listOf("""logs/we"ird.txt""", "logs/multi\nline.txt")) {
            assertEquals(listOf(path to hash), ArtifactHashes.parse(ArtifactHashes.formatLine(path, hash)))
        }
    }

    @Test
    fun `parse rejects malformed records`() {
        assertThrows<IllegalArgumentException> { ArtifactHashes.parse("no-comma-at-all") }
        assertThrows<IllegalArgumentException> { ArtifactHashes.parse("\"unterminated.txt,${"a".repeat(64)}") }
    }
}

class DigestingOutputStreamTest {

    @Test
    fun `computes sha256 of streamed bytes and invokes onClosed`() {
        val data = "ro.product.model=Pixel 7\n".toByteArray()
        val collected = java.io.ByteArrayOutputStream()
        var closedHash: String? = null
        DigestingOutputStream(collected) { closedHash = it }.use { it.write(data) }
        assertEquals(closedHash, "891766a4ce3c09c34a50c8fd41bfb1785144eb13d74b07f2732e8325b9e2cc34")
        assertEquals(data.size, collected.size())
    }
}

class EncryptedAcquisitionHashesTest {

    private fun tempDir() = Files.createTempDirectory("hashes-test-").toFile().also { it.deleteOnExit() }

    @Test
    fun `hashes csv is reserved from module artifacts`() {
        val dir = tempDir()
        EncryptedAcquisitionWriter(dir, InMemoryKeyVault(), reserveBytes = 0).use { writer ->
            val error = runCatching { writer.openArtifact(HASHES_FILE) }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(error!!.message!!.contains(HASHES_FILE))
        }
    }

    @Test
    fun `written hashes round-trip and match the artifact contents`() {
        val dir = tempDir()
        val vault = InMemoryKeyVault()
        val artifacts = mapOf(
            "getprop.txt" to "ro.product.model=Pixel 7\n".toByteArray(),
            "logs/anr/trace.txt" to ByteArray(1 shl 16) { it.toByte() },
            // Hostile device filename: quoting must keep it one manifest record.
            "logs/innocent.txt,${"0".repeat(64)}\nbugreport.zip" to "boom".toByteArray(),
        )
        EncryptedAcquisitionWriter(dir, vault, reserveBytes = 0).use { writer ->
            artifacts.forEach { (path, data) ->
                writer.useArtifact(path) { it.write(data) }
            }
        }

        val hashes = EncryptedAcquisitionReader(dir, vault).use { it.readHashes() }
        assertNotNull(hashes)
        assertEquals(
            artifacts.mapValues { sha256Hex(it.value) },
            hashes!!.toMap(),
        )
    }

    @Test
    fun `artifacts cannot be added after the manifest is archived`() {
        val dir = tempDir()
        val writer = EncryptedAcquisitionWriter(dir, InMemoryKeyVault(), reserveBytes = 0)
        writer.useArtifact("getprop.txt") { it.write(1) }
        writer.close()
        assertThrows<IllegalStateException> { writer.openArtifact("late.txt") }
    }

    @Test
    fun `writer stops writing once free space is below the reserve`() {
        val dir = tempDir()
        // The unit-test StatFs stub reports 0 free bytes, so any positive reserve trips.
        EncryptedAcquisitionWriter(dir, InMemoryKeyVault(), reserveBytes = 1).use { writer ->
            assertThrows<InsufficientStorageException> {
                writer.useArtifact("big.bin") { it.write(ByteArray(4 * 1024 * 1024)) }
            }
            assertTrue(writer.outOfSpace)
        }
    }
}
