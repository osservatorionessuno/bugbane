package org.osservatorionessuno.qf

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Timestamped acquisition log, stored as `command.log` in the archive with
 * androidqf's line format: `<RFC3339> [LEVEL] message`.
 *
 * [step] also shows the message, localized, as the running module's status.
 * The recorded copy is always English.
 */
class AcquisitionLog(private val context: Context) {
    private val english by lazy {
        context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        )
    }
    private val buffer = StringBuilder()
    private val operations = AtomicInteger()

    /** Receives localized step messages; set by the runner around each module. */
    @Volatile var onStep: ((String) -> Unit)? = null

    fun debug(message: String) = write("DEBUG", message)
    fun info(message: String) = write("INFO", message)
    fun warning(message: String) = write("WARNING", message)
    fun error(message: String) = write("ERROR", message)

    /**
     * Record [description] under a fresh `#id`, run [block], then record its
     * duration and [result] detail, or the error it failed with.
     */
    fun <T> operation(description: String, result: (T) -> String? = { null }, block: () -> T): T {
        val id = operations.incrementAndGet()
        info("[#$id] $description")
        val started = System.nanoTime()
        try {
            val value = block()
            val detail = result(value)?.let { ", $it" } ?: ""
            info("[#$id] Completed in ${elapsed(started)}$detail")
            return value
        } catch (c: AcquisitionCancelledException) {
            warning("[#$id] Cancelled by the user after ${elapsed(started)}")
            throw c
        } catch (t: Throwable) {
            error("[#$id] Failed after ${elapsed(started)}: ${t.describe()}")
            throw t
        }
    }

    /** Record a step and show it in the UI. */
    fun step(@StringRes res: Int, vararg args: Any) {
        info(english.getString(res, *args))
        show(res, *args)
    }

    /** Show a step in the UI without recording it (for fast-changing status like percentages). */
    fun show(@StringRes res: Int, vararg args: Any) {
        onStep?.invoke(context.getString(res, *args))
    }

    @Synchronized
    private fun write(level: String, message: String) {
        buffer.append(formatLine(OffsetDateTime.now(), level, message))
    }

    companion object {
        fun elapsed(startedNanos: Long): String =
            String.format(Locale.ROOT, "%.3f s", (System.nanoTime() - startedNanos) / 1e9)

        /** The error and its causes, e.g. "All attempts failed: Sync failed: Permission denied". */
        fun Throwable.describe(): String =
            generateSequence(this) { it.cause }.take(4)
                .joinToString(": ") { it.message ?: it.javaClass.simpleName }

        fun formatLine(time: OffsetDateTime, level: String, message: String): String =
            "${DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(time.truncatedTo(ChronoUnit.SECONDS))} [$level] $message\n"
    }

    @Synchronized
    fun toByteArray(): ByteArray = buffer.toString().toByteArray(Charsets.UTF_8)
}

/** [AcquisitionLog.operation] when a log is attached, otherwise just [block]. */
fun <T> AcquisitionLog?.operation(description: String, result: (T) -> String? = { null }, block: () -> T): T =
    if (this == null) block() else operation(description, result, block)
