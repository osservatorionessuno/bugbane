package org.osservatorionessuno.qf

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.osservatorionessuno.cadb.AdbShell

private const val TAG = "DeviceInfo"

/** Identity of the acquired device, best effort; values are untrusted and capped. */
data class DeviceInfo(
    val manufacturer: String? = null,
    val model: String? = null,
    val androidVersion: String? = null,
    val sdk: String? = null,
    val securityPatch: String? = null,
    val serial: String? = null,
    val androidId: String? = null,
    val imei: List<String> = emptyList(),
    val imsi: List<String> = emptyList(),
    val iccid: List<String> = emptyList(),
) {
    val label: String?
        get() {
            val m = manufacturer?.takeIf { model?.startsWith(it, ignoreCase = true) != true }
            return listOfNotNull(m, model).joinToString(" ").ifBlank { null }
        }

    val summary: String
        get() = listOfNotNull(
            label,
            androidVersion?.let { "Android $it" },
            imei.firstOrNull()?.let { "IMEI $it" },
        ).joinToString(" · ")

    fun toJsonObject(): JSONObject = JSONObject().apply {
        putOpt("manufacturer", manufacturer)
        putOpt("model", model)
        putOpt("android_version", androidVersion)
        putOpt("sdk", sdk)
        putOpt("security_patch", securityPatch)
        putOpt("serial", serial)
        putOpt("android_id", androidId)
        if (imei.isNotEmpty()) put("imei", JSONArray(imei))
        if (imsi.isNotEmpty()) put("imsi", JSONArray(imsi))
        if (iccid.isNotEmpty()) put("iccid", JSONArray(iccid))
    }

    companion object {
        private const val MAX_LEN = 64

        fun fromJsonObject(o: JSONObject): DeviceInfo = DeviceInfo(
            manufacturer = o.optString("manufacturer").ifBlank { null },
            model = o.optString("model").ifBlank { null },
            androidVersion = o.optString("android_version").ifBlank { null },
            sdk = o.optString("sdk").ifBlank { null },
            securityPatch = o.optString("security_patch").ifBlank { null },
            serial = o.optString("serial").ifBlank { null },
            androidId = o.optString("android_id").ifBlank { null },
            imei = o.optJSONArray("imei").toStringList(),
            imsi = o.optJSONArray("imsi").toStringList(),
            iccid = o.optJSONArray("iccid").toStringList(),
        )

        private fun JSONArray?.toStringList(): List<String> =
            if (this == null) emptyList() else (0 until length()).map { getString(it) }

        @Suppress("DEPRECATION") // exec buffers; these replies are a few lines
        fun collect(shell: AdbShell): DeviceInfo {
            val props = mutableMapOf<String, String>()
            probe("getprop") { shell.execForEachLine("getprop") { parseGetPropLine(it)?.let { (k, v) -> props[k] = v } } }
            val androidId = probe("android_id") { shell.execFirstLine("settings get secure android_id") }
                ?.takeIf { it.matches(Regex("[0-9a-fA-F]{16}")) }
            // iphonesubinfo transaction 1 is getDeviceId() since Android 5.
            val imei = probe("imei") { shell.exec("service call iphonesubinfo 1 s16 com.android.shell") }
                ?.let { parseParcelString(it) }?.filter(Char::isDigit)
                ?.takeIf { it.length in 14..16 && (it.length != 15 || luhnValid(it)) }
            // siminfo lists past SIMs too; sim_id >= 0 means inserted.
            val sims = probe("siminfo") {
                shell.exec("content query --uri content://telephony/siminfo --projection sim_id:imsi:icc_id")
            }?.let { parseContentRows(it) }.orEmpty()
                .filter { (it["sim_id"]?.toIntOrNull() ?: -1) >= 0 }
            return DeviceInfo(
                manufacturer = clean(props["ro.product.manufacturer"]),
                model = clean(props["ro.product.model"]),
                androidVersion = clean(props["ro.build.version.release"]),
                sdk = clean(props["ro.build.version.sdk"]),
                securityPatch = clean(props["ro.build.version.security_patch"]),
                serial = clean(props["ro.serialno"] ?: props["ro.boot.serialno"])?.takeIf { it != "unknown" },
                androidId = androidId,
                imei = listOfNotNull(imei),
                imsi = sims.mapNotNull { digits(it["imsi"]) },
                iccid = sims.mapNotNull { digits(it["icc_id"]) },
            )
        }

        private fun <T> probe(what: String, block: () -> T): T? =
            try { block() } catch (t: Throwable) { Log.w(TAG, "$what unavailable: ${t.message}"); null }

        private fun clean(s: String?): String? =
            s?.trim()?.filter { it >= ' ' }?.take(MAX_LEN)?.ifBlank { null }

        private fun digits(s: String?): String? =
            s?.filter(Char::isDigit)?.takeIf { it.length in 5..20 }

        fun parseGetPropLine(line: String): Pair<String, String>? {
            val m = GETPROP.matchEntire(line.trim()) ?: return null
            return m.groupValues[1] to m.groupValues[2]
        }
        private val GETPROP = Regex("""\[(.+?)]: \[(.*)]""")

        /** The Parcel dump's quoted ASCII column, with the UTF-16 padding dots removed. */
        fun parseParcelString(output: String): String? {
            if (!output.contains("Parcel(")) return null
            val text = QUOTED.findAll(output).joinToString("") { it.groupValues[1] }
            return text.replace(".", "").trim().ifBlank { null }
        }
        private val QUOTED = Regex("'([^']*)'")

        fun luhnValid(digits: String): Boolean {
            var sum = 0
            digits.reversed().forEachIndexed { i, c ->
                var d = c - '0'
                if (i % 2 == 1) { d *= 2; if (d > 9) d -= 9 }
                sum += d
            }
            return sum % 10 == 0
        }

        fun parseContentRows(output: String): List<Map<String, String>> =
            output.lineSequence()
                .filter { it.trimStart().startsWith("Row:") }
                .map { line ->
                    line.substringAfter(' ').substringAfter(' ')
                        .split(", ")
                        .mapNotNull { kv -> kv.indexOf('=').takeIf { it > 0 }?.let { kv.substring(0, it) to kv.substring(it + 1) } }
                        .toMap()
                }
                .toList()
    }
}
