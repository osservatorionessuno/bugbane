package org.osservatorionessuno.bugbane.update

import com.github.difflib.UnifiedDiffUtils
import com.github.difflib.patch.PatchFailedException

/**
 * Applies the update feed's deltas (zero-context unified diffs produced by the bugbane-updater
 * builder) using java-diff-utils. The result is only adopted if its SHA-256 equals the signed full
 * hash, so the caller treats any failure here as a reason to fall back to a full download.
 *
 */
object DeltaPatch {

    /** @throws PatchFailedException if the diff's removed lines don't match [full] (e.g. corruption). */
    fun apply(full: ByteArray, delta: ByteArray): ByteArray {
        val source = String(full, Charsets.UTF_8).split("\n")
        val deltaLines = String(delta, Charsets.UTF_8).split("\n").toMutableList()
        // Drop the trailing empty element from the delta's own trailing newline before parsing.
        if (deltaLines.isNotEmpty() && deltaLines.last().isEmpty()) deltaLines.removeAt(deltaLines.size - 1)

        val patch = UnifiedDiffUtils.parseUnifiedDiff(deltaLines)
        val out = patch.applyTo(source)
        return out.joinToString("\n").toByteArray(Charsets.UTF_8)
    }
}
