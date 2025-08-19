package org.osservatorionessuno.bugbane.qf

import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.*
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.*

class Shell(
    private val manager: AbsAdbConnectionManager,
    private val tag: String = "ShellQF",
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

    fun execToFile(command: String, file: File) {
        val temp = File(file.parentFile, file.name + ".part").apply {
            parentFile?.mkdirs()
            delete()
        }

        FileOutputStream(temp).use { out ->
            execInternal(command, out)
        }

        if (file.exists()) file.delete()
        if (!temp.renameTo(file)) temp.copyTo(file, overwrite = true)
        temp.delete()
    }

    private fun execInternal(command: String, sink: OutputStream) {
        var lastErr: Throwable? = null
        repeat(RETRIES + 1) { attempt ->
            try {
                val marker = "__QF_MARKER_${UUID.randomUUID()}__"
                val fullCmd = "$command; echo $marker"
                Log.d(tag, "[exec] Running: $fullCmd")
                runWithStream("shell:$fullCmd", sink, marker)
                return
            } catch (t: Throwable) {
                Log.w(tag, "[exec] Attempt $attempt failed: ${t.message}")
                lastErr = t
            }
        }
        throw IOException("All attempts failed", lastErr)
    }

    private fun runWithStream(command: String, sink: OutputStream, marker: String) {
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
                if (System.nanoTime() - startTime > TimeUnit.MILLISECONDS.toNanos(timeoutMs)) {
                    throw IOException("Shell command timed out: $command")
                }

                val bytesRead = try {
                    readOnceWithTimeout(executor, input, buf, inactivityMs)
                } catch (e: TimeoutException) {
                    Log.d(tag, "[exec] Inactivity fallback triggered for: $command")
                    break
                } catch (e: IOException) {
                    if (e.message?.contains("stream closed", ignoreCase = true) == true) break
                    throw e
                }

                if (bytesRead == -1) break

                var writeUntil = bytesRead

                for (i in 0 until bytesRead) {
                    sliding.addLast(buf[i])
                    if (sliding.size > markerBytes.size) sliding.removeFirst()

                    if (sliding.size == markerBytes.size && sliding.toByteArray().contentEquals(markerBytes)) {
                        markerMatched = true
                        writeUntil = i - markerBytes.size + 1
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

            if (!markerMatched) {
                Log.w(tag, "[exec] Marker not found; using fallback")
            }
        } finally {
            executor.shutdownNow()
            stream.close()
        }
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
}
