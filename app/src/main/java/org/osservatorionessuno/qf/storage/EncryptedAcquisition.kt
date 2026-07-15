package org.osservatorionessuno.qf.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import org.json.JSONObject
import org.osservatorionessuno.qf.Utils
import org.osservatorionessuno.libmvt.common.ReopenableInput
import org.osservatorionessuno.qf.crypto.AgeZipArchiveReader
import org.osservatorionessuno.qf.crypto.AgeZipArchiveWriter
import org.osservatorionessuno.qf.crypto.KeyVault

/** Single encrypted archive holding every artifact of an acquisition. */
const val ARCHIVE_FILE: String = "acquisition.age"

/*
    Writes artifacts as entries of one encrypted+compressed archive (a standard
    ZIP inside age) in the acquisition directory. The ZIP only exists as a
    transient stream inside the age encryptor, so artifact plaintext never
    touches disk; the file key is wrapped by [vault].
*/
class EncryptedAcquisitionWriter(
    private val acquisitionDir: File,
    vault: KeyVault,
) : ArtifactSink {
    private val writer = AgeZipArchiveWriter(FileOutputStream(File(acquisitionDir, ARCHIVE_FILE)), vault)
    private val writtenPaths = mutableSetOf<String>()
    private var indexWritten = false

    override fun openArtifact(path: String, modifiedTime: Long?): OutputStream {
        val name = normalizeArtifactPath(path)
        if (isReservedArtifact(name) || !writtenPaths.add(name)) {
            throw IOException("Artifact already exists: $path")
        }
        return writer.putEntry(name, modifiedTime)
    }

    override fun artifactExists(path: String): Boolean {
        val name = normalizeArtifactPath(path)
        return isReservedArtifact(name) || name in writtenPaths
    }

    override fun close(): Unit = writer.close()

    /** Write [index] as the final [METADATA_FILE] zip entry (before [close]). */
    @Throws(IOException::class)
    fun writeIndex(index: AcquisitionIndex) {
        check(!indexWritten) { "index already written" }
        val json = Utils.toJsonString(index.toJsonObject()).toByteArray(Charsets.UTF_8)
        writer.putEntry(METADATA_FILE).use { it.write(json) }
        indexWritten = true
        // Keep a copy in plaintext so acquisitions can be listed without unlocking the Keystore.
        File(acquisitionDir, METADATA_FILE).writeText(Utils.toJsonString(index.toJsonObject()), Charsets.UTF_8)
    }
}

/*
    Reads artifacts back out of the encrypted archive in a single streaming
    decrypt+unzip pass — bounded memory, and no plaintext written to disk.
*/
class EncryptedAcquisitionReader(
    private val acquisitionDir: File,
    private val vault: KeyVault,
) : ArtifactReader {
    override fun forEachArtifact(block: (AcquisitionArtifact) -> Unit) {
        val archive = File(acquisitionDir, ARCHIVE_FILE)
        if (!archive.exists()) return
        AgeZipArchiveReader.forEachEntry(archive, vault) { name, modifiedTime, open ->
            val reopenable = ReopenableInput.of(name) { open() }
            block(AcquisitionArtifact(path = name, modifiedTime = modifiedTime, reopenable = reopenable))
        }
    }

    fun readIndex(): AcquisitionIndex? {
        var index: AcquisitionIndex? = null
        forEachArtifact { artifact ->
            if (artifact.path != METADATA_FILE) return@forEachArtifact
            index = AcquisitionIndex.fromJsonObject(
                JSONObject(artifact.reopenable.openStream().bufferedReader().readText()),
            )
        }
        return index
    }

    override fun close(): Unit {
    }
}

/** Read [METADATA_FILE] from the archive, falling back to a legacy sidecar file. */
fun readAcquisitionIndex(acquisitionDir: File, vault: KeyVault): AcquisitionIndex? {
    EncryptedAcquisitionReader(acquisitionDir, vault).use { reader ->
        reader.readIndex()?.let { return it }
    }
    val legacy = File(acquisitionDir, METADATA_FILE)
    if (!legacy.exists()) return null
    return try {
        AcquisitionIndex.fromJsonObject(JSONObject(legacy.readText(Charsets.UTF_8)))
    } catch (_: Exception) {
        null
    }
}

private fun normalizeArtifactPath(path: String): String {
    val normalized = path.replace('\\', '/').trimStart('/')
    require(normalized.isNotEmpty() && normalized.split('/').none { it.isEmpty() || it == ".." }) {
        "Invalid artifact path: $path"
    }
    return normalized
}

/*
    Reserved zip entry names must not be claimed by module artifacts.
*/
private fun isReservedArtifact(name: String): Boolean {
    if (name == METADATA_FILE) return true
    if (name.startsWith("${AcquisitionIndex.ANALYSIS_DIR}/")) return true
    return false
}
