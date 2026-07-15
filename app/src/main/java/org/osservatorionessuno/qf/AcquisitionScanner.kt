package org.osservatorionessuno.qf

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.osservatorionessuno.bugbane.utils.Utils
import org.osservatorionessuno.bugbane.utils.initLibmvtLogging
import org.osservatorionessuno.libmvt.android.ForensicRunner
import org.osservatorionessuno.libmvt.common.GroupedDetection
import org.osservatorionessuno.libmvt.common.Indicators
import org.osservatorionessuno.bugbane.update.IndicatorStore
import org.osservatorionessuno.bugbane.utils.AndroidStringResolver
import org.osservatorionessuno.libmvt.common.Artifact
import org.osservatorionessuno.qf.storage.EncryptedAcquisitionReader
import org.osservatorionessuno.qf.storage.ARCHIVE_FILE
import java.io.File
import java.time.Instant
import java.util.LinkedHashMap
import java.util.UUID

object AcquisitionScanner {
    fun scan(context: Context, acquisitionDir: File): File {
        initLibmvtLogging()

        val indicatorsDir = IndicatorStore(context).indicatorsDir
        return scanWithIndicators(context, acquisitionDir, indicatorsDir)
    }

    private fun scanWithIndicators(context: Context, acquisitionDir: File, indicatorsDir: File): File {
        val started = Instant.now()
        val indicators = Indicators()
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

        val resolver = AndroidStringResolver(context)
        val runner = ForensicRunner(resolver)
        runner.setIndicators(indicators)

        val artifacts = LinkedHashMap<String, Artifact>()
        if (File(acquisitionDir, ARCHIVE_FILE).exists()) {
            // Encrypted acquisition: decrypt + unzip in one bounded-memory pass and
            // analyze each artifact from its stream — no plaintext is written to disk.
            val vault = AcquisitionRunner.acquisitionKeyVault()
            EncryptedAcquisitionReader(acquisitionDir, vault).use { reader ->
                reader.forEachArtifact { artifact ->
                    if (ForensicRunner.findModuleIndices(artifact.path).isEmpty()) return@forEachArtifact
                    runner.streamFileAnalysis(artifact.reopenable)?.let { parsed ->
                        // Assign all the modules' detections to the artifact
                        artifacts[artifact.path] = parsed
                    }
                }
            }
        }

        val completed = Instant.now()
        val analysisPath = File(acquisitionDir, "analysis").toPath()
        val outDir = analysisPath.toFile()
        if (!outDir.exists()) outDir.mkdirs()

        val uuid = UUID.randomUUID().toString()
        val outFile = File(outDir, "${started.toString().replace(':', '-')}.json")
        indicatorHashes.sort()
        val grouped = GroupedDetection.fromArtifacts(artifacts)
        val root = JSONObject()
        root.put("uuid", uuid)
        root.put("started", started.toString())
        root.put("completed", completed.toString())
        root.put("indicatorsHash", Utils.Companion.sha256(indicatorHashes.joinToString("")))
        root.put("indicators", indicatorsArr)
        root.put("groupedResults", GroupedDetection.toJsonArray(grouped, resolver))

        outFile.writeText(root.toString(1))
        return outFile
    }
}
