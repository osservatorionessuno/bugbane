package org.osservatorionessuno.qf.modules

import android.content.Context
import android.os.Environment
import android.util.JsonWriter
import org.osservatorionessuno.qf.Module
import org.osservatorionessuno.cadb.AdbShell
import org.osservatorionessuno.cadb.AdbConnectionManager
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter

class Files : Module {
    override val name = "files"

    override fun run(
        context: Context,
        manager: AdbConnectionManager,
        outDir: File,
        progress: ((Long) -> Unit)?
    ) {
        val sh = AdbShell(manager, progress = progress)

        // Detect find -printf capability
        val supportsPrintf = runCatching {
            sh.exec("""find '/' -maxdepth 1 -printf '%T@ %m %s %u %g %p\n' 2>/dev/null""")
        }.getOrDefault("").isNotBlank()

        // Folders from https://github.com/mvt-project/androidqf/blob/main/modules/files.go
        val roots = mutableListOf(
            Environment.getExternalStorageDirectory().path, "/sdcard/", "/system/", "/system_ext/",
            "/vendor/", "/cust/", "/product/", "/apex/", "/data/local/tmp/", "/data/media/0/",
            "/data/misc/radio/", "/data/vendor/secradio/", "/data/log/", "/tmp/", "/", "/data/data/"
        )
        runCatching { sh.exec("env 2>/dev/null") }.getOrNull()?.let { env ->
            env.lineSequence().forEach {
                when {
                    it.startsWith("TMPDIR=") -> addDir(roots, it.substringAfter("TMPDIR="))
                    it.startsWith("EXTERNAL_STORAGE=") -> addDir(roots, it.substringAfter("EXTERNAL_STORAGE="))
                }
            }
        }
        val folders = roots.distinct()

        val seen = HashSet<String>()

        val outFile = File(outDir, "files.json")
        JsonWriter(BufferedWriter(OutputStreamWriter(outFile.outputStream(), Charsets.UTF_8))).use { jw ->
            jw.setIndent(" ")
            jw.beginArray()

            for (folder in folders) {
                val cmd = if (supportsPrintf)
                    """find ${shQuote(folder)} -printf '%T@ %m %s %u %g %p\n' 2>/dev/null"""
                else
                    """find ${shQuote(folder)} -print 2>/dev/null"""

                val out = runCatching { sh.exec(cmd) }.getOrDefault("")
                if (out.isBlank()) continue

                if (supportsPrintf) {
                    // "%T@ %m %s %u %g %p"
                    out.lineSequence().forEach { line ->
                        val parts = line.trim().split(Regex("\\s+"), limit = 6)
                        if (parts.size < 6) return@forEach
                        val mtime = parts[0].toDoubleOrNull()
                        val mode  = parts[1]
                        val size  = parts[2].toLongOrNull()
                        val user  = parts[3]
                        val group = parts[4]
                        val path  = parts[5]
                        if (seen.add(path)) {
                            writeEntry(jw, path, mtime, mode, size, user, group)
                        }
                    }
                } else {
                    out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { path ->
                        if (seen.add(path)) {
                            writeEntry(jw, path, null, null, null, null, null)
                        }
                    }
                }

                jw.flush()
            }

            jw.endArray()
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
