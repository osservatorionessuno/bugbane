package org.osservatorionessuno.qf.crypto

/**
 * Abstraction over a non-exportable, device-bound key used to wrap small secrets
 * (the acquisition identity's private key; the ADB private key). On a device this
 * is backed by the Android Keystore — StrongBox when available, otherwise the TEE
 * (see `AndroidKeystoreKeyVault` in the app module).
 */
interface KeyVault {
    /** Encrypt (wrap) [fileKey]; the returned blob can only be unwrapped on this device. */
    fun wrap(fileKey: ByteArray): ByteArray

    /** Decrypt (unwrap) a blob previously produced by [wrap]. */
    fun unwrap(blob: ByteArray): ByteArray
}
