package org.osservatorionessuno.qf.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Abstraction over the secret that protects acquisition *file keys* at rest.
 *
 * The acquisition payload is encrypted with a random per-archive file key (age
 * semantics). That file key is wrapped by the KeyVault and stored as a recipient
 * stanza in the age header. On a device this is backed by the Android Keystore
 * (StrongBox when available, TEE otherwise); [InMemoryKeyVault] is the
 * transparent, auto-unlocking implementation used for tests and JVM tooling.
 *
 * The [stanzaType] differentiates the hardware backing so a reader can warn the
 * user (e.g. `bugbane-se` vs `bugbane-tee`).
 */
interface KeyVault {
    /** age stanza type written into the header, e.g. `bugbane-se` / `bugbane-tee`. */
    val stanzaType: String

    /** Encrypt (wrap) a file key; the returned blob becomes the stanza body. */
    fun wrap(fileKey: ByteArray): ByteArray

    /** Decrypt (unwrap) a blob previously produced by [wrap]. */
    fun unwrap(blob: ByteArray): ByteArray
}

/**
 * Software KeyVault holding the key-encryption-key (KEK) in memory and
 * auto-unlocking (no user authentication). Wraps with ChaCha20-Poly1305 (AEAD).
 *
 * Used for unit tests and any "transparent" mode; on Android the production
 * implementation lives in the app module and delegates to the Keystore.
 */
class InMemoryKeyVault(
    private val kek: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) },
    override val stanzaType: String = "bugbane-test",
) : KeyVault {

    override fun wrap(fileKey: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "ChaCha20"), IvParameterSpec(nonce))
        return nonce + c.doFinal(fileKey)
    }

    override fun unwrap(blob: ByteArray): ByteArray {
        val nonce = blob.copyOfRange(0, NONCE_LEN)
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "ChaCha20"), IvParameterSpec(nonce))
        return c.doFinal(blob, NONCE_LEN, blob.size - NONCE_LEN)
    }

    private companion object {
        const val TRANSFORM = "ChaCha20-Poly1305"
        const val NONCE_LEN = 12
    }
}
