package org.osservatorionessuno.qf.crypto.age

import kage.Age as KageAge
import kage.crypto.scrypt.ScryptIdentity as KageScryptIdentity
import kage.crypto.scrypt.ScryptRecipient as KageScryptRecipient
import kage.crypto.x25519.X25519Identity as KageX25519Identity
import kage.crypto.x25519.X25519Recipient as KageX25519Recipient
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.osservatorionessuno.qf.crypto.FileRandomAccess
import org.osservatorionessuno.qf.crypto.tempAgeFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class AgeTest {

    // Cover every chunk-boundary case: empty, partial, exactly one chunk, exact
    // multiple of the chunk size, and chunk+partial.
    private val payloads = listOf(
        ByteArray(0),
        "hello age".toByteArray(),
        ByteArray(65536) { (it % 251).toByte() },
        ByteArray(65536 * 2) { (it % 251).toByte() },
        ByteArray(65536 + 100) { ((it * 7) % 251).toByte() },
        ByteArray(200_000) { ((it * 31) % 251).toByte() },
    )

    private fun encrypt(recipients: List<AgeRecipient>, data: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> Age.encryptingStream(recipients, out).use { it.write(data) } }.toByteArray()

    private fun decrypt(bytes: ByteArray, ids: List<AgeIdentity>): ByteArray {
        val access = FileRandomAccess(tempAgeFile(bytes))
        try {
            return AgePayload.open(access, ids).stream().readBytes()
        } finally {
            access.close()
        }
    }

    private fun kageDecrypt(bytes: ByteArray, id: kage.Identity): ByteArray =
        ByteArrayOutputStream().also { KageAge.decryptStream(listOf(id), ByteArrayInputStream(bytes), it) }.toByteArray()

    @Test
    fun `round-trip at every chunk boundary, plus random access`() {
        val pass = "hunter2".toByteArray()
        for (d in payloads) {
            val ct = encrypt(listOf(ScryptRecipient(pass, logN = 10)), d)
            assertArrayEquals(d, decrypt(ct, listOf(ScryptIdentity(pass))), "size ${d.size}")
            if (d.size >= 1000) {
                val access = FileRandomAccess(tempAgeFile(ct))
                try {
                    val p = AgePayload.open(access, listOf(ScryptIdentity(pass)))
                    assertArrayEquals(d.copyOfRange(d.size - 500, d.size), p.read((d.size - 500).toLong(), 500))
                } finally {
                    access.close()
                }
            }
        }
    }

    @Test
    fun `interop X25519 both directions against kage (the age-CLI-proven reference)`() {
        val secret = ByteArray(32) { (it + 1).toByte() }
        val pub = AgePrimitives.x25519Base(secret)
        val kageId = KageX25519Identity(secret, pub)
        for (d in payloads) {
            // ours -> kage
            assertArrayEquals(d, kageDecrypt(encrypt(listOf(X25519Recipient(pub)), d), kageId), "ours->kage ${d.size}")
            // kage -> ours
            val kct = ByteArrayOutputStream()
            KageAge.encryptStream(listOf(KageX25519Recipient(pub)), ByteArrayInputStream(d), kct)
            assertArrayEquals(d, decrypt(kct.toByteArray(), listOf(X25519Identity(secret))), "kage->ours ${d.size}")
        }
    }

    @Test
    fun `interop scrypt both directions against kage`() {
        val pass = "correct horse battery staple".toByteArray()
        for (d in payloads) {
            assertArrayEquals(d, kageDecrypt(encrypt(listOf(ScryptRecipient(pass, logN = 10)), d), KageScryptIdentity(pass)), "ours->kage ${d.size}")
            val kct = ByteArrayOutputStream()
            KageAge.encryptStream(listOf(KageScryptRecipient(pass, workFactor = 10)), ByteArrayInputStream(d), kct)
            assertArrayEquals(d, decrypt(kct.toByteArray(), listOf(ScryptIdentity(pass))), "kage->ours ${d.size}")
        }
    }

    @Test
    fun `tampered header MAC is rejected`() {
        val pass = "p".toByteArray()
        val ct = encrypt(listOf(ScryptRecipient(pass, logN = 10)), "secret".toByteArray()).copyOf()
        // flip a bit in the base64 MAC region
        val footer = String(ct, 0, minOf(300, ct.size), Charsets.US_ASCII).indexOf("--- ")
        ct[footer + 5] = (ct[footer + 5].toInt() xor 0x01).toByte()
        assertThrows(AgeFormatException::class.java) { decrypt(ct, listOf(ScryptIdentity(pass))) }
    }

    @Test
    fun `malformed input throws AgeFormatException, never a raw panic`() {
        val garbage = ByteArray(100) { 0x41 } // "AAAA..."
        assertThrows(AgeFormatException::class.java) {
            val access = FileRandomAccess(tempAgeFile(garbage))
            try {
                AgePayload.open(access, listOf(ScryptIdentity("x".toByteArray())))
            } finally {
                access.close()
            }
        }
    }

    @Test
    fun `scrypt work factor is capped to prevent a decrypt DoS`() {
        // A hand-built "scrypt bomb" (logN=30 ≈ 1 TiB) must be rejected BEFORE scrypt
        // ever runs — i.e. an AgeFormatException, not an OOM.
        val bomb = AgeStanza("scrypt", listOf(AgeFormat.encodeArg(ByteArray(16)), "30"), ByteArray(32))
        assertThrows(AgeFormatException::class.java) {
            ScryptIdentity("pw".toByteArray()).unwrap(listOf(bomb))
        }
    }

    @Test
    fun `dump a file for a stock age CLI check`() {
        System.getProperty("bugbane.dumpAge")?.let { dir ->
            val secret = AgePrimitives.randomBytes(32)
            val pub = AgePrimitives.x25519Base(secret)
            File(dir, "ours.age").writeBytes(encrypt(listOf(X25519Recipient(pub)), "stock age interop OK\n".toByteArray()))
            File(dir, "ours_id.txt").writeText(KageX25519Identity(secret, pub).encodeToString())
        }
    }
}
