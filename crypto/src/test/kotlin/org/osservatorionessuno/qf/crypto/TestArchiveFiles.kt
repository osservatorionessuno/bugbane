package org.osservatorionessuno.qf.crypto

import java.io.File
import org.osservatorionessuno.qf.crypto.age.AgeIdentity
import org.osservatorionessuno.qf.crypto.age.AgePayload

internal fun tempAgeFile(bytes: ByteArray): File =
    File.createTempFile("bugbane-test-", ".age").also {
        it.writeBytes(bytes)
        it.deleteOnExit()
    }

internal fun readAgeFile(file: File, identities: List<AgeIdentity>): ByteArray {
    val access = FileRandomAccess(file)
    try {
        return AgePayload.open(access, identities).stream().readBytes()
    } finally {
        access.close()
    }
}
