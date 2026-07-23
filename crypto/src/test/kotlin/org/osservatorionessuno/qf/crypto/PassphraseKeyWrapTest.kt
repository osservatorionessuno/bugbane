package org.osservatorionessuno.qf.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.security.SecureRandom

class PassphraseKeyWrapTest {

    private fun randomSecret(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    @Test
    fun `seal then open roundtrips`() {
        val secret = randomSecret()
        val blob = PassphraseKeyWrap.seal(secret, "correct horse battery staple".toByteArray())
        val opened = PassphraseKeyWrap.open(blob, "correct horse battery staple".toByteArray())
        assertArrayEquals(secret, opened)
    }

    @Test
    fun `deriveKey then openWithKey equals open, and the key is reusable`() {
        val secret = randomSecret()
        val blob = PassphraseKeyWrap.seal(secret, "correct horse battery staple".toByteArray())
        // Derive once (the slow step), then open the blob repeatedly with the cached key.
        val key = PassphraseKeyWrap.deriveKey(blob, "correct horse battery staple".toByteArray())
        assertArrayEquals(secret, PassphraseKeyWrap.openWithKey(blob, key))
        assertArrayEquals(secret, PassphraseKeyWrap.openWithKey(blob, key))
    }

    @Test
    fun `a key derived from the wrong passphrase does not open`() {
        val blob = PassphraseKeyWrap.seal(randomSecret(), "right".toByteArray())
        val wrongKey = PassphraseKeyWrap.deriveKey(blob, "wrong".toByteArray())
        assertNull(PassphraseKeyWrap.openWithKey(blob, wrongKey))
    }

    @Test
    fun `wrong passphrase returns null`() {
        val blob = PassphraseKeyWrap.seal(randomSecret(), "right".toByteArray())
        assertNull(PassphraseKeyWrap.open(blob, "wrong".toByteArray()))
    }

    @Test
    fun `tampered blob returns null`() {
        val blob = PassphraseKeyWrap.seal(randomSecret(), "pass".toByteArray())
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        assertNull(PassphraseKeyWrap.open(blob, "pass".toByteArray()))
    }

    @Test
    fun `oversized argon2 parameters are rejected`() {
        val blob = PassphraseKeyWrap.seal(randomSecret(), "pass".toByteArray())
        // Forge memoryKiB = 2^30 KiB (a ~1 TiB "argon2 bomb").
        blob[3] = 0x40
        assertThrows(IllegalArgumentException::class.java) {
            PassphraseKeyWrap.open(blob, "pass".toByteArray())
        }
    }

    @Test
    fun `fresh salt per seal`() {
        val secret = randomSecret()
        val pass = "pass".toByteArray()
        val a = PassphraseKeyWrap.seal(secret, pass)
        val b = PassphraseKeyWrap.seal(secret, pass)
        assertEquals(a.size, b.size)
        assert(!a.contentEquals(b))
    }

    /** RFC 9106 §5.3 Argon2id test vector. */
    @Test
    fun `argon2id matches RFC 9106 vector`() {
        // argon2id, version 0x13, m=32 KiB, t=3, p=4, tag length 32,
        // password 32x 0x01, salt 16x 0x02, secret 8x 0x03, associated data 12x 0x04.
        val out = PassphraseKeyWrap.argon2id(
            passphrase = ByteArray(32) { 1 },
            salt = ByteArray(16) { 2 },
            memoryKiB = 32,
            tCost = 3,
            parallelism = 4,
            secret = ByteArray(8) { 3 },
            additional = ByteArray(12) { 4 },
        )
        val expected = intArrayOf(
            0x0d, 0x64, 0x0d, 0xf5, 0x8d, 0x78, 0x76, 0x6c,
            0x08, 0xc0, 0x37, 0xa3, 0x4a, 0x8b, 0x53, 0xc9,
            0xd0, 0x1e, 0xf0, 0x45, 0x2d, 0x75, 0xb6, 0x5e,
            0xb5, 0x25, 0x20, 0xe9, 0x6b, 0x01, 0xe6, 0x59,
        ).map { it.toByte() }.toByteArray()
        assertArrayEquals(expected, out)
    }
}
