package org.osservatorionessuno.bugbane.analysis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.osservatorionessuno.libmvt.common.Indicators
import org.osservatorionessuno.libmvt.common.IndicatorsUpdates
import org.osservatorionessuno.libmvt.matcher.IndicatorMatcher
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

object AcquisitionScanner {
    fun scan(context: Context, acquisitionDir: File): File {
        val indicatorsDir = IndicatorsUpdates(context.filesDir.toPath(), null).getIndicatorsFolder().toFile()
        return scanWithIndicators(acquisitionDir, indicatorsDir)
    }

    private fun scanWithIndicators(acquisitionDir: File, indicatorsDir: File): File {
        val started = Instant.now()
        val indicators = Indicators.loadFromDirectory(indicatorsDir)
        val matcher = IndicatorMatcher(indicators)

        val indicatorsArr = JSONArray()
        val indicatorHashes = mutableListOf<String>()
        indicatorsDir.listFiles { file -> file.isFile }?.forEach { file ->
            val hash = sha256(file)
            val obj = JSONObject()
            obj.put("file", file.name)
            obj.put("sha256", hash)
            indicatorsArr.put(obj)
            indicatorHashes += hash
        }

        val results = JSONArray()
        val analysisPath = File(acquisitionDir, "analysis").toPath()
        acquisitionDir.walkTopDown().forEach { file ->
            if (file.isFile && !file.toPath().startsWith(analysisPath)) {
                val lines: List<String> = try {
                    Files.readAllLines(file.toPath())
                } catch (_: Exception) {
                    emptyList()
                }
                val detections = matcher.matchAllStrings(lines)
                if (detections.isNotEmpty()) {
                    val rel = file.relativeTo(acquisitionDir).path
                    for (d in detections) {
                        val obj = JSONObject()
                        obj.put("file", rel)
                        obj.put("type", d.type.name)
                        obj.put("ioc", d.ioc)
                        obj.put("context", d.context)
                        results.put(obj)
                    }
                }
            }
        }

        val completed = Instant.now()
        val outDir = analysisPath.toFile()
        if (!outDir.exists()) outDir.mkdirs()
        val uuid = UUID.randomUUID().toString()
        val outFile = File(outDir, "${started.toString().replace(':', '-')}.json")
        indicatorHashes.sort()
        val root = JSONObject()
        root.put("uuid", uuid)
        root.put("started", started.toString())
        root.put("completed", completed.toString())
        root.put("indicatorsHash", sha256(indicatorHashes.joinToString("")))
        root.put("indicators", indicatorsArr)
        root.put("results", results)
        outFile.writeText(root.toString(1))
        return outFile
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(data: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(data.toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

