package org.osservatorionessuno.cadb

import android.util.Log
import java.io.*
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.*

class AdbShell(
    private val manager: AdbConnectionManager,
    private val tag: String = "AdbShell",
    private val progress: ((Long) -> Unit)? = null,
    private val timeoutMs: Long = 30_000L,
    private val inactivityMs: Long = 5_000L
) {
    companion object {
        private const val RETRIES = 1
    }

    fun exec(command: String): String {
        // TODO: implement command-injection checks!
        val output = ByteArrayOutputStream()
        execInternal(command, output)
        return output.toString(StandardCharsets.UTF_8.name())
    }

    fun execToFile(command: String, file: File) {
        val temp = File(file.parentFile, file.name + ".part").apply {
            parentFile?.mkdirs()
            delete()
        }
        FileOutputStream(temp).use { out -> execInternal(command, out) }
        if (file.exists()) file.delete()
        if (!temp.renameTo(file)) temp.copyTo(file, overwrite = true)
        temp.delete()
    }

    private fun execInternal(command: String, sink: OutputStream) {
        var lastErr: Throwable? = null
        repeat(RETRIES + 1) { attempt ->
            try {
                val marker = "__QF__${UUID.randomUUID()}__EOX__"
                // Always run inside a shell and print marker via printf (more reliable than echo).
                val script = "LC_ALL=C; exec 2>&1; { $command ; }; /system/bin/printf \"%s\\n\" \"$marker\""
                val wrapped = "/system/bin/sh -c " + shSingleQuote(script)
                Log.d(tag, "[exec] Running: $wrapped")

                val found = runWithStream("shell:$wrapped", sink, marker)
                if (!found) {
                    Log.w(tag, "[exec] Marker not seen; stream ended/idle before marker (accepting output)")
                }
                return
            } catch (t: Throwable) {
                Log.w(tag, "[exec] Attempt $attempt failed: ${t.message}")
                lastErr = t
            }
        }
        throw IOException("All attempts failed", lastErr)
    }

    /**
     * Reads the stream, writes to [sink], returns true if marker matched, false if stream ended/idle first.
     * Throws only on hard timeout or unexpected IO.
     */
    private fun runWithStream(command: String, sink: OutputStream, marker: String): Boolean {
        val stream = manager.openStream(command)
        val input = stream.openInputStream().buffered()
        val executor = Executors.newSingleThreadExecutor { Thread(it, "ShellReader").apply { isDaemon = true } }

        val buf = ByteArray(DEFAULT_BUFFER_SIZE)
        val markerBytes = marker.toByteArray(StandardCharsets.UTF_8)
        val sliding = ArrayDeque<Byte>(markerBytes.size)
        var markerMatched = false
        val startTime = System.nanoTime()

        try {
            loop@ while (true) {
                // Hard timeout always enforced
                if (System.nanoTime() - startTime > TimeUnit.MILLISECONDS.toNanos(timeoutMs)) {
                    throw IOException("Shell command timed out: $command")
                }

                val bytesRead = try {
                    readOnceWithTimeout(executor, input, buf, inactivityMs)
                } catch (e: TimeoutException) {
                    Log.d(tag, "[exec] Inactivity: no bytes for ${inactivityMs}ms (fallback exit)")
                    break
                } catch (e: IOException) {
                    // Some devices close the stream abruptly when the process exits.
                    if (e.message?.contains("stream closed", ignoreCase = true) == true) {
                        Log.d(tag, "[exec] Stream closed by remote")
                        break
                    }
                    throw e
                }

                if (bytesRead == -1) {
                    Log.d(tag, "[exec] EOF")
                    break
                }

                var writeUntil = bytesRead

                // Fast byte scanner for marker (no decoding).
                for (i in 0 until bytesRead) {
                    sliding.addLast(buf[i])
                    if (sliding.size > markerBytes.size) sliding.removeFirst()

                    if (sliding.size == markerBytes.size && slidingMatches(sliding, markerBytes)) {
                        markerMatched = true
                        writeUntil = i - markerBytes.size + 1 // exclude the marker itself
                        break
                    }
                }

                if (writeUntil > 0) {
                    sink.write(buf, 0, writeUntil)
                    progress?.invoke(writeUntil.toLong())
                }

                if (markerMatched) {
                    Log.d(tag, "[exec] Marker matched; command complete")
                    break@loop
                }
            }

            sink.flush()
            return markerMatched
        } finally {
            executor.shutdownNow()
            stream.close()
        }
    }

    private fun slidingMatches(q: ArrayDeque<Byte>, bytes: ByteArray): Boolean {
        if (q.size != bytes.size) return false
        var i = 0
        for (b in q) {
            if (b != bytes[i++]) return false
        }
        return true
    }

    private fun readOnceWithTimeout(
        executor: ExecutorService,
        input: InputStream,
        buf: ByteArray,
        timeoutMs: Long
    ): Int {
        val f = executor.submit<Int> { input.read(buf) }
        return try {
            f.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            f.cancel(true)
            throw e
        } catch (e: Exception) {
            f.cancel(true)
            val cause = e.cause
            if (cause is IOException) throw cause
            throw e
        }
    }

    /** Safely single-quote a script for sh -c. */
    private fun shSingleQuote(s: String): String {
        // ' -> '"'"'  (classic POSIX-safe quoting)
        val escaped = s.replace("'", "'\"'\"'")
        return "'$escaped'"
    }
}
