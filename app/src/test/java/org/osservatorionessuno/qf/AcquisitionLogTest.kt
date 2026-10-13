package org.osservatorionessuno.qf

import android.content.ContextWrapper
import java.io.IOException
import java.time.OffsetDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AcquisitionLogTest {
    // Same layout as androidqf's command.log: Go's RFC3339, then [LEVEL].
    @Test
    fun formatsLikeAndroidqf() {
        assertEquals(
            "2026-10-02T12:34:56+02:00 [INFO] Running module packages\n",
            AcquisitionLog.formatLine(OffsetDateTime.parse("2026-10-02T12:34:56.789+02:00"), "INFO", "Running module packages"),
        )
        assertEquals(
            "2026-10-02T10:34:56Z [WARNING] x\n",
            AcquisitionLog.formatLine(OffsetDateTime.parse("2026-10-02T10:34:56Z"), "WARNING", "x"),
        )
    }

    @Test
    fun operationsRecordOutcome() {
        val log = AcquisitionLog(ContextWrapper(null))
        assertEquals(42, log.operation("shell: id", { "$it bytes" }) { 42 })
        assertThrows(IOException::class.java) {
            log.operation<Unit>("sync pull: /x") { throw IOException("All attempts failed", IOException("Permission denied")) }
        }
        val lines = log.toByteArray().toString(Charsets.UTF_8).lines().map { it.substringAfter(' ') }
        assertEquals("[INFO] [#1] shell: id", lines[0])
        assertTrue(lines[1].matches(Regex("""\[INFO] \[#1] Completed in \d+\.\d{3} s, 42 bytes""")), lines[1])
        assertEquals("[INFO] [#2] sync pull: /x", lines[2])
        assertTrue(lines[3].matches(Regex("""\[ERROR] \[#2] Failed after \d+\.\d{3} s: All attempts failed: Permission denied""")), lines[3])
    }
}
