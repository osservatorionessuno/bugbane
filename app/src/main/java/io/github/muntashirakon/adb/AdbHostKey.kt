package io.github.muntashirakon.adb

import java.security.interfaces.RSAPublicKey

/**
 * [AndroidPubkey] is package-private in libadb; expose the adb_keys encoding.
 */
object AdbHostKey {
    /** The `<base64> <name>` line the device stores in its authorized keys for this host. */
    fun adbKeysLine(publicKey: RSAPublicKey, name: String): String =
        String(AndroidPubkey.encodeWithName(publicKey, name), Charsets.UTF_8)
            .trimEnd('\u0000') // the AUTH payload is NUL-terminated
}
