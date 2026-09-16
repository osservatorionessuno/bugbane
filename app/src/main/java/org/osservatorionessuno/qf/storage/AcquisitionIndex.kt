package org.osservatorionessuno.qf.storage

import java.io.File
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.osservatorionessuno.qf.DeviceInfo
import org.osservatorionessuno.qf.Utils

private const val INDEX_FORMAT_VERSION: Int = 1

/** How the device was reached; [hotspotSsid] is the per-session network the target saved. */
data class AcquisitionTransport(val type: String, val hotspotSsid: String? = null) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("type", type)
        putOpt("hotspot_ssid", hotspotSsid)
    }

    companion object {
        const val LOCAL = "local"
        const val USB = "usb"
        const val WIFI_DIRECT = "wifi_direct"
        const val WIFI_HOTSPOT = "wifi_hotspot"

        fun fromJsonObject(o: JSONObject): AcquisitionTransport =
            AcquisitionTransport(o.optString("type", LOCAL), o.optString("hotspot_ssid").ifBlank { null })
    }
}

data class AcquisitionIndex(
    val uuid: String,
    val status: String,
    val created: String,
    val completed: String?,
    val bugbaneVersion: String,
    val storagePath: String,
    val tmpDir: String,
    val sdcard: String,
    val cpu: String,
    val analysisDir: String,
    val adbHostPublicKey: String? = null,
    // Modules that threw or were skipped for low storage; the run is only
    // "complete" when both are empty.
    val failedModules: List<String> = emptyList(),
    val skippedModules: List<String> = emptyList(),
    val device: DeviceInfo? = null,
    // User label; the UI falls back to the device label, then the uuid.
    val name: String? = null,
    val transport: AcquisitionTransport? = null,
) {
    fun displayName(dir: File): String = name ?: device?.label ?: dir.name

    fun toJsonObject(): JSONObject {
        val root = JSONObject()
        root.put("uuid", uuid)
        root.put("format_version", INDEX_FORMAT_VERSION)
        root.put("status", status)
        root.put("created", created)
        root.put("completed", completed ?: JSONObject.NULL)
        root.put("bugbane_version", bugbaneVersion)
        root.put("androidqf_version", "Bugbane-$bugbaneVersion")
        root.put("storage_path", storagePath)
        root.put("tmp_dir", tmpDir)
        root.put("sdcard", sdcard)
        root.put("cpu", cpu)
        root.put("streaming_mode", true)
        root.put("encrypted", true)
        root.put("analysis_dir", analysisDir)
        adbHostPublicKey?.let { root.put("adb_host_public_key", it) }
        if (failedModules.isNotEmpty()) root.put("failed_modules", JSONArray(failedModules))
        if (skippedModules.isNotEmpty()) root.put("skipped_modules", JSONArray(skippedModules))
        device?.let { root.put("device", it.toJsonObject()) }
        name?.let { root.put("name", it) }
        transport?.let { root.put("transport", it.toJsonObject()) }
        return root
    }

    /** Plaintext copy next to the archive, so the list needs no unlock. */
    fun writeSidecar(dir: File) {
        File(dir, METADATA_FILE).writeText(Utils.toJsonString(toJsonObject()), Charsets.UTF_8)
    }

    /** Finalize a run: [STATUS_INCOMPLETE] if any module failed or was skipped. */
    fun markAsFinished(completedAt: Instant, failed: List<String>, skipped: List<String>): AcquisitionIndex {
        val status = if (failed.isEmpty() && skipped.isEmpty()) STATUS_COMPLETE else STATUS_INCOMPLETE
        return copy(status = status, completed = completedAt.toString(),
            failedModules = failed, skippedModules = skipped)
    }

    fun markAsCancelled(cancelledAt: Instant): AcquisitionIndex {
        return copy(status = STATUS_CANCELLED, completed = cancelledAt.toString())
    }

    companion object {
        const val STATUS_RUNNING: String = "running"
        const val STATUS_COMPLETE: String = "complete"
        const val STATUS_INCOMPLETE: String = "incomplete"
        const val STATUS_CANCELLED: String = "cancelled"
        const val ANALYSIS_DIR: String = "analysis"

        private fun JSONArray?.toStringList(): List<String> =
            if (this == null) emptyList() else (0 until length()).map { getString(it) }

        fun fromJsonObject(root: JSONObject): AcquisitionIndex {
            return AcquisitionIndex(
                uuid = root.getString("uuid"),
                status = root.optString("status", STATUS_COMPLETE),
                created = root.optString("created", root.optString("started")),
                completed = root.optString("completed").ifBlank { null },
                bugbaneVersion = root.optString("bugbane_version"),
                storagePath = root.optString("storage_path"),
                tmpDir = root.optString("tmp_dir"),
                sdcard = root.optString("sdcard"),
                cpu = root.optString("cpu"),
                analysisDir = root.optString("analysis_dir", ANALYSIS_DIR),
                adbHostPublicKey = root.optString("adb_host_public_key").ifBlank { null },
                failedModules = root.optJSONArray("failed_modules").toStringList(),
                skippedModules = root.optJSONArray("skipped_modules").toStringList(),
                device = root.optJSONObject("device")?.let { DeviceInfo.fromJsonObject(it) },
                name = root.optString("name").ifBlank { null },
                transport = root.optJSONObject("transport")?.let { AcquisitionTransport.fromJsonObject(it) },
            )
        }

        fun readSidecar(dir: File): AcquisitionIndex? {
            val file = File(dir, METADATA_FILE)
            if (!file.exists()) return null
            return runCatching { fromJsonObject(JSONObject(file.readText(Charsets.UTF_8))) }.getOrNull()
        }

        /** Only the sidecar changes; the archive stays as acquired. */
        fun rename(dir: File, name: String): Boolean {
            val index = readSidecar(dir) ?: return false
            return runCatching { index.copy(name = name.trim().take(100)).writeSidecar(dir) }.isSuccess
        }
    }
}

