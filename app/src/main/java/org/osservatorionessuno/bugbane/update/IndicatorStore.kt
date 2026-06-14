package org.osservatorionessuno.bugbane.update

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Bugbane-owned on-disk state for the indicator feed, replacing libmvt's `IndicatorsUpdates` for
 * everything except loading.
 *
 * State is scoped to the feed schema: moving to a new schema bootstraps from version 0.
 */
class IndicatorStore(private val filesDir: File) {

    constructor(context: Context) : this(context.filesDir)

    private val stateFile = File(filesDir, STATE_FILE)

    /** Directory of STIX bundle files for libmvt to load. */
    val indicatorsDir: File = File(filesDir, INDICATORS_DIR).apply { mkdirs() }

    private val bundleFile: File = File(indicatorsDir, BUNDLE_FILE)

    data class State(
        val schema: Int = 0,
        val version: Int = 0,
        val sha256: String = "",
        val sunset: String? = null,
        val buildDate: String? = null,
        val objectCount: Int = 0,
        val lastCheckEpoch: Long = 0,
        val lastUpdateEpoch: Long = 0,
    )

    /** Reads current state from disk. */
    fun readState(): State {
        if (!stateFile.exists()) return State()
        return try {
            val o = JSONObject(stateFile.readText())
            State(
                schema = o.optInt("schema", 0),
                version = o.optInt("version", 0),
                sha256 = o.optString("sha256", ""),
                sunset = o.optString("sunset").ifBlank { null },
                buildDate = o.optString("buildDate").ifBlank { null },
                objectCount = o.optInt("objectCount", 0),
                lastCheckEpoch = o.optLong("lastCheckEpoch", 0),
                lastUpdateEpoch = o.optLong("lastUpdateEpoch", 0),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not read update state; treating as empty", e)
            State()
        }
    }

    fun writeState(state: State) {
        val o = JSONObject()
            .put("schema", state.schema)
            .put("version", state.version)
            .put("sha256", state.sha256)
            .put("sunset", state.sunset)
            .put("buildDate", state.buildDate)
            .put("objectCount", state.objectCount)
            .put("lastCheckEpoch", state.lastCheckEpoch)
            .put("lastUpdateEpoch", state.lastUpdateEpoch)
        atomicWrite(stateFile, o.toString().toByteArray(Charsets.UTF_8))
    }

    /** The locally adopted full bundle, or null if none has been written. */
    fun readBundle(): ByteArray? = if (bundleFile.exists()) bundleFile.readBytes() else null

    /** Atomically replace the indicators directory contents with a single full STIX bundle. */

    fun writeBundle(bytes: ByteArray): Int {
        indicatorsDir.listFiles()?.forEach { f ->
            if (f != bundleFile && (f.name.endsWith(".json") || f.name.endsWith(".stix2"))) f.delete()
        }
        val tmp = File(indicatorsDir, bundleFile.name + ".tmp")
        var newlines = 0
        tmp.outputStream().use { out ->
            var i = 0
            while (i < bytes.size) {
                val end = minOf(i + COPY_CHUNK, bytes.size)
                for (j in i until end) if (bytes[j] == NL) newlines++
                out.write(bytes, i, end - i)
                i = end
            }
        }
        rename(tmp, bundleFile)
        return objectCount(newlines)
    }

    fun countObjects(bytes: ByteArray): Int = objectCount(bytes.count { it == NL })

    private fun objectCount(newlines: Int): Int = (newlines - 2).coerceAtLeast(0)

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        rename(tmp, target)
    }

    private fun rename(tmp: File, target: File) {
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw java.io.IOException("Could not replace ${target.name}")
        }
    }

    companion object {
        private const val TAG = "IndicatorStore"
        private const val STATE_FILE = "indicator_update_state.json"
        private const val INDICATORS_DIR = "bugbane-indicators"
        private const val BUNDLE_FILE = "indicators.json"
        private const val COPY_CHUNK = 64 * 1024
        private val NL = '\n'.code.toByte()
    }
}
