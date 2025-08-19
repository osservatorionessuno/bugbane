package org.osservatorionessuno.bugbane.qf.modules

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.osservatorionessuno.bugbane.qf.Module
import org.osservatorionessuno.bugbane.qf.Shell
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.File

class Files : Module {
    override val name = "files"

    override fun run(
        context: Context,
        manager: AbsAdbConnectionManager,
        outDir: File,
        progress: ((Long) -> Unit)?
    ) {
        outDir.mkdirs()
        val sh = Shell(manager, progress = progress)

        // Detect find -printf capability
        val supportsPrintf = runCatching {
            sh.exec("""find '/' -maxdepth 1 -printf '%T@ %m %s %u %g %p\n' 2>/dev/null""")
        }.getOrDefault("").isNotBlank()

        // Roots (same as Go), plus TMPDIR / EXTERNAL_STORAGE if present
        val roots = mutableListOf(
            "/sdcard/", "/system/", "/system_ext/", "/vendor/", "/cust/", "/product/", "/apex/",
            "/data/local/tmp/", "/data/media/0/", "/data/misc/radio/", "/data/vendor/secradio/",
            "/data/log/", "/tmp/", "/", "/data/data/"
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
        val arr = JSONArray()

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
                        arr.put(
                            JSONObject().apply {
                                put("path", path)
                                put("mtime", mtime ?: JSONObject.NULL)
                                put("mode", mode)
                                put("size", size ?: JSONObject.NULL)
                                put("user", user)
                                put("group", group)
                            }
                        )
                    }
                }
            } else {
                out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { path ->
                    if (seen.add(path)) {
                        arr.put(
                            JSONObject().apply {
                                put("path", path)
                                put("mtime", JSONObject.NULL)
                                put("mode", JSONObject.NULL)
                                put("size", JSONObject.NULL)
                                put("user", JSONObject.NULL)
                                put("group", JSONObject.NULL)
                            }
                        )
                    }
                }
            }
        }

        File(outDir, "files.json").writeText(arr.toString(1))
    }

    /* helpers */

    private fun addDir(list: MutableList<String>, dir: String) {
        if (dir.isBlank()) return
        list += if (dir.endsWith('/')) dir else "$dir/"
    }

    private fun shQuote(s: String) = "'" + s.replace("'", "'\"'\"'") + "'"
}
