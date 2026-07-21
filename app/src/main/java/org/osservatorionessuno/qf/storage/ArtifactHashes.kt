package org.osservatorionessuno.qf.storage

/** SHA-256 manifest for artifacts in an acquisition archive. */
const val HASHES_FILE: String = "hashes.csv"

/*
    Same "path,sha256" format as androidqf's hashes.csv: RFC 4180 quoting
    (as produced by Go's encoding/csv) for paths containing commas, quotes,
    or newlines.
*/
object ArtifactHashes {
    fun formatLine(path: String, sha256: String): String = "${quote(path)},$sha256\n"

    /** @throws IllegalArgumentException on malformed content; treat as an integrity failure. */
    fun parse(content: String): List<Pair<String, String>> {
        val records = mutableListOf<String>()
        val record = StringBuilder()
        var inQuotes = false
        for (ch in content) {
            when {
                ch == '"' -> {
                    inQuotes = !inQuotes
                    record.append(ch)
                }
                (ch == '\n' || ch == '\r') && !inQuotes -> {
                    if (record.isNotEmpty()) records.add(record.toString())
                    record.clear()
                }
                else -> record.append(ch)
            }
        }
        require(!inQuotes) { "Unterminated quote in hashes.csv" }
        if (record.isNotEmpty()) records.add(record.toString())
        return records.map { parseRecord(it) }
    }

    private fun parseRecord(record: String): Pair<String, String> {
        // The hash is the fixed-format last field, so the separator is the
        // last comma even if an unquoted path contains commas.
        val comma = record.lastIndexOf(',')
        require(comma > 0) { "Invalid hashes.csv record: $record" }
        return unquote(record.substring(0, comma)) to record.substring(comma + 1)
    }

    private fun quote(field: String): String =
        if (field.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) field
        else "\"${field.replace("\"", "\"\"")}\""

    private fun unquote(field: String): String =
        if (field.length >= 2 && field.startsWith('"') && field.endsWith('"')) {
            field.substring(1, field.length - 1).replace("\"\"", "\"")
        } else {
            field
        }
}
