package org.osservatorionessuno.qf.modules

import android.content.Context
import android.util.Log
import org.osservatorionessuno.qf.Module
import org.osservatorionessuno.cadb.AdbShell
import org.osservatorionessuno.cadb.AdbSync
import org.osservatorionessuno.cadb.AdbConnectionManager
import org.osservatorionessuno.libmvt.android.parsers.APKParser
import org.osservatorionessuno.libmvt.android.parsers.CertificateParser
import org.osservatorionessuno.qf.ArtifactJson
import org.osservatorionessuno.qf.storage.ArtifactSink
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Collects the list of installed packages using `pm list packages`.
 * In the future we wanna gather all the APK for the installed packages.
 */
class Packages : Module {
    override val name: String = "packages"
    private val TAG = "PackagesModule"

    companion object {
        private val WHITESPACE = Regex("\\s+")
        private val HEX = Regex("[0-9a-f]+")
        // Packages per batched `pm path` exec, to bound single-command output and runtime.
        private const val PM_PATH_BATCH = 50
        private val HASH_ALGORITHMS = listOf("MD5", "SHA-1", "SHA-256", "SHA-512")
    }

    // Data class to hold package info
    data class Package(
        val name: String,
        val files: List<PackageFile> = emptyList(),
        val installer: String = "",
        val uid: Int = -1,
        var disabled: Boolean = false,
        var system: Boolean = false,
        var thirdParty: Boolean = false,
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
        var infiles: List<String>,
    )

    private data class FileHashes(
        val md5: String,
        val sha1: String,
        val sha256: String,
        val sha512: String,
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
        while (writer.artifactExists("apks/$localPath")) {
            counter++
            localPath = "${packageName}${fileName}_$counter.apk"
        }
        return "apks/$localPath"
    }

    /**
     * Resolve APK paths for [packageNames] with batched `pm path` loops instead of
     * one exec per package.
     */
    private fun collectPackagePaths(shell: AdbShell, packageNames: List<String>): Map<String, List<String>> {
        val paths = HashMap<String, LinkedHashSet<String>>()
        for (batch in packageNames.chunked(PM_PATH_BATCH)) {
            val names = batch.joinToString(" ") { "'$it'" }
            var current: LinkedHashSet<String>? = null
            runCatching {
                shell.execForEachLine("for p in $names; do echo \"PKG:\$p\"; pm path \"\$p\"; done") { line ->
                    val trimmed = line.trim()
                    when {
                        // exec retries re-stream the same lines: the per-package set dedups paths
                        trimmed.startsWith("PKG:") ->
                            current = paths.getOrPut(trimmed.removePrefix("PKG:")) { LinkedHashSet() }
                        trimmed.startsWith("package:") ->
                            current?.add(trimmed.removePrefix("package:"))
                    }
                }
            }.onFailure { Log.w(TAG, "pm path batch failed: ${it.message}") }
        }
        return paths.mapValues { it.value.toList() }
    }

    /** Hash the APK with a single local read; the app can read other packages' APKs directly. */
    private fun hashFileLocally(file: File): FileHashes? {
        val digests = HASH_ALGORITHMS.map { MessageDigest.getInstance(it) }
        try {
            FileInputStream(file).use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buf)
                    if (read < 0) break
                    for (digest in digests) digest.update(buf, 0, read)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Local hashing failed for ${file.path}: ${e.message}")
            return null
        }
        val (md5, sha1, sha256, sha512) = digests.map { digest ->
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        return FileHashes(md5, sha1, sha256, sha512)
    }

    /** Fallback: all four sums in one exec, matched to fields by digest length. */
    private fun hashFileRemotely(shell: AdbShell, path: String): FileHashes {
        var md5 = ""
        var sha1 = ""
        var sha256 = ""
        var sha512 = ""
        runCatching {
            val quoted = "\"$path\""
            shell.execForEachLine("md5sum $quoted; sha1sum $quoted; sha256sum $quoted; sha512sum $quoted") { line ->
                val token = line.trim().split(WHITESPACE, 2).firstOrNull().orEmpty()
                if (token.matches(HEX)) when (token.length) {
                    32 -> md5 = token
                    40 -> sha1 = token
                    64 -> sha256 = token
                    128 -> sha512 = token
                }
            }
        }
        return FileHashes(md5, sha1, sha256, sha512)
    }

    private fun buildPackageFile(
        shell: AdbShell,
        sync: AdbSync,
        writer: ArtifactSink,
        packageName: String,
        packagePath: String,
    ): PackageFile {
        val hashes = hashFileLocally(File(packagePath)) ?: hashFileRemotely(shell, packagePath)
        val packageFile = PackageFile(
            path = packagePath,
            localName = "", // not set/used here
            md5 = hashes.md5,
            sha1 = hashes.sha1,
            sha256 = hashes.sha256,
            sha512 = hashes.sha512,
            suspicious = false,
            certificates = emptyList(),
            infiles = emptyList(),
        )

        runCatching {
            val apkInfo = APKParser.parseAPK(File(packagePath))
            packageFile.suspicious = apkInfo.suspicious
            packageFile.certificates = apkInfo.certificates
            packageFile.infiles = apkInfo.files
        }.onFailure { Log.w(TAG, "Failed to parse $packagePath: ${it.message}") }

        if (packageFile.suspicious) {
            val archivePath = getLocalFileName(writer, packageName, packageFile.path)
            packageFile.localName = archivePath
            Log.i(TAG, "downloading $packagePath")
            val result = runCatching {
                writer.useArtifact(archivePath) { output ->
                    sync.pull(packagePath, output)
                }
            }
            if (result.isFailure) {
                // TODO: write this feedback to the acquisition report in some way
                Log.e(TAG, "Failed to copy $packagePath", result.exceptionOrNull())
            }
        }

        return packageFile
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

        val packages = mutableListOf<Package>()
        // execInternal retries re-stream the same lines into this callback, and the pm-list
        // fallbacks below can re-run after a partial first attempt: dedup by name so neither
        // can produce duplicate records in packages.json.
        val seen = HashSet<String>()

        fun addPackage(line: String) {
            if (line.isBlank()) return
            val fields = line.trim().split(WHITESPACE)
            val (packageName, installer, uid) = parsePackageLine(fields, withInstaller)
            if (packageName.isBlank() || !seen.add(packageName)) return

            packages.add(
                Package(
                    name = packageName,
                    installer = installer,
                    uid = uid,
                )
            )
        }

        // A fallback replaces whatever a partly-failed attempt collected, so every record is
        // parsed with the flags of the command that actually succeeded.
        fun restart() {
            packages.clear()
            seen.clear()
        }

        try {
            shell.execForEachLine("pm list packages -U -u -i") { addPackage(it) }
        } catch (_: Throwable) {
            // fallback without "-i"
            try {
                withInstaller = false
                restart()
                shell.execForEachLine("pm list packages -U -u") { addPackage(it) }
            } catch (_: Throwable) {
                // Some Samsung devices allow only packages installed by current user, as per AndroidQF
                try {
                    withInstaller = true
                    restart()
                    shell.execForEachLine("pm list packages -U -u -i --user 0") { addPackage(it) }
                } catch (e: Throwable) {
                    // Could not obtain packages; write an empty JSON array and return.
                    writer.useArtifact("packages.json") { output -> ArtifactJson.Array(output).close() }
                    return
                }
            }
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

        val byName = packages.associateBy { it.name }
        for ((fieldName, arg) in filters) {
            val setFlag = fieldMap[fieldName]
            val markLine = { line: String ->
                val packageName = line.trim().removePrefix("package:")
                if (packageName.isNotBlank()) {
                    byName[packageName]?.let { p ->
                        setFlag?.invoke(p)
                    }
                }
            }
            // "-u" keeps each flag pass on the same package set as the enumeration above;
            // without it, a system package not installed for user 0 (e.g. the Android 16
            // private-space app) is never marked system and reads as sideloaded.
            try {
                shell.execForEachLine("pm list packages $arg -u", markLine)
            } catch (_: Throwable) {
                // Same Samsung quirk as the enumeration fallback: retry scoped to user 0.
                try {
                    shell.execForEachLine("pm list packages $arg -u --user 0", markLine)
                } catch (_: Throwable) {
                    continue
                }
            }
        }

        // System packages don't need path resolution or hashing.
        val pathsByPackage = collectPackagePaths(
            shell,
            packages.filter { !it.system }.map { it.name },
        )
        for (i in packages.indices) {
            val pkg = packages[i]
            val packagePaths = pathsByPackage[pkg.name] ?: continue
            packages[i] = pkg.copy(
                files = packagePaths.map { buildPackageFile(shell, sync, writer, pkg.name, it) },
            )
        }

        writer.useArtifact("packages.json") { output ->
            ArtifactJson.Array(output).use { arr ->
                for (pkg in packages) {
                    arr.pkg(pkg)
                }
            }
        }
    }
}
