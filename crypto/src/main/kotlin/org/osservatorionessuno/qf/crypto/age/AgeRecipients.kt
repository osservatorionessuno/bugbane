package org.osservatorionessuno.qf.crypto.age

/**
 * age spec "X25519 recipient" stanza:
 * ```
 *   -> X25519 base64(ephemeral share = X25519(eph secret, basepoint))
 *   encrypt[ HKDF[share || recipient pubkey, "age-encryption.org/v1/X25519"]
 *            ( X25519(eph secret, recipient pubkey) ) ]( file key )
 * ```
 * The body `encrypt[]` uses an all-zero nonce — safe because the wrap key is fresh
 * per stanza (fresh ephemeral). Low-order DH results are rejected (see `x25519`).
 */
class X25519Recipient(private val publicKey: ByteArray) : AgeRecipient {
    override fun wrap(fileKey: ByteArray): List<AgeStanza> {
        val ephemeral = AgePrimitives.randomBytes(32)
        val share = AgePrimitives.x25519Base(ephemeral)
        val shared = AgePrimitives.x25519(ephemeral, publicKey)
            ?: throw IllegalArgumentException("X25519 recipient is a low-order point")
        val wrapKey = AgePrimitives.hkdf(share + publicKey, shared, INFO, 32)
        val body = AgePrimitives.chachaSeal(wrapKey, ZERO_NONCE, fileKey)
        return listOf(AgeStanza("X25519", listOf(AgeFormat.encodeArg(share)), body))
    }

    internal companion object {
        val INFO = "age-encryption.org/v1/X25519".toByteArray()
        val ZERO_NONCE = ByteArray(12)
    }
}

class X25519Identity(private val secretKey: ByteArray) : AgeIdentity {
    private val publicKey = AgePrimitives.x25519Base(secretKey)
    fun recipient(): X25519Recipient = X25519Recipient(publicKey)

    override fun unwrap(stanzas: List<AgeStanza>): ByteArray? {
        for (s in stanzas) {
            if (s.type != "X25519" || s.args.size != 1) continue
            val share = runCatching { AgeFormat.decodeArg(s.args[0]) }.getOrNull() ?: continue
            if (share.size != 32) continue
            val shared = AgePrimitives.x25519(secretKey, share) ?: continue
            val wrapKey = AgePrimitives.hkdf(share + publicKey, shared, X25519Recipient.INFO, 32)
            runCatching { AgePrimitives.chachaOpen(wrapKey, X25519Recipient.ZERO_NONCE, s.body) }
                .getOrNull()?.let { if (it.size == AgeFormat.FILE_KEY_SIZE) return it }
        }
        return null
    }

    companion object { fun generate(): X25519Identity = X25519Identity(AgePrimitives.randomBytes(32)) }
}

/**
 * age spec "scrypt recipient" stanza (must be the file's only recipient):
 * ```
 *   -> scrypt base64(salt) log2(N)
 *   encrypt[ scrypt["age-encryption.org/v1/scrypt" || salt, N](passphrase) ]( file key )
 * ```
 * Body `encrypt[]` uses an all-zero nonce (fresh per-stanza salt ⇒ fresh wrap key).
 */
class ScryptRecipient(private val passphrase: ByteArray, private val logN: Int = 18) : AgeRecipient {
    override fun wrap(fileKey: ByteArray): List<AgeStanza> {
        val salt = AgePrimitives.randomBytes(16)
        val wrapKey = AgePrimitives.scrypt(passphrase, LABEL + salt, logN)
        val body = AgePrimitives.chachaSeal(wrapKey, X25519Recipient.ZERO_NONCE, fileKey)
        return listOf(AgeStanza("scrypt", listOf(AgeFormat.encodeArg(salt), logN.toString()), body))
    }

    internal companion object {
        val LABEL = "age-encryption.org/v1/scrypt".toByteArray()
        // Decrypt-side cap on the work factor. scrypt with N=2^logN uses ~2^(logN+10)
        // bytes, so logN=20 ≈ 1 GiB; rejecting higher stops a malicious file from
        // forcing an OOM ("scrypt bomb"). Our own exports use logN=15 (~32 MiB).
        const val MAX_LOG_N = 20
    }
}

class ScryptIdentity(private val passphrase: ByteArray) : AgeIdentity {
    override fun unwrap(stanzas: List<AgeStanza>): ByteArray? {
        val s = stanzas.firstOrNull { it.type == "scrypt" } ?: return null
        if (stanzas.size != 1) throw AgeFormatException("scrypt must be the only recipient")
        if (s.args.size != 2) return null
        val salt = AgeFormat.decodeArg(s.args[0])
        val logN = s.args[1].toIntOrNull() ?: return null
        if (salt.size != 16 || logN !in 1..ScryptRecipient.MAX_LOG_N) {
            throw AgeFormatException("invalid or unsafe scrypt work factor (logN=$logN)")
        }
        val wrapKey = AgePrimitives.scrypt(passphrase, ScryptRecipient.LABEL + salt, logN)
        return runCatching { AgePrimitives.chachaOpen(wrapKey, X25519Recipient.ZERO_NONCE, s.body) }.getOrNull()
    }
}
