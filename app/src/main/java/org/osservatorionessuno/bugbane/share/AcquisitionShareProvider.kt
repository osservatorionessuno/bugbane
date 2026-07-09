package org.osservatorionessuno.bugbane.share

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Streams a passphrase re-wrapped acquisition archive to a share target with no
 * copy staged on disk: [openFile] pipes [AcquisitionExport.writeTo] on demand,
 * so the archive lives on disk only as its at-rest copy. Register a share with
 * [enqueue] and hand the returned URI to an ACTION_SEND intent.
 */
class AcquisitionShareProvider : ContentProvider() {

    private class PendingExport(
        val archive: File,
        val passphrase: String,
        val displayName: String,
    )

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val export = resolve(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns)
        val row = cursor.newRow()
        for (column in columns) {
            when (column) {
                OpenableColumns.DISPLAY_NAME -> row.add(export.displayName)
                OpenableColumns.SIZE -> row.add(AcquisitionExport.size(export.archive, export.passphrase))
                else -> row.add(null)
            }
        }
        return cursor
    }

    override fun getType(uri: Uri): String = "application/octet-stream"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("read-only share URI: $uri")
        val id = uri.lastPathSegment
        val export = id?.let { pending[it] } ?: throw FileNotFoundException("unknown share URI: $uri")
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        // Encrypt lazily into the pipe; the reader (the share target) pulls the
        // bytes. A closed reader just aborts the export — nothing to clean up.
        Thread {
            try {
                FileOutputStream(writeSide.fileDescriptor).use { out ->
                    AcquisitionExport.writeTo(export.archive, export.passphrase, out)
                }
            } catch (_: Throwable) {
                // receiver closed early / IO error — no partial file persisted
            } finally {
                writeSide.close()
                // Drop the entry once the transfer ends so the (cleartext) passphrase
                // doesn't linger in the process heap past the share.
                pending.remove(id)
            }
        }.apply { name = "acq-share-export"; isDaemon = true }.start()
        return readSide
    }

    // Read-only provider: the mutating operations are unsupported.
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun resolve(uri: Uri): PendingExport =
        pending[uri.lastPathSegment] ?: throw FileNotFoundException("unknown share URI: $uri")

    companion object {
        private const val AUTHORITY_SUFFIX = ".shareprovider"

        private val pending = ConcurrentHashMap<String, PendingExport>()

        /**
         * Register [archive] to be shared, re-wrapped to [passphrase], and return a
         * content URI whose reads stream the re-wrapped archive. [displayName] is
         * what the share target shows and saves the file as.
         */
        fun enqueue(context: Context, archive: File, passphrase: String, displayName: String): Uri {
            val id = UUID.randomUUID().toString()
            pending[id] = PendingExport(archive, passphrase, displayName)
            return Uri.parse("content://${context.packageName}$AUTHORITY_SUFFIX/$id")
        }
    }
}
