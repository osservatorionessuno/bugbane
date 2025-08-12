package org.osservatorionessuno.bugbane.qf

import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.LocalServices
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Small wrapper around [AbsAdbConnectionManager] that executes shell commands
 * and returns their standard output.
 */
class Shell(private val manager: AbsAdbConnectionManager) {

    /**
     * Execute [command] via the ADB shell service and return its output.
     *
     * The implementation is intentionally simple and meant for background
     * operations. Errors are propagated to the caller.
     */
    @Throws(IOException::class)
    fun exec(command: String): String {
        val stream: AdbStream = manager.openStream(LocalServices.SHELL)
        stream.use { s ->
            s.openOutputStream().use { os ->
                os.write((command + "\n").toByteArray(StandardCharsets.UTF_8))
                os.flush()
            }
            val sb = StringBuilder()
            BufferedReader(InputStreamReader(s.openInputStream())).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.appendLine(line)
                }
            }
            return sb.toString()
        }
    }

    /**
     * Execute [command] and stream the resulting standard output directly into
     * [file].
     *
     * This method is intended for commands that may produce large outputs, such
     * as `dumpsys` or `logcat`, to avoid keeping the entire response in memory.
     */
    @Throws(IOException::class)
    fun execToFile(command: String, file: File) {
        val stream: AdbStream = manager.openStream(LocalServices.SHELL)
        stream.use { s ->
            s.openOutputStream().use { os ->
                os.write((command + "\n").toByteArray(StandardCharsets.UTF_8))
                os.flush()
            }
            s.openInputStream().use { input ->
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
    }
}