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
        val cipher = Cipher.getInstance(TRANSFORM) // nosemgrep: kotlin.lang.security.gcm-detection.gcm-detection
        cipher.init(Cipher.ENCRYPT_MODE, key) // nosemgrep: kotlin.lang.security.gcm-detection.gcm-detection
        val iv = cipher.iv
        val ct = cipher.doFinal(fileKey)
        // [ivLen:1][iv][ciphertext+tag]
        return byteArrayOf(iv.size.toByte()) + iv + ct
    }

    override fun unwrap(blob: ByteArray): ByteArray {
        val ivLen = blob[0].toInt() and 0xFF
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val cipher = Cipher.getInstance(TRANSFORM) // nosemgrep: kotlin.lang.security.gcm-detection.gcm-detection
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv)) // nosemgrep: kotlin.lang.security.gcm-detection.gcm-detection
        return cipher.doFinal(blob, 1 + ivLen, blob.size - 1 - ivLen)
    }

    /** How to pick StrongBox vs TEE when generating a new AES key. */
    enum class StrongBoxPolicy {
        /** Try StrongBox, fall back to TEE on [StrongBoxUnavailableException]. */
        PREFER,
        /** Always generate in the TEE. */
        NEVER,
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        const val STANZA_STRONGBOX = "bugbane-se"
        const val STANZA_TEE = "bugbane-tee"

        /** Load or create a vault for [alias]. */
        @JvmStatic
        fun getOrCreateKeyVault(
            alias: String,
            strongBoxPolicy: StrongBoxPolicy,
        ): AndroidKeystoreKeyVault {
            val key = getOrCreateKey(
                alias,
                strongBoxPolicy,
            )
            return AndroidKeystoreKeyVault(key, stanzaTypeOf(key))
        }

        private fun getOrCreateKey(
            alias: String,
            strongBoxPolicy: StrongBoxPolicy,
        ): SecretKey {
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }

            return when (strongBoxPolicy) {
                StrongBoxPolicy.PREFER -> try {
                    generateAesKey(alias, strongBox = true)
                } catch (_: StrongBoxUnavailableException) {
                    generateAesKey(alias, strongBox = false)
                }
                StrongBoxPolicy.NEVER -> generateAesKey(alias, strongBox = false)
            }
        }

        private fun generateAesKey(
            alias: String,
            strongBox: Boolean,
        ): SecretKey {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Auto-unlock for now (see class KDoc); tiered per-key user auth
                // (SE credential / TEE passphrase) is tracked as a separate PR.
                // Coupling auth to the StrongBox flag made key creation crash on
                // devices/emulators with no enrolled biometric.
                .setUserAuthenticationRequired(false)
                .setIsStrongBoxBacked(strongBox)
                .build()
            generator.init(spec)
            return generator.generateKey()
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
