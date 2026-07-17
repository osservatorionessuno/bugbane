package org.osservatorionessuno.bugbane.update

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest

class IndicatorStoreTest {

    @TempDir
    lateinit var tmp: File

    @Test
    fun stateRoundTrips() {
        val store = IndicatorStore(tmp)
        assertEquals(IndicatorStore.State(), store.readState()) // defaults when absent

        val state = IndicatorStore.State(
            schema = 1, version = 7, sha256 = "abc", sunset = "2027-01-01",
            buildDate = "2026-06-13T00:00:00Z", objectCount = 42,
            lastCheckEpoch = 111, lastUpdateEpoch = 222,
        )
        store.writeState(state)
        assertEquals(state, store.readState())
    }

    @Test
    fun adoptStagedClearsStaleIndicatorFilesAndCounts() {
        val dir = IndicatorStore(tmp).indicatorsDir
        // Pre-existing stale indicator files that a previous mechanism might have left behind.
        File(dir, "old-a.json").writeText("{}")
        File(dir, "old-b.stix2").writeText("{}")

        val store = IndicatorStore(tmp)
        // Builder framing: head line, one object per line, tail line, trailing newline.
        val bundle = ("""{"id":"bundle--x","objects":[""" + "\n" +
            """{"id":"indicator--1"},""" + "\n" +
            """{"id":"indicator--2"}""" + "\n" +
            """],"type":"bundle"}""" + "\n").toByteArray()
        val staged = store.stage { it.write(bundle) }
        assertEquals(2, staged.objectCount)
        assertEquals(sha256Hex(bundle), staged.sha256)
        store.adoptStaged(staged)

        val names = dir.listFiles()!!.map { it.name }.toSet()
        assertFalse("old-a.json" in names)
        assertFalse("old-b.stix2" in names)
        assertTrue("indicators.json" in names)
        assertArrayEquals(bundle, store.openBundle()!!.use { it.readBytes() })
        assertEquals(0, store.stage { it.write("not json".toByteArray()) }.also { it.discard() }.objectCount)
    }

    @Test
    fun discardedStageLeavesNoBundle() {
        val store = IndicatorStore(tmp)
        store.stage { it.write("candidate".toByteArray()) }.discard()
        assertFalse(store.hasBundle())
        assertNull(store.openBundle())
        assertTrue(store.indicatorsDir.listFiles()!!.isEmpty())
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
