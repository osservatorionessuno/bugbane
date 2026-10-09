package org.osservatorionessuno.qf.modules

import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DumpsysServiceSnifferTest {
    @Test
    fun reportsServicesAndPassesOutputThrough() {
        val text = "Currently running services:\n  activity\n" +
            "-------------------------------------------------------------------------------\n" +
            "DUMP OF SERVICE activity:\nACTIVITY MANAGER\r\nDUMP OF SERVICE CRITICAL SurfaceFlinger:\nx\n"
        val out = ByteArrayOutputStream()
        val services = mutableListOf<String>()
        val bytes = text.toByteArray()
        DumpsysServiceSniffer(out) { services += it }.apply {
            // Split mid-header to cover chunk boundaries.
            write(bytes, 0, 70)
            write(bytes, 70, bytes.size - 70)
        }
        assertEquals(listOf("activity", "SurfaceFlinger"), services)
        assertEquals(text, out.toString(Charsets.UTF_8.name()))
    }
}
