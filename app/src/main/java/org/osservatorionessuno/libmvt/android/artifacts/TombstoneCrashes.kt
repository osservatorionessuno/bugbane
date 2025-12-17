package org.osservatorionessuno.libmvt.android.artifacts

import org.osservatorionessuno.bugbane.R
import org.osservatorionessuno.libmvt.common.AlertLevel
import org.osservatorionessuno.libmvt.common.Detection
import org.osservatorionessuno.libmvt.common.IndicatorType

/**
 * Parser for Android tombstone crash files.
 * - Text format: always supported.
 * - Protobuf format: attempted via reflection, so no hard dependency is needed.
 */
class TombstoneCrashes : AndroidArtifact() {

    override fun parse(input: String?) {
        results.clear()
        if (input == null) return

        val rec = HashMap<String, Any?>()
        val lines = input.split('\n')
        for (raw in lines) {
            val line = raw.trim()
            when {
                line.startsWith("Timestamp:") -> {
                    var ts = line.substring(10).trim()
                    ts = ts.replace(Regex("[+-][0-9]{4}$"), "")
                    val dot = ts.indexOf('.')
                    if (dot >= 0) {
                        var frac = ts.substring(dot + 1)
                        if (frac.length > 6) frac = frac.substring(0, 6)
                        ts = ts.substring(0, dot) + "." + frac
                    }
                    rec["timestamp"] = ts
                }
                line.startsWith("Cmdline:") -> {
                    val cmd = line.substring(8).trim()
                    rec["command_line"] = listOf(cmd)
                }
                line.startsWith("uid:") -> {
                    val v = line.substring(4).trim()
                    v.toIntOrNull()?.let { rec["uid"] = it }
                }
                line.startsWith("pid:") -> {
                    // Example:
                    // pid: 25541, tid: 21307, name: mtk.ape.decoder  >>> /vendor/bin/hw/android.hardware.media.c2@1.2-mediatek <<<
                    val parts = line.split(",")
                    if (parts.size >= 3) {
                        val pidStr = parts[0].split(":").getOrNull(1)?.trim()
                        val tidStr = parts[1].split(":").getOrNull(1)?.trim()
                        var rest = parts[2].trim()
                        if (rest.startsWith("name:")) {
                            rest = rest.substring(5).trim()
                            val nameParts = rest.split(">>>")
                            val procName = nameParts[0].trim()
                            rec["process_name"] = procName
                        }
                        pidStr?.toIntOrNull()?.let { rec["pid"] = it }
                        tidStr?.toIntOrNull()?.let { rec["tid"] = it }
                    }
                }
            }
        }
        if (rec.isNotEmpty()) results.add(rec)
    }

    /**
     * Parse Android tombstone protobuf bytes via reflection to avoid a hard dependency.
     * If the generated class or protobuf runtime isn’t present, this is a no-op.
     */
    fun parseProtobuf(data: ByteArray?) {
        results.clear()
        if (data == null) return

        try {
            // Load TombstoneProtos.Tombstone
            val tpClass = Class.forName("com.android.server.os.TombstoneProtos\$Tombstone")
            val parseFrom = tpClass.getMethod("parseFrom", ByteArray::class.java)
            val pb = parseFrom.invoke(null, data)

            // timestamp
            val tsGetter = tpClass.getMethod("getTimestamp")
            var ts = tsGetter.invoke(pb) as? String ?: ""
            ts = ts.replace(Regex("[+-][0-9]{4}$"), "")
            val dot = ts.indexOf('.')
            if (dot >= 0) {
                var frac = ts.substring(dot + 1)
                if (frac.length > 6) frac = frac.substring(0, 6)
                ts = ts.substring(0, dot) + "." + frac
            }

            val rec = HashMap<String, Any?>()
            rec["timestamp"] = ts

            // command_line: List<String>
            val getCmdLine = tpClass.getMethod("getCommandLineList")
            val cmdList = (getCmdLine.invoke(pb) as? List<*>)?.map { it?.toString() ?: "" } ?: emptyList()
            if (cmdList.isNotEmpty()) {
                rec["command_line"] = ArrayList(cmdList)
            }

            // pid / tid / uid
            val getPid = tpClass.getMethod("getPid")
            val getTid = tpClass.getMethod("getTid")
            val getUid = tpClass.getMethod("getUid")
            rec["pid"] = (getPid.invoke(pb) as? Number)?.toInt()
            rec["tid"] = (getTid.invoke(pb) as? Number)?.toInt()
            rec["uid"] = (getUid.invoke(pb) as? Number)?.toInt()

            // threadsMap[int tid] -> object with getName()
            val getThreadsMap = tpClass.getMethod("getThreadsMap")
            @Suppress("UNCHECKED_CAST")
            val threadsMap = getThreadsMap.invoke(pb) as? Map<Int, Any?>

            val tid = rec["tid"] as? Int
            if (tid != null && threadsMap != null) {
                val th = threadsMap[tid]
                if (th != null) {
                    val nameGetter = th.javaClass.getMethod("getName")
                    val name = nameGetter.invoke(th) as? String
                    if (!name.isNullOrEmpty()) rec["process_name"] = name
                }
            }

            if (rec.isNotEmpty()) results.add(rec)
        } catch (_: Throwable) {
            // No protobuf runtime or generated class present — ignore to keep no-deps build.
        }
    }

    override fun checkIndicators() {
        if (indicators == null) return

        for (obj in results) {
            @Suppress("UNCHECKED_CAST")
            val map = obj as? Map<String, Any?> ?: continue

            val proc = map["process_name"] as? String
            if (proc != null) {
                detected.addAll(indicators!!.matchString(proc, IndicatorType.PROCESS))
            }

            val cmdLineObj = map["command_line"]
            if (cmdLineObj is List<*> && cmdLineObj.isNotEmpty()) {
                val cmd = cmdLineObj[0]?.toString() ?: ""
                val slash = cmd.lastIndexOf('/')
                val name = if (slash >= 0) cmd.substring(slash + 1) else cmd
                detected.addAll(indicators!!.matchString(name, IndicatorType.PROCESS))
            }

            val uid = when (val u = map["uid"]) {
                is Number -> u.toInt()
                is String -> u.toIntOrNull()
                else -> null
            }
            if (uid != null && (uid == 0 || uid == 1000 || uid == 2000)) {
                detected.add(Detection(AlertLevel.MEDIUM, context.getString(R.string.mvt_tombstone_crashes_uid_title),
                    String.format(
                        context.getString(R.string.mvt_tombstone_crashes_uid_message),
                        uid, proc ?: ""
                    )));
            }
        }
    }
}
