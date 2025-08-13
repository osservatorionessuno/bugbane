package org.osservatorionessuno.bugbane.qf

import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import java.io.*
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class Shell(private val manager: AbsAdbConnectionManager) {

    companion object {
        private const val NO_PROGRESS_MS = 5_000L   // rule: no bytes for 5s => EOF
        private const val RETRIES = 1               // rule: if 0 bytes total => retry once
    }

    /** Try exec first, fall back to shell. */
    @Throws(IOException::class)
    private fun openCommandStream(command: String): AdbStream {
        return try {
            manager.openStream("exec:$command")
        } catch (_: IOException) {
            manager.openStream("shell:$command")
        }
    }

    /** Run a command and return its stdout as UTF-8 text. */
    @Throws(IOException::class)
    fun exec(command: String): String {
        var lastErr: Throwable? = null
        repeat(RETRIES + 1) { attempt ->
            val baos = ByteArrayOutputStream()
            try {
                readWithInactivityEOF(command, sink = baos)
                if (baos.size() == 0) {
                    // rule: no bytes read => fail & retry
                    if (attempt < RETRIES) return@repeat
                    throw IOException("No bytes read from command: $command")
                }
                return baos.toString(StandardCharsets.UTF_8.name())
            } catch (t: Throwable) {
                lastErr = t
                if (attempt >= RETRIES) throw t
            }
        }
        throw (lastErr ?: IOException("exec failed: $command"))
    }

    /** Run a command and stream stdout to a file. */
    @Throws(IOException::class)
    fun execToFile(command: String, file: File) {
        var lastErr: Throwable? = null
        repeat(RETRIES + 1) { attempt ->
            file.parentFile?.mkdirs()
            // write to temp first; only move on success
            val tmp = File(file.parentFile, file.name + ".part")
            if (tmp.exists()) tmp.delete()

            try {
                FileOutputStream(tmp).use { out ->
                    readWithInactivityEOF(command, sink = out)
                }
                if (tmp.length() == 0L) {
                    // rule: no bytes read => fail & retry
                    tmp.delete()
                    if (attempt < RETRIES) return@repeat
                    throw IOException("No bytes read from command: $command")
                }
                if (file.exists()) file.delete()
                if (!tmp.renameTo(file)) {
                    // best-effort copy/replace
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
                return
            } catch (t: Throwable) {
                lastErr = t
                tmp.delete()
                if (attempt >= RETRIES) throw t
            }
        }
        throw (lastErr ?: IOException("execToFile failed: $command"))
    }

    /* -------------------- core: read with inactivity=>EOF -------------------- */

    /**
     * Reads stdout of [command] and writes to [sink].
     *
     * Rules implemented:
     *  - IOException containing "closed"/"Stream closed" => treat as EOF (success).
     *  - If a read produces no bytes for NO_PROGRESS_MS => treat as EOF (success).
     *  - Caller handles "total bytes == 0" as failure/ retry.
     */
    @Throws(IOException::class)
    private fun readWithInactivityEOF(command: String, sink: OutputStream) {
        openCommandStream(command).use { stream ->
            stream.openInputStream().use { input ->
                val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                var total: Long = 0

                // single-threaded read with per-read inactivity timeout
                val exec = Executors.newSingleThreadExecutor { r ->
                    Thread(r, "QF.read").apply { isDaemon = true }
                }
                try {
                    while (true) {
                        val bytes = try {
                            readOnceWithTimeout(exec, input, buf, NO_PROGRESS_MS)
                        } catch (te: TimeoutException) {
                            // rule: no bytes for 5s => EOF
                            break
                        } catch (io: IOException) {
                            // rule: stream closed => EOF
                            val m = io.message?.lowercase() ?: ""
                            if ("closed" in m) break
                            throw io
                        }
                        if (bytes == -1) {
                            // normal EOF
                            break
                        }
                        if (bytes > 0) {
                            sink.write(buf, 0, bytes)
                            total += bytes
                        }
                        // If bytes == 0, continue; (shouldn't happen with blocking read)
                    }
                    sink.flush()
                } finally {
                    exec.shutdownNow()
                }
            }
        }
    }

    /**
     * Performs a single blocking read() with an inactivity timeout.
     * If the read produces no result within [timeoutMs], throws TimeoutException.
     */
    private fun readOnceWithTimeout(
        executor: java.util.concurrent.ExecutorService,
        input: InputStream,
        buf: ByteArray,
        timeoutMs: Long
    ): Int {
        val future = executor.submit<Int> { input.read(buf) }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw e
        } catch (e: Exception) {
            future.cancel(true)
            // Unwrap IOExceptions thrown from the callable
            val cause = e.cause
            if (cause is IOException) throw cause
            throw e
        }
    }
}
