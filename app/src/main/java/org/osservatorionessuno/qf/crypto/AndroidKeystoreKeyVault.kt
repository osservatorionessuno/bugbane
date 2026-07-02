package org.osservatorionessuno.qf.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Production [KeyVault] backed by the Android Keystore.
 *
 * The key-encryption-key (KEK) is a non-exportable AES-256-GCM key generated in
 * hardware — **StrongBox** (a discrete secure element, e.g. Titan M2) when the
 * device has one, otherwise the **TEE**. The backing is reflected in
 * [stanzaType]:
 *
 *  - `bugbane-se`  — StrongBox: credential brute-force is rate-limited by a
 *    separate tamper-resistant chip; the system biometric/credential gate is
 *    considered sufficient.
 *  - `bugbane-tee` — TEE only: a capable forensic adversary may extract the
 *    hardware secret and brute-force a weak PIN offline. A reader seeing this
 *    type should warn the user and offer an additional high-entropy passphrase
 *    factor (combine the KEK with `Argon2id(passphrase)` before wrapping).
 *
 * For now the key auto-unlocks (`setUserAuthenticationRequired(false)`) so the
 * pipeline is transparent; per-use biometric auth is a follow-up.
 */
class AndroidKeystoreKeyVault private constructor(
    private val key: SecretKey,
    override val stanzaType: String,
) : KeyVault {

    override fun wrap(fileKey: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ct = cipher.doFinal(fileKey)
        // [ivLen:1][iv][ciphertext+tag]
        return byteArrayOf(iv.size.toByte()) + iv + ct
    }

    override fun unwrap(blob: ByteArray): ByteArray {
        val ivLen = blob[0].toInt() and 0xFF
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(blob, 1 + ivLen, blob.size - 1 - ivLen)
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "bugbane.acquisition.kek"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        const val STANZA_STRONGBOX = "bugbane-se"
        const val STANZA_TEE = "bugbane-tee"

        /** Load the existing acquisition KEK or create one (StrongBox-preferred). */
        fun getOrCreate(): AndroidKeystoreKeyVault {
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            val existing = (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            if (existing != null) {
                return AndroidKeystoreKeyVault(existing, stanzaTypeOf(existing))
            }
            return try {
                generate(strongBox = true)
            } catch (_: StrongBoxUnavailableException) {
                generate(strongBox = false)
            }
        }

        private fun generate(strongBox: Boolean): AndroidKeystoreKeyVault {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Transparent for now; switch to true + per-use BiometricPrompt later.
                .setUserAuthenticationRequired(false)
                .apply { if (strongBox) setIsStrongBoxBacked(true) }
                .build()
            generator.init(spec)
            val key = generator.generateKey()
            return AndroidKeystoreKeyVault(key, stanzaTypeOf(key))
        }

        /** Determine whether [key] lives in StrongBox (SE) or the TEE. */
        private fun stanzaTypeOf(key: SecretKey): String {
            val factory = SecretKeyFactory.getInstance(key.algorithm, KEYSTORE)
            val info = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
            val strongBox = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
            } else {
                // Pre-31 can't distinguish SE from TEE; treat as TEE (the safer warning).
                false
            }
            return if (strongBox) STANZA_STRONGBOX else STANZA_TEE
        }
    }
}
