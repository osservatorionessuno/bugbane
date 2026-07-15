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

    private fun encrypt(vault: KeyVault, entries: List<Entry>): java.io.File {
        val bytes = ByteArrayOutputStream().also { out ->
            AgeZipArchiveWriter(out, vault).use { writer ->
                for (e in entries) {
                    writer.putEntry(e.name, e.modifiedTime).use { sink ->
                        e.open().use { it.copyTo(sink, 64 * 1024) }
                    }
                }
            }
        }.toByteArray()
        return tempAgeFile(bytes)
    }

    @Test
    fun `seek reads a single file straight from the encrypted envelope`() {
        val vault = InMemoryKeyVault()
        val archive = encrypt(vault, entries())

        SeekableArchive(archive, vault).use { arc ->
            // central directory parsed from the encrypted envelope
            assertEquals(setOf("a/first.txt", "big.bin", "z/getprop.txt"), arc.names())
            assertArrayEquals(lastText, arc.open("z/getprop.txt").use { it.readBytes() })
            assertArrayEquals(firstText, arc.open("a/first.txt").use { it.readBytes() })
            assertArrayEquals(bigBinary, arc.open("big.bin").use { it.readBytes() })
        }
    }

    @Test
    fun `forEachEntry agrees with SeekableArchive`() {
        val vault = InMemoryKeyVault()
        val archive = encrypt(vault, entries())

        val viaReader = LinkedHashMap<String, ByteArray>()
        AgeZipArchiveReader.forEachEntry(archive, vault) { name, _, open ->
            viaReader[name] = open().use { it.readBytes() }
        }
        SeekableArchive(archive, vault).use { arc ->
            for (name in viaReader.keys) {
                assertArrayEquals(viaReader[name], arc.open(name).use { it.readBytes() }, name)
            }
        }
    }
}
