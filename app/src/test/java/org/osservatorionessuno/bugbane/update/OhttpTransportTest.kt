package org.osservatorionessuno.bugbane.update

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream

// Response content-decoding: gzip bodies are transparently decompressed, an unencoded body passes
// through, and a gzip that expands past the cap is refused (bomb guard) rather than filling memory.
class OhttpTransportTest {
    private val transport = OhttpTransport()

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    @Test
    fun `gzip response body is decompressed`() {
        val original = ("{\"objects\":[" + "\"x\",".repeat(5000) + "\"end\"]}").toByteArray()
        val out = transport.decoded(listOf("content-encoding" to "GZIP"), gzip(original).inputStream()).readBytes()
        assertArrayEquals(original, out)
    }

    @Test
    fun `unencoded body passes through unchanged`() {
        val original = "not compressed".toByteArray()
        val out = transport.decoded(listOf("content-type" to "application/json"), original.inputStream()).readBytes()
        assertArrayEquals(original, out)
    }

    @Test
    fun `gzip expanding past the cap is refused`() {
        // 1 MiB of zeros compresses tiny but expands well past the 64 KiB cap below.
        val bomb = gzip(ByteArray(1 shl 20))
        val decoded = transport.decoded(listOf("content-encoding" to "gzip"), bomb.inputStream(), maxBytes = 64L * 1024)
        assertThrows(IOException::class.java) { decoded.readBytes() }
    }
}
