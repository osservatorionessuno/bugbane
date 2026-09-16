package org.osservatorionessuno.qf

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeviceInfoTest {
    @Test
    fun getpropLineParses() {
        assertEquals("ro.product.model" to "Pixel 8a", DeviceInfo.parseGetPropLine("[ro.product.model]: [Pixel 8a]"))
        assertEquals("a" to "", DeviceInfo.parseGetPropLine("[a]: []"))
        assertNull(DeviceInfo.parseGetPropLine("garbage"))
    }

    @Test
    fun parcelStringDecodesUtf16Column() {
        val out = """
            Result: Parcel(
              0x00000000: 00000000 0000000f 00350033 00380037 '........3.5.8.7.'
              0x00000010: 00360030 00300031 00300030 00340031 '6.0.0.1.0.0.4.1.'
              0x00000020: 00320036 00000034                   '2.6.4...        ')
        """.trimIndent()
        assertEquals("358760010041264", DeviceInfo.parseParcelString(out))
        assertNull(DeviceInfo.parseParcelString("Result: Parcel(00000000 ffffffff   '........')"))
        assertNull(DeviceInfo.parseParcelString("service: Service iphonesubinfo does not exist"))
    }

    @Test
    fun luhn() {
        assertTrue(DeviceInfo.luhnValid("490154203237518"))
        assertFalse(DeviceInfo.luhnValid("490154203237519"))
    }

    @Test
    fun contentRowsParse() {
        val out = "Row: 0 sim_id=0, imsi=222011234567890, icc_id=8939010012345678901\nRow: 1 sim_id=-1, imsi=NULL, icc_id=89390100987"
        val rows = DeviceInfo.parseContentRows(out)
        assertEquals(2, rows.size)
        assertEquals("222011234567890", rows[0]["imsi"])
        assertEquals("-1", rows[1]["sim_id"])
    }

    @Test
    fun labelAndJsonRoundTrip() {
        val info = DeviceInfo(manufacturer = "Google", model = "Pixel 8a", androidVersion = "15", imei = listOf("358760010041264"))
        assertEquals("Google Pixel 8a", info.label)
        assertEquals("Google Pixel 8a · Android 15 · IMEI 358760010041264", info.summary)
        assertEquals(info, DeviceInfo.fromJsonObject(JSONObject(info.toJsonObject().toString())))
        // A model that already names the maker is not repeated.
        assertEquals("samsung SM-A515F", DeviceInfo(manufacturer = "samsung", model = "SM-A515F").label)
        assertEquals("Xiaomi 13", DeviceInfo(manufacturer = "Xiaomi", model = "Xiaomi 13").label)
        assertNull(DeviceInfo().label)
    }
}

class AcquisitionTransportTest {
    @Test
    fun transportRoundTripsThroughTheIndex() {
        val index = org.osservatorionessuno.qf.storage.AcquisitionIndex(
            uuid = "u", status = "complete", created = "c", completed = null, bugbaneVersion = "v",
            storagePath = "p", tmpDir = "t", sdcard = "s", cpu = "arm64", analysisDir = "analysis",
            transport = org.osservatorionessuno.qf.storage.AcquisitionTransport(
                org.osservatorionessuno.qf.storage.AcquisitionTransport.WIFI_DIRECT, "DIRECT-bb-bugbane-k3f9a2",
            ),
        )
        val back = org.osservatorionessuno.qf.storage.AcquisitionIndex.fromJsonObject(JSONObject(index.toJsonObject().toString()))
        assertEquals(index.transport, back.transport)
        assertEquals("DIRECT-bb-bugbane-k3f9a2", back.transport?.hotspotSsid)
        val failed = index.markAsFinished(java.time.Instant.EPOCH, listOf("bugreport"), emptyList(), mapOf("bugreport" to "Shell command inactive"))
        val failedBack = org.osservatorionessuno.qf.storage.AcquisitionIndex.fromJsonObject(JSONObject(failed.toJsonObject().toString()))
        assertEquals("Shell command inactive", failedBack.moduleErrors["bugreport"])
        // Older indexes have no transport block.
        assertNull(org.osservatorionessuno.qf.storage.AcquisitionIndex.fromJsonObject(JSONObject("""{"uuid":"u"}""")).transport)
    }
}
