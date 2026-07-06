package org.osservatorionessuno.bugbane.update

import org.json.JSONObject

/**
 * The `signed` metadata of an `update.json` document.
 *
 * `update.json` is either `{"proofs": {...}, "signed": {...}}` (sealed) or the bare `signed` object
 * (unsigned build). We read the `signed` subtree if present, else the document itself. Signature
 * proofs are intentionally ignored for now. Unknown fields are preserved-by-ignoring per the feed's
 * client contract.
 */
data class UpdateMetadata(
    val schema: Int,
    val version: Int,
    val sha256: String,
    val buildDate: String?,
    /** Optional ISO date (e.g. "2027-01-01") after which the app must be updated to keep receiving updates. */
    val sunset: String?,
) {
    companion object {

        fun parse(bytes: ByteArray): UpdateMetadata {
            val doc = JSONObject(String(bytes, Charsets.UTF_8))
            val signed = doc.optJSONObject("signed") ?: doc
            val sha256 = signed.getString("sha256").lowercase()
            return UpdateMetadata(
                schema = signed.getInt("schema"),
                version = signed.getInt("version"),
                sha256 = sha256,
                buildDate = signed.optString("build_date").ifBlank { null },
                sunset = signed.optString("sunset").ifBlank { null },
            )
        }
    }
}
