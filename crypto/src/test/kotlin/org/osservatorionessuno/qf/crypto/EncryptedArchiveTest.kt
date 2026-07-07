package org.osservatorionessuno.qf.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.osservatorionessuno.qf.crypto.age.AgeFormatException
import org.osservatorionessuno.qf.crypto.age.AgePayload
import org.osservatorionessuno.qf.crypto.age.ScryptIdentity
import org.osservatorionessuno.qf.crypto.age.ScryptRecipient
import org.osservatorionessuno.qf.crypto.age.X25519Identity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import org.osservatorionessuno.qf.crypto.age.AgeIdentity

class EncryptedArchiveTest {

    private fun entries() = listOf(
        Entry("acquisition.json") {
            ByteArrayInputStream("""{"uuid":"demo","streaming_mode":true}""".toByteArray())
        },
        Entry("getprop.txt") {
            ByteArrayInputStream(("ro.product.model=Pixel 7\n".repeat(500)).toByteArray())
        },
        Entry("dumpsys/large.bin") {
            ByteArrayInputStream(ByteArray(5_000_000) { ((it * 31) % 251).toByte() })
        },
    )

    private fun encrypt(vault: KeyVault, entries: List<Entry>): ByteArray =
        ByteArrayOutputStream().also { AgeZipArchiveReader.write(it, vault, entries) }.toByteArray()

    private fun readAll(bytes: ByteArray, vault: KeyVault): Map<String, ByteArray> {
        val map = LinkedHashMap<String, ByteArray>()
        AgeZipArchiveReader.forEachEntry(ByteArrayRandomAccess(bytes), vault) { name, _, data ->
            map[name] = data.readBytes()
        }
        return map
    }

    private fun decryptZip(bytes: ByteArray, ids: List<AgeIdentity>): Map<String, ByteArray> =
        unzip(AgePayload.open(ByteArrayRandomAccess(bytes), ids).stream().readBytes())

    private fun unzip(zipBytes: ByteArray): Map<String, ByteArray> {
        val map = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { z ->
            var e = z.nextEntry
            while (e != null) { map[e.name] = z.readBytes(); z.closeEntry(); e = z.nextEntry }
        }
        return map
    }

    @Test
    fun `streaming acquire then analyze preserves every entry`() {
        val vault = InMemoryKeyVault()
        val out = readAll(encrypt(vault, entries()), vault)
        assertEquals(setOf("acquisition.json", "getprop.txt", "dumpsys/large.bin"), out.keys)
        entries().forEach { e -> assertArrayEquals(e.open().readBytes(), out[e.name], e.name) }
    }

    @Test
    fun `at-rest is an age file whose payload is a standard ZIP, with no plaintext`() {
        val vault = InMemoryKeyVault()
        val atRest = encrypt(vault, entries())

        assertEquals("age-encryption.org/v1", String(atRest, 0, 21, Charsets.US_ASCII))
        assertFalse(indexOf(atRest, "ro.product.model=Pixel 7".toByteArray()) >= 0, "plaintext leaked")

        // decrypted payload is a real ZIP that stock tools can read
        val payload = AgePayload.open(ByteArrayRandomAccess(atRest), listOf(KeyVaultIdentity(vault))).stream().readBytes()
        assertEquals(0x50, payload[0].toInt() and 0xFF) // 'P'
        assertEquals(0x4B, payload[1].toInt() and 0xFF) // 'K'
        assertEquals(setOf("acquisition.json", "getprop.txt", "dumpsys/large.bin"), unzip(payload).keys)
    }

    @Test
    fun `compression shrinks compressible data`() {
        val vault = InMemoryKeyVault()
        val raw = ("I/ActivityManager: state=1 foo=bar\n".repeat(100_000)).toByteArray()
        val atRest = encrypt(vault, listOf(Entry("logcat.txt") { ByteArrayInputStream(raw) }))
        assertTrue(atRest.size < raw.size / 5, "expected >5x, got ${raw.size} -> ${atRest.size}")
    }

    @Test
    fun `production export to a passphrase recipient decrypts with the passphrase`() {
        val vault = InMemoryKeyVault()
        val atRest = encrypt(vault, entries())
        val pass = "correct horse battery staple".toByteArray()

        val exportBytes = ByteArrayOutputStream().also {
            AgeExporter.export(ByteArrayInputStream(atRest), vault, ScryptRecipient(pass, logN = 10), it)
        }.toByteArray()

        assertArrayEquals(bodyOf(atRest), bodyOf(exportBytes), "payload copied verbatim")
        val recovered = decryptZip(exportBytes, listOf(ScryptIdentity(pass)))
        entries().forEach { e -> assertArrayEquals(e.open().readBytes(), recovered[e.name], e.name) }
    }

    @Test
    fun `a stripped payload is rejected, not read as an empty archive`() {
        val vault = InMemoryKeyVault()
        val atRest = encrypt(vault, entries())
        // Keep the header + 16-byte payload nonce only — i.e. zero STREAM chunks.
        val payloadStart = atRest.size - bodyOf(atRest).size
        val truncated = atRest.copyOfRange(0, payloadStart + 16)
        assertThrows(AgeFormatException::class.java) {
            AgePayload.open(ByteArrayRandomAccess(truncated), listOf(KeyVaultIdentity(vault)))
        }
    }

    @Test
    fun `exportedSize equals the actual exported byte count`() {
        val vault = InMemoryKeyVault()
        val atRest = encrypt(vault, entries())
        val pass = "correct horse battery staple".toByteArray()

        val predicted = AgeExporter.exportedSize(
            ByteArrayInputStream(atRest), vault, ScryptRecipient(pass, logN = 10), atRest.size.toLong(),
        )
        val actual = ByteArrayOutputStream().also {
            AgeExporter.export(ByteArrayInputStream(atRest), vault, ScryptRecipient(pass, logN = 10), it)
        }.size().toLong()

        // The share provider answers OpenableColumns.SIZE from exportedSize before
        // streaming the export; a mismatch silently truncates/pads the shared file.
        assertEquals(actual, predicted)
    }

    @Test
    fun `verbatim age export copies payload and decrypts under recipient key`() {
        val vault = InMemoryKeyVault()
        val atRest = encrypt(vault, entries())

        val identity = X25519Identity.generate()
        val exportBytes = ByteArrayOutputStream().also {
            AgeExporter.export(ByteArrayInputStream(atRest), vault, identity.recipient(), it)
        }.toByteArray()

        assertArrayEquals(bodyOf(atRest), bodyOf(exportBytes), "payload copied verbatim")
        val recovered = decryptZip(exportBytes, listOf(identity))
        entries().forEach { e -> assertArrayEquals(e.open().readBytes(), recovered[e.name], e.name) }

        System.getProperty("bugbane.dumpExport")?.let { dir ->
            File(dir, "bugbane_export.age").writeBytes(exportBytes)
        }
    }

    private fun bodyOf(age: ByteArray): ByteArray {
        var idx = 0
        while (idx < age.size) {
            var j = idx
            while (j < age.size && age[j] != '\n'.code.toByte()) j++
            if (String(age, idx, j - idx, Charsets.US_ASCII).startsWith("---")) return age.copyOfRange(j + 1, age.size)
            idx = j + 1
        }
        error("no age footer found")
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
