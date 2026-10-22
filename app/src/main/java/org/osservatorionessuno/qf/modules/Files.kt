package org.osservatorionessuno.qf.modules

import android.content.Context
import android.os.Environment
import org.osservatorionessuno.bugbane.R
import org.osservatorionessuno.qf.AcquisitionLog
import org.osservatorionessuno.qf.Module
import org.osservatorionessuno.qf.rethrowIfCancelled
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import org.osservatorionessuno.cadb.AdbShell
import org.osservatorionessuno.cadb.AdbConnectionManager
import org.osservatorionessuno.qf.ArtifactJson
import org.osservatorionessuno.qf.storage.ArtifactSink

class Files : Module {
    override val name = "files"

    private companion object {
        private val WHITESPACE = Regex("\\s+")
        // androidqf/MVT field order: atime ctime mtime symbolic-mode size selinux-context user group path.
        private const val PRINTF = "%A@ %C@ %T@ %M %s %Z %u %g %p"
    }

    override fun run(
        context: Context,
        manager: AdbConnectionManager,
        writer: ArtifactSink,
        progress: ((Long) -> Unit)?,
        log: AcquisitionLog,
    ) {
        val sh = AdbShell(manager, progress = progress)

        // Detect find -printf capability with the exact format used below (so a device
        // lacking any directive, e.g. %Z, falls back to path-only instead of misaligning).
        var supportsPrintf = false
        runCatching {
            sh.execForEachLine("""find '/' -maxdepth 1 -printf '$PRINTF\n' 2>/dev/null""") {
                supportsPrintf = true
            }
        }

        // Folders from https://github.com/mvt-project/androidqf/blob/main/modules/files.go
        val roots = mutableListOf(
            Environment.getExternalStorageDirectory().path, "/sdcard/", "/system/", "/system_ext/",
            "/vendor/", "/cust/", "/product/", "/apex/", "/data/local/tmp/", "/data/media/0/",
            "/data/misc/radio/", "/data/vendor/secradio/", "/data/log/", "/tmp/", "/", "/data/data/"
        )
        runCatching {
            sh.execForEachLine("env 2>/dev/null") { line ->
                when {
                    line.startsWith("TMPDIR=") -> addDir(roots, line.substringAfter("TMPDIR="))
                    line.startsWith("EXTERNAL_STORAGE=") -> addDir(roots, line.substringAfter("EXTERNAL_STORAGE="))
                }
            }
        }
        val folders = roots.distinct()

        val seen = HashSet<String>()
        val printf = if (supportsPrintf) "-printf '$PRINTF\n'" else "-print"

        // Shell output arrives in small packets, each acknowledged, so a big listing crawls
        // on a slow or power-saving link (15 MB took 77 s over Wi-Fi); gzipped it is ~2 MB.
        artifactStarted = false
        val collected = runCatching { collectCompressed(sh, writer, folders, printf, seen, supportsPrintf, log) }
            .rethrowIfCancelled()
            .onFailure {
                // Once files.json is open in the archive there is no second try: the sink streams.
                if (artifactStarted) throw it
                log.warning("Compressed listing failed (${it.message}); streaming the listing instead")
            }
            .isSuccess
        if (collected) return

        val failed = mutableListOf<String>()
        writer.useArtifact("files.json") { output ->
            val onLine = lineHandler(output, seen, supportsPrintf)
            for (folder in folders) {
                log.step(R.string.step_files_listing, folder)
                val cmd = "find ${shQuote(folder)} -type f $printf 2>/dev/null"
                // A stalled link kills a listing part-way; run it again once (`seen` drops the
                // repeats) and only then count the root as lost.
                var listed = false
                for (attempt in 1..2) {
                    listed = runCatching { sh.execForEachLine(cmd, onLine) }.rethrowIfCancelled()
                        .onFailure { log.warning("Listing $folder failed (attempt $attempt): ${it.message}") }.isSuccess
                    if (listed) break
                }
                if (!listed) { failed += folder; log.error("Listing $folder failed twice") }
                output.flush()
            }
        }
        // What was listed is kept; the module is incomplete, not successful.
        if (failed.isNotEmpty()) throw IOException("listing failed for ${failed.joinToString()}")
    }

    private var artifactStarted = false

    /**
     * Run every find on the device through gzip in one shell stream and parse the listing here
     * from memory. Shell output is slow on a weak link because every small packet is
     * acknowledged; compressed it is a few MB instead of tens. Nothing is written to a file on
     * either phone: only the encrypted archive holds the target's data.
     */
    private fun collectCompressed(
        sh: AdbShell,
        writer: ArtifactSink,
        folders: List<String>,
        printf: String,
        seen: HashSet<String>,
        supportsPrintf: Boolean,
        log: AcquisitionLog,
    ) {
        val finds = folders.joinToString("; ") { "find ${shQuote(it)} -type f $printf 2>/dev/null" }
        log.step(R.string.step_files_collecting)
        val compressed = ByteArrayOutputStream()
        sh.execToStream("( $finds ) 2>/dev/null | gzip -c", compressed)
        if (compressed.size() < 20) throw IOException("the listing came back empty")
        artifactStarted = true
        writer.useArtifact("files.json") { output ->
            val onLine = lineHandler(output, seen, supportsPrintf)
            GZIPInputStream(ByteArrayInputStream(compressed.toByteArray())).bufferedReader().useLines { lines -> lines.forEach(onLine) }
        }
    }

    private fun lineHandler(output: OutputStream, seen: HashSet<String>, supportsPrintf: Boolean): (String) -> Unit =
        if (supportsPrintf) { line ->
            val parts = line.trim().split(WHITESPACE, limit = 9)
            if (parts.size >= 9) {
                val path = parts[8]
                if (seen.add(path)) {
                    ArtifactJson.file(
                        output, path,
                        parts[0].toDoubleOrNull(), parts[1].toDoubleOrNull(), parts[2].toDoubleOrNull(),
                        parts[3], parts[4].toLongOrNull(), parts[5], parts[6], parts[7],
                    )
                }
            }
        } else { line ->
            val path = line.trim()
            if (path.isNotEmpty() && seen.add(path)) {
                ArtifactJson.file(output, path, null, null, null, null, null, null, null, null)
            }
        }

    /* helpers */

    private fun addDir(list: MutableList<String>, dir: String) {
        if (dir.isBlank()) return
        list += if (dir.endsWith('/')) dir else "$dir/"
    }

    private fun shQuote(s: String) = "'" + s.replace("'", "'\"'\"'") + "'"
}
