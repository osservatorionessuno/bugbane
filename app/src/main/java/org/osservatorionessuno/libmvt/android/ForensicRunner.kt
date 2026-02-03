package org.osservatorionessuno.libmvt.android

import android.content.Context
import android.util.Log
import org.osservatorionessuno.libmvt.android.artifacts.*
import org.osservatorionessuno.libmvt.common.Artifact
import org.osservatorionessuno.libmvt.common.Indicators
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.nio.charset.Charset
import java.util.LinkedHashMap
import java.util.zip.ZipFile

/**
 * Simple helper to run the available AndroidQF artifact parsers on a folder
 * containing extracted androidqf data. Android 11+ safe (no Java 9/11 APIs).
 */
class ForensicRunner(private val context: Context? = null) {

    private var indicators: Indicators? = null

    /** Assign indicators to use for IOC matching. */
    fun setIndicators(indicators: Indicators?) {
        this.indicators = indicators
        indicators?.setContext(context)
    }

    /** This is a LEGACY method to analyze a plaintext directory. */
    @Throws(Exception::class)
    fun streamLegacyAnalysisFromDirectory(directory: File): Map<String, Artifact> {
        val map = LinkedHashMap<String, Artifact>()
        val files = directory.listFiles()
        if (files == null) return map
        for (file in files) {
            if (!file.isFile) continue;

            val art = streamFileAnalysis(file.absolutePath, file.inputStream())
            if (art != null) {
                map[file.name] = art
            }
        }
        return map
    }

    /** This is a method to analyze a zip file. */
    @Throws(Exception::class)
    fun streamAnalysisFromZip(zip: File): Map<String, Artifact> {
        val map = LinkedHashMap<String, Artifact>()
        val zipFile = ZipFile(zip)
        val entries = zipFile.entries()
        for (entry in entries) {
            val art = streamFileAnalysis(entry.name, zipFile.getInputStream(entry))
            if (art != null) {
                map[entry.name] = art
            }
        }
        return map
    }

    /** Format-agnostic analysis of a file stream.
     * 
     * @param path the path of the file
     * @param content the content of the file
     * @return the artifact
     * 
     * This method is used to analyze a file stream. It will determine the module to use based on the file name
     * and then parse the content of the file using the appropriate module.
     * 
     * The method will log an error and return null if the file is not known.
     */
    @Throws(Exception::class)
    fun streamFileAnalysis(path: String, content: InputStream): Artifact? {
        content.use {
            val fileName = path.split('/').last()
            val index = MODULES_MAP[fileName]
            if (index == null) {
                Log.w("ForensicRunner", "Unknown file: $fileName")
                return null
            }
            val art = MODULES_LIST[index]
            art.parse(it)
            return finalizeArtifact(art)
        }
    }

    private fun finalizeArtifact(art: AndroidArtifact): Artifact {
        context?.let { ctx: Context ->
            art.context = ctx
        }
        indicators?.let { ind: Indicators ->
            art.setIndicators(ind)
            art.checkIndicators()
        }
        return art
    }

    companion object {
        private const val TAG = "ForensicRunner"

        @JvmField
        val MODULES_LIST: List<AndroidArtifact> = listOf(
                // Bugreport modules
                DumpsysAccessibility(),
                DumpsysPackageActivities(),
                DumpsysAdb(),
                DumpsysAppops(),
                DumpsysBatteryDaily(),
                DumpsysBatteryHistory(),
                DumpsysDBInfo(),
                DumpsysPackages(),
                DumpsysPlatformCompat(),
                DumpsysReceivers(),
                // AndroidQF modules
                Packages(),
                Processes(),
                GetProp(),
                Settings(),
                Files(),
                SMS(),
                RootBinaries(),
                Mounts(),
        )

        /** Map from file name to artifact instance, built from MODULES_LIST paths(). */
        @JvmField
        val MODULES_MAP: Map<String, Int> = run {
            val map = mutableMapOf<String, Int>()
            for ((index, module) in MODULES_LIST.withIndex()) {
                for (path in module.paths()) {
                    map[path] = index
                }
            }
            map
        }
    }
}
