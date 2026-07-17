package org.osservatorionessuno.qf.modules

import android.content.Context
import android.util.Log
import org.osservatorionessuno.qf.Module
import org.osservatorionessuno.cadb.AdbShell
import org.osservatorionessuno.cadb.AdbConnectionManager
import org.osservatorionessuno.qf.ArtifactProtobuf
import org.osservatorionessuno.qf.storage.ArtifactSink

/**
 * Checks for common rooting binaries/apps in PATH and saves the found paths.
 * Output: root_binaries.pb  (length-delimited protobuf string records)
 */
class RootBinaries : Module {
    override val name: String = "root_binaries"
    private val TAG = "RootBinaries"

    // Same (and a bit extended) list as the Go version
    private val targets = listOf(
        "su",
        "busybox",
        "supersu",
        "Superuser.apk",
        "KingoUser.apk",
        "SuperSu.apk",
        "magisk",
        "magiskhide",
        "magiskinit",
        "magiskpolicy"
    )

    override fun run(
        context: Context,
        manager: AdbConnectionManager,
        writer: ArtifactSink,
        progress: ((Long) -> Unit)?
    ) {
        // Shell output bytes aren't meaningful progress here; keep null.
        val shell = AdbShell(manager, tag = "ShellQF", progress = null)

        val found = LinkedHashSet<String>()
        for (bin in targets) {
            val script = """
                # Expand PATH to include common root locations
                PATH="${'$'}PATH:/system/bin:/system/xbin:/sbin:/su/bin:/vendor/bin"
                # If command -v finds it, enumerate all matches along PATH; else try which -a
                if command -v $bin >/dev/null 2>&1; then
                  IFS=:; for d in ${'$'}PATH; do
                    [ -x "${'$'}d/$bin" ] && printf "%s\n" "${'$'}d/$bin"
                  done
                else
                  which -a $bin 2>/dev/null || true
                fi
            """.trimIndent()

            runCatching {
                shell.execForEachLine(script) { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("/")) found.add(trimmed)
                }
            }
        }

        val unique = found.distinct()
        Log.i(TAG, "Found ${unique.size} root-related binaries")
        writer.useArtifact("root_binaries.pb") { output ->
            for (path in unique) {
                ArtifactProtobuf.writeDelimitedStringRecord(output, path)
            }
        }
    }
}
