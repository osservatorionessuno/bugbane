package org.osservatorionessuno.qf.crypto

import org.osservatorionessuno.qf.crypto.age.AgeIdentity
import org.osservatorionessuno.qf.crypto.age.AgeRecipient
import org.osservatorionessuno.qf.crypto.age.AgeStanza

/**
 * age [AgeRecipient] that wraps the file key under a [KeyVault], producing a
 * single custom recipient stanza (`-> <stanzaType>`). This is what makes the
 * at-rest file a valid age file only this device can open.
 */
class KeyVaultRecipient(private val vault: KeyVault) : AgeRecipient {
    override fun wrap(fileKey: ByteArray): List<AgeStanza> =
        listOf(AgeStanza(vault.stanzaType, emptyList(), vault.wrap(fileKey)))
}

/** age [AgeIdentity] that unwraps the file key from the bugbane KeyVault stanza. */
class KeyVaultIdentity(private val vault: KeyVault) : AgeIdentity {
    override fun unwrap(stanzas: List<AgeStanza>): ByteArray? {
        val stanza = stanzas.firstOrNull { it.type == vault.stanzaType } ?: return null
        return runCatching { vault.unwrap(stanza.body) }.getOrNull()
    }
}
