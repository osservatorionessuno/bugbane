package org.osservatorionessuno.qf.crypto.age

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.SCrypt
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.SecureRandom

/**
 * All crypto for the age format, from a single backend (BouncyCastle).
 *
 * Implements the primitives the age v1 spec is written in terms of —
 * `HKDF[salt, label](key)`, `encrypt[key](plaintext)`, `scrypt`, `X25519`.
 * Spec: https://github.com/C2SP/C2SP/blob/main/age.md
 */
internal object AgePrimitives {
    private val rng = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    // age spec "Header"/"Payload": HKDF is HKDF-SHA-256, written `HKDF[salt, label](key)`.
    // A null/empty salt means the RFC 5869 default (HashLen zeros) — used for the header MAC key.
    fun hkdf(salt: ByteArray?, ikm: ByteArray, info: ByteArray, len: Int): ByteArray {
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters(ikm, salt, info))
        return ByteArray(len).also { gen.generateBytes(it, 0, len) }
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = HMac(SHA256Digest())
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        return ByteArray(mac.macSize).also { mac.doFinal(it, 0) }
    }

    // age spec: `encrypt[key](plaintext)` and the STREAM chunks are ChaCha20-Poly1305
    // (12-byte nonce, 16-byte tag, no AAD). Stanza bodies use an all-zero nonce; STREAM
    // uses a per-chunk counter nonce — hence nonce is a parameter here.
    fun chachaSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, ptOff: Int = 0, ptLen: Int = plaintext.size): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(key), 128, nonce))
        val out = ByteArray(c.getOutputSize(ptLen))
        var n = c.processBytes(plaintext, ptOff, ptLen, out, 0)
        n += c.doFinal(out, n)
        return if (n == out.size) out else out.copyOf(n)
    }

    /** ChaCha20-Poly1305 AEAD open; throws on tag mismatch. */
    fun chachaOpen(key: ByteArray, nonce: ByteArray, ct: ByteArray, ctOff: Int = 0, ctLen: Int = ct.size): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(false, AEADParameters(KeyParameter(key), 128, nonce))
        val out = ByteArray(c.getOutputSize(ctLen))
        var n = c.processBytes(ct, ctOff, ctLen, out, 0)
        n += c.doFinal(out, n)
        return if (n == out.size) out else out.copyOf(n)
    }

    // age spec "scrypt recipient": scrypt with r=8, p=1, N=2^logN, 32-byte output.
    fun scrypt(passphrase: ByteArray, salt: ByteArray, logN: Int): ByteArray =
        SCrypt.generate(passphrase, salt, 1 shl logN, 8, 1, 32)

    // age spec "X25519 recipient": `X25519(ephemeral secret, basepoint)` — the ephemeral share.
    fun x25519Base(scalar: ByteArray): ByteArray {
        val out = ByteArray(32)
        X25519.scalarMultBase(scalar, 0, out, 0)
        return out
    }

    // age spec "X25519 recipient": `X25519(secret, point)`. Per spec, reject the
    // all-zero (low-order) shared secret — calculateAgreement returns false there.
    fun x25519(scalar: ByteArray, point: ByteArray): ByteArray? {
        val out = ByteArray(32)
        return if (X25519.calculateAgreement(scalar, 0, point, 0, out, 0)) out else null
    }
}
