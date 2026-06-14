package org.osservatorionessuno.bugbane.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64

/**
 * Cross-implementation parity check: the fixtures below were produced by the bugbane-updater
 * builder (`bundle_bytes` + `delta_bytes`) for a change that modifies, deletes, and inserts objects
 * across multiple hunks. [DeltaPatch.apply] must reconstruct the exact new bundle bytes and hash.
 */
class DeltaPatchTest {
    private fun b64(s: String) = Base64.getDecoder().decode(s)

    private val old = b64(
        "eyJpZCI6ImJ1bmRsZS0tOGYxZDZjM2UtMmE0Yi01YzZkLThlOWYtMGExYjJjM2Q0ZTVmIiwib2JqZWN0cyI6Wwp7ImlkIjoiaW5kaWNhdG9yLS0wMDAwMDAwMSIsInBhdHRlcm4iOiJbZG9tYWluLW5hbWU6dmFsdWUgPSAnZXZpbC5jb20nXSIsInR5cGUiOiJpbmRpY2F0b3IifSwKeyJpZCI6ImluZGljYXRvci0tMDAwMDAwMDIiLCJwYXR0ZXJuIjoiW2RvbWFpbi1uYW1lOnZhbHVlID0gJ2V2aWwuY29tJ10iLCJ0eXBlIjoiaW5kaWNhdG9yIn0sCnsiaWQiOiJpbmRpY2F0b3ItLTAwMDAwMDAzIiwicGF0dGVybiI6Iltkb21haW4tbmFtZTp2YWx1ZSA9ICdldmlsLmNvbSddIiwidHlwZSI6ImluZGljYXRvciJ9LAp7ImlkIjoiaW5kaWNhdG9yLS0wMDAwMDAwNSIsInBhdHRlcm4iOiJbZG9tYWluLW5hbWU6dmFsdWUgPSAnZXZpbC5jb20nXSIsInR5cGUiOiJpbmRpY2F0b3IifQpdLCJ0eXBlIjoiYnVuZGxlIn0K",
    )
    private val new = b64(
        "eyJpZCI6ImJ1bmRsZS0tOGYxZDZjM2UtMmE0Yi01YzZkLThlOWYtMGExYjJjM2Q0ZTVmIiwib2JqZWN0cyI6Wwp7ImlkIjoiaW5kaWNhdG9yLS0wMDAwMDAwMSIsInBhdHRlcm4iOiJbZG9tYWluLW5hbWU6dmFsdWUgPSAnZXZpbC5jb20nXSIsInR5cGUiOiJpbmRpY2F0b3IifSwKeyJpZCI6ImluZGljYXRvci0tMDAwMDAwMDIiLCJwYXR0ZXJuIjoiW2RvbWFpbi1uYW1lOnZhbHVlID0gJ2NoYW5nZWQuY29tJ10iLCJ0eXBlIjoiaW5kaWNhdG9yIn0sCnsiaWQiOiJpbmRpY2F0b3ItLTAwMDAwMDA0IiwicGF0dGVybiI6Iltkb21haW4tbmFtZTp2YWx1ZSA9ICdldmlsLmNvbSddIiwidHlwZSI6ImluZGljYXRvciJ9LAp7ImlkIjoiaW5kaWNhdG9yLS0wMDAwMDAwNSIsInBhdHRlcm4iOiJbZG9tYWluLW5hbWU6dmFsdWUgPSAnZXZpbC5jb20nXSIsInR5cGUiOiJpbmRpY2F0b3IifSwKeyJpZCI6ImluZGljYXRvci0tMDAwMDAwMDYiLCJwYXR0ZXJuIjoiW2RvbWFpbi1uYW1lOnZhbHVlID0gJ2V2aWwuY29tJ10iLCJ0eXBlIjoiaW5kaWNhdG9yIn0KXSwidHlwZSI6ImJ1bmRsZSJ9Cg==",
    )
    private val delta = b64(
        "LS0tIG9sZAorKysgbmV3CkBAIC0zLDMgKzMsNCBAQAoteyJpZCI6ImluZGljYXRvci0tMDAwMDAwMDIiLCJwYXR0ZXJuIjoiW2RvbWFpbi1uYW1lOnZhbHVlID0gJ2V2aWwuY29tJ10iLCJ0eXBlIjoiaW5kaWNhdG9yIn0sCi17ImlkIjoiaW5kaWNhdG9yLS0wMDAwMDAwMyIsInBhdHRlcm4iOiJbZG9tYWluLW5hbWU6dmFsdWUgPSAnZXZpbC5jb20nXSIsInR5cGUiOiJpbmRpY2F0b3IifSwKLXsiaWQiOiJpbmRpY2F0b3ItLTAwMDAwMDA1IiwicGF0dGVybiI6Iltkb21haW4tbmFtZTp2YWx1ZSA9ICdldmlsLmNvbSddIiwidHlwZSI6ImluZGljYXRvciJ9Cit7ImlkIjoiaW5kaWNhdG9yLS0wMDAwMDAwMiIsInBhdHRlcm4iOiJbZG9tYWluLW5hbWU6dmFsdWUgPSAnY2hhbmdlZC5jb20nXSIsInR5cGUiOiJpbmRpY2F0b3IifSwKK3siaWQiOiJpbmRpY2F0b3ItLTAwMDAwMDA0IiwicGF0dGVybiI6Iltkb21haW4tbmFtZTp2YWx1ZSA9ICdldmlsLmNvbSddIiwidHlwZSI6ImluZGljYXRvciJ9LAoreyJpZCI6ImluZGljYXRvci0tMDAwMDAwMDUiLCJwYXR0ZXJuIjoiW2RvbWFpbi1uYW1lOnZhbHVlID0gJ2V2aWwuY29tJ10iLCJ0eXBlIjoiaW5kaWNhdG9yIn0sCit7ImlkIjoiaW5kaWNhdG9yLS0wMDAwMDAwNiIsInBhdHRlcm4iOiJbZG9tYWluLW5hbWU6dmFsdWUgPSAnZXZpbC5jb20nXSIsInR5cGUiOiJpbmRpY2F0b3IifQo=",
    )
    private val newSha = "461ac917421bbe122b08be61e90a59572a5b7f4af55fcf51285daa3f55e463ea"

    @Test
    fun reproducesFullBundleBytesAndHash() {
        val result = DeltaPatch.apply(old, delta)
        assertEquals(String(new, Charsets.UTF_8), String(result, Charsets.UTF_8))
        assertEquals(newSha, sha256Hex(result))
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
