package org.osservatorionessuno.bugbane.update

import android.util.Log
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Harness for the feed's `sunset` field. Evaluation only for now
 */
sealed interface SunsetStatus {
    /** No sunset advertised by the feed. */
    object None : SunsetStatus

    /** A sunset is set but still in the future: prompt the user to update before [date]. */
    data class Upcoming(val date: LocalDate) : SunsetStatus

    /** The sunset has passed: keep last-good indicators and tell the user to update the app. */
    data class Reached(val date: LocalDate) : SunsetStatus

    companion object {
        const val TAG = "IndicatorSunset"

        fun evaluate(sunset: String?, today: LocalDate = LocalDate.now()): SunsetStatus {
            if (sunset.isNullOrBlank()) return None
            val date = try {
                LocalDate.parse(sunset)
            } catch (e: DateTimeParseException) {
                Log.w(TAG, "Ignoring unparseable sunset value '$sunset'", e)
                return None
            }
            return if (today.isBefore(date)) Upcoming(date) else Reached(date)
        }

        /** Log the status. Placeholder for the future user-facing prompt (no popup yet). */
        fun log(status: SunsetStatus) {
            when (status) {
                is None -> {}
                is Upcoming -> Log.w(TAG, "App update required before ${status.date} to keep receiving indicator updates")
                is Reached -> Log.w(TAG, "Indicator update line sunset on ${status.date}; update the app to continue receiving updates")
            }
        }
    }
}
