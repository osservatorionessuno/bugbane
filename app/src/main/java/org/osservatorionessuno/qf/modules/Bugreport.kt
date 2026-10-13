package org.osservatorionessuno.qf.modules

import android.content.Context
import android.util.Log
import org.osservatorionessuno.cadb.AdbConnectionManager
import org.osservatorionessuno.qf.AcquisitionLog
import org.osservatorionessuno.qf.Module
import org.osservatorionessuno.qf.rethrowIfCancelled
import org.osservatorionessuno.cadb.AdbShell
import org.osservatorionessuno.cadb.AdbSync
import java.io.IOException
import org.osservatorionessuno.bugbane.R
import org.osservatorionessuno.qf.storage.ArtifactSink
import org.osservatorionessuno.qf.storage.InsufficientStorageException

/**
 * Pulls bugreports already on the device (androidqf#50) into bugreports/, then
 * generates a fresh one and pulls it as bugreport.zip.
 */
class Bugreport : Module {
    override val name: String = "bugreport"
    private val TAG = "BugreportModule"

    // On modern devices the second entry is a symlink to the first: pull from the
    // first directory that has content so nothing is stored twice.
    private val oldBugreportDirs = listOf(
        "/data/user_de/0/com.android.shell/files/bugreports/",
        "/bugreports/",
    )

    override fun run(
        context: Context,
        manager: AdbConnectionManager,
        writer: ArtifactSink,
        progress: ((Long) -> Unit)?,
        log: AcquisitionLog,
    ) {
        // Shell output is not file progress, so report zero bytes; the callback
        // must still run so a cancel can abort bugreportz mid-generation.
        val shell = AdbShell(
            manager = manager,
            tag = "ShellQF",
            progress = progress?.let { report -> { _: Long -> report(0L) } },
            timeoutMs = 15 * 60_000L, // 15 min hard cap
            inactivityMs = 60_000L    // bugreportz can be quiet for a while
        )

        // Sync progress is the one we want to surface.
        val sync = AdbSync(manager, progress)

        // Before generating, so the fresh report isn't also pulled as an old one.
        pullOldBugreports(sync, writer, log)

        var remotePath: String? = null
        var pulled = false

        try {
            log.step(R.string.step_bugreport_generating)
            remotePath = discoverBugreportPath(shell, log)
            log.info("Bugreport generated at $remotePath")

            log.step(R.string.step_bugreport_copying)
            writer.useArtifact("bugreport.zip") { output ->
                sync.pull(remotePath, output)
            }
            pulled = true
            log.info("Pulled bugreport")
        } finally {
            // Only delete remote if pull succeeded, to avoid races.
            if (pulled) {
                runCatching { remotePath?.let { shell.execForEachLine("""rm -f "$it"""") {} } }
                    .onFailure { log.warning("Failed to remove $remotePath from the device: ${it.message}") }
            } else {
                log.warning("Bugreport not pulled, leaving ${remotePath ?: "it"} on the device")
            }
        }
    }

    private fun pullOldBugreports(sync: AdbSync, writer: ArtifactSink, log: AcquisitionLog) {
        for (dir in oldBugreportDirs) {
            val entries = runCatching { sync.list(dir) }.getOrElse { emptyList() }
            if (entries.none { it["path"] != "." && it["path"] != ".." }) continue

            runCatching {
                sync.pullFolder(dir, writer, "bugreports") { path ->
                    log.step(R.string.step_copying_file, path)
                }
            }.rethrowIfCancelled().onFailure {
                // Out of space: don't spend minutes generating a report there's no room for.
                if (it is InsufficientStorageException) throw it
                log.warning("Failed to pull earlier bugreports from $dir: ${it.message}")
            }
            return
        }
        log.info("No earlier bugreports on the device")
    }

    /**
     * Prefer modern bugreportz; if we miss the OK line due to quiet output,
     * find the newest ZIP in the shell bugreports directory. Then fall back to legacy.
     */
    private fun discoverBugreportPath(shell: AdbShell, log: AcquisitionLog): String {
        // A) Modern bugreportz: if the OK line is missing from quiet output, look for the
        // written ZIP before generating a whole second bugreport. Only accept ZIPs written
        // after we started, so a stale report from an earlier run is never pulled.
        val startedEpochSec = runCatching {
            shell.execFirstLine("date +%s").toLong()
        }.getOrDefault(0L)

        runCatching {
            runBugreportz(shell, "bugreportz -p", log)?.let { return it }

            findNewestShellBugreport(shell, startedEpochSec, log)?.let { return it }

            runBugreportz(shell, "bugreportz", log)?.let { return it }

            findNewestShellBugreport(shell, startedEpochSec, log)?.let { return it }
        }.rethrowIfCancelled().onFailure {
            log.warning("bugreportz failed: ${it.message}")
        }

        // B) Fallback: legacy zip writer
        val zipFallback = "/sdcard/Download/bugreport.zip"
        runCatching {
            log.info("Falling back to: bugreport -f \"$zipFallback\"")
            shell.execForEachLine("""bugreport -f "$zipFallback"""") { line ->
                Log.d(TAG, "bugreport -f: $line")
            }
            if (remoteFileExists(shell, zipFallback)) return zipFallback
        }.rethrowIfCancelled().onFailure {
            log.warning("bugreport -f failed: ${it.message}")
        }

        // C) Last resort: legacy text
        val txtFallback = "/sdcard/Download/bugreport.txt"
        log.info("Falling back to legacy text bugreport -> \"$txtFallback\"")
        val outTxt = shell.execFirstLine("""bugreport >"$txtFallback" 2>/dev/null; echo $?""")
        Log.d(TAG, "legacy bugreport exit? $outTxt")
        if (remoteFileExists(shell, txtFallback)) return txtFallback

        throw IOException("Unable to generate bugreport via bugreportz or bugreport (zip/text).")
    }

    /**
     * Runs bugreportz and parses lines like:
     *   OK: /data/user_de/0/com.android.shell/files/bugreports/bugreport-YYYY...zip
     *   PROGRESS: 23/100  -> shown as a percentage
     *   FAILED: <reason>  -> throw
     * Failure is thrown only after the command completes: an exception from the
     * line callback would make AdbShell retry the whole bugreportz run.
     */
    private fun runBugreportz(shell: AdbShell, command: String, log: AcquisitionLog): String? {
        var okPath: String? = null
        var failLine: String? = null
        shell.execForEachLine(command) { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@execForEachLine
            Log.d(TAG, "$command: $line")
            when {
                line.startsWith("FAIL", ignoreCase = true) -> if (failLine == null) failLine = line
                line.startsWith("OK:", ignoreCase = true) -> okPath = line.substringAfter("OK:").trim()
                // `bugreportz -p` reports PROGRESS:<done>/<total>
                line.startsWith("PROGRESS:") -> {
                    val n = line.removePrefix("PROGRESS:").split('/').mapNotNull { it.trim().toLongOrNull() }
                    if (n.size == 2 && n[1] > 0) log.show(R.string.step_bugreport_generating_percent, (n[0] * 100 / n[1]).toInt())
                }
            }
        }
        failLine?.let { throw IOException("bugreportz failed: $it") }
        return okPath?.takeIf { it.startsWith("/") }
    }

    private fun remoteFileExists(shell: AdbShell, path: String): Boolean {
        var missing = false
        shell.execForEachLine("""ls -l "$path" || echo MISSING""") { line ->
            Log.d(TAG, "ls -l $path: $line")
            if (line.contains("MISSING")) missing = true
        }
        return !missing
    }

    /**
     * Find the newest ZIP where bugreportz typically writes on modern Android.
     * Rejects files older than [notBeforeEpochSec] (0 disables the check).
     */
    private fun findNewestShellBugreport(shell: AdbShell, notBeforeEpochSec: Long, log: AcquisitionLog): String? {
        val candidateDirs = listOf(
            "/data/user_de/0/com.android.shell/files/bugreports",
            "/data/user/0/com.android.shell/files/bugreports"
        )
        for (dir in candidateDirs) {
            var newest: String? = null
            shell.execForEachLine("""ls -1t "$dir"/*.zip 2>/dev/null | head -n 1 || true""") { line ->
                val trimmed = line.trim()
                if (newest == null && trimmed.isNotEmpty() && trimmed.startsWith("/")) {
                    newest = trimmed
                }
            }
            val candidate = newest ?: continue
            if (notBeforeEpochSec > 0) {
                val mtime = runCatching {
                    shell.execFirstLine("""stat -c %Y "$candidate"""").toLong()
                }.getOrDefault(0L)
                if (mtime < notBeforeEpochSec) {
                    log.warning("Ignoring stale bugreport ZIP: $candidate (mtime $mtime)")
                    continue
                }
            }
            log.info("Found newest bugreport ZIP in $dir: $candidate")
            return candidate
        }
        return null
    }
}
