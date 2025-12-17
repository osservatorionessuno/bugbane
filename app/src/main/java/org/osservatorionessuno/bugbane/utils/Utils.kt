package org.osservatorionessuno.bugbane.utils

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

class Utils {
    companion object {
        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    md.update(buffer, 0, read)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    
        fun sha256(data: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(data.toByteArray())
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        fun calculateSize(file: File): Long {
            return if (file.isFile) file.length() else file.listFiles()?.sumOf { calculateSize(it) } ?: 0L
        }
        
        fun formatBytes(bytes: Long): String {
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            var value = bytes.toDouble()
            var idx = 0
            while (value >= 1024 && idx < units.lastIndex) {
                value /= 1024
                idx++
            }
            return String.format("%.1f %s", value, units[idx])
        }

        fun generatePassphrase(): String {
            val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
            val rnd = SecureRandom()
            return (1..32).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        }
    }
}
