package org.osservatorionessuno.qf.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class SeekableArchiveTest {

    private val firstText = ("alpha beta gamma\n".repeat(40_000)).toByteArray()
    private val bigBinary = ByteArray(3_000_000) { ((it * 7) % 251).toByte() }
    private val lastText = "ro.product.model=Pixel 7\n".repeat(300).toByteArray()

    private fun entries() = listOf(
        Entry("a/first.txt") { ByteArrayInputStream(firstText) },
        Entry("big.bin") { ByteArrayInputStream(bigBinary) },
        Entry("z/getprop.txt") { ByteArrayInputStream(lastText) },
    )

    @Test
    fun `seek reads a single file straight from the encrypted envelope`() {
        val vault = InMemoryKeyVault()
        val archive = ByteArrayOutputStream()
            .also { AgeZipArchiveReader.write(it, vault, entries()) }.toByteArray()

        SeekableArchive(ByteArrayRandomAccess(archive), vault).use { arc ->
            // central directory parsed from the encrypted envelope
            assertEquals(setOf("a/first.txt", "big.bin", "z/getprop.txt"), arc.names())
            // a small entry stored AFTER a 3 MB one — decrypt+inflate only it
            assertArrayEquals(lastText, arc.read("z/getprop.txt"))
            assertArrayEquals(firstText, arc.read("a/first.txt"))
            assertArrayEquals(bigBinary, arc.read("big.bin"))
        }
    }

    @Test
    fun `seek agrees with the sequential reader`() {
        val vault = InMemoryKeyVault()
        val archive = ByteArrayOutputStream()
            .also { AgeZipArchiveReader.write(it, vault, entries()) }.toByteArray()

        val sequential = LinkedHashMap<String, ByteArray>()
        AgeZipArchiveReader.forEachEntry(ByteArrayRandomAccess(archive), vault) { name, _, data ->
            sequential[name] = data.readBytes()
        }
        SeekableArchive(ByteArrayRandomAccess(archive), vault).use { arc ->
            for (name in sequential.keys) {
                assertArrayEquals(sequential[name], arc.read(name), name)
            }
        }
    }
}
