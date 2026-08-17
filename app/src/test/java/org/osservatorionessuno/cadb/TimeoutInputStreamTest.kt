package org.osservatorionessuno.cadb

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class TimeoutInputStreamTest {

    @Test
    fun `passes reads through when data flows`() {
        val data = "hello sync".toByteArray()
        TimeoutInputStream(ByteArrayInputStream(data), 1_000).use { input ->
            val buf = ByteArray(data.size)
            var off = 0
            while (off < data.size) off += input.read(buf, off, data.size - off)
            assertArrayEquals(data, buf)
        }
    }

    @Test
    fun `throws SyncInactivityException when a read stalls past the window`() {
        val blocking = object : InputStream() {
            override fun read(): Int = read(ByteArray(1), 0, 1)
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                Thread.sleep(10_000) // never delivers within the window
                return -1
            }
        }
        val start = System.nanoTime()
        TimeoutInputStream(blocking, 200).use { input ->
            assertThrows(SyncInactivityException::class.java) { input.read(ByteArray(4), 0, 4) }
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(elapsedMs < 5_000, "should have aborted near the 200ms window, took ${elapsedMs}ms")
    }

    @Test
    fun `surfaces the underlying IOException`() {
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("boom")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("boom")
        }
        TimeoutInputStream(failing, 1_000).use { input ->
            val e = assertThrows(IOException::class.java) { input.read(ByteArray(4), 0, 4) }
            assertEquals("boom", e.message)
        }
    }
}
