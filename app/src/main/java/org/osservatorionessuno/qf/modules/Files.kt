package org.osservatorionessuno.qf.modules

import android.content.Context
import android.os.Environment
import android.util.JsonWriter
import org.osservatorionessuno.qf.Module
import org.osservatorionessuno.cadb.AdbShell
import org.osservatorionessuno.cadb.AdbConnectionManager
import org.osservatorionessuno.qf.ArtifactProtobuf
import org.osservatorionessuno.qf.storage.ArtifactSink

class Files : Module {
    override val name = "files"

    override fun run(
        context: Context,
        manager: AdbConnectionManager,
        writer: ArtifactSink,
        progress: ((Long) -> Unit)?
    ) {
        val sh = AdbShell(manager, progress = progress)

        // Detect find -printf capability
        var supportsPrintf = false
        runCatching {
            sh.execForEachLine("""find '/' -maxdepth 1 -printf '%T@ %m %s %u %g %p\n' 2>/dev/null""") {
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

        writer.useArtifact("files.pb") { output ->
            for (folder in folders) {
                val cmd = if (supportsPrintf)
                    """find ${shQuote(folder)} -type f -printf '%T@ %m %s %u %g %p\n' 2>/dev/null"""
                else
                    """find ${shQuote(folder)} -type f -print 2>/dev/null"""

                if (supportsPrintf) {
                    // "%T@ %m %s %u %g %p"
                    runCatching { sh.execForEachLine(cmd) { line ->
                        val parts = line.trim().split(Regex("\\s+"), limit = 6)
                        if (parts.size < 6) return@execForEachLine
                        val mtime = parts[0].toDoubleOrNull()
                        val mode  = parts[1]
                        val size  = parts[2].toLongOrNull()
                        val user  = parts[3]
                        val group = parts[4]
                        val path  = parts[5]
                        if (seen.add(path)) {
                            ArtifactProtobuf.writeDelimitedFileRecord(output, path, mtime, mode, size, user, group)
                        }
                    } }
                } else {
                    runCatching { sh.execForEachLine(cmd) { line ->
                        val path = line.trim()
                        if (path.isEmpty()) return@execForEachLine
                        if (seen.add(path)) {
                            ArtifactProtobuf.writeDelimitedFileRecord(output, path, null, null, null, null, null)
                        }
                    } }
                }
                output.flush()
            }
        }
    }

    /* helpers */

    private fun writeEntry(
        jw: JsonWriter,
        path: String,
        mtime: Double?,
        mode: String?,
        size: Long?,
        user: String?,
        group: String?
    ) {
        jw.beginObject()
        jw.name("path").value(path)
        jw.name("mtime"); if (mtime == null) jw.nullValue() else jw.value(mtime)
        jw.name("mode");  if (mode  == null) jw.nullValue() else jw.value(mode)
        jw.name("size");  if (size  == null) jw.nullValue() else jw.value(size)
        jw.name("user");  if (user  == null) jw.nullValue() else jw.value(user)
        jw.name("group"); if (group == null) jw.nullValue() else jw.value(group)
        jw.endObject()
    }

    private fun addDir(list: MutableList<String>, dir: String) {
        if (dir.isBlank()) return
        list += if (dir.endsWith('/')) dir else "$dir/"
    }

    private fun shQuote(s: String) = "'" + s.replace("'", "'\"'\"'") + "'"
}
