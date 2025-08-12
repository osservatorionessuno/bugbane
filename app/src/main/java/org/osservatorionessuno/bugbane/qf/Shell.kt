package org.osservatorionessuno.bugbane.qf

import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class Shell(private val manager: AbsAdbConnectionManager) {

    // Try exec: first (no PTY, cleaner), fall back to shell:
    @Throws(IOException::class)
    private fun openCommandStream(command: String): AdbStream {
        return try {
            manager.openStream("exec:$command")
        } catch (e: IOException) {
            // Older/newer mismatches or vendor builds may lack exec:
            manager.openStream("shell:$command")
        }
    }

    /** Run a command and return its entire stdout as UTF-8 text. */
    @Throws(IOException::class)
    fun exec(command: String): String {
        openCommandStream(command).use { s ->
            s.openInputStream().use { input ->
                return input.readBytes().toString(Charsets.UTF_8)
            }
        }
    }

    /** Run a command and stream stdout to a file (good for big outputs). */
    @Throws(IOException::class)
    fun execToFile(command: String, file: File) {
        openCommandStream(command).use { s ->
            s.openInputStream().use { input ->
                file.parentFile?.mkdirs()
                FileOutputStream(file).use { output ->
                    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val n = try {
                            input.read(buf)
                        } catch (e: IOException) {
                            // Many devices throw "Stream closed" at logical EOF. If we've written
                            // something already, treat it as success; otherwise rethrow.
                            if (file.length() > 0L) break else throw e
                        }
                        if (n == -1) break
                        output.write(buf, 0, n)
                    }
                    output.flush()
                }
            }
        }
    }
}
