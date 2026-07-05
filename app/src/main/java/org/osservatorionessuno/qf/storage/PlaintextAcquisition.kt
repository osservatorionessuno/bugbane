package org.osservatorionessuno.qf.storage

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.IOException
import java.time.Instant
import org.json.JSONObject
import org.osservatorionessuno.qf.storage.ArtifactSink
import org.osservatorionessuno.qf.storage.ArtifactReader
import org.osservatorionessuno.qf.storage.AcquisitionArtifact
import org.osservatorionessuno.qf.storage.AcquisitionIndex
import org.osservatorionessuno.qf.Utils

private const val METADATA_FILE: String = "acquisition.json"

/*
    Helper class to write plaintext artifacts to the acquisition directory.
*/
@Deprecated("Use EncryptedAcquisitionWriter instead")
class PlaintextAcquisitionWriter(
    private val acquisitionDir: File,
) : ArtifactSink {
    private var hasOpenArtifact: Boolean = false

    override fun openArtifact(path: String, modifiedTime: Long?): OutputStream {
        check(!hasOpenArtifact) { "Only one artifact stream can be open at a time" }
        hasOpenArtifact = true
        val file = resolveArtifactFile(acquisitionDir, path)
        file.parentFile?.mkdirs()
        if (file.exists()) {
            // TODO: think about how to handle this case
            throw IOException("Artifact already exists: $path")
        }
        return PlaintextArtifactOutputStream(file, modifiedTime) { hasOpenArtifact = false }
    }

    override fun close(): Unit {
    }

    override fun artifactExists(path: String): Boolean {
        val file = resolveArtifactFile(acquisitionDir, path)
        if (isReservedArtifact(acquisitionDir, file)) return true
        return file.exists()
    }

    fun writeIndex(index: AcquisitionIndex): Unit {
        useArtifact(METADATA_FILE, Instant.parse(index.created).toEpochMilli()) { output ->
            output.writer(Charsets.UTF_8).use { writer ->
                writer.write(Utils.toJsonString(index.toJsonObject()))
            }
        }
    }
}

/*
    Helper class to read plaintext artifacts from the acquisition directory.
*/
@Deprecated("Use EncryptedAcquisitionReader instead")
class PlaintextAcquisitionReader(
    private val acquisitionDir: File,
) : ArtifactReader {
    /*
        Iterate over all plaintext artifacts in the acquisition directory.
        This function is used to load artifacts into memory for scanning.
        Some files are reserved and should not be included in the iteration.
    */
    override fun forEachArtifact(block: (AcquisitionArtifact) -> Unit): Unit {
        if (!acquisitionDir.exists()) return
        acquisitionDir.walkTopDown()
            .filter { file -> file.isFile && !isReservedArtifact(acquisitionDir, file) }
            .forEach { file ->
                val path = file.relativeTo(acquisitionDir).path.replace('\\', '/')
                file.inputStream().use { input ->
                    block(
                        AcquisitionArtifact(
                            path = path,
                            modifiedTime = file.lastModified(),
                            inputStream = input,
                        ),
                    )
                }
            }
    }

    override fun close(): Unit {
    }

    fun readIndex(): AcquisitionIndex {
        val file = resolveArtifactFile(acquisitionDir, METADATA_FILE)
        if (!file.exists()) throw IOException("Metadata file not found: ${file.path}")
        return try {
            AcquisitionIndex.fromJsonObject(JSONObject(file.readText(Charsets.UTF_8)))
        } catch (e: Exception) {
            throw IOException("Failed to parse metadata file: ${file.path}: ${e.message}", e)
        }
    }
}

private class PlaintextArtifactOutputStream(
    private val file: File,
    private val modifiedTime: Long?,
    private val onClosed: () -> Unit,
) : OutputStream() {
    private val output: FileOutputStream = FileOutputStream(file)
    private var isClosed: Boolean = false

    override fun write(b: Int): Unit {
        output.write(b)
    }

    override fun write(bytes: ByteArray, off: Int, len: Int): Unit {
        output.write(bytes, off, len)
    }

    override fun flush(): Unit {
        output.flush()
    }

    override fun close(): Unit {
        if (isClosed) return
        output.close()
        if (modifiedTime != null) {
            file.setLastModified(modifiedTime)
        }
        isClosed = true
        onClosed()
    }
}

private fun resolveArtifactFile(acquisitionDir: File, path: String): File {
    val normalizedPath = path.replace('\\', '/').trimStart('/')
    val file = File(acquisitionDir, normalizedPath)
    val canonicalDir = acquisitionDir.canonicalFile
    val canonicalFile = file.canonicalFile
    require(canonicalFile.path.startsWith(canonicalDir.path)) {
        "Invalid artifact path: $path"
    }
    return file
}

/*
    Check if a file is reserved and should not be included in the iteration.
*/
private fun isReservedArtifact(acquisitionDir: File, file: File): Boolean {
    val relativePath = file.relativeTo(acquisitionDir).path.replace('\\', '/')
    if (relativePath == METADATA_FILE) return true
    if (relativePath.startsWith("${AcquisitionIndex.ANALYSIS_DIR}/")) return true
    return false
}
