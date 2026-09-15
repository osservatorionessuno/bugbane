package org.osservatorionessuno.bugbane.update

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.osservatorionessuno.bugbane.update.IndicatorFileInspector.Problem
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

/**
 * User-imported indicator sets, kept next to the feed bundle in [IndicatorStore.indicatorsDir]
 * so libmvt loads them in the same `loadFromDirectory` pass and every analysis records them
 * (file name + SHA-256) alongside the feed.
 *
 * Files are content-addressed (`custom-<sha256>.stix2`): re-importing the same bytes is a no-op
 * and the on-disk name is never derived from user input. A manifest (`custom_indicators.json`)
 * holds the user-facing name, indicator count and declared malware families. The feed updater
 * never touches these files ([IndicatorStore.adoptStaged] skips the `custom-` prefix).
 *
 * A candidate is streamed to a scratch file (never memory), hashed while writing, validated
 * with [IndicatorFileInspector] and only then moved into place.
 */
class CustomIndicatorStore(private val filesDir: File) {

    constructor(context: Context) : this(context.filesDir)

    private val dir: File = IndicatorStore(filesDir).indicatorsDir
    private val manifestFile = File(filesDir, MANIFEST_FILE)

    data class Entry(
        val sha256: String,
        val name: String,
        val indicators: Int,
        val families: List<String>,
        val importedEpoch: Long,
    )

    sealed class ImportResult {
        data class Imported(val entry: Entry) : ImportResult()
        data class Duplicate(val entry: Entry) : ImportResult()
        data class Rejected(val problem: Problem) : ImportResult()
    }

    /** Imported sets, oldest first. Entries whose file went missing are dropped. */
    @Synchronized
    fun list(): List<Entry> {
        val entries = readManifest()
        val present = entries.filter { file(it).exists() }
        if (present.size != entries.size) writeManifest(present)
        return present
    }

    fun file(entry: Entry): File = File(dir, "$CUSTOM_PREFIX${entry.sha256}.stix2")

    /**
     * Import [source] under the user-facing [name] (the picked file's display name; stored and
     * shown only, never used as a path). Reads at most [MAX_BYTES].
     */
    @Synchronized
    fun import(name: String, source: InputStream, nowEpoch: Long = System.currentTimeMillis() / 1000): ImportResult {
        val tmp = File.createTempFile("import-", ".tmp", filesDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            tmp.outputStream().buffered().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = source.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) return ImportResult.Rejected(Problem.TOO_LARGE)
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }

            val entries = readManifest()
            entries.firstOrNull { it.sha256 == sha256 && file(it).exists() }?.let { return ImportResult.Duplicate(it) }

            val summary = try {
                IndicatorFileInspector.inspect(tmp)
            } catch (e: IndicatorFileInspector.InvalidIndicatorFile) {
                return ImportResult.Rejected(e.problem)
            }

            val entry = Entry(
                sha256 = sha256,
                name = name.take(MAX_NAME_CHARS).ifBlank { "indicators" },
                indicators = summary.indicators,
                families = summary.families,
                importedEpoch = nowEpoch,
            )
            Files.move(tmp.toPath(), file(entry).toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
            writeManifest(entries.filter { it.sha256 != sha256 } + entry)
            Log.i(TAG, "Imported custom indicators $sha256 (${entry.indicators} indicators)")
            return ImportResult.Imported(entry)
        } finally {
            tmp.delete()
        }
    }

    /** Remove [entry]'s file and manifest row. */
    @Synchronized
    fun remove(entry: Entry) {
        file(entry).delete()
        writeManifest(readManifest().filter { it.sha256 != entry.sha256 })
    }

    /** Rows with a malformed hash are dropped: the hash is the file name, so it must be plain hex. */
    private fun readManifest(): List<Entry> {
        if (!manifestFile.exists()) return emptyList()
        return try {
            val arr = JSONObject(manifestFile.readText()).getJSONArray("imports")
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val sha = o.getString("sha256").takeIf { SHA256_HEX.matches(it) } ?: return@mapNotNull null
                val fams = o.optJSONArray("families") ?: JSONArray()
                Entry(
                    sha256 = sha,
                    name = o.optString("name"),
                    indicators = o.optInt("indicators"),
                    families = (0 until fams.length()).map { fams.getString(it) },
                    importedEpoch = o.optLong("importedEpoch"),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read custom indicators manifest; treating as empty", e)
            emptyList()
        }
    }

    private fun writeManifest(entries: List<Entry>) {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject()
                    .put("sha256", e.sha256)
                    .put("name", e.name)
                    .put("indicators", e.indicators)
                    .put("families", JSONArray(e.families))
                    .put("importedEpoch", e.importedEpoch),
            )
        }
        val tmp = File(filesDir, "$MANIFEST_FILE.tmp")
        tmp.writeText(JSONObject().put("imports", arr).toString())
        Files.move(tmp.toPath(), manifestFile.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
    }

    companion object {
        private const val TAG = "CustomIndicatorStore"
        private const val MANIFEST_FILE = "custom_indicators.json"
        private const val CUSTOM_PREFIX = IndicatorStore.CUSTOM_PREFIX
        private const val MAX_NAME_CHARS = 80
        private val SHA256_HEX = Regex("[0-9a-f]{64}")

        /**
         * Twice the full feed (~8 MB). Every analysis loads all indicator files into libmvt's
         * in-memory tries, so this bounds per-scan memory as much as disk use.
         */
        const val MAX_BYTES: Long = 16L * 1024 * 1024
    }
}
