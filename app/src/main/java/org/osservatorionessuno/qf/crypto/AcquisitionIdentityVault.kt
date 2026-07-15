package org.osservatorionessuno.qf.crypto

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.osservatorionessuno.bugbane.R
import org.osservatorionessuno.qf.crypto.age.AgeIdentity
import org.osservatorionessuno.qf.crypto.age.X25519Identity
import org.osservatorionessuno.qf.crypto.age.X25519Recipient
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKeyFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The device **acquisition identity**: a single X25519 keypair every acquisition
 * is encrypted to.
 *
 * The public key is stored in the clear, so *acquiring* never needs a secret and
 * never prompts. The private key — which reads or exports an acquisition — is
 * stored wrapped according to the tier chosen for the device (see bugbane#86):
 *
 *  - [Tier.STRONGBOX] — wrapped by a StrongBox AES key requiring a fresh
 *    biometric/credential authentication per operation. Unextractable, and the
 *    secure element makes a boot-chain (BFU) attack inert.
 *  - [Tier.TEE_AUTH] — same, in the TEE, for devices without a secure element
 *    that keep only the fingerprint gate. Holds against plain root; a strong
 *    screen lock is what protects it against forensic extraction.
 *  - [Tier.PASSPHRASE] — the identity is Argon2id-sealed with a passphrase and
 *    device-bound by a plain keystore key. The only tier for devices with no
 *    secure lock. A forensic adversary still faces an offline Argon2id search.
 *  - [Tier.STRONGBOX_PASSPHRASE] — opt-in on secure element devices: Argon2id
 *    seal inside, auth-gated StrongBox key outside (fingerprint *and* passphrase).
 *
 * Devices with no secure lock (or no hardware keystore) have no identity created
 * at onboarding; the first acquisition is encrypted to an in-memory ephemeral
 * keypair, and the post-acquisition "set a password" step seals *that* keypair
 * so the archive already written stays readable.
 */
object AcquisitionIdentityVault {
    // Single file, written atomically, so the public key and sealed blob can never
    // be left in a half-written / mismatched state by a crash. Layout:
    //   [public key: PUB_SIZE][tier tag: 1][sealed blob …]
    private const val IDENTITY_FILE = "acquisition_identity"
    private const val PUB_SIZE = 32

    private const val STRONGBOX_KEY_ALIAS = "bugbane.acquisition.identity.se"
    private const val TEE_AUTH_KEY_ALIAS = "bugbane.acquisition.identity.tee.auth"
    private const val TEE_BIND_KEY_ALIAS = "bugbane.acquisition.identity.tee"
    private const val HW_PROBE_ALIAS = "bugbane.hwprobe"

    /** Transparent KEK used before the identity scheme; kept only to read old archives. */
    private const val LEGACY_KEK_ALIAS = "bugbane.acquisition.kek"

    private const val SECRET_SIZE = 32

    private const val TIER_STRONGBOX: Byte = 1
    private const val TIER_TEE_AUTH: Byte = 2
    private const val TIER_PASSPHRASE: Byte = 3
    private const val TIER_STRONGBOX_PASSPHRASE: Byte = 4

    enum class Tier(internal val tag: Byte) {
        STRONGBOX(TIER_STRONGBOX),
        TEE_AUTH(TIER_TEE_AUTH),
        PASSPHRASE(TIER_PASSPHRASE),
        STRONGBOX_PASSPHRASE(TIER_STRONGBOX_PASSPHRASE),
        ;

        val usesBiometric: Boolean get() = this == STRONGBOX || this == TEE_AUTH || this == STRONGBOX_PASSPHRASE
        val usesPassphrase: Boolean get() = this == PASSPHRASE || this == STRONGBOX_PASSPHRASE
    }

    /** Thrown when the user cancels or fails the biometric/credential prompt. */
    class UserAuthenticationException(message: String) : Exception(message)

    /** What the post-acquisition "set a password" step should offer, by device tier. */
    enum class PasswordPromptKind {
        /** No secure lock: a password is the only protection and must be set. */
        MANDATORY,

        /** TEE-only device with the fingerprint gate: a password is strongly encouraged, skippable. */
        TEE_ENCOURAGED,

        /** Secure element device: a password is an optional extra factor, freely declined. */
        SE_OPTIONAL,

        /** Already has a password, or no prompt applies. */
        NONE,
    }

    fun passwordPromptKind(context: Context): PasswordPromptKind = when (tier(context)) {
        Tier.PASSPHRASE, Tier.STRONGBOX_PASSPHRASE -> PasswordPromptKind.NONE
        Tier.STRONGBOX -> PasswordPromptKind.SE_OPTIONAL
        Tier.TEE_AUTH -> PasswordPromptKind.TEE_ENCOURAGED
        null -> if (hasPendingEphemeral()) PasswordPromptKind.MANDATORY else PasswordPromptKind.NONE
    }

    // In-memory secret for the first acquisition on a device with no persisted
    // identity yet (no secure lock). Sealed by the mandatory password step; lost
    // with the process if the user never sets one (the orphan archive is then
    // swept on next launch).
    @Volatile
    private var pendingSecret: ByteArray? = null

    @Volatile
    private var hwKeystoreCache: Boolean? = null

    // ---------------------------------------------------------- capabilities

    fun isDeviceSecure(context: Context): Boolean =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure

    fun strongBoxAvailable(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    /** Whether the keystore is hardware-backed (TEE/StrongBox) rather than software (emulator). */
    fun hasHardwareKeystore(): Boolean {
        hwKeystoreCache?.let { return it }
        // null = the probe threw (e.g. keystore momentarily unavailable). Only a
        // genuine hardware/software determination is cached; a transient failure
        // must NOT be memoized, or a StrongBox device could be permanently
        // downgraded to the password tier for the process lifetime.
        val probed: Boolean? = runCatching {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(HW_PROBE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            val key = gen.generateKey()
            val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
                .getKeySpec(key, KeyInfo::class.java) as KeyInfo
            val hw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                info.securityLevel != KeyProperties.SECURITY_LEVEL_SOFTWARE
            } else {
                @Suppress("DEPRECATION") info.isInsideSecureHardware
            }
            AndroidKeystoreKeyVault.deleteKey(HW_PROBE_ALIAS)
            hw
        }.getOrNull()
        if (probed != null) hwKeystoreCache = probed
        return probed ?: false
    }

    /**
     * Whether the fingerprint onboarding step applies: a hardware keystore plus a
     * secure lock are both required to create an auth-gated key. Otherwise the
     * device goes straight to the mandatory-password path.
     */
    fun onboardingUsesBiometric(context: Context): Boolean =
        hasHardwareKeystore() && isDeviceSecure(context)

    // ---------------------------------------------------------------- state

    fun isInitialized(context: Context): Boolean = identityBytes(context) != null

    /** The tier chosen at setup, or null if no identity is persisted yet. */
    fun tier(context: Context): Tier? {
        val bytes = identityBytes(context) ?: return null
        val tag = bytes[PUB_SIZE]
        return Tier.entries.firstOrNull { it.tag == tag }
    }

    // ------------------------------------------------------------ recipients

    /**
     * Recipient a new acquisition is encrypted to. If an identity is persisted,
     * its public key; otherwise an in-memory ephemeral keypair (reused until the
     * password step seals it). No secret is unlocked, so this never prompts.
     */
    fun recipient(context: Context): X25519Recipient {
        persistedPublicKey(context)?.let { return X25519Recipient(it) }
        val secret = pendingSecret ?: newSecret().also { pendingSecret = it }
        return X25519Identity(secret).recipient()
    }

    fun hasPendingEphemeral(): Boolean = pendingSecret != null

    // --------------------------------------------------- orphan bookkeeping

    private const val PREFS = "acquisition_protection"
    private const val KEY_UNSEALED = "unsealed_acquisitions"

    // Each entry is "absolutePathbase64(ephemeral public key)": the key the
    // archive was encrypted to. This lets [sweepOrphans] tell a genuine orphan
    // (its ephemeral key never became the persisted identity) from an archive that
    // *was* sealed but whose record wasn't cleared (e.g. a crash between store()
    // and clearUnsealed) — the latter must not be deleted.
    private const val UNSEALED_SEP = ""

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Record an acquisition encrypted to the in-memory ephemeral identity, tagged
     * with that identity's public key. Until the password step seals it the
     * archive is only decryptable within this process; if the process dies first
     * (without sealing) [sweepOrphans] deletes it.
     */
    fun markUnsealed(context: Context, acquisitionDir: File) {
        val pub = pendingSecret?.let { publicKeyOf(it) } ?: return
        val entry = acquisitionDir.absolutePath + UNSEALED_SEP + b64(pub)
        val set = prefs(context).getStringSet(KEY_UNSEALED, emptySet())!!.toMutableSet()
        set.removeAll { it.substringBefore(UNSEALED_SEP) == acquisitionDir.absolutePath }
        set.add(entry)
        prefs(context).edit().putStringSet(KEY_UNSEALED, set).apply()
    }

    private fun clearUnsealed(context: Context) =
        prefs(context).edit().remove(KEY_UNSEALED).apply()

    private fun b64(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    // Set when an invalidated identity is discarded (see [beginRecovery]): the
    // device is back to "no identity", and the user must actively re-establish
    // protection — even on a device with no secure lock, where first-run onboarding
    // would otherwise defer the password. Cleared automatically once a new identity
    // is persisted (see [store]).
    private const val KEY_RECOVERY_PENDING = "recovery_pending"

    private const val KEY_PROMPT_DISMISSED = "password_prompt_dismissed"

    /** Whether the user has declined the optional/encouraged password prompt (never for [PasswordPromptKind.MANDATORY]). */
    fun isPasswordPromptDismissed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PROMPT_DISMISSED, false)

    fun setPasswordPromptDismissed(context: Context) =
        prefs(context).edit().putBoolean(KEY_PROMPT_DISMISSED, true).apply()

    /**
     * Delete acquisitions written to an ephemeral identity that was never sealed
     * (the process died before the mandatory password step), since their key is
     * gone and they can never be read. An archive whose recorded ephemeral key is
     * now the persisted identity's public key **was** sealed (its record just
     * wasn't cleared) and is kept. No-op while an ephemeral identity is still live
     * in this process (e.g. across a configuration change). Returns the count.
     */
    fun sweepOrphans(context: Context): Int {
        if (pendingSecret != null) return 0
        val set = prefs(context).getStringSet(KEY_UNSEALED, emptySet())!!
        if (set.isEmpty()) return 0
        val persistedPub = persistedPublicKey(context)?.let { b64(it) }
        var deleted = 0
        for (entry in set) {
            val path = entry.substringBefore(UNSEALED_SEP)
            val recordedPub = entry.substringAfter(UNSEALED_SEP, "")
            // Delete only when we can confirm it's an orphan: a recorded key that
            // is NOT the current persisted identity (so its private key is gone).
            // A match means it was sealed; an empty/legacy record can't be
            // confirmed, so leave it rather than risk deleting readable data.
            if (recordedPub.isNotEmpty() && recordedPub != persistedPub) {
                if (File(path).deleteRecursively()) deleted++
            }
        }
        clearUnsealed(context)
        return deleted
    }

    /** Identity for archives wrapped by the legacy transparent KEK, if that key still exists. */
    fun legacyIdentities(): List<AgeIdentity> =
        if (AndroidKeystoreKeyVault.keyExists(LEGACY_KEK_ALIAS)) {
            listOf(KeyVaultIdentity(AndroidKeystoreKeyVault.getOrCreateKeyVault(LEGACY_KEK_ALIAS, AndroidKeystoreKeyVault.StrongBoxPolicy.PREFER)))
        } else {
            emptyList()
        }

    // --------------------------------------------------------------- setup

    /**
     * [Tier.STRONGBOX] setup (onboarding on secure element devices). One
     * biometric/credential prompt, which also proves the gate works here.
     *
     * @throws android.security.keystore.StrongBoxUnavailableException fall back to [setupTeeAuth].
     * @throws UserAuthenticationException when the prompt is cancelled/fails.
     */
    suspend fun setupStrongBox(activity: Context) =
        setupAuthGated(activity, strongBoxVault(), TIER_STRONGBOX) { it }

    /** [Tier.TEE_AUTH] setup (onboarding on TEE-only devices). One biometric/credential prompt. */
    suspend fun setupTeeAuth(activity: Context) =
        setupAuthGated(activity, teeAuthVault(), TIER_TEE_AUTH) { it }

    private suspend fun setupAuthGated(
        activity: Context,
        vault: AndroidKeystoreKeyVault,
        tierTag: Byte,
        sealInner: suspend (ByteArray) -> ByteArray,
    ) {
        check(!isInitialized(activity)) { "acquisition identity already initialized" }
        val secret = newSecret()
        try {
            val inner = sealInner(secret)
            val cipher = authenticate(
                activity,
                vault.beginWrap(),
                activity.getString(R.string.acquisition_protect_prompt_title),
                activity.getString(R.string.acquisition_protect_prompt_subtitle),
            )
            store(activity, publicKeyOf(secret), byteArrayOf(tierTag) + vault.finishWrap(cipher, inner))
        } finally {
            secret.fill(0)
        }
    }

    // ----------------------------------------------- password step (deferred)

    /**
     * Establish a [Tier.PASSPHRASE] identity sealed with [passphrase]. Seals the
     * pending ephemeral identity a just-run acquisition was encrypted to, or — when
     * there is none (onboarding without an acquisition, or recovery) — mints a fresh
     * keypair. The mandatory path for devices with no secure lock. Argon2id runs on
     * [Dispatchers.Default].
     */
    suspend fun sealPendingWithPassphrase(context: Context, passphrase: ByteArray) {
        val secret = pendingSecret ?: newSecret()
        try {
            val sealed = withContext(Dispatchers.Default) { PassphraseKeyWrap.seal(secret, passphrase) }
            store(context, publicKeyOf(secret), byteArrayOf(TIER_PASSPHRASE) + teeBindVault().wrap(sealed))
        } finally {
            secret.fill(0)
        }
        pendingSecret = null
        // Any acquisitions encrypted to this now-persisted identity are readable, so
        // they are no longer orphan candidates.
        clearUnsealed(context)
    }

    /**
     * Migrate an existing [Tier.TEE_AUTH] identity to [Tier.PASSPHRASE]: unlock
     * once (biometric), Argon2id-seal, device-bind, and drop the auth-gated key —
     * so the fingerprint is no longer used. Argon2id runs on [Dispatchers.Default].
     */
    suspend fun migrateTeeAuthToPassphrase(activity: Context, passphrase: ByteArray) {
        val secret = authUnwrap(activity, teeAuthVault(), TIER_TEE_AUTH)
        try {
            val sealed = withContext(Dispatchers.Default) { PassphraseKeyWrap.seal(secret, passphrase) }
            store(activity, publicKeyOf(secret), byteArrayOf(TIER_PASSPHRASE) + teeBindVault().wrap(sealed))
            AndroidKeystoreKeyVault.deleteKey(TEE_AUTH_KEY_ALIAS)
        } finally {
            secret.fill(0)
        }
    }

    /**
     * Add a passphrase to an existing [Tier.STRONGBOX] identity → [Tier.STRONGBOX_PASSPHRASE]:
     * Argon2id seal inside, re-wrapped under the StrongBox key. Two biometric
     * prompts (unwrap, then re-wrap — per-operation auth). Argon2id on [Dispatchers.Default].
     */
    suspend fun addStrongBoxPassphrase(activity: Context, passphrase: ByteArray) {
        val vault = strongBoxVault()
        val secret = authUnwrap(activity, vault, TIER_STRONGBOX)
        try {
            val sealed = withContext(Dispatchers.Default) { PassphraseKeyWrap.seal(secret, passphrase) }
            val cipher = authenticate(
                activity,
                vault.beginWrap(),
                activity.getString(R.string.acquisition_protect_prompt_title),
                activity.getString(R.string.acquisition_protect_prompt_subtitle),
            )
            store(activity, publicKeyOf(secret), byteArrayOf(TIER_STRONGBOX_PASSPHRASE) + vault.finishWrap(cipher, sealed))
        } finally {
            secret.fill(0)
        }
    }

    // --------------------------------------------------------------- unlock

    /** Unlock a [Tier.STRONGBOX] or [Tier.TEE_AUTH] identity with one biometric/credential prompt. */
    suspend fun unlockWithBiometric(activity: Context): X25519Identity =
        when (tier(activity)) {
            Tier.STRONGBOX -> X25519Identity(authUnwrap(activity, strongBoxVault(), TIER_STRONGBOX))
            Tier.TEE_AUTH -> X25519Identity(authUnwrap(activity, teeAuthVault(), TIER_TEE_AUTH))
            else -> throw IllegalStateException("tier does not unlock with biometric alone")
        }

    /**
     * First half of a [Tier.STRONGBOX_PASSPHRASE] unlock: one prompt unwraps the
     * outer StrongBox layer and returns the Argon2id-sealed inner blob. Feed it to
     * [openPassphraseInner] (retryable without further prompts); zero it when done.
     */
    suspend fun unlockStrongBoxOuter(activity: Context): ByteArray =
        authUnwrap(activity, strongBoxVault(), TIER_STRONGBOX_PASSPHRASE)

    /** Open an Argon2id inner blob with the passphrase. Null on wrong passphrase. Runs on [Dispatchers.Default]. */
    suspend fun openPassphraseInner(inner: ByteArray, passphrase: ByteArray): X25519Identity? =
        withContext(Dispatchers.Default) { PassphraseKeyWrap.open(inner, passphrase) }?.let { X25519Identity(it) }

    /** Unlock a [Tier.PASSPHRASE] identity with the passphrase. Null on wrong passphrase. Runs on [Dispatchers.Default]. */
    suspend fun unlockWithPassphrase(context: Context, passphrase: ByteArray): X25519Identity? {
        val sealed = teeBindVault().unwrap(sealedBlob(context, TIER_PASSPHRASE))
        return openPassphraseInner(sealed, passphrase)
    }

    // --------------------------------------------- screen-lock invalidation

    /**
     * Android permanently invalidates an auth-gated key when the secure lock
     * screen is *removed* (a credential *change* preserves it, via the stable
     * Gatekeeper SID). True when we're on a biometric-gated tier but the device is
     * no longer secured — the identity is now unusable and its acquisitions are
     * unrecoverable. Detect this on resume to explain the loss rather than failing
     * with a generic error later.
     */
    fun isIdentityInvalidatedByLockRemoval(context: Context): Boolean =
        tier(context)?.usesBiometric == true && !isDeviceSecure(context)

    /**
     * Discard an identity whose auth-gated key was invalidated by lock removal:
     * remove the identity files and every keystore key so protection can be set up
     * fresh on the next launch. Acquisitions encrypted to it are already
     * unreadable and are deleted separately by the caller.
     */
    fun discardIdentity(context: Context) {
        identityFile(context).delete()
        AndroidKeystoreKeyVault.deleteKey(STRONGBOX_KEY_ALIAS)
        AndroidKeystoreKeyVault.deleteKey(TEE_AUTH_KEY_ALIAS)
        AndroidKeystoreKeyVault.deleteKey(TEE_BIND_KEY_ALIAS)
        prefs(context).edit().remove(KEY_UNSEALED).remove(KEY_PROMPT_DISMISSED).apply()
        pendingSecret = null
    }

    /**
     * Discard an invalidated identity and mark that protection must be re-established
     * before the app is usable again. Unlike [discardIdentity] this also raises the
     * recovery flag, so onboarding presents the "set a screen lock or a password"
     * step even on a device that no longer has a secure lock (see [SlideshowManager]).
     * The caller deletes the now-unreadable acquisitions.
     */
    fun beginRecovery(context: Context) {
        discardIdentity(context)
        prefs(context).edit().putBoolean(KEY_RECOVERY_PENDING, true).apply()
    }

    /** Whether a discarded-identity recovery is still awaiting a fresh protection setup. */
    fun isRecoveryPending(context: Context): Boolean =
        !isInitialized(context) && prefs(context).getBoolean(KEY_RECOVERY_PENDING, false)

    // ------------------------------------------------------ change passphrase

    /** Change the passphrase on [Tier.PASSPHRASE]. False if [old] is wrong. Argon2id on [Dispatchers.Default]. */
    suspend fun changePassphrase(context: Context, old: ByteArray, new: ByteArray): Boolean {
        val vault = teeBindVault()
        val sealed = vault.unwrap(sealedBlob(context, TIER_PASSPHRASE))
        val secret = withContext(Dispatchers.Default) { PassphraseKeyWrap.open(sealed, old) } ?: return false
        try {
            val reSealed = withContext(Dispatchers.Default) { PassphraseKeyWrap.seal(secret, new) }
            // Same identity, so the public key is unchanged; rewrite the whole file.
            store(context, publicKeyOf(secret), byteArrayOf(TIER_PASSPHRASE) + vault.wrap(reSealed))
        } finally {
            secret.fill(0)
        }
        return true
    }

    /**
     * Change the passphrase on [Tier.STRONGBOX_PASSPHRASE]. False if [old] is wrong.
     * Two biometric prompts (unwrap, then re-wrap). Argon2id on [Dispatchers.Default].
     */
    suspend fun changeStrongBoxPassphrase(activity: Context, old: ByteArray, new: ByteArray): Boolean {
        val vault = strongBoxVault()
        val inner = authUnwrap(activity, vault, TIER_STRONGBOX_PASSPHRASE)
        val secret = withContext(Dispatchers.Default) { PassphraseKeyWrap.open(inner, old) }
            ?: run { inner.fill(0); return false }
        try {
            val reSealed = withContext(Dispatchers.Default) { PassphraseKeyWrap.seal(secret, new) }
            val cipher = authenticate(
                activity,
                vault.beginWrap(),
                activity.getString(R.string.acquisition_protect_prompt_title),
                activity.getString(R.string.acquisition_protect_prompt_subtitle),
            )
            store(activity, publicKeyOf(secret), byteArrayOf(TIER_STRONGBOX_PASSPHRASE) + vault.finishWrap(cipher, reSealed))
        } finally {
            secret.fill(0)
            inner.fill(0)
        }
        return true
    }

    // ------------------------------------------------------------- internals

    private fun newSecret(): ByteArray = ByteArray(SECRET_SIZE).also { SecureRandom().nextBytes(it) }

    private fun publicKeyOf(secret: ByteArray): ByteArray {
        val identity = X25519Identity(secret)
        return identity.publicKeyBytes()
    }

    private fun strongBoxVault() = AndroidKeystoreKeyVault.getOrCreateKeyVault(
        STRONGBOX_KEY_ALIAS, AndroidKeystoreKeyVault.StrongBoxPolicy.REQUIRE, requireAuth = true,
    )

    private fun teeAuthVault() = AndroidKeystoreKeyVault.getOrCreateKeyVault(
        TEE_AUTH_KEY_ALIAS, AndroidKeystoreKeyVault.StrongBoxPolicy.NEVER, requireAuth = true,
    )

    private fun teeBindVault() = AndroidKeystoreKeyVault.getOrCreateKeyVault(
        TEE_BIND_KEY_ALIAS, AndroidKeystoreKeyVault.StrongBoxPolicy.NEVER,
    )

    private suspend fun authUnwrap(activity: Context, vault: AndroidKeystoreKeyVault, expectedTier: Byte): ByteArray {
        val blob = sealedBlob(activity, expectedTier)
        val cipher = authenticate(
            activity,
            vault.beginUnwrap(blob),
            activity.getString(R.string.acquisition_unlock_prompt_title),
            activity.getString(R.string.acquisition_unlock_prompt_subtitle),
        )
        return vault.finishUnwrap(cipher, blob)
    }

    private fun sealedBlob(context: Context, expectedTier: Byte): ByteArray {
        val bytes = identityBytes(context) ?: error("acquisition identity not initialized")
        check(bytes[PUB_SIZE] == expectedTier) { "acquisition identity tier mismatch" }
        return bytes.copyOfRange(PUB_SIZE + 1, bytes.size)
    }

    /**
     * Persist the identity as one atomic file: [publicKey] then the tier-tagged
     * [sealedBlob] the callers already build ([tier][wrapped]).
     */
    private fun store(context: Context, publicKey: ByteArray, sealedBlob: ByteArray) {
        require(publicKey.size == PUB_SIZE)
        writeAtomically(identityFile(context), publicKey + sealedBlob)
        // An identity now exists, so any pending recovery is resolved.
        prefs(context).edit().remove(KEY_RECOVERY_PENDING).apply()
    }

    /** Whole identity file if present and well-formed (public key + at least a tier byte). */
    private fun identityBytes(context: Context): ByteArray? {
        val f = identityFile(context)
        if (!f.exists()) return null
        val b = f.readBytes()
        return if (b.size > PUB_SIZE) b else null
    }

    private fun persistedPublicKey(context: Context): ByteArray? =
        identityBytes(context)?.copyOfRange(0, PUB_SIZE)

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) throw IOException("unable to write ${target.name}")
    }

    private fun identityFile(context: Context) = File(context.filesDir, IDENTITY_FILE)

    /** Show a biometric-or-credential prompt authorizing exactly [cipher]'s operation. */
    private suspend fun authenticate(
        context: Context,
        cipher: Cipher,
        title: String,
        subtitle: String,
    ): Cipher = suspendCancellableCoroutine { cont ->
        val prompt = BiometricPrompt.Builder(context)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
            .build()
        val signal = CancellationSignal()
        cont.invokeOnCancellation { signal.cancel() }
        prompt.authenticate(
            BiometricPrompt.CryptoObject(cipher),
            signal,
            context.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authorized = result.cryptoObject?.cipher
                    if (authorized != null) cont.resume(authorized)
                    else cont.resumeWithException(UserAuthenticationException("no cipher in authentication result"))
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    cont.resumeWithException(UserAuthenticationException(errString?.toString() ?: "error $errorCode"))
                }
            },
        )
    }
}
