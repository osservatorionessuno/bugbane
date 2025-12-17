package org.osservatorionessuno.qf.modules

import android.content.Context
import android.util.Log
import org.osservatorionessuno.cadb.AdbConnectionManager
import org.osservatorionessuno.qf.Module
import org.osservatorionessuno.cadb.AdbShell
import org.osservatorionessuno.cadb.AdbSync
import java.io.File
import java.io.IOException

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
        outDir: File,
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

        val dest = File(outDir, "bugreport.zip")
        var remotePath: String? = null
        var pulled = false

        try {
            remotePath = discoverBugreportPath(shell)
            Log.i(TAG, "Bugreport path on device: $remotePath")

            sync.pull(remotePath, dest)  // <-- progress reported from here
            pulled = true
            Log.i(TAG, "Pulled bugreport to: ${dest.absolutePath}")
        } finally {
            // Only delete remote if pull succeeded, to avoid races.
            if (pulled) {
                runCatching { remotePath?.let { shell.exec("""rm -f "$it"""") } }
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
        // A) Modern bugreportz (-p then plain)
        runCatching {
            val outP = shell.exec("bugreportz -p").trim()
            Log.d(TAG, "bugreportz -p output:\n$outP")
            parseBugreportzOutput(outP)?.let { return it }

            val out = shell.exec("bugreportz").trim()
            Log.d(TAG, "bugreportz output:\n$out")
            parseBugreportzOutput(out)?.let { return it }

            // Try to discover the latest file where bugreportz usually writes
            findNewestShellBugreport(shell)?.let { return it }
        }.onFailure {
            Log.w(TAG, "bugreportz invocation failed: ${it.message}")
        }

        // B) Fallback: legacy zip writer
        val zipFallback = "/sdcard/Download/bugreport.zip"
        runCatching {
            Log.i(TAG, "Falling back to: bugreport -f \"$zipFallback\"")
            val out = shell.exec("""bugreport -f "$zipFallback"""")
            Log.d(TAG, "bugreport -f output:\n$out")
            val stat = shell.exec("""ls -l "$zipFallback" || echo MISSING""")
            Log.d(TAG, "ls -l zipFallback:\n$stat")
            if (!stat.contains("MISSING")) return zipFallback
        }.onFailure {
            Log.w(TAG, "bugreport -f failed: ${it.message}")
        }

        // C) Last resort: legacy text
        val txtFallback = "/sdcard/Download/bugreport.txt"
        Log.i(TAG, "Falling back to legacy text bugreport -> \"$txtFallback\"")
        val outTxt = shell.exec("""bugreport >"$txtFallback" 2>/dev/null; echo $?""")
        Log.d(TAG, "legacy bugreport exit?\n$outTxt")
        val statTxt = shell.exec("""ls -l "$txtFallback" || echo MISSING""")
        Log.d(TAG, "ls -l txtFallback:\n$statTxt")
        if (!statTxt.contains("MISSING")) return txtFallback

        throw IOException("Unable to generate bugreport via bugreportz or bugreport (zip/text).")
    }

    /**
     * Accepts outputs like:
     *   OK: /data/user_de/0/com.android.shell/files/bugreports/bugreport-YYYY...zip
     *   FAILED: <reason>  -> throw
     */
    private fun parseBugreportzOutput(stdout: String): String? {
        if (stdout.isBlank()) return null
        val lines = stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }

        lines.firstOrNull {
            it.startsWith("FAIL", ignoreCase = true) || it.startsWith("FAILED", ignoreCase = true)
        }?.let { throw IOException("bugreportz failed: $it") }

        val okLine = lines.lastOrNull { it.startsWith("OK:", ignoreCase = true) } ?: return null
        val path = okLine.substringAfter("OK:", "").trim()
        return if (path.startsWith("/")) path else null
    }

    /**
     * Find the newest ZIP where bugreportz typically writes on modern Android.
     */
    private fun findNewestShellBugreport(shell: AdbShell): String? {
        val candidateDirs = listOf(
            "/data/user_de/0/com.android.shell/files/bugreports",
            "/data/user/0/com.android.shell/files/bugreports"
        )
        for (dir in candidateDirs) {
            val ls = shell.exec("""ls -1t "$dir"/*.zip 2>/dev/null | head -n 1 || true""").trim()
            if (ls.isNotEmpty() && ls.startsWith("/")) {
                Log.i(TAG, "Found newest bugreport ZIP in $dir: $ls")
                return ls
            }
        }
        return null
    }
}
