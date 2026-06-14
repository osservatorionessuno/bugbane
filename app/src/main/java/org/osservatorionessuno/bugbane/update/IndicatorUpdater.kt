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
        val localFull = store.readBundle()
        val canDelta = local.version > 0 && local.schema == SCHEMA && gap in 1..DELTA_WINDOW && localFull != null

        var viaDelta = false
        var verified: Verified? = null
        if (canDelta) {
            verified = tryDelta(localFull!!, local.version, meta)
            viaDelta = verified != null
        }
        if (verified == null) {
            verified = fetchFull(meta) ?: return Outcome.Failed("full download failed", sunset)
        }

        // Disk is touched only now, after the bytes verified against the hash we computed. State
        // records that computed hash (over the exact bytes written), not the server-provided value,
        // so `state.sha256 == hash(bundle on disk)` holds by construction.
        val objectCount = store.writeBundle(verified.bytes)
        store.writeState(
            IndicatorStore.State(
                schema = meta.schema,
                version = meta.version,
                sha256 = verified.sha256,
                sunset = meta.sunset,
                buildDate = meta.buildDate,
                objectCount = objectCount,
                lastCheckEpoch = checkedAt,
                lastUpdateEpoch = checkedAt,
            ),
        )
        Log.i(TAG, "Adopted version ${meta.version} (${if (viaDelta) "delta" else "full"}), $objectCount objects")
        return Outcome.Updated(
            fromVersion = local.version,
            toVersion = meta.version,
            viaDelta = viaDelta,
            newObjects = (objectCount - local.objectCount).coerceAtLeast(0),
            sunset = sunset,
        )
    }

    /** Fetch and apply `delta-<local>-<target>.diff`; returns the reconstructed full iff the hash we
     *  compute over it matches the feed's advertised hash. */
    private fun tryDelta(localFull: ByteArray, localVersion: Int, meta: UpdateMetadata): Verified? {
        return try {
            val resp = transport.get("$BASE/deltas/delta-$localVersion-${meta.version}.diff")
            if (resp.statusCode != 200) {
                Log.i(TAG, "Delta delta-$localVersion-${meta.version} not available (status ${resp.statusCode}); using full")
                return null
            }
            val candidate = DeltaPatch.apply(localFull, resp.body)
            val hash = OhttpTransport.sha256Hex(candidate)
            if (hash == meta.sha256) {
                Verified(candidate, hash)
            } else {
                Log.w(TAG, "Delta result hash mismatch; falling back to full")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Delta apply failed; falling back to full", e)
            null
        }
    }

    /** Download the content-addressed full bundle and return it iff the hash we compute matches. */
    private fun fetchFull(meta: UpdateMetadata): Verified? {
        return try {
            val resp = transport.get("$BASE/${meta.sha256}.json")
            if (resp.statusCode != 200) {
                Log.e(TAG, "Full ${meta.sha256}.json -> inner status ${resp.statusCode}")
                return null
            }
            val hash = OhttpTransport.sha256Hex(resp.body)
            if (hash != meta.sha256) {
                Log.e(TAG, "Full bundle hash mismatch (expected ${meta.sha256}, got $hash)")
                return null
            }
            Verified(resp.body, hash)
        } catch (e: Exception) {
            Log.e(TAG, "Full download failed", e)
            null
        }
    }

    /** A bundle whose SHA-256 we computed ourselves and confirmed equals the feed's advertised hash. */
    private class Verified(val bytes: ByteArray, val sha256: String)

    companion object {
        private const val TAG = "IndicatorUpdater"

        /** Feed schema this app understands; also the path prefix on the origin. */
        const val SCHEMA = 1
        private const val BASE = "/v$SCHEMA"

        /** Builder publishes deltas to the previous 5 versions. */
        const val DELTA_WINDOW = 5
    }
}
