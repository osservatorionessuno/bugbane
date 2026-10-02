package org.osservatorionessuno.qf.storage

import java.util.UUID
import org.json.JSONObject

/** An artifact as stored in the archive; [sha256] is null when truncated. */
data class StoredArtifact(val path: String, val bytes: Long, val sha256: String?, val module: String)

/*
    Colander feed ("entities" and "relations") describing the acquired device
    and every stored artifact. It is merged into the archived acquisition.json,
    so the decrypted file can be imported into a Colander case as is: Colander
    ignores the androidqf keys, androidqf/MVT ignore these.
*/
object ColanderFeed {
    // Forensic data from a person's phone; never WHITE (Colander's default).
    private const val TLP = "AMBER"

    private val DESCRIPTIONS = mapOf(
        "acquisition metadata" to "Public key Bugbane used to authenticate to the device over ADB",
        "bugreport" to "Android bug report generated on the device (dumpstate)",
        "dumpsys" to "Output of dumpsys for every system service",
        "env" to "Environment of the ADB shell",
        "files" to "Listing of the files readable by the ADB shell, with metadata",
        "getprop" to "System properties (getprop)",
        "intrusion_logs" to "Android intrusion detection logs",
        "logcat" to "Logcat buffers",
        "logs" to "Log file copied from the device",
        "mounts" to "Mounted filesystems",
        "packages" to "Installed packages, or an APK copied from the device",
        "processes" to "Running processes (ps)",
        "root_binaries" to "Root binaries (su, magisk, …) found on the device",
        "selinux" to "SELinux status",
        "services" to "Running services (service list)",
        "settings" to "Android settings namespace",
        "sms_backup" to "SMS backup (Android backup format)",
        "temp" to "File copied from the device's temporary directory",
    )

    /** Put the feed's "entities" and "relations" into [root]. */
    fun addTo(root: JSONObject, index: AcquisitionIndex, artifacts: List<StoredArtifact>) {
        val entities = JSONObject()
        val device = index.device
        val deviceId = UUID.randomUUID().toString()
        entities.put(deviceId, entity(deviceId, "DEVICE", "MOBILE" to "Mobile device", device?.label ?: "Acquired device").apply {
            put("description", "Android device acquired by Bugbane ${index.bugbaneVersion}, acquisition ${index.uuid}")
            put("attributes", JSONObject().apply {
                // Colander attributes are flat strings.
                device?.let { d ->
                    listOf(
                        "manufacturer" to d.manufacturer, "model" to d.model,
                        "android_version" to d.androidVersion, "sdk" to d.sdk,
                        "security_patch" to d.securityPatch, "serial" to d.serial, "android_id" to d.androidId,
                        "imei" to d.imei.joinToString(","), "imsi" to d.imsi.joinToString(","),
                        "iccid" to d.iccid.joinToString(","),
                    ).forEach { (k, v) -> if (!v.isNullOrEmpty()) put(k, v) }
                }
                put("acquisition_uuid", index.uuid)
                put("acquired_at", index.created)
            })
        })
        // Colander turns artifacts into SHA256 observables, so a truncated one
        // (no hash) would fail the whole import; hashes.csv skips them too.
        for (a in artifacts) {
            val sha256 = a.sha256 ?: continue
            val id = UUID.randomUUID().toString()
            entities.put(id, entity(id, "ARTIFACT", artifactType(a.path), a.path).apply {
                put("description", DESCRIPTIONS[a.module] ?: "Collected by the ${a.module} module")
                put("size_in_bytes", a.bytes)
                put("sha256", sha256)
                put("extracted_from", deviceId)
                put("attributes", JSONObject().apply {
                    put("module", a.module)
                    put("acquisition_uuid", index.uuid)
                })
            })
        }
        root.put("entities", entities).put("relations", JSONObject())
    }

    // Colander needs the type name as well as its short name.
    private fun entity(id: String, superType: String, type: Pair<String, String>, name: String) = JSONObject().apply {
        put("id", id)
        put("super_type", JSONObject().put("short_name", superType))
        put("type", JSONObject().put("short_name", type.first).put("name", type.second))
        put("name", name)
        put("tlp", TLP)
        put("pap", TLP)
    }

    private fun artifactType(path: String): Pair<String, String> = when {
        path.endsWith(".apk") -> "ANDROID_SAMPLE" to "Android sample"
        path.endsWith(".ab") -> "ANDROID_BACKUP" to "Android backup image"
        path.endsWith(".zip") -> "ARCHIVE" to "Archive"
        path.endsWith(".json") -> "JSON" to "JSON file"
        path.endsWith(".txt") -> "TEXT" to "Text file"
        else -> "GENERIC" to "Generic"
    }
}
