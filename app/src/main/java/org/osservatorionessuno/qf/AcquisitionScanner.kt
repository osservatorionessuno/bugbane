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
import org.osservatorionessuno.libmvt.common.AbstractInput
import org.osservatorionessuno.libmvt.common.Artifact
import org.osservatorionessuno.qf.crypto.AcquisitionIdentityVault
import org.osservatorionessuno.qf.crypto.SessionKeyCache
import org.osservatorionessuno.qf.crypto.age.AgeIdentity
import org.osservatorionessuno.qf.storage.EncryptedAcquisitionReader
import org.osservatorionessuno.qf.storage.ARCHIVE_FILE
import java.io.File
import java.time.Instant
import java.util.LinkedHashMap
import java.util.UUID

object AcquisitionScanner {
    /**
     * Analyze [acquisitionDir] decrypting with [identity] (plus any legacy
     * identity for archives from before the identity scheme).
     */
    fun scan(context: Context, acquisitionDir: File, identity: AgeIdentity): File {
        initLibmvtLogging()

        // Make sure the indicators shipped in the APK are adopted before analysis, so a
        // device that has never updated online still analyzes against a real IOC set.
        org.osservatorionessuno.bugbane.update.BundledIndicators.seedIfStale(context)
        val indicatorsDir = IndicatorStore(context).indicatorsDir
        val identities = listOf(identity) + AcquisitionIdentityVault.legacyIdentities()
        return scanWithIndicators(context, acquisitionDir, indicatorsDir, identities)
    }

    /**
     * Auto-analysis right after an acquisition: decrypt with the file key cached
     * at write time (no prompt). Returns null if the cache is cold (e.g. the app
     * was restarted) — the user can then re-run analysis, which unlocks the identity.
     */
    fun scanFromSessionCache(context: Context, acquisitionDir: File): File? {
        val cached = SessionKeyCache.identityFor(acquisitionDir) ?: return null
        return try {
            scan(context, acquisitionDir, cached)
        } finally {
            cached.destroy()
        }
    }

    private fun scanWithIndicators(
        context: Context,
        acquisitionDir: File,
        indicatorsDir: File,
        identities: List<AgeIdentity>,
    ): File {
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
            EncryptedAcquisitionReader(acquisitionDir, identities).use { reader ->
                reader.forEachArtifact { artifact ->
                    if (ForensicRunner.findModuleIndices(artifact.path).isEmpty()) return@forEachArtifact
                    // runCatching: a malformed artifact (exactly what a compromised device might
                    // produce) skips that artifact instead of aborting the whole scan.
                    runCatching { runner.streamFileAnalysis(artifact.reopenable) }.getOrNull()?.let { parsed ->
                        // Keep only the detections: the parsed Artifact drags its full results
                        // list (e.g. the entire device file list), and retaining every artifact
                        // until the end of the scan would hold all of them in memory at once.
                        // Grouping only reads `detected`, so a stub carries just that.
                        val detectionsOnly = object : Artifact() {
                            override fun parse(artifactInput: AbstractInput) = Unit
                            override fun checkIndicators() = Unit
                        }
                        detectionsOnly.detected.addAll(parsed.detected)
                        artifacts[artifact.path] = detectionsOnly
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
