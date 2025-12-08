package org.osservatorionessuno.bugbane.qf.modules

import android.content.Context
import org.osservatorionessuno.bugbane.qf.Module
import org.osservatorionessuno.bugbane.qf.Shell
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.osservatorionessuno.bugbane.utils.Utils

/**
 * Collects the list of installed packages using `pm list packages`.
 * In the future we wanna gather all the APK for the installed packages.
 */
class Packages : Module {
    override val name: String = "packages"

    // Data class to hold package info
    data class Package(
        val name: String,
        val files: List<PackageFile>,
        var installer: String = "",
        var uid: Int = -1,
        var disabled: Boolean = false,
        var system: Boolean = false,
        var thirdParty: Boolean = false
    )
    
    data class PackageFile(
        val path: String,
        val localName: String,
        var md5: String,
        var sha1: String,
        var sha256: String,
        var sha512: String,
        // TODO: implement certificate validation checks
    ) {
        fun toJsonObject(): JSONObject {
            return JSONObject().apply {
                put("path", path)
                put("local_name", localName)
                put("md5", md5)
                put("sha1", sha1)
                put("sha256", sha256)
                put("sha512", sha512)
            }
        }
        
        override fun toString(): String {
            return Utils.toJsonString(toJsonObject())
        }
    }

    // Helper function to parse package info line
    fun parsePackageLine(fields: List<String>, withInstaller: Boolean): Triple<String, String, Int> {
        val packageName = fields.getOrNull(0)?.trim()?.removePrefix("package:") ?: ""
        var installer = ""
        var uid = -1
        try {
            if (withInstaller && fields.size >= 3) {
                installer = fields[1].trim().removePrefix("installer=")
                uid = fields[2].trim().removePrefix("uid:").toIntOrNull() ?: -1
            } else if (!withInstaller && fields.size >= 2) {
                uid = fields[1].trim().removePrefix("uid:").toIntOrNull() ?: -1
            }
        } catch (_: Throwable) {}
        return Triple(packageName, installer, uid)
    }

    fun getPackageFiles(shell: Shell, packageName: String): List<PackageFile> {
        val files = mutableListOf<PackageFile>()
        val output = try {
            shell.exec("pm path $packageName")
        } catch (_: Throwable) {
            return files
        }

        output.lineSequence().forEach { line ->
            val packagePath = line.trim().removePrefix("package:").trim()
            if (packagePath.isEmpty()) return@forEach

            val packageFile = PackageFile(
                path = packagePath,
                localName = "", // not set/used here
                md5 = "",
                sha1 = "",
                sha256 = "",
                sha512 = "",
            )

            runCatching {
                val md5Out = shell.exec("md5sum ${packagePath}")
                packageFile.md5 = md5Out.trim().split(Regex("\\s+"), 2).getOrElse(0) { "" }
            }
            runCatching {
                val sha1Out = shell.exec("sha1sum ${packagePath}")
                packageFile.sha1 = sha1Out.trim().split(Regex("\\s+"), 2).getOrElse(0) { "" }
            }
            runCatching {
                val sha256Out = shell.exec("sha256sum ${packagePath}")
                packageFile.sha256 = sha256Out.trim().split(Regex("\\s+"), 2).getOrElse(0) { "" }
            }
            runCatching {
                val sha512Out = shell.exec("sha512sum ${packagePath}")
                packageFile.sha512 = sha512Out.trim().split(Regex("\\s+"), 2).getOrElse(0) { "" }
            }

            files.add(packageFile)
        }
        return files
    }

    override fun run(
        context: Context,
        manager: AbsAdbConnectionManager,
        outDir: File,
        progress: ((Long) -> Unit)?
    ) {
        val shell = Shell(manager, progress = progress)

        var withInstaller = true

        var output = ""
        var success = false
        try {
            output = shell.exec("pm list packages -U -u -i")
            success = true
        } catch (_: Throwable) {
            // fallback without "-i"
            try {
                output = shell.exec("pm list packages -U -u")
                withInstaller = false
                success = true
            } catch (_: Throwable) {
                // Some Samsung devices allow only packages installed by current user, as per AndroidQF
                try {
                    output = shell.exec("pm list packages -U -u -i --user 0")
                    success = true
                } catch (e: Throwable) {
                    // Could not obtain packages, write empty JSON and return
                    // TODO: understand if this is OK for MVT
                    File(outDir, "packages.json").writeText(Utils.toJsonString(JSONArray()))
                    return
                }
            }
        }

        val packages = mutableListOf<Package>()

        output.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val fields = line.trim().split(Regex("\\s+"))
            val (packageName, installer, uid) = parsePackageLine(fields, withInstaller)
            if (packageName.isBlank()) return@forEach

            packages.add(
                Package(
                    name = packageName,
                    installer = installer,
                    uid = uid,
                    files = getPackageFiles(shell, packageName)
                )
            )
        }

        // These map to field names in the Package class
        val filters = listOf(
            Pair("disabled", "-d"),
            Pair("system", "-s"),
            Pair("thirdParty", "-3")
        )
        val fieldMap = mapOf(
            "disabled" to { p: Package -> p.disabled = true },
            "system" to { p: Package -> p.system = true },
            "thirdParty" to { p: Package -> p.thirdParty = true }
        )

        for ((fieldName, arg) in filters) {
            val filterOut = try {
                shell.exec("pm list packages $arg")
            } catch (_: Throwable) {
                ""
            }
            if (filterOut.isBlank()) continue

            val setFlag = fieldMap[fieldName]
            filterOut.lineSequence().forEach { line ->
                val packageName = line.trim().removePrefix("package:")
                if (packageName.isBlank()) return@forEach
                packages.find { it.name == packageName }?.let { p ->
                    setFlag?.invoke(p)
                }
            }
        }

        // Output to JSON
        val jsonArray = JSONArray()
        for (pkg in packages) {
            val filesArray = JSONArray()
            for (file in pkg.files) {
                filesArray.put(file.toJsonObject())
            }
            val obj = JSONObject().apply {
                put("name", pkg.name)
                put("files", filesArray)
                put("installer", pkg.installer)
                put("uid", pkg.uid)
                put("disabled", pkg.disabled)
                put("system", pkg.system)
                put("thirdParty", pkg.thirdParty)
            }
            jsonArray.put(obj)
        }
        File(outDir, "packages.json").writeText(Utils.toJsonString(jsonArray))
    }
}