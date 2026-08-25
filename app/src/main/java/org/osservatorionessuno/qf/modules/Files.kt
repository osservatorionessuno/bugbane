package org.osservatorionessuno.qf.modules

import android.content.Context
import android.os.Environment
import org.osservatorionessuno.qf.Module
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
        progress: ((Long) -> Unit)?
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

        writer.useArtifact("files.json") { output ->
            for (folder in folders) {
                val cmd = if (supportsPrintf)
                    """find ${shQuote(folder)} -type f -printf '$PRINTF\n' 2>/dev/null"""
                else
                    """find ${shQuote(folder)} -type f -print 2>/dev/null"""

                if (supportsPrintf) {
                    runCatching { sh.execForEachLine(cmd) { line ->
                        val parts = line.trim().split(WHITESPACE, limit = 9)
                        if (parts.size < 9) return@execForEachLine
                        val path = parts[8]
                        if (seen.add(path)) {
                            ArtifactJson.file(
                                output, path,
                                parts[0].toDoubleOrNull(), parts[1].toDoubleOrNull(), parts[2].toDoubleOrNull(),
                                parts[3], parts[4].toLongOrNull(), parts[5], parts[6], parts[7],
                            )
                        }
                    } }
                } else {
                    runCatching { sh.execForEachLine(cmd) { line ->
                        val path = line.trim()
                        if (path.isEmpty()) return@execForEachLine
                        if (seen.add(path)) {
                            ArtifactJson.file(output, path, null, null, null, null, null, null, null, null)
                        }
                    } }
                }
                output.flush()
            }
        }
    }

    /* helpers */

    private fun addDir(list: MutableList<String>, dir: String) {
        if (dir.isBlank()) return
        list += if (dir.endsWith('/')) dir else "$dir/"
    }

    private fun shQuote(s: String) = "'" + s.replace("'", "'\"'\"'") + "'"
}
