package org.osservatorionessuno.cadb

import android.util.Log
import io.github.muntashirakon.adb.LocalServices
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.math.min
import org.osservatorionessuno.qf.storage.ArtifactSink

private data class SyncDirEntry(
    val name: String,
    val mode: Int,
    val mtime: Int,
)

private data class RemoteFileEntry(
    val remotePath: String,
    val artifactPath: String,
    val mtime: Int,
)

/**
 * Minimal ADB sync implementation supporting file pulls.
 */
class AdbSync(
    private val manager: AdbConnectionManager,
    private val progress: ((Long) -> Unit)? = null,
) {
    private companion object {
        private const val TAG = "AdbSync"
        // Unix file type bits (st_mode & 0xF000)
        private const val S_IFMT = 0xF000
        private const val S_IFDIR = 0x4000
        private const val S_IFREG = 0x8000
    }

    /**
     * Pull a remote file to a local destination.
     *
     * @param remotePath Path on the device to pull from.
     * @param output Output stream to write to.
     */
    @Throws(IOException::class, InterruptedException::class)
    fun pull(remotePath: String, output: OutputStream) {
        manager.openStream(LocalServices.SYNC).use { stream ->
            val out = stream.openOutputStream()
            val input = stream.openInputStream()
            sendRecv(out, remotePath)
            receiveFile(input, output)
        }
    }

    /**
     * List remote files in a directory.
     *
     * @param remoteDir Path on the device to list from.
     * @return List of file info maps: [ { "path": ..., "mode": ..., "size": ..., "mtime": ... }, ... ]
     */
    @Throws(IOException::class)
    fun list(remoteDir: String): List<Map<String, Any>> {
        manager.openStream(LocalServices.SYNC).use { stream ->
            val out = stream.openOutputStream()
            val input = stream.openInputStream()
            return listEntries(input, out, remoteDir).map { entry ->
                mapOf(
                    "path" to entry.name,
                    "mode" to entry.mode,
                    "size" to 0,
                    "mtime" to entry.mtime,
                )
            }
        }
    }

    /**
     * Pull a remote folder to a local destination.
     *
     * This will first wall all the local files and then pull each of them.
     * Doing otherwill can end up in a desync state with ADB daemon.
     */
    @Throws(IOException::class)
    fun pullFolder(
        remoteDir: String,
        writer: ArtifactSink,
        artifactPrefix: String = "",
    ) {
        require(remoteDir.endsWith("/")) { "remoteDir must end with /" }
        val prefix = artifactPrefix.trimEnd('/').let { normalized ->
            if (normalized.isEmpty()) "" else "$normalized/"
        }
        manager.openStream(LocalServices.SYNC).use { stream ->
            val out = stream.openOutputStream()
            val input = stream.openInputStream()
            val files = mutableListOf<RemoteFileEntry>()
            collectFiles(input, out, remoteDir, prefix, files)
            for (file in files) {
                try {
                    writer.useArtifact(file.artifactPath, file.mtime.toLong() * 1000) { artifact ->
                        // this does not call pull() explicitly cause otherwise a new manager->stream would be created
                        sendRecv(out, file.remotePath)
                        receiveFile(input, artifact)
                    }
                } catch (e: IOException) {
                    if (e.message.orEmpty().contains("Permission denied", ignoreCase = true)) {
                        continue
                    }
                    throw e
                }
            }
        }
    }

    private fun collectFiles(
        input: InputStream,
        out: OutputStream,
        remoteDir: String,
        prefix: String,
        files: MutableList<RemoteFileEntry>,
    ) {
        for (entry in listEntries(input, out, remoteDir)) {
            if (entry.name == "." || entry.name == "..") continue
            when {
                isDirectory(entry.mode) -> {
                    collectFiles(
                        input,
                        out,
                        "$remoteDir${entry.name}/",
                        prefix + entry.name + "/",
                        files,
                    )
                }
                isRegularFile(entry.mode) -> {
                    files += RemoteFileEntry(
                        remotePath = remoteDir + entry.name,
                        artifactPath = prefix + entry.name,
                        mtime = entry.mtime,
                    )
                }
                else -> {
                    // Skip non-regular files (symlinks, sockets, fifos, device nodes).
                    // Some pseudo-files (e.g. under /proc, /sys) may never reach EOF when read.
                    Log.d(TAG, "Skipping non-regular entry: ${remoteDir}${entry.name} (mode=${Integer.toHexString(entry.mode)})")
                }
            }
        }
    }

    private fun listEntries(input: InputStream, out: OutputStream, remoteDir: String): List<SyncDirEntry> {
        sendSyncRequest(out, AdbConstants.LIST, remoteDir)
        return readListResponses(input, AdbConstants.LIST_V1_DONE_TAIL)
    }

    private fun sendSyncRequest(out: OutputStream, command: String, remotePath: String) {
        val pathBytes = remotePath.toByteArray(StandardCharsets.UTF_8)
        val header = ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(command.toByteArray(StandardCharsets.US_ASCII))
            .putInt(pathBytes.size)
            .array()
        out.write(header)
        out.write(pathBytes)
        out.flush()
    }

    private fun readListResponses(input: InputStream, doneTailBytes: Int): List<SyncDirEntry> {
        val entries = mutableListOf<SyncDirEntry>()
        val lenBuf = ByteArray(4)

        while (true) {
            // Read 4-byte command header
            readFully(input, lenBuf, 0, 4)
            when (val respCmd = String(lenBuf, 0, 4, StandardCharsets.US_ASCII)) {
                AdbConstants.DENT -> entries += readDentV1(input, lenBuf)
                AdbConstants.DNT2 -> readDentV2(input, lenBuf)?.let { entries += it }
                AdbConstants.DONE -> {
                    skipFully(input, doneTailBytes)
                    return entries
                }
                AdbConstants.FAIL -> throw IOException("List failed: ${readFailMessage(input)}")
                else -> throw IOException("Unexpected list response: $respCmd (${cmdHex(lenBuf)})")
            }
        }
    }

    private fun readDentV1(input: InputStream, lenBuf: ByteArray): SyncDirEntry {
        readFully(input, lenBuf, 0, 4)
        val mode = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).int

        readFully(input, lenBuf, 0, 4)
        // size (uint32) — unused for listing

        readFully(input, lenBuf, 0, 4)
        val mtime = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).int

        readFully(input, lenBuf, 0, 4)
        val nameLen = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).int
        val nameBytes = ByteArray(nameLen)
        readFully(input, nameBytes, 0, nameLen)
        return SyncDirEntry(
            name = parseEntryName(nameBytes, nameLen),
            mode = mode,
            mtime = mtime,
        )
    }

    /** sync_dent_v2 on the wire */
    private fun readDentV2(input: InputStream, lenBuf: ByteArray): SyncDirEntry? {
        readFully(input, lenBuf, 0, 4)
        val errorCode = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).int

        skipFully(input, 8) // dev
        skipFully(input, 8) // ino

        readFully(input, lenBuf, 0, 4)
        val mode = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).int

        skipFully(input, 4) // nlink
        skipFully(input, 4) // uid
        skipFully(input, 4) // gid
        skipFully(input, 8) // size
        skipFully(input, 8) // atime
        val mtime = readInt64Le(input).toInt()
        skipFully(input, 8) // ctime

        readFully(input, lenBuf, 0, 4)
        val nameLen = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).int
        val nameBytes = ByteArray(nameLen)
        readFully(input, nameBytes, 0, nameLen)

        if (errorCode != 0) {
            Log.d(TAG, "Skipping sync list entry with error code $errorCode")
            return null
        }
        return SyncDirEntry(
            name = parseEntryName(nameBytes, nameLen),
            mode = mode,
            mtime = mtime,
        )
    }

    private fun sendRecv(out: OutputStream, remotePath: String) {
        val pathBytes = remotePath.toByteArray(StandardCharsets.UTF_8)
        val request = ByteBuffer.allocate(8 + pathBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        request.put(AdbConstants.RECV.toByteArray(StandardCharsets.US_ASCII))
        request.putInt(pathBytes.size)
        request.put(pathBytes)
        out.write(request.array())
        out.flush()
    }

    private fun receiveFile(input: InputStream, output: OutputStream) {
        val header = ByteArray(4)
        val lenBuf = ByteArray(4)
        val buf = ByteArray(8192)
        while (true) {
            readFully(input, header, 0, 4)
            when (String(header, StandardCharsets.US_ASCII)) {
                // DATA <len> <payload>
                AdbConstants.DATA -> {
                    readFully(input, lenBuf, 0, 4)
                    var remaining = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).int
                    while (remaining > 0) {
                        // Read the payload in chunks to avoid oversized single reads.
                        val chunk = min(remaining, buf.size)
                        readFully(input, buf, 0, chunk)
                        output.write(buf, 0, chunk)
                        progress?.invoke(chunk.toLong())
                        remaining -= chunk
                    }
                }
                AdbConstants.DONE -> {
                    // DONE is followed by a 4-byte mtime (uint32). Ignore contents.
                    readFully(input, lenBuf, 0, 4)
                    output.flush()
                    return
                }
                AdbConstants.FAIL -> throw IOException("Sync failed: ${readFailMessage(input)}")
                else -> throw IOException("Unexpected sync response: ${String(header, StandardCharsets.US_ASCII)}")
            }
        }
    }

    private fun readFailMessage(input: InputStream): String {
        val lenBuf = ByteArray(4)
        readFully(input, lenBuf, 0, 4)
        val msgLen = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).int
        val msgBytes = ByteArray(msgLen)
        readFully(input, msgBytes, 0, msgLen)
        return String(msgBytes, StandardCharsets.UTF_8)
    }

    private fun parseEntryName(nameBytes: ByteArray, nameLen: Int): String {
        val end = if (nameLen > 0 && nameBytes[nameLen - 1] == 0.toByte()) nameLen - 1 else nameLen
        return String(nameBytes, 0, end, StandardCharsets.UTF_8)
    }

    private fun isDirectory(mode: Int): Boolean {
        return (mode and S_IFMT) == S_IFDIR
    }

    private fun isRegularFile(mode: Int): Boolean {
        return (mode and S_IFMT) == S_IFREG
    }

    private fun readInt64Le(input: InputStream): Long {
        val buf = ByteArray(8)
        readFully(input, buf, 0, 8)
        return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).long
    }

    private fun skipFully(input: InputStream, length: Int) {
        if (length <= 0) return
        val buf = ByteArray(min(length, 8192))
        var remaining = length
        while (remaining > 0) {
            val chunk = min(remaining, buf.size)
            readFully(input, buf, 0, chunk)
            remaining -= chunk
        }
    }

    private fun cmdHex(cmd: ByteArray): String =
        cmd.joinToString(" ") { "%02x".format(it) }

    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int) {
        var off = offset
        var remaining = length
        while (remaining > 0) {
            val read = input.read(buffer, off, remaining)
            if (read < 0) throw EOFException("EOF while reading from input stream: $remaining bytes remaining")
            off += read
            remaining -= read
        }
    }
}
