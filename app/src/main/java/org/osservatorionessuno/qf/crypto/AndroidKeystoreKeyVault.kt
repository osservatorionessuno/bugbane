package org.osservatorionessuno.qf.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Production [KeyVault] backed by the Android Keystore.
 *
 * The key-encryption-key (KEK) is a non-exportable AES-256-GCM key generated in
 * hardware — **StrongBox** (a discrete secure element, e.g. Titan M2) when the
 * device has one, otherwise the **TEE**.
 *
 * Keys created with `requireAuth` demand a **recent** user authentication
 * (biometric if enrolled, else the lock-screen credential — the screen unlock
 * itself counts): [wrap]/[unwrap] throw
 * [android.security.keystore.UserNotAuthenticatedException] once the last
 * authentication is older than [AUTH_WINDOW_SECONDS]; show a
 * `BiometricPrompt` to refresh the window and retry.
 */
class AndroidKeystoreKeyVault private constructor(
    private val key: SecretKey,
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
        /** StrongBox or fail: [StrongBoxUnavailableException] propagates to the caller. */
        REQUIRE,
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        /** How long a successful authentication arms auth-gated keys. */
        const val AUTH_WINDOW_SECONDS = 10 * 60

        /**
         * Load or create a vault for [alias]. [requireAuth] (creation-time only)
         * gates key operations behind a biometric/credential authentication no
         * older than [AUTH_WINDOW_SECONDS]; it requires a secure lock screen.
         */
        @JvmStatic
        @JvmOverloads
        fun getOrCreateKeyVault(
            alias: String,
            strongBoxPolicy: StrongBoxPolicy,
            requireAuth: Boolean = false,
        ): AndroidKeystoreKeyVault {
            val key = getOrCreateKey(alias, strongBoxPolicy, requireAuth)
            return AndroidKeystoreKeyVault(key)
        }

        /** Whether a key for [alias] already exists (never creates one). */
        @JvmStatic
        fun keyExists(alias: String): Boolean {
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            return ks.containsAlias(alias)
        }

        /** Delete the key for [alias] if present. */
        @JvmStatic
        fun deleteKey(alias: String) {
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        }

        private fun getOrCreateKey(
            alias: String,
            strongBoxPolicy: StrongBoxPolicy,
            requireAuth: Boolean,
        ): SecretKey {
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }

            return when (strongBoxPolicy) {
                StrongBoxPolicy.PREFER -> try {
                    generateAesKey(alias, strongBox = true, requireAuth = requireAuth)
                } catch (_: StrongBoxUnavailableException) {
                    generateAesKey(alias, strongBox = false, requireAuth = requireAuth)
                }
                StrongBoxPolicy.NEVER -> generateAesKey(alias, strongBox = false, requireAuth = requireAuth)
                StrongBoxPolicy.REQUIRE -> generateAesKey(alias, strongBox = true, requireAuth = requireAuth)
            }
        }

        private fun generateAesKey(
            alias: String,
            strongBox: Boolean,
            requireAuth: Boolean,
        ): SecretKey {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setIsStrongBoxBacked(strongBox)
                .apply {
                    if (requireAuth) {
                        setUserAuthenticationRequired(true)
                        // Any successful authentication — the screen unlock included —
                        // arms the key for AUTH_WINDOW_SECONDS; outside the window
                        // operations throw UserNotAuthenticatedException and the caller
                        // prompts. Credential is always accepted so devices without
                        // biometrics (or after biometric lockout) still work.
                        setUserAuthenticationParameters(
                            AUTH_WINDOW_SECONDS,
                            KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                        )
                        // Enrolling a new fingerprint already requires the device
                        // credential, which this key accepts — invalidating on enrollment
                        // would only risk data loss without adding protection.
                        setInvalidatedByBiometricEnrollment(false)
                        setUnlockedDeviceRequired(true)
                    } else {
                        setUserAuthenticationRequired(false)
                    }
                }
                .build()
            generator.init(spec)
            return generator.generateKey()
        }
    }
}
