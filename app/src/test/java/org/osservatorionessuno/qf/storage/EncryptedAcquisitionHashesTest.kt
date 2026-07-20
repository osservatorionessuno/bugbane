package org.osservatorionessuno.qf.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.osservatorionessuno.qf.crypto.InMemoryKeyVault
import java.io.File
import java.security.MessageDigest

class ArtifactHashesTest {

    @Test
    fun `parse matches example hashes csv format`() {
        val content = """
            getprop.txt,a7adc0aeb573a9c881d7379f87444359afc4bf2515a0378de89e3d26e3473d8b
            env.txt,7fec02dc1108aa9b2a53337c11c7f2980947da6e294c35f1e49dddb5d5fa5c65

        """.trimIndent()

        val parsed = ArtifactHashes.parse(content)
        assertEquals(2, parsed.size)
        assertEquals("getprop.txt" to "a7adc0aeb573a9c881d7379f87444359afc4bf2515a0378de89e3d26e3473d8b", parsed[0])
        assertEquals("env.txt" to "7fec02dc1108aa9b2a53337c11c7f2980947da6e294c35f1e49dddb5d5fa5c65", parsed[1])
    }

    @Test
    fun `formatLine is path comma sha256 newline`() {
        assertEquals(
            "getprop.txt,abc\n",
            ArtifactHashes.formatLine("getprop.txt", "abc"),
        )
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

    @Test
    fun `hashes csv is reserved from module artifacts`() {
        val dir = createTempDir(prefix = "hashes-reserved-").also { it.deleteOnExit() }
        val vault = InMemoryKeyVault()
        EncryptedAcquisitionWriter(dir, vault).use { writer ->
            val error = runCatching { writer.openArtifact(HASHES_FILE) }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(error!!.message!!.contains(HASHES_FILE))
        }
    }
}
