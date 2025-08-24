package org.osservatorionessuno.libmvt.android

import org.osservatorionessuno.libmvt.android.artifacts.*
import org.osservatorionessuno.libmvt.common.Artifact
import org.osservatorionessuno.libmvt.common.Indicators
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.util.LinkedHashMap

/**
 * Simple helper to run the available AndroidQF artifact parsers on a folder
 * containing extracted androidqf data. Android 11+ safe (no Java 9/11 APIs).
 */
class AndroidQFRunner(private val directory: File) {

    private var indicators: Indicators? = null

    /** Assign indicators to use for IOC matching. */
    fun setIndicators(indicators: Indicators?) {
        this.indicators = indicators
    }

    /** Run all known modules on the provided directory. */
    @Throws(Exception::class)
    fun runAll(): Map<String, Artifact> {
        val map = LinkedHashMap<String, Artifact>()
        for (name in AVAILABLE_MODULES) {
            val art = runModule(name)
            if (art != null) {
                map[name] = art
            }
        }
        return map
    }

    /** Run a single module by name. */
    @Throws(Exception::class)
    fun runModule(moduleName: String): Artifact? = runModule(moduleName, directory)

    /** Run a single module on a custom directory. */
    @Throws(Exception::class)
    fun runModule(moduleName: String, dir: File): Artifact? {
        return when (moduleName) {
            "dumpsys_accessibility" ->
            runDumpsysSection(dir, DumpsysAccessibility(), "DUMP OF SERVICE accessibility:")
            "dumpsys_activities" ->
            runDumpsysSection(dir, DumpsysPackageActivities(), "DUMP OF SERVICE package:")
            "dumpsys_receivers" ->
            runDumpsysSection(dir, DumpsysReceivers(), "DUMP OF SERVICE package:")
            "dumpsys_adb" ->
            runDumpsysSection(dir, DumpsysAdb(), "DUMP OF SERVICE adb:")
            "dumpsys_appops" ->
            runDumpsysSection(dir, DumpsysAppops(), "DUMP OF SERVICE appops:")
            "dumpsys_battery_daily" ->
            runDumpsysSection(dir, DumpsysBatteryDaily(), "DUMP OF SERVICE batterystats:")
            "dumpsys_battery_history" ->
            runDumpsysSection(dir, DumpsysBatteryHistory(), "DUMP OF SERVICE batterystats:")
            "dumpsys_dbinfo" ->
            runDumpsysSection(dir, DumpsysDBInfo(), "DUMP OF SERVICE dbinfo:")
            "dumpsys_packages" ->
            runDumpsysSection(dir, DumpsysPackages(), "DUMP OF SERVICE package:")
            "dumpsys_platform_compat" ->
            runDumpsysSection(dir, DumpsysPlatformCompat(), "DUMP OF SERVICE platform_compat:")
            "processes" ->
            runSimpleFile(dir, "ps.txt", Processes())
            "getprop" ->
            runSimpleFile(dir, "getprop.txt", GetProp())
            "settings" ->
            runSettings(dir)
            else -> throw IllegalArgumentException("Unknown module: $moduleName")
        }
    }

    private fun finalizeArtifact(art: AndroidArtifact): Artifact {
        indicators?.let {
            art.setIndicators(it)
            art.checkIndicators()
        }
        return art
    }

    @Throws(Exception::class)
    private fun runDumpsysSection(dir: File, art: AndroidArtifact, header: String): Artifact? {
        val file = File(dir, "dumpsys.txt")
        if (!file.exists()) return null
        val dumpsys = safeReadText(file)
        val section = extractSection(dumpsys, header)
        art.parse(section)
        return finalizeArtifact(art)
    }

    @Throws(Exception::class)
    private fun runSimpleFile(dir: File, name: String, art: AndroidArtifact): Artifact? {
        val file = File(dir, name)
        if (!file.exists()) return null
        val data = safeReadText(file)
        art.parse(data)
        return finalizeArtifact(art)
    }

    @Throws(Exception::class)
    private fun runSettings(dir: File): Artifact? {
        if (!dir.exists() || !dir.isDirectory) return null
        val files = dir.listFiles { f ->
                f.isFile && f.name.startsWith("settings_") && f.name.endsWith(".txt")
        }?.sortedBy { it.name } ?: emptyList()

        if (files.isEmpty()) return null

        val sb = StringBuilder()
        for (f in files) {
            sb.append(safeReadText(f)).append('\n')
        }
        val settings = Settings()
        settings.parse(sb.toString())
        return finalizeArtifact(settings)
    }

    private fun extractSection(dumpsys: String, header: String): String {
        val linesOut = ArrayList<String>()
        var inSection = false
        val delimiter = buildHyphens(78)
        for (raw in dumpsys.split('\n')) {
            val line = raw.trim()
            if (!inSection) {
                if (line == header) inSection = true
                continue
            }
            if (line.startsWith(delimiter)) break
            linesOut.add(raw) // preserve original spacing
        }
        return linesOut.joinToString("\n")
    }

    private fun buildHyphens(n: Int): String = buildString {
        repeat(n) { append('-') }
    }

    @Throws(IOException::class)
    private fun safeReadText(file: File, charset: Charset = Charsets.UTF_8): String {
        // Avoid Files.readString; use buffered reader for Android friendliness
        file.inputStream().buffered().reader(charset).use { br ->
                val sb = StringBuilder()
            val buf = CharArray(8192)
            while (true) {
                val read = br.read(buf)
                if (read < 0) break
                sb.append(buf, 0, read)
            }
            return sb.toString()
        }
    }

    companion object {
        /** List of all module names understood by the runner. */
        @JvmField
        val AVAILABLE_MODULES: List<String> = listOf(
                "dumpsys_accessibility",
                "dumpsys_activities",
                "dumpsys_receivers",
                "dumpsys_adb",
                "dumpsys_appops",
                "dumpsys_battery_daily",
                "dumpsys_battery_history",
                "dumpsys_dbinfo",
                "dumpsys_packages",
                "dumpsys_platform_compat",
                "processes",
                "getprop",
                "settings"
        )
    }
}
