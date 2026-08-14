package io.github.muntashirakon.adb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64

class AdbHostKeyTest {
    @Test
    fun `adbKeysLine emits base64 key and name with no terminator`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val publicKey = keyPair.public as RSAPublicKey

        val line = AdbHostKey.adbKeysLine(publicKey, "Bugbane")

        assertTrue(Regex("^[A-Za-z0-9+/=]+ Bugbane$").matches(line), "unexpected format: $line")

        val decoded = AndroidPubkey.decode(Base64.getDecoder().decode(line.substringBefore(' ')))
        assertEquals(publicKey.modulus, decoded.modulus)
        assertEquals(publicKey.publicExponent, decoded.publicExponent)
    }
}
