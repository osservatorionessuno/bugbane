package org.osservatorionessuno.qf.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SecretTest {

    @Test
    fun `withBytes exposes the value and zeroes the transient copy`() {
        var leaked: ByteArray? = null
        Secret(byteArrayOf(1, 2, 3, 4)).use { s ->
            s.withBytes { b ->
                assertArrayEquals(byteArrayOf(1, 2, 3, 4), b)
                leaked = b // retaining is a misuse; used here only to observe the wipe
            }
        }
        assertArrayEquals(ByteArray(4), leaked) // the transient copy was zeroed on exit
    }

    @Test
    fun `construction zeroes the source array`() {
        val source = byteArrayOf(9, 8, 7)
        Secret(source).use { }
        assertArrayEquals(ByteArray(3), source)
    }

    @Test
    fun `withBytes throws after close`() {
        val s = Secret(byteArrayOf(5))
        s.close()
        assertThrows(IllegalStateException::class.java) { s.withBytes { } }
    }

    @Test
    fun `close is idempotent`() {
        val s = Secret(byteArrayOf(1, 2))
        s.close()
        s.close()
    }

    @Test
    fun `copyBytes is detached from the secret`() {
        Secret(byteArrayOf(5, 6, 7)).use { s ->
            val copy = s.copyBytes()
            copy[0] = 0
            s.withBytes { assertEquals(5, it[0]) } // mutating the copy doesn't touch the secret
        }
    }

    @Test
    fun `ofChars encodes UTF-8 and clears the input`() {
        val chars = "correct horse ☕".toCharArray()
        Secret.ofChars(chars).use { s ->
            s.withBytes { assertArrayEquals("correct horse ☕".toByteArray(Charsets.UTF_8), it) }
        }
        assertTrue(chars.all { it.code == 0 }, "password chars must be wiped")
    }

    @Test
    fun `withBytes wipes even when the block throws`() {
        var leaked: ByteArray? = null
        assertThrows(RuntimeException::class.java) {
            Secret(byteArrayOf(1, 2, 3)).use { s ->
                s.withBytes { b -> leaked = b; throw RuntimeException("boom") }
            }
        }
        assertArrayEquals(ByteArray(3), leaked)
    }
}
