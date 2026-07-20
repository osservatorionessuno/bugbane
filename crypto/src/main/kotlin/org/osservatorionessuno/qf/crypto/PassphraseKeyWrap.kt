package org.osservatorionessuno.qf.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.osservatorionessuno.qf.crypto.age.AgePrimitives

/**
 * Passphrase wrap of a small secret (e.g. the 32-byte acquisition identity)
 * using Argon2id + ChaCha20-Poly1305.
 *
 * This is the extra factor for devices whose Keystore is only TEE-backed (or
 * that have no secure lock at all): a forensic adversary who extracts the TEE
 * hardware secret and brute-forces a weak lock-screen PIN still has to crack
 * this passphrase offline, and Argon2id (memory-hard, RFC 9106) makes each
 * guess cost ~[MEMORY_KIB] KiB of RAM.
 *
 * Blob layout (all lengths fixed except the sealed payload):
 * ```
 *   [version:1][tCost:1][parallelism:1][memoryKiB:4 BE][salt:16][chacha20poly1305(secret)]
 * ```
 * The AEAD nonce is all-zero — safe because the wrap key is fresh per blob
 * (fresh random salt), same argument as the age scrypt stanza.
 */
object PassphraseKeyWrap {
    private const val VERSION: Byte = 1
    private const val SALT_SIZE = 16
    private const val HEADER_SIZE = 1 + 1 + 1 + 4 + SALT_SIZE

    // RFC 9106 §4 second recommendation, sized for mobile: 64 MiB, t=3, p=4.
    const val MEMORY_KIB = 64 * 1024
    const val T_COST = 3
    const val PARALLELISM = 4

    // Decrypt-side caps so a malicious blob can't force an OOM ("argon2 bomb").
    private const val MAX_MEMORY_KIB = 256 * 1024
    private const val MAX_T_COST = 16
    private const val MAX_PARALLELISM = 8

    /** Seal [secret] under [passphrase]. The derivation takes O(seconds) — call off the main thread. */
    fun seal(secret: ByteArray, passphrase: ByteArray): ByteArray {
        val salt = AgePrimitives.randomBytes(SALT_SIZE)
        val key = argon2id(passphrase, salt, MEMORY_KIB, T_COST, PARALLELISM)
        try {
            val header = ByteArray(HEADER_SIZE)
            header[0] = VERSION
            header[1] = T_COST.toByte()
            header[2] = PARALLELISM.toByte()
            for (i in 0..3) header[3 + i] = (MEMORY_KIB ushr (8 * (3 - i)) and 0xFF).toByte()
            salt.copyInto(header, 7)
            return header + AgePrimitives.chachaSeal(key, ZERO_NONCE, secret)
        } finally {
            key.fill(0)
        }
    }

    /** Open a blob produced by [seal]; returns null on a wrong passphrase (or tampered blob). */
    fun open(blob: ByteArray, passphrase: ByteArray): ByteArray? {
        val key = deriveKey(blob, passphrase)
        try {
            return openWithKey(blob, key)
        } finally {
            key.fill(0)
        }
    }

    /**
     * Run Argon2id over [passphrase] with the parameters embedded in [blob], returning
     * the 32-byte wrap key. This is the slow, memory-hard step — split out so a caller
     * may cache the key (see the acquisition password cache) and re-open without
     * re-deriving. The returned key alone does not open the blob without [openWithKey].
     */
    fun deriveKey(blob: ByteArray, passphrase: ByteArray): ByteArray {
        require(blob.size > HEADER_SIZE) { "passphrase wrap blob too short" }
        require(blob[0] == VERSION) { "unsupported passphrase wrap version ${blob[0]}" }
        val tCost = blob[1].toInt() and 0xFF
        val parallelism = blob[2].toInt() and 0xFF
        var memoryKiB = 0
        for (i in 0..3) memoryKiB = (memoryKiB shl 8) or (blob[3 + i].toInt() and 0xFF)
        require(tCost in 1..MAX_T_COST && parallelism in 1..MAX_PARALLELISM && memoryKiB in 8..MAX_MEMORY_KIB) {
            "unsafe argon2 parameters (m=$memoryKiB KiB, t=$tCost, p=$parallelism)"
        }
        val salt = blob.copyOfRange(7, HEADER_SIZE)
        return argon2id(passphrase, salt, memoryKiB, tCost, parallelism)
    }

    /** Open [blob] with a wrap key from [deriveKey]; null if the key is wrong or the blob was tampered. */
    fun openWithKey(blob: ByteArray, key: ByteArray): ByteArray? =
        runCatching {
            AgePrimitives.chachaOpen(key, ZERO_NONCE, blob, HEADER_SIZE, blob.size - HEADER_SIZE)
        }.getOrNull()

    internal fun argon2id(
        passphrase: ByteArray,
        salt: ByteArray,
        memoryKiB: Int,
        tCost: Int,
        parallelism: Int,
        secret: ByteArray? = null,
        additional: ByteArray? = null,
    ): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(memoryKiB)
            .withIterations(tCost)
            .withParallelism(parallelism)
            .withSalt(salt)
            .apply { secret?.let { withSecret(it) } }
            .apply { additional?.let { withAdditional(it) } }
            .build()
        val generator = Argon2BytesGenerator()
        generator.init(params)
        return ByteArray(32).also { generator.generateBytes(passphrase, it) }
    }

    private val ZERO_NONCE = ByteArray(12)
}
