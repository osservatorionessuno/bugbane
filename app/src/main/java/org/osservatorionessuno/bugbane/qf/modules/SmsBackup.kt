package org.osservatorionessuno.bugbane.qf.modules

import android.content.Context
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import org.osservatorionessuno.bugbane.qf.Module
import org.osservatorionessuno.bugbane.qf.Shell
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

class SmsBackup : Module {
    override val name: String = "sms_backup"
    private val TAG = "SmsBackupModule"
    private val TELEPHONY_PKG = "com.android.providers.telephony"

    override fun run(
        context: Context,
        manager: AbsAdbConnectionManager,
        outDir: File,
        progress: ((Long) -> Unit)?
    ) {
        if (!outDir.exists()) outDir.mkdirs()
        val dest = File(outDir, "sms_backup.ab")
        val temp = File(outDir, "sms_backup.ab.part").apply { delete() }

        // Optional preflight: if allowBackup=false, likely no data.
        runCatching {
            val shell = Shell(manager, "ShellQF", progress = null, timeoutMs = 30_000, inactivityMs = 10_000)
            val hint = shell.exec("""dumpsys package $TELEPHONY_PKG | grep -i allowBackup || true""").trim()
            if (hint.contains("allowBackup=false", ignoreCase = true)) {
                Log.w(TAG, "Telephony has allowBackup=false; ADB backup may be empty.")
            }
        }

        // Mirrors: adb backup -noapk com.android.providers.telephony
        val service = "backup:-noapk $TELEPHONY_PKG"
        Log.i(TAG, "Opening ADB service: $service")
        Log.i(TAG, "Unlock device and CONFIRM the on-screen backup prompt.")

        var stream: AdbStream? = null
        var total = 0L
        var sawAnyBytes = false

        try {
            stream = manager.openStream(service)
            stream.openInputStream().use { ins ->
                FileOutputStream(temp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = try {
                            ins.read(buf)
                        } catch (e: IOException) {
                            if (e.message?.contains("Stream closed", ignoreCase = true) == true && sawAnyBytes) {
                                Log.i(TAG, "Backup stream closed by device (EOF after $total bytes).")
                                break
                            }
                            throw e
                        }

                        if (n < 0) {
                            Log.i(TAG, "EOF after $total bytes.")
                            break
                        }
                        if (n == 0) continue

                        out.write(buf, 0, n)
                        total += n
                        sawAnyBytes = true
                        progress?.invoke(total)
                    }
                    out.fd.sync()
                }
            }

            // Atomic move
            if (dest.exists()) dest.delete()
            if (!temp.renameTo(dest)) temp.copyTo(dest, overwrite = true)
            temp.delete()

            Log.i(TAG, "Wrote $total bytes to ${dest.absolutePath}")
            runCatching { logAbHeader(dest) }
                .onFailure { Log.w(TAG, "Could not parse .ab header: ${it.message}") }

            if (dest.length() < 2048L) {
                Log.w(TAG, "Backup looks header-only (${dest.length()} bytes). " +
                        "Device likely blocks SMS app-data via ADB backup (allowBackup=false or modern restrictions).")
            } else {
                Log.i(TAG, "SMS backup completed: ${dest.absolutePath}")
            }
        } catch (e: IOException) {
            // If user cancelled immediately, we might have 0 bytes + Stream closed -> still error
            temp.delete()
            Log.e(TAG, "SMS backup failed: ${e.message}", e)
            throw e
        } finally {
            runCatching { stream?.close() }
        }
    }

    /** Minimal ANDROID BACKUP header introspection for logging. */
    private fun logAbHeader(file: File) {
        FileInputStream(file).use { ins ->
            fun readLine(): String {
                val sb = StringBuilder()
                while (true) {
                    val b = ins.read()
                    if (b == -1 || b == '\n'.code) break
                    sb.append(b.toChar())
                }
                return sb.toString()
            }

            val magic = readLine()
            if (magic != "ANDROID BACKUP") {
                Log.w(TAG, "Not an ANDROID BACKUP file (magic=$magic)")
                return
            }
            val ver = readLine().toIntOrNull() ?: -1
            val comp = readLine()
            val enc = readLine()
            Log.i(TAG, "AB header: version=$ver, compression=$comp, encryption=$enc")
            if (!enc.equals("none", ignoreCase = true)) {
                Log.i(TAG, "Encrypted backup: small files can still be valid (salts/IV/metadata even if no app data).")
            }
        }
    }
}
