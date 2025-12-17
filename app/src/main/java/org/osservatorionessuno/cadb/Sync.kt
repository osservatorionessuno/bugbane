package org.osservatorionessuno.cadb

import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.LocalServices
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.math.min

/**
 * Minimal ADB sync implementation supporting file pulls.
 */
class Sync(
    private val manager: AbsAdbConnectionManager,
    private val progress: ((Long) -> Unit)? = null,
) {
    /**
     * Pull a remote file to a local destination.
     *
     * @param remotePath Path on the device to pull from.
     * @param localDest Local file to write to.
     */
    @Throws(IOException::class, InterruptedException::class)
    fun pull(remotePath: String, localDest: File) {
        val temp = File(localDest.parentFile, localDest.name + ".part").apply {
            parentFile?.mkdirs()
            delete()
        }

        manager.openStream(LocalServices.SYNC).use { stream ->
            val out = stream.openOutputStream()
            val input = stream.openInputStream()

            // Send RECV request
            val pathBytes = remotePath.toByteArray(StandardCharsets.UTF_8)
            val req = ByteBuffer.allocate(8 + pathBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            req.put("RECV".toByteArray(StandardCharsets.US_ASCII))
            req.putInt(pathBytes.size)
            req.put(pathBytes)
            out.write(req.array())
            out.flush()

            FileOutputStream(temp).use { fileOut ->
                val header = ByteArray(4)
                val lenBuf = ByteArray(4)
                val buf = ByteArray(8192) // local IO buffer

                while (true) {
                    readFully(input, header, 0, 4)
                    val cmd = String(header, StandardCharsets.US_ASCII)

                    when (cmd) {
                        "DATA" -> {
                            // DATA <len> <payload>
                            readFully(input, lenBuf, 0, 4)
                            var remaining = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).int
                            // Read the payload in chunks to avoid oversized single reads.
                            while (remaining > 0) {
                                val chunk = min(remaining, buf.size)
                                readFully(input, buf, 0, chunk)
                                fileOut.write(buf, 0, chunk)
                                progress?.invoke(chunk.toLong())
                                remaining -= chunk
                            }
                        }

                        "DONE" -> {
                            // DONE is followed by a 4-byte mtime (uint32). Ignore contents.
                            readFully(input, lenBuf, 0, 4)
                            break
                        }

                        "FAIL" -> {
                            readFully(input, lenBuf, 0, 4)
                            val msgLen = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).int
                            val msgBytes = ByteArray(msgLen)
                            readFully(input, msgBytes, 0, msgLen)
                            val msg = String(msgBytes, StandardCharsets.UTF_8)
                            throw IOException("Sync failed: $msg")
                        }

                        else -> throw IOException("Unexpected sync response: $cmd")
                    }
                }
            }
        }

        if (localDest.exists()) localDest.delete()
        if (!temp.renameTo(localDest)) temp.copyTo(localDest, overwrite = true)
        temp.delete()
    }

    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int) {
        var off = offset
        var remaining = length
        while (remaining > 0) {
            val read = input.read(buffer, off, remaining)
            if (read < 0) throw EOFException()
            off += read
            remaining -= read
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
        val entries = mutableListOf<Map<String, Any>>()

        manager.openStream(LocalServices.SYNC).use { stream ->
            val out = stream.openOutputStream()
            val input = stream.openInputStream()

            // Send LIST request
            val remoteBytes = remoteDir.toByteArray(StandardCharsets.UTF_8)
            val payloadLen = remoteBytes.size
            val cmd = "LIST"
            val header = ByteBuffer.allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(cmd.toByteArray(StandardCharsets.US_ASCII))
                .putInt(payloadLen)
                .array()
            out.write(header)
            out.write(remoteBytes)
            out.flush()

            val nameBuf = ByteArray(1024)
            val lenBuf = ByteArray(4)

            while (true) {
                // Read 4-byte command header
                readFully(input, nameBuf, 0, 4)
                val respCmd = String(nameBuf, 0, 4, StandardCharsets.US_ASCII)

                when (respCmd) {
                    "DENT" -> {
                        // Read 8 bytes for mode (uint32), size (uint64 not supported in V1: still uint32), mtime (uint32)
                        readFully(input, lenBuf, 0, 4)
                        val mode = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).int

                        readFully(input, lenBuf, 0, 4)
                        val size = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).int

                        readFully(input, lenBuf, 0, 4)
                        val mtime = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).int

                        // Next 4 is name length, then name data
                        readFully(input, lenBuf, 0, 4)
                        val nameLen = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).int
                        val nameBytes = ByteArray(nameLen)
                        readFully(input, nameBytes, 0, nameLen)
                        val name = String(nameBytes, StandardCharsets.UTF_8)

                        val entry = mapOf(
                            "path" to name,
                            "mode" to mode,
                            "size" to size,
                            "mtime" to mtime
                        )
                        entries.add(entry)
                    }

                    "DONE" -> {
                        // No payload, end of list
                        break
                    }

                    "FAIL" -> {
                        // 4-byte length, then error string
                        readFully(input, lenBuf, 0, 4)
                        val msgLen = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).int
                        val msgBytes = ByteArray(msgLen)
                        readFully(input, msgBytes, 0, msgLen)
                        val msg = String(msgBytes, StandardCharsets.UTF_8)
                        throw IOException("List failed: $msg")
                    }

                    else -> {
                        throw IOException("Unexpected list response: $respCmd")
                    }
                }
            }
        }
        return entries
    }

    /**
     * Pull a remote folder to a local destination.
     *
     * @param remoteDir Path on the device to pull from.
     * @param localDest Local folder to write to.
     */
    @Throws(IOException::class)
    fun pullFolder(remoteDir: String, localDest: File) {
        assert(remoteDir.endsWith("/"))

        val entries = list(remoteDir)
        for (entry in entries) {
            val path = entry["path"] as String
            val mode = entry["mode"] as Int
            val mtime = entry["mtime"] as Int

            if (path == "." || path == "..") continue

            val localPath = File(localDest, path)
            localPath.parentFile?.mkdirs()

            if (mode and 0x4000 != 0) {
                pullFolder(remoteDir + path, localPath)
            } else {
                pull(remoteDir + path, localPath)
                // set last modified time accordingly
                localPath.setLastModified(mtime.toLong() * 1000)
            }
        }
    }
}
