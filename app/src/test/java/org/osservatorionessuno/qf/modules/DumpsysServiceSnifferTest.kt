package org.osservatorionessuno.qf.modules

import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun requiredServicesDetectsMissingNames() {
        val seen = setOf("adb", "package", "appops")
        val missing = Dumpsys.REQUIRED_SERVICES.filter { it.name !in seen }.map { it.name }
        assertTrue("accessibility" in missing)
        assertTrue("adb" !in missing)
        assertTrue(missing.isNotEmpty())
    }

    @Test
    fun dropboxUsesPrintArgs() {
        val dropbox = Dumpsys.REQUIRED_SERVICES.single { it.name == "dropbox" }
        assertEquals("dropbox --print", dropbox.extraArgs)
    }
}
