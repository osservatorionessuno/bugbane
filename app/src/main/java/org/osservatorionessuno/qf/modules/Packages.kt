package org.osservatorionessuno.qf.modules

import android.content.Context
import android.util.Log
import org.osservatorionessuno.qf.Module
import org.osservatorionessuno.cadb.AdbShell
import org.osservatorionessuno.cadb.AdbSync
import org.osservatorionessuno.cadb.AdbConnectionManager
import org.osservatorionessuno.libmvt.android.parsers.APKParser
import org.osservatorionessuno.libmvt.android.parsers.CertificateParser
import org.osservatorionessuno.qf.ArtifactProtobuf
import org.osservatorionessuno.qf.storage.ArtifactSink
import java.io.File

/**
 * Collects the list of installed packages using `pm list packages`.
 * In the future we wanna gather all the APK for the installed packages.
 */
class Packages : Module {
    override val name: String = "packages"
    private val TAG = "PackagesModule"

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
        var localName: String,
        var md5: String,
        var sha1: String,
        var sha256: String,
        var sha512: String,
        var suspicious: Boolean,
        var certificates: List<CertificateParser.CertificateInfo>,
        var infiles: List<String>
    )

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

    fun getLocalFileName(writer: ArtifactSink, packageName: String, filePath: String): String {
        val fileName = APKParser.extractFileName(filePath)
        var counter = 0

        var localPath = "${packageName}${fileName}.apk"
        while (writer.artifactExists("apks/${localPath}")) {
            counter++
            localPath = "${packageName}${fileName}_$counter.apk"
        }
        return localPath
    }

    fun getPackageFiles(
        shell: AdbShell,
        sync: AdbSync,
        writer: ArtifactSink,
        packageName: String,
    ): List<PackageFile> {
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
                suspicious = false,
                certificates = emptyList(),
                infiles = emptyList(),
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

            val apkInfo = APKParser.parseAPK(File(packagePath))
            packageFile.suspicious = apkInfo.suspicious
            packageFile.certificates = apkInfo.certificates
            packageFile.infiles = apkInfo.files

            // TODO: download only if third-party and not system (??)
            if (packageFile.suspicious) {
                val localPath = getLocalFileName(writer, packageName, packageFile.path)
                Log.i(TAG, "downloading $packagePath")
                val result = runCatching {
                    writer.useArtifact(localPath) { output ->
                        sync.pull(packagePath, output)
                    }
                }
                if (result.isFailure) {
                    // TODO: write this feedback to the acquisition report in some way
                    Log.e(TAG, "Failed to copy $packagePath", result.exceptionOrNull())
                }
            }

            files.add(packageFile)
        }
        return files
    }

    override fun run(
        context: Context,
        manager: AdbConnectionManager,
        writer: ArtifactSink,
        progress: ((Long) -> Unit)?
    ) {
        val shell = AdbShell(manager, progress = progress)
        val sync = AdbSync(manager, progress = progress)

        var withInstaller = true

        val output = try {
            shell.exec("pm list packages -U -u -i")
        } catch (_: Throwable) {
            // fallback without "-i"
            try {
                withInstaller = false
                shell.exec("pm list packages -U -u")
            } catch (_: Throwable) {
                // Some Samsung devices allow only packages installed by current user, as per AndroidQF
                try {
                    shell.exec("pm list packages -U -u -i --user 0")
                } catch (e: Throwable) {
                    // Could not obtain packages, write empty protobuf stream and return
                    // TODO: understand if this is OK for MVT
                    writer.useArtifact("packages.pb") { }
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
                    files = getPackageFiles(shell, sync, writer, packageName)
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

        writer.useArtifact("packages.pb") { protobufOutput ->
            for (pkg in packages) {
                ArtifactProtobuf.writeDelimitedPackageRecord(protobufOutput, pkg)
            }
        }
    }
}