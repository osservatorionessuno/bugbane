package org.osservatorionessuno.qf.storage

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

data class AcquisitionArtifact(
    val path: String,
    val modifiedTime: Long?,
    val inputStream: InputStream,
)

const val METADATA_FILE: String = "acquisition.json"

interface ArtifactSink : Closeable {
    fun openArtifact(path: String, modifiedTime: Long? = null): OutputStream

    /** Opens an artifact and closes it when [block] finishes. Prefer this over bare [openArtifact]. */
    fun useArtifact(path: String, modifiedTime: Long? = null, block: (OutputStream) -> Unit) {
        openArtifact(path, modifiedTime).use(block)
    }

    fun artifactExists(path: String): Boolean
}

interface ArtifactReader : Closeable {
    fun forEachArtifact(block: (AcquisitionArtifact) -> Unit)
}
