package org.osservatorionessuno.qf.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.osservatorionessuno.qf.crypto.age.AgeFormatException
import org.osservatorionessuno.qf.crypto.age.FileKeyIdentity
import org.osservatorionessuno.qf.crypto.age.X25519Identity
import java.io.ByteArrayOutputStream
import java.io.File

class FileKeyIdentityTest {

    private fun writeArchive(): Pair<File, ByteArray> {
        val out = ByteArrayOutputStream()
        var captured: ByteArray? = null
        val identity = X25519Identity.generate()
        AgeZipArchiveWriter(out, listOf(identity.recipient()), onFileKey = { captured = it }).use { writer ->
            writer.putEntry("hello.txt").use { it.write("hello".toByteArray()) }
        }
        assertNotNull(captured)
        return tempAgeFile(out.toByteArray()) to captured!!
    }

    @Test
    fun `captured file key reads the archive without any identity`() {
        val (archive, fileKey) = writeArchive()
        val entries = mutableMapOf<String, ByteArray>()
        AgeZipArchiveReader.forEachEntry(
            archive,
            listOf(FileKeyIdentity(fileKey)),
        ) { name, _, open -> entries[name] = open().use { it.readBytes() } }
        assertEquals(setOf("hello.txt"), entries.keys)
        assertArrayEquals("hello".toByteArray(), entries["hello.txt"])
    }

    @Test
    fun `wrong file key is rejected by the header MAC`() {
        val (archive, _) = writeArchive()
        assertThrows(AgeFormatException::class.java) {
            AgeZipArchiveReader.forEachEntry(
                archive,
                listOf(FileKeyIdentity(ByteArray(16))),
            ) { _, _, _ -> }
        }
    }

    @Test
    fun `destroyed identity no longer decrypts`() {
        val (archive, fileKey) = writeArchive()
        val identity = FileKeyIdentity(fileKey)
        identity.destroy()
        assertThrows(AgeFormatException::class.java) {
            AgeZipArchiveReader.forEachEntry(archive, listOf(identity)) { _, _, _ -> }
        }
    }
}
