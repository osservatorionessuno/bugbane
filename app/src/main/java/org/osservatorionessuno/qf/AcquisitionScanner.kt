package org.osservatorionessuno.qf

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.osservatorionessuno.bugbane.utils.Utils
import org.osservatorionessuno.libmvt.android.ForensicRunner
import org.osservatorionessuno.libmvt.common.Indicators
import org.osservatorionessuno.libmvt.common.IndicatorsUpdates
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.collections.iterator

object AcquisitionScanner {
    fun scan(context: Context, acquisitionDir: File): File {
        val indicatorsDir = IndicatorsUpdates(context.filesDir.toPath(), null).getIndicatorsFolder().toFile()
        return scanWithIndicators(context, acquisitionDir, indicatorsDir)
    }

    private fun scanWithIndicators(context: Context, acquisitionDir: File, indicatorsDir: File): File {
        val started = Instant.now()
        val indicators = Indicators();
        indicators.loadFromDirectory(indicatorsDir)

        val indicatorsArr = JSONArray()
        val indicatorHashes = mutableListOf<String>()
        indicatorsDir.listFiles { file -> file.isFile }?.forEach { file ->
            val hash = Utils.Companion.sha256(file)
            val obj = JSONObject()
            obj.put("file", file.name)
            obj.put("sha256", hash)
            indicatorsArr.put(obj)
            indicatorHashes += hash
        }

        val runner = ForensicRunner(acquisitionDir, context);
        runner.setIndicators(indicators);
        val detections = runner.runAll();

        val results = JSONArray()
        for ((key, value) in detections) {
            for (detected in value.getDetected()) {
                val obj = JSONObject()
                obj.put("level", detected.level.name)
                obj.put("title", detected.title)
                obj.put("context", detected.context)
                results.put(obj)
            }
        }

        val completed = Instant.now()
        val analysisPath = File(acquisitionDir, "analysis").toPath()
        val outDir = analysisPath.toFile()
        if (!outDir.exists()) outDir.mkdirs()

        val uuid = UUID.randomUUID().toString()
        val outFile = File(outDir, "${started.toString().replace(':', '-')}.json")
        indicatorHashes.sort()
        val root = JSONObject()
        root.put("uuid", uuid)
        root.put("started", started.toString())
        root.put("completed", completed.toString())
        root.put("indicatorsHash", Utils.Companion.sha256(indicatorHashes.joinToString("")))
        root.put("indicators", indicatorsArr)
        root.put("results", results)

        outFile.writeText(root.toString(1))
        return outFile
    }
}