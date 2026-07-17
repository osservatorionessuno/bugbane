package org.osservatorionessuno.bugbane.update

import android.content.Context
import android.util.Log

/**
 * Drives the indicator update feed over OHTTP (see bugbane-updater's protocol).
 *
 * Flow per run:
 *   1. GET `v<schema>/update.json`, read the `signed` metadata.
 *   2. If `signed.schema` is unknown, keep last-good indicators and surface staleness.
 *   3. If version+hash match local state, do nothing.
 *   4. If 1..[DELTA_WINDOW] versions ahead, fetch and apply the delta; otherwise download the full.
 *   5. Adopt the result only if its SHA-256 equals `signed.sha256`; on any delta failure, fall back
 *      to the full. The local hash is stored and compared.
 *
 */
class IndicatorUpdater(
    private val store: IndicatorStore,
    private val transport: UpdateTransport,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    constructor(context: Context) : this(IndicatorStore(context), OhttpTransport())

    sealed interface Outcome {
        val sunset: SunsetStatus

        data class UpToDate(override val sunset: SunsetStatus) : Outcome
        data class UnknownSchema(val schema: Int, override val sunset: SunsetStatus) : Outcome
        data class Failed(val reason: String, override val sunset: SunsetStatus = SunsetStatus.None) : Outcome
        data class Updated(
            val fromVersion: Int,
            val toVersion: Int,
            val viaDelta: Boolean,
            val newObjects: Int,
            override val sunset: SunsetStatus,
        ) : Outcome
    }

    fun runUpdate(): Outcome {
        val meta = try {
            val resp = transport.get("$BASE/update.json")
            if (resp.statusCode != 200) return Outcome.Failed("update.json -> inner status ${resp.statusCode}")
            UpdateMetadata.parse(resp.body)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch update.json", e)
            return Outcome.Failed("update.json fetch failed: ${e.message}")
        }

        val sunset = SunsetStatus.evaluate(meta.sunset).also { SunsetStatus.log(it) }
        val local = store.readState()
        val checkedAt = now()

        if (meta.schema != SCHEMA) {
            Log.w(TAG, "Unknown feed schema ${meta.schema} (this app supports $SCHEMA); keeping last-good indicators")
            store.writeState(local.copy(lastCheckEpoch = checkedAt))
            return Outcome.UnknownSchema(meta.schema, sunset)
        }

        val upToDate = meta.version == local.version && meta.sha256 == local.sha256
        val behind = meta.version < local.version
        if (upToDate || behind) {
            if (behind) Log.w(TAG, "Feed version ${meta.version} is behind local ${local.version}; keeping local")
            else Log.i(TAG, "Indicators up to date (schema $SCHEMA, version ${meta.version})")
            store.writeState(local.copy(lastCheckEpoch = checkedAt))
            return Outcome.UpToDate(sunset)
        }

        val gap = meta.version - local.version
        val canDelta = local.version > 0 && local.schema == SCHEMA && gap in 1..DELTA_WINDOW && store.hasBundle()

        // Both paths adopt through the single gate (see IndicatorAdoption): the candidate streams
        // to a scratch file and is only adopted if the hash computed on the way through verifies.
        var viaDelta = false
        var objectCount: Int? = null
        if (canDelta) {
            objectCount = tryDelta(local.version, meta, checkedAt)
            viaDelta = objectCount != null
        }
        if (objectCount == null) {
            objectCount = fetchFull(meta, checkedAt)
                ?: return Outcome.Failed("full download or verification failed", sunset)
        }
        Log.i(TAG, "Adopted version ${meta.version} (${if (viaDelta) "delta" else "full"}), $objectCount objects")
        return Outcome.Updated(
            fromVersion = local.version,
            toVersion = meta.version,
            viaDelta = viaDelta,
            newObjects = (objectCount - local.objectCount).coerceAtLeast(0),
            sunset = sunset,
        )
    }

    /** Fetch `delta-<local>-<target>.diff` and stream-patch the on-disk bundle through the adoption
     *  gate; returns the adopted object count iff the reconstructed full verifies. */
    private fun tryDelta(localVersion: Int, meta: UpdateMetadata, checkedAt: Long): Int? {
        return try {
            val resp = transport.get("$BASE/deltas/delta-$localVersion-${meta.version}.diff")
            if (resp.statusCode != 200) {
                Log.i(TAG, "Delta delta-$localVersion-${meta.version} not available (status ${resp.statusCode}); using full")
                return null
            }
            val adopted = IndicatorAdoption.adopt(store, meta, checkedAt, checkedAt) { out ->
                store.openBundle()!!.bufferedReader(Charsets.UTF_8).use { local ->
                    DeltaPatch.apply(local, resp.body, out)
                }
            }
            if (adopted == null) Log.w(TAG, "Delta result hash mismatch; falling back to full")
            adopted
        } catch (e: Exception) {
            Log.w(TAG, "Delta apply failed; falling back to full", e)
            null
        }
    }

    /** Download the content-addressed full bundle, streaming it straight through the adoption
     *  gate — it is never held in memory — and adopt it iff the hash we compute matches. */
    private fun fetchFull(meta: UpdateMetadata, checkedAt: Long): Int? {
        return try {
            transport.getStream("$BASE/${meta.sha256}.json") { status, body ->
                if (status != 200) {
                    Log.e(TAG, "Full ${meta.sha256}.json -> inner status $status")
                    null
                } else {
                    val adopted = IndicatorAdoption.adopt(store, meta, checkedAt, checkedAt) { body.copyTo(it) }
                    if (adopted == null) Log.e(TAG, "Full bundle hash mismatch (expected ${meta.sha256})")
                    adopted
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Full download failed", e)
            null
        }
    }

    companion object {
        private const val TAG = "IndicatorUpdater"

        /** Feed schema this app understands; also the path prefix on the origin. */
        const val SCHEMA = 1
        private const val BASE = "/v$SCHEMA"

        /** Builder publishes deltas to the previous 5 versions. */
        const val DELTA_WINDOW = 5
    }
}
