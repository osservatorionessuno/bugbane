package org.osservatorionessuno.bugbane.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.security.MessageDigest

/**
 * Live end-to-end fetch from the real update server over Oblivious HTTP, including the HPKE round
 * trip through the relay. Opt-in (network + external dependency), so it is skipped unless
 * `BUGBANE_LIVE_OHTTP=1` is set:
 *
 *     BUGBANE_LIVE_OHTTP=1 ./gradlew :app:testProductionDebugUnitTest --tests '*OhttpFetchLiveTest*'
 *
 * It validates that the pinned key config is current, the relay/gateway are reachable, and the
 * advertised full bundle is fetchable and hashes to `signed.sha256`.
 */
@EnabledIfEnvironmentVariable(named = "BUGBANE_LIVE_OHTTP", matches = "1")
class OhttpFetchLiveTest {

    @Test
    fun fetchesUpdateJsonOverOhttp() {
        val resp = OhttpTransport().get("/v1/update.json")
        assertEquals(200, resp.statusCode)
        val meta = UpdateMetadata.parse(resp.body)
        assertEquals(IndicatorUpdater.SCHEMA, meta.schema)
        assertTrue(meta.version >= 1)
        assertEquals(64, meta.sha256.length)
    }

    @Test
    fun fullBundleMatchesAdvertisedHash() {
        val transport = OhttpTransport()
        val meta = UpdateMetadata.parse(transport.get("/v1/update.json").body)
        val full = transport.get("/v1/${meta.sha256}.json")
        assertEquals(200, full.statusCode)
        assertEquals(meta.sha256, sha256Hex(full.body))
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
