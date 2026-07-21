package org.osservatorionessuno.qf.storage

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FreeSpaceGuardTest {

    private val reserve = 100L

    @Test
    fun `record only checks after the interval is crossed`() {
        val guard = FreeSpaceGuard(reserve, checkIntervalBytes = 100L) { 50L }
        guard.record(40) // below interval, no check
        assertFalse(guard.tripped)
        assertThrows<InsufficientStorageException> { guard.record(70) } // crosses 100 -> low
        assertTrue(guard.tripped)
    }

    @Test
    fun `record never trips while space stays above reserve`() {
        val guard = FreeSpaceGuard(reserve, checkIntervalBytes = 10L) { 1_000L }
        repeat(100) { guard.record(50) }
        assertFalse(guard.tripped)
    }

    @Test
    fun `once tripped every call throws immediately`() {
        val guard = FreeSpaceGuard(reserve, checkIntervalBytes = 1L) { 10L }
        assertThrows<InsufficientStorageException> { guard.record(1) }
        assertThrows<InsufficientStorageException> { guard.record(1) }
    }

    @Test
    fun `a non-positive reserve disables the guard`() {
        val guard = FreeSpaceGuard(0L, checkIntervalBytes = 1L) { 0L }
        repeat(10) { guard.record(100) }
        assertFalse(guard.tripped)
    }
}
