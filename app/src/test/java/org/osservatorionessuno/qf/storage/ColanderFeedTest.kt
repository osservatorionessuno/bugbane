package org.osservatorionessuno.qf.storage

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.osservatorionessuno.qf.DeviceInfo

class ColanderFeedTest {

    @Test
    fun `feed has the device and one artifact per hashed file`() {
        val index = AcquisitionIndex(
            uuid = "0b7a5c3e-4d2f-4a8b-9c1d-2e3f4a5b6c7d", status = AcquisitionIndex.STATUS_COMPLETE,
            created = "2026-10-02T10:00:00Z", completed = null, bugbaneVersion = "1.0",
            storagePath = "", tmpDir = "", sdcard = "", cpu = "", analysisDir = AcquisitionIndex.ANALYSIS_DIR,
            device = DeviceInfo(manufacturer = "Google", model = "Pixel 7", imei = listOf("1", "2")),
        )
        val feed = JSONObject()
        ColanderFeed.addTo(feed, index, listOf(
            StoredArtifact("apks/com.example_base.apk", 10, "a".repeat(64), "packages"),
            StoredArtifact("bugreport.zip", 20, null, "bugreport"),
        ))
        val entities = feed.getJSONObject("entities")
        entities.keys().forEach { assertEquals(it, entities.getJSONObject(it).getString("id")) }
        val all = entities.keys().asSequence().map { entities.getJSONObject(it) }.toList()

        val device = all.single { it.getJSONObject("super_type").getString("short_name") == "DEVICE" }
        assertEquals("1,2", device.getJSONObject("attributes").getString("imei"))
        val apk = all.single { it.getJSONObject("super_type").getString("short_name") == "ARTIFACT" }
        assertEquals("ANDROID_SAMPLE", apk.getJSONObject("type").getString("short_name"))
        assertEquals(device.getString("id"), apk.getString("extracted_from"))
        all.forEach { assertEquals("AMBER", it.getString("tlp")) }
    }
}
