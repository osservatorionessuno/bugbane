package org.osservatorionessuno.cadb

import android.util.Log
import java.io.*
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.*

class ShellTimeoutException(message: String) : IOException(message)

class ShellInactivityException(message: String, cause: Throwable? = null) : IOException(message, cause)

class AdbShell(
    private val manager: AdbConnectionManager,
    private val tag: String = "AdbShell",
    private val progress: ((Long) -> Unit)? = null,
    // Generous defaults: full dumpsys/logcat dumps run for minutes and can stay quiet ~10s.
    private val timeoutMs: Long = 5 * 60_000L,
    private val inactivityMs: Long = 30_000L
) {
    companion object {
        private const val RETRIES = 1
        private const val READ_BUFFER_SIZE = 1 shl 20
    }

    @Deprecated("This method buffers and could use a lot of memory. Use execToStream or execForEachLine whenever possible")
    fun exec(command: String): String {
        val output = ByteArrayOutputStream()
        execInternal(command, output)
        return output.toString(StandardCharsets.UTF_8.name())
    }

    @Deprecated("This method write files on disk and should not be used. Use execToStream instead")
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

    fun execToStream(command: String, output: OutputStream) {
        execInternal(command, output)
    }

    fun execForEachLine(command: String, onLine: (String) -> Unit) {
        LineDispatchOutputStream(onLine).use { output ->
            execInternal(command, output)
        }
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
            } catch (t: ShellTimeoutException) {
                // Timeouts are deterministic-slow, not flaky: retrying just doubles the stall.
                throw IOException("All attempts failed", t)
            } catch (t: ShellInactivityException) {
                throw IOException("All attempts failed", t)
            } catch (t: Throwable) {
                Log.w(tag, "[exec] Attempt $attempt failed: ${t.message}")
                lastErr = t
            }
        }
        throw IOException("All attempts failed", lastErr)
    }

    /**
     * Reads the stream, writes to [sink], returns true if marker matched.
     * Throws on hard timeout, inactivity, or unexpected IO.
     */
    private fun runWithStream(command: String, sink: OutputStream, marker: String): Boolean {
        val stream = manager.openStream(command)
        val input = stream.openInputStream()
        val executor = Executors.newSingleThreadExecutor { Thread(it, "ShellReader").apply { isDaemon = true } }

        val markerBytes = marker.toByteArray(StandardCharsets.UTF_8)
        // Hold back the last markerBytes.size - 1 bytes across reads so a marker
        // split between chunks is neither missed nor leaked into the sink.
        val keep = markerBytes.size - 1
        val work = ByteArray(READ_BUFFER_SIZE + keep)
        var carry = 0
        var markerMatched = false
        val startTime = System.nanoTime()

        try {
            while (true) {
                // Hard timeout always enforced
                if (System.nanoTime() - startTime > TimeUnit.MILLISECONDS.toNanos(timeoutMs)) {
                    throw ShellTimeoutException("Shell command timed out after ${timeoutMs}ms: $command")
                }

                val bytesRead = try {
                    readOnceWithTimeout(executor, input, work, carry, work.size - carry, inactivityMs)
                } catch (e: TimeoutException) {
                    throw ShellInactivityException(
                        "Shell command inactive for ${inactivityMs}ms: $command",
                        e,
                    )
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

                val length = carry + bytesRead
                val markerAt = indexOf(work, length, markerBytes)
                if (markerAt >= 0) {
                    if (markerAt > 0) {
                        sink.write(work, 0, markerAt)
                        progress?.invoke(markerAt.toLong())
                    }
                    markerMatched = true
                    Log.d(tag, "[exec] Marker matched; command complete")
                    break
                }

                val flush = length - keep
                if (flush > 0) {
                    sink.write(work, 0, flush)
                    progress?.invoke(flush.toLong())
                    System.arraycopy(work, flush, work, 0, keep)
                    carry = keep
                } else {
                    carry = length
                }
            }

            // Stream ended without marker: the held-back tail is real output.
            if (!markerMatched && carry > 0) {
                sink.write(work, 0, carry)
                progress?.invoke(carry.toLong())
            }

            sink.flush()
            return markerMatched
        } finally {
            executor.shutdownNow()
            stream.close()
        }
    }

    private fun indexOf(haystack: ByteArray, length: Int, needle: ByteArray): Int {
        val first = needle[0]
        val max = length - needle.size
        var i = 0
        while (i <= max) {
            if (haystack[i] == first) {
                var j = 1
                while (j < needle.size && haystack[i + j] == needle[j]) j++
                if (j == needle.size) return i
            }
            i++
        }
        return -1
    }

    private fun readOnceWithTimeout(
        executor: ExecutorService,
        input: InputStream,
        buf: ByteArray,
        off: Int,
        len: Int,
        timeoutMs: Long
    ): Int {
        val f = executor.submit<Int> { input.read(buf, off, len) }
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

    /** Buffers shell output and invokes [onLine] once per newline-delimited line. */
    private class LineDispatchOutputStream(
        private val onLine: (String) -> Unit,
    ) : OutputStream() {
        private val pending = ByteArrayOutputStream()

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(bytes: ByteArray, off: Int, len: Int) {
            var start = off
            val end = off + len
            while (start < end) {
                var newlineAt = start
                while (newlineAt < end && bytes[newlineAt] != '\n'.code.toByte()) {
                    newlineAt++
                }
                if (newlineAt == end) {
                    pending.write(bytes, start, end - start)
                    return
                }
                pending.write(bytes, start, newlineAt - start)
                dispatchLine()
                start = newlineAt + 1
            }
        }

        override fun close() {
            dispatchLine()
        }

        private fun dispatchLine() {
            if (pending.size() == 0) return
            // toString(Charset) requires API 33; minSdk is 30
            onLine(String(pending.toByteArray(), StandardCharsets.UTF_8))
            pending.reset()
        }
    }
}
