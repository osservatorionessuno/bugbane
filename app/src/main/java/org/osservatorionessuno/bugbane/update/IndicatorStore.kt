package org.osservatorionessuno.bugbane.update

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

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

    /** Whether a full bundle has been adopted. */
    fun hasBundle(): Boolean = bundleFile.exists()

    /** The locally adopted full bundle as a stream, or null if none has been written. */
    fun openBundle(): InputStream? = if (bundleFile.exists()) bundleFile.inputStream() else null

    /**
     * A candidate bundle streamed to a scratch file, with its SHA-256 and object count computed
     * while writing — the bundle itself is never held in memory. Either [adoptStaged] or
     * [discard][Staged.discard] it.
     */
    class Staged internal constructor(internal val file: File, val sha256: String, val objectCount: Int) {
        fun discard() {
            file.delete()
        }
    }

    /** Stream a candidate bundle from [writeTo] into a scratch file next to the real one. */
    fun stage(writeTo: (OutputStream) -> Unit): Staged {
        val tmp = File(indicatorsDir, bundleFile.name + ".tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        var newlines = 0
        try {
            tmp.outputStream().buffered().use { fileOut ->
                writeTo(object : OutputStream() {
                    override fun write(b: Int) {
                        digest.update(b.toByte())
                        if (b.toByte() == NL) newlines++
                        fileOut.write(b)
                    }

                    override fun write(b: ByteArray, off: Int, len: Int) {
                        digest.update(b, off, len)
                        for (i in off until off + len) if (b[i] == NL) newlines++
                        fileOut.write(b, off, len)
                    }
                })
            }
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        return Staged(tmp, sha256, objectCount(newlines))
    }

    /** Atomically replace the stored bundle with [staged], clearing any stale indicator files. */
    fun adoptStaged(staged: Staged) {
        indicatorsDir.listFiles()?.forEach { f ->
            if (f != bundleFile && (f.name.endsWith(".json") || f.name.endsWith(".stix2"))) f.delete()
        }
        rename(staged.file, bundleFile)
    }

    /** Builder framing is head line + one object per line + tail line, hence newlines - 2. */
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
        private val NL = '\n'.code.toByte()
    }
}
