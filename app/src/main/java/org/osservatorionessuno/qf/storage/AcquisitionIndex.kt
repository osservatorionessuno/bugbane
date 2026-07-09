package org.osservatorionessuno.qf.storage

import java.io.File
import java.time.Instant
import org.json.JSONObject

private const val INDEX_FORMAT_VERSION: Int = 1

data class AcquisitionIndex(
    val uuid: String,
    val status: String,
    val created: String,
    val completed: String?,
    val androidqfVersion: String,
    val storagePath: String,
    val tmpDir: String,
    val sdcard: String,
    val cpu: String,
    val analysisDir: String,
) {
    fun toJsonObject(): JSONObject {
        val root = JSONObject()
        root.put("uuid", uuid)
        root.put("format_version", INDEX_FORMAT_VERSION)
        root.put("status", status)
        root.put("created", created)
        root.put("completed", completed ?: JSONObject.NULL)
        root.put("androidqf_version", androidqfVersion)
        root.put("storage_path", storagePath)
        root.put("tmp_dir", tmpDir)
        root.put("sdcard", sdcard)
        root.put("cpu", cpu)
        root.put("streaming_mode", true)
        root.put("encrypted", true)
        root.put("analysis_dir", analysisDir)
        return root
    }

    fun markAsComplete(completedAt: Instant): AcquisitionIndex {
        return copy(status = STATUS_COMPLETE, completed = completedAt.toString())
    }

    fun markAsCancelled(cancelledAt: Instant): AcquisitionIndex {
        return copy(status = STATUS_CANCELLED, completed = cancelledAt.toString())
    }

    companion object {
        const val STATUS_RUNNING: String = "running"
        const val STATUS_COMPLETE: String = "complete"
        const val STATUS_CANCELLED: String = "cancelled"
        const val ANALYSIS_DIR: String = "analysis"

        fun fromJsonObject(root: JSONObject): AcquisitionIndex {
            return AcquisitionIndex(
                uuid = root.getString("uuid"),
                status = root.optString("status", STATUS_COMPLETE),
                created = root.optString("created", root.optString("started")),
                completed = root.optString("completed").ifBlank { null },
                androidqfVersion = root.optString("androidqf_version"),
                storagePath = root.optString("storage_path"),
                tmpDir = root.optString("tmp_dir"),
                sdcard = root.optString("sdcard"),
                cpu = root.optString("cpu"),
                analysisDir = root.optString("analysis_dir", ANALYSIS_DIR),
            )
        }
    }
}

