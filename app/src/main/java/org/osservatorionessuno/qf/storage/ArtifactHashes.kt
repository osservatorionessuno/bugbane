package org.osservatorionessuno.qf.storage

/** SHA-256 manifest for artifacts in an acquisition archive. */
const val HASHES_FILE: String = "hashes.csv"

object ArtifactHashes {
    fun formatLine(path: String, sha256: String): String = "$path,$sha256\n"

    fun parse(content: String): List<Pair<String, String>> =
        content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                val comma = line.indexOf(',')
                require(comma > 0) { "Invalid hashes.csv line: $line" }
                line.substring(0, comma) to line.substring(comma + 1)
            }
            .toList()
}
