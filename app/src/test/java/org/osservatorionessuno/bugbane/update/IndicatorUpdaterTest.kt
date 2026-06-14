package org.osservatorionessuno.bugbane.update

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.osservatorionessuno.libbhttp.BhttpResponse
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * Exercises every branch of [IndicatorUpdater] against an in-memory [UpdateTransport] and a real
 * on-disk [IndicatorStore] (temp dir). Bundles/deltas are the same fixtures the bugbane-updater
 * builder emits (see [DeltaPatchTest]); the "old" bundle is versions [obj1,obj2,obj3,obj5] and the
 * "new" bundle is [obj1,obj2',obj4,obj5,obj6].
 */
class IndicatorUpdaterTest {

    @TempDir
    lateinit var tmp: File

    // --- fixtures (base64, produced by the Python builder) -----------------------------------------
    private val oldBundle = b64(
        "eyJpZCI6ImJ1bmRsZS0tOGYxZDZjM2UtMmE0Yi01YzZkLThlOWYtMGExYjJjM2Q0ZTVmIiwib2JqZWN0cyI6Wwp7ImlkIjoiaW5kaWNhdG9yLS0wMDAwMDAwMSIsInBhdHRlcm4iOiJbZG9tYWluLW5hbWU6dmFsdWUgPSAnZXZpbC5jb20nXSIsInR5cGUiOiJpbmRpY2F0b3IifSwKeyJpZCI6ImluZGljYXRvci0tMDAwMDAwMDIiLCJwYXR0ZXJuIjoiW2RvbWFpbi1uYW1lOnZhbHVlID0gJ2V2aWwuY29tJ10iLCJ0eXBlIjoiaW5kaWNhdG9yIn0sCnsiaWQiOiJpbmRpY2F0b3ItLTAwMDAwMDAzIiwicGF0dGVybiI6Iltkb21haW4tbmFtZTp2YWx1ZSA9ICdldmlsLmNvbSddIiwidHlwZSI6ImluZGljYXRvciJ9LAp7ImlkIjoiaW5kaWNhdG9yLS0wMDAwMDAwNSIsInBhdHRlcm4iOiJbZG9tYWluLW5hbWU6dmFsdWUgPSAnZXZpbC5jb20nXSIsInR5cGUiOiJpbmRpY2F0b3IifQpdLCJ0eXBlIjoiYnVuZGxlIn0K",
    )
    private val newBundle = b64(
        "eyJpZCI6ImJ1bmRsZS0tOGYxZDZjM2UtMmE0Yi01YzZkLThlOWYtMGExYjJjM2Q0ZTVmIiwib2JqZWN0cyI6Wwp7ImlkIjoiaW5kaWNhdG9yLS0wMDAwMDAwMSIsInBhdHRlcm4iOiJbZG9tYWluLW5hbWU6dmFsdWUgPSAnZXZpbC5jb20nXSIsInR5cGUiOiJpbmRpY2F0b3IifSwKeyJpZCI6ImluZGljYXRvci0tMDAwMDAwMDIiLCJwYXR0ZXJuIjoiW2RvbWFpbi1uYW1lOnZhbHVlID0gJ2NoYW5nZWQuY29tJ10iLCJ0eXBlIjoiaW5kaWNhdG9yIn0sCnsiaWQiOiJpbmRpY2F0b3ItLTAwMDAwMDA0IiwicGF0dGVybiI6Iltkb21haW4tbmFtZTp2YWx1ZSA9ICdldmlsLmNvbSddIiwidHlwZSI6ImluZGljYXRvciJ9LAp7ImlkIjoiaW5kaWNhdG9yLS0wMDAwMDAwNSIsInBhdHRlcm4iOiJbZG9tYWluLW5hbWU6dmFsdWUgPSAnZXZpbC5jb20nXSIsInR5cGUiOiJpbmRpY2F0b3IifSwKeyJpZCI6ImluZGljYXRvci0tMDAwMDAwMDYiLCJwYXR0ZXJuIjoiW2RvbWFpbi1uYW1lOnZhbHVlID0gJ2V2aWwuY29tJ10iLCJ0eXBlIjoiaW5kaWNhdG9yIn0KXSwidHlwZSI6ImJ1bmRsZSJ9Cg==",
    )
    private val delta = b64(
        "LS0tIG9sZAorKysgbmV3CkBAIC0zLDMgKzMsNCBAQAoteyJpZCI6ImluZGljYXRvci0tMDAwMDAwMDIiLCJwYXR0ZXJuIjoiW2RvbWFpbi1uYW1lOnZhbHVlID0gJ2V2aWwuY29tJ10iLCJ0eXBlIjoiaW5kaWNhdG9yIn0sCi17ImlkIjoiaW5kaWNhdG9yLS0wMDAwMDAwMyIsInBhdHRlcm4iOiJbZG9tYWluLW5hbWU6dmFsdWUgPSAnZXZpbC5jb20nXSIsInR5cGUiOiJpbmRpY2F0b3IifSwKLXsiaWQiOiJpbmRpY2F0b3ItLTAwMDAwMDA1IiwicGF0dGVybiI6Iltkb21haW4tbmFtZTp2YWx1ZSA9ICdldmlsLmNvbSddIiwidHlwZSI6ImluZGljYXRvciJ9Cit7ImlkIjoiaW5kaWNhdG9yLS0wMDAwMDAwMiIsInBhdHRlcm4iOiJbZG9tYWluLW5hbWU6dmFsdWUgPSAnY2hhbmdlZC5jb20nXSIsInR5cGUiOiJpbmRpY2F0b3IifSwKK3siaWQiOiJpbmRpY2F0b3ItLTAwMDAwMDA0IiwicGF0dGVybiI6Iltkb21haW4tbmFtZTp2YWx1ZSA9ICdldmlsLmNvbSddIiwidHlwZSI6ImluZGljYXRvciJ9LAoreyJpZCI6ImluZGljYXRvci0tMDAwMDAwMDUiLCJwYXR0ZXJuIjoiW2RvbWFpbi1uYW1lOnZhbHVlID0gJ2V2aWwuY29tJ10iLCJ0eXBlIjoiaW5kaWNhdG9yIn0sCit7ImlkIjoiaW5kaWNhdG9yLS0wMDAwMDAwNiIsInBhdHRlcm4iOiJbZG9tYWluLW5hbWU6dmFsdWUgPSAnZXZpbC5jb20nXSIsInR5cGUiOiJpbmRpY2F0b3IifQo=",
    )
    private val shaOld get() = sha(oldBundle)
    private val shaNew get() = sha(newBundle)

    // --- helpers -----------------------------------------------------------------------------------
    private fun b64(s: String) = Base64.getDecoder().decode(s)

    private fun sha(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun updateJson(version: Int, sha256: String, schema: Int = 1, sunset: String? = null): ByteArray {
        val signed = JSONObject()
            .put("schema", schema)
            .put("version", version)
            .put("sha256", sha256)
            .put("build_date", "2026-06-13T00:00:00Z")
        if (sunset != null) signed.put("sunset", sunset)
        return JSONObject().put("signed", signed).toString().toByteArray(Charsets.UTF_8)
    }

    private class FakeTransport : UpdateTransport {
        val routes = mutableMapOf<String, Pair<Int, ByteArray>>()
        val requested = mutableListOf<String>()
        fun route(path: String, status: Int, body: ByteArray) { routes[path] = status to body }
        override fun get(path: String): BhttpResponse {
            requested += path
            val (status, body) = routes[path] ?: (404 to ByteArray(0))
            return BhttpResponse(status, emptyList(), body, emptyList())
        }
    }

    private fun store() = IndicatorStore(tmp)

    private fun seed(store: IndicatorStore, version: Int, sha256: String, bundle: ByteArray, objectCount: Int) {
        store.writeBundle(bundle)
        store.writeState(IndicatorStore.State(schema = 1, version = version, sha256 = sha256, objectCount = objectCount))
    }

    private fun fullPath(sha: String) = "/v1/$sha.json"
    private fun deltaPath(from: Int, to: Int) = "/v1/deltas/delta-$from-$to.diff"

    // --- tests -------------------------------------------------------------------------------------

    @Test
    fun freshInstall_downloadsFull() {
        val store = store()
        val t = FakeTransport().apply {
            route("/v1/update.json", 200, updateJson(1, shaNew))
            route(fullPath(shaNew), 200, newBundle)
        }
        val out = IndicatorUpdater(store, t) { 1000L }.runUpdate()

        assertTrue(out is IndicatorUpdater.Outcome.Updated)
        out as IndicatorUpdater.Outcome.Updated
        assertEquals(0, out.fromVersion)
        assertEquals(1, out.toVersion)
        assertFalse(out.viaDelta)
        assertEquals(5, out.newObjects)
        assertArrayEquals(newBundle, store.readBundle())
        val s = store.readState()
        assertEquals(1, s.version)
        assertEquals(shaNew, s.sha256)
        assertEquals(5, s.objectCount)
        assertEquals(1000L, s.lastUpdateEpoch)
    }

    @Test
    fun sameVersionAndHash_doesNothing() {
        val store = store()
        seed(store, version = 2, sha256 = shaNew, bundle = newBundle, objectCount = 5)
        val t = FakeTransport().apply { route("/v1/update.json", 200, updateJson(2, shaNew)) }

        val out = IndicatorUpdater(store, t) { 1234L }.runUpdate()

        assertTrue(out is IndicatorUpdater.Outcome.UpToDate)
        // Only update.json was fetched; no full or delta.
        assertEquals(listOf("/v1/update.json"), t.requested)
        // lastCheck is bumped even on a no-op.
        assertEquals(1234L, store.readState().lastCheckEpoch)
    }

    @Test
    fun upToDate_usesStoredHashWithoutRecomputingLocalBundle() {
        val store = store()
        // On-disk bundle is garbage, but the stored hash matches the server: must NOT re-download.
        store.writeBundle("not a real bundle".toByteArray())
        store.writeState(IndicatorStore.State(schema = 1, version = 2, sha256 = shaNew, objectCount = 5))
        val t = FakeTransport().apply { route("/v1/update.json", 200, updateJson(2, shaNew)) }

        val out = IndicatorUpdater(store, t).runUpdate()

        assertTrue(out is IndicatorUpdater.Outcome.UpToDate)
        assertEquals(listOf("/v1/update.json"), t.requested)
    }

    @Test
    fun oneVersionAhead_appliesDelta() {
        val store = store()
        seed(store, version = 1, sha256 = shaOld, bundle = oldBundle, objectCount = 4)
        val t = FakeTransport().apply {
            route("/v1/update.json", 200, updateJson(2, shaNew))
            route(deltaPath(1, 2), 200, delta)
            route(fullPath(shaNew), 200, newBundle) // fallback that must not be used
        }
        val out = IndicatorUpdater(store, t).runUpdate()

        assertTrue(out is IndicatorUpdater.Outcome.Updated)
        out as IndicatorUpdater.Outcome.Updated
        assertTrue(out.viaDelta)
        assertEquals(1, out.fromVersion)
        assertEquals(2, out.toVersion)
        assertArrayEquals(newBundle, store.readBundle())
        assertEquals(shaNew, store.readState().sha256)
        assertTrue(deltaPath(1, 2) in t.requested)
        assertFalse(fullPath(shaNew) in t.requested)
    }

    @Test
    fun deltaMissing_fallsBackToFull() {
        val store = store()
        seed(store, version = 1, sha256 = shaOld, bundle = oldBundle, objectCount = 4)
        val t = FakeTransport().apply {
            route("/v1/update.json", 200, updateJson(2, shaNew))
            // delta-1-2 intentionally absent -> 404
            route(fullPath(shaNew), 200, newBundle)
        }
        val out = IndicatorUpdater(store, t).runUpdate()

        assertTrue(out is IndicatorUpdater.Outcome.Updated)
        assertFalse((out as IndicatorUpdater.Outcome.Updated).viaDelta)
        assertArrayEquals(newBundle, store.readBundle())
        assertTrue(deltaPath(1, 2) in t.requested)
        assertTrue(fullPath(shaNew) in t.requested)
    }

    @Test
    fun deltaCorrupt_fallsBackToFull() {
        val store = store()
        seed(store, version = 1, sha256 = shaOld, bundle = oldBundle, objectCount = 4)
        val t = FakeTransport().apply {
            route("/v1/update.json", 200, updateJson(2, shaNew))
            route(deltaPath(1, 2), 200, "@@ -1,0 +1,1 @@\n+garbage".toByteArray()) // reconstructs wrong bytes
            route(fullPath(shaNew), 200, newBundle)
        }
        val out = IndicatorUpdater(store, t).runUpdate()

        assertTrue(out is IndicatorUpdater.Outcome.Updated)
        assertFalse((out as IndicatorUpdater.Outcome.Updated).viaDelta)
        assertArrayEquals(newBundle, store.readBundle())
    }

    @Test
    fun gapBeyondWindow_downloadsFullWithoutDelta() {
        val store = store()
        seed(store, version = 1, sha256 = shaOld, bundle = oldBundle, objectCount = 4)
        val t = FakeTransport().apply {
            route("/v1/update.json", 200, updateJson(10, shaNew)) // gap of 9 > window of 5
            route(fullPath(shaNew), 200, newBundle)
        }
        val out = IndicatorUpdater(store, t).runUpdate()

        assertTrue(out is IndicatorUpdater.Outcome.Updated)
        assertFalse((out as IndicatorUpdater.Outcome.Updated).viaDelta)
        assertFalse(t.requested.any { it.startsWith("/v1/deltas/") })
    }

    @Test
    fun sameVersionHashMismatch_reDownloadsFull() {
        val store = store()
        // Same version locally, but a different hash than the feed advertises -> re-download.
        seed(store, version = 2, sha256 = shaOld, bundle = oldBundle, objectCount = 4)
        val t = FakeTransport().apply {
            route("/v1/update.json", 200, updateJson(2, shaNew))
            route(fullPath(shaNew), 200, newBundle)
        }
        val out = IndicatorUpdater(store, t).runUpdate()

        assertTrue(out is IndicatorUpdater.Outcome.Updated)
        assertFalse((out as IndicatorUpdater.Outcome.Updated).viaDelta)
        assertEquals(shaNew, store.readState().sha256)
        assertArrayEquals(newBundle, store.readBundle())
    }

    @Test
    fun unknownSchema_keepsLastGood() {
        val store = store()
        seed(store, version = 3, sha256 = shaOld, bundle = oldBundle, objectCount = 4)
        val t = FakeTransport().apply { route("/v1/update.json", 200, updateJson(5, shaNew, schema = 2)) }

        val out = IndicatorUpdater(store, t).runUpdate()

        assertTrue(out is IndicatorUpdater.Outcome.UnknownSchema)
        assertEquals(2, (out as IndicatorUpdater.Outcome.UnknownSchema).schema)
        // Local indicators untouched.
        assertArrayEquals(oldBundle, store.readBundle())
        assertEquals(3, store.readState().version)
    }

    @Test
    fun fullHashMismatch_failsWithoutAdopting() {
        val store = store()
        val t = FakeTransport().apply {
            route("/v1/update.json", 200, updateJson(1, shaNew))
            route(fullPath(shaNew), 200, oldBundle) // body whose hash != advertised sha
        }
        val out = IndicatorUpdater(store, t).runUpdate()

        assertTrue(out is IndicatorUpdater.Outcome.Failed)
        assertEquals(0, store.readState().version)
        assertNull(store.readBundle())
    }

    @Test
    fun updateJsonNotFound_fails() {
        val t = FakeTransport() // update.json -> 404
        val out = IndicatorUpdater(store(), t).runUpdate()
        assertTrue(out is IndicatorUpdater.Outcome.Failed)
    }

    @Test
    fun sunsetUpcoming_isReportedOnOutcome() {
        val store = store()
        val t = FakeTransport().apply {
            route("/v1/update.json", 200, updateJson(1, shaNew, sunset = "2999-01-01"))
            route(fullPath(shaNew), 200, newBundle)
        }
        val out = IndicatorUpdater(store, t).runUpdate()
        assertTrue(out.sunset is SunsetStatus.Upcoming)
        assertEquals("2999-01-01", store.readState().sunset)
    }

    @Test
    fun sunsetReached_isReportedOnOutcome() {
        val store = store()
        val t = FakeTransport().apply {
            route("/v1/update.json", 200, updateJson(1, shaNew, sunset = "2000-01-01"))
            route(fullPath(shaNew), 200, newBundle)
        }
        val out = IndicatorUpdater(store, t).runUpdate()
        assertTrue(out.sunset is SunsetStatus.Reached)
    }
}
