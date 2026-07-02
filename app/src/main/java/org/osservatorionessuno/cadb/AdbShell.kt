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
    private val timeoutMs: Long = 30_000L,
    private val inactivityMs: Long = 5_000L
) {
    companion object {
        private const val RETRIES = 1
    }

    fun exec(command: String): String {
        val output = ByteArrayOutputStream()
        execInternal(command, output)
        return output.toString(StandardCharsets.UTF_8.name())
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
                    throw ShellTimeoutException("Shell command timed out after ${timeoutMs}ms: $command")
                }

                val bytesRead = try {
                    readOnceWithTimeout(executor, input, buf, inactivityMs)
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
            onLine(pending.toString(StandardCharsets.UTF_8))
            pending.reset()
        }
    }
}
