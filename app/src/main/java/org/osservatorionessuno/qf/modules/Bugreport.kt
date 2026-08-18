package org.osservatorionessuno.qf.modules

import android.content.Context
import android.util.Log
import org.osservatorionessuno.cadb.AdbConnectionManager
import org.osservatorionessuno.qf.Module
import org.osservatorionessuno.cadb.AdbShell
import org.osservatorionessuno.cadb.AdbSync
import java.io.IOException
import org.osservatorionessuno.qf.storage.ArtifactSink

/**
 * Generates a bugreport on the device and pulls it locally via ADB Sync.
 * Output file: bugreport.zip
 */
class Bugreport : Module {
    override val name: String = "bugreport"
    private val TAG = "BugreportModule"

    override fun run(
        context: Context,
        manager: AdbConnectionManager,
        writer: ArtifactSink,
        progress: ((Long) -> Unit)?
    ) {
        // Shell progress is NOT file progress; leave it null.
        val shell = AdbShell(
            manager = manager,
            tag = "ShellQF",
            progress = null,
            timeoutMs = 15 * 60_000L, // 15 min hard cap
            inactivityMs = 60_000L    // bugreportz can be quiet for a while
        )

        // Sync progress is the one we want to surface.
        val sync = AdbSync(manager, progress)

        var remotePath: String? = null
        var pulled = false

        try {
            remotePath = discoverBugreportPath(shell)
            Log.i(TAG, "Bugreport path on device: $remotePath")

            writer.useArtifact("bugreport.zip") { output ->
                sync.pull(remotePath, output)
            }
            pulled = true
            Log.i(TAG, "Pulled bugreport")
        } finally {
            // Only delete remote if pull succeeded, to avoid races.
            if (pulled) {
                runCatching { remotePath?.let { shell.execForEachLine("""rm -f "$it"""") {} } }
                    .onFailure { Log.w(TAG, "Cleanup failed for $remotePath: ${it.message}") }
            } else {
                Log.w(TAG, "Not cleaning up remote; pull failed.")
            }
        }
    }

    /**
     * Prefer modern bugreportz; if we miss the OK line due to quiet output,
     * find the newest ZIP in the shell bugreports directory. Then fall back to legacy.
     */
    private fun discoverBugreportPath(shell: AdbShell): String {
        // A) Modern bugreportz: if the OK line is missing from quiet output, look for the
        // written ZIP before generating a whole second bugreport. Only accept ZIPs written
        // after we started, so a stale report from an earlier run is never pulled.
        val startedEpochSec = runCatching {
            shell.execFirstLine("date +%s").toLong()
        }.getOrDefault(0L)

        runCatching {
            runBugreportz(shell, "bugreportz -p")?.let { return it }

            findNewestShellBugreport(shell, startedEpochSec)?.let { return it }

            runBugreportz(shell, "bugreportz")?.let { return it }

            findNewestShellBugreport(shell, startedEpochSec)?.let { return it }
        }.onFailure {
            Log.w(TAG, "bugreportz invocation failed: ${it.message}")
        }

        // B) Fallback: legacy zip writer
        val zipFallback = "/sdcard/Download/bugreport.zip"
        runCatching {
            Log.i(TAG, "Falling back to: bugreport -f \"$zipFallback\"")
            shell.execForEachLine("""bugreport -f "$zipFallback"""") { line ->
                Log.d(TAG, "bugreport -f: $line")
            }
            if (remoteFileExists(shell, zipFallback)) return zipFallback
        }.onFailure {
            Log.w(TAG, "bugreport -f failed: ${it.message}")
        }

        // C) Last resort: legacy text
        val txtFallback = "/sdcard/Download/bugreport.txt"
        Log.i(TAG, "Falling back to legacy text bugreport -> \"$txtFallback\"")
        val outTxt = shell.execFirstLine("""bugreport >"$txtFallback" 2>/dev/null; echo $?""")
        Log.d(TAG, "legacy bugreport exit? $outTxt")
        if (remoteFileExists(shell, txtFallback)) return txtFallback

        throw IOException("Unable to generate bugreport via bugreportz or bugreport (zip/text).")
    }

    /**
     * Runs bugreportz and parses lines like:
     *   OK: /data/user_de/0/com.android.shell/files/bugreports/bugreport-YYYY...zip
     *   FAILED: <reason>  -> throw
     * Failure is thrown only after the command completes: an exception from the
     * line callback would make AdbShell retry the whole bugreportz run.
     */
    private fun runBugreportz(shell: AdbShell, command: String): String? {
        var okPath: String? = null
        var failLine: String? = null
        shell.execForEachLine(command) { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@execForEachLine
            Log.d(TAG, "$command: $line")
            when {
                line.startsWith("FAIL", ignoreCase = true) -> if (failLine == null) failLine = line
                line.startsWith("OK:", ignoreCase = true) -> okPath = line.substringAfter("OK:").trim()
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
    private fun findNewestShellBugreport(shell: AdbShell, notBeforeEpochSec: Long): String? {
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
                    Log.w(TAG, "Ignoring stale bugreport ZIP: $candidate (mtime=$mtime)")
                    continue
                }
            }
            Log.i(TAG, "Found newest bugreport ZIP in $dir: $candidate")
            return candidate
        }
        return null
    }
}
