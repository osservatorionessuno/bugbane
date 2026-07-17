package org.osservatorionessuno.bugbane.update

import java.io.IOException
import java.io.OutputStream
import java.io.Reader

/**
 * Applies the update feed's deltas (zero-context unified diffs produced by the bugbane-updater
 * builder) in a single streaming pass: the local bundle is read line by line from a [Reader] and
 * the reconstructed full is written straight to an [OutputStream], so peak memory is bounded by
 * the delta size, never the bundle size.
 *
 * The caller only adopts the result if its SHA-256 equals the signed full hash, so any mismatch or
 * [IOException] here just means falling back to a full download.
 *
 * Line semantics match the builder's: the bundle is a sequence of `\n`-separated segments (a file
 * with a trailing newline ends in an empty segment), and hunk line numbers are 1-based indices
 * into that sequence. Output is the segments joined by `\n`, which reproduces the input bytes
 * exactly where hunks don't touch them.
 */
object DeltaPatch {

    /** @throws IOException if the diff is malformed or its removed lines don't match [source]. */
    fun apply(source: Reader, delta: ByteArray, out: OutputStream) {
        val deltaLines = String(delta, Charsets.UTF_8).split("\n").toMutableList()
        // Drop the trailing empty element from the delta's own trailing newline before parsing.
        if (deltaLines.isNotEmpty() && deltaLines.last().isEmpty()) deltaLines.removeAt(deltaLines.size - 1)

        val src = LineSource(source)
        val sink = LineSink(out)

        var i = 0
        while (i < deltaLines.size) {
            val header = deltaLines[i]
            // Anything outside a hunk body that isn't a hunk header (`---`/`+++` file headers) is skipped.
            if (!header.startsWith("@@")) {
                i++
                continue
            }
            val hunk = parseHunkHeader(header)
            i++

            // Copy the untouched lines between the previous hunk and this one. A zero-length
            // removal means "insert after line N", so the insertion point itself is copied too.
            val firstTouched = if (hunk.removeCount == 0) hunk.removeStart else hunk.removeStart - 1
            if (firstTouched < src.consumed) throw IOException("Delta hunks overlap or are out of order")
            while (src.consumed < firstTouched) {
                sink.write(src.next() ?: throw IOException("Delta hunk starts beyond end of bundle"))
            }

            var removeLeft = hunk.removeCount
            var addLeft = hunk.addCount
            while (removeLeft > 0 || addLeft > 0) {
                if (i >= deltaLines.size) throw IOException("Delta hunk truncated")
                val body = deltaLines[i]
                i++
                when {
                    body.startsWith("-") -> {
                        if (removeLeft <= 0) throw IOException("Delta hunk removes more lines than declared")
                        val actual = src.next() ?: throw IOException("Delta removes beyond end of bundle")
                        if (actual != body.substring(1)) {
                            throw IOException("Delta does not match local bundle at line ${src.consumed}")
                        }
                        removeLeft--
                    }
                    body.startsWith("+") -> {
                        if (addLeft <= 0) throw IOException("Delta hunk adds more lines than declared")
                        sink.write(body.substring(1))
                        addLeft--
                    }
                    body.startsWith(" ") -> {
                        // The builder emits zero-context diffs; tolerate context lines anyway.
                        if (removeLeft <= 0 || addLeft <= 0) throw IOException("Unexpected context line in delta hunk")
                        val actual = src.next() ?: throw IOException("Delta context beyond end of bundle")
                        if (actual != body.substring(1)) {
                            throw IOException("Delta context does not match local bundle at line ${src.consumed}")
                        }
                        sink.write(actual)
                        removeLeft--
                        addLeft--
                    }
                    body.startsWith("\\") -> Unit // "\ No newline at end of file" marker
                    else -> throw IOException("Malformed delta hunk line")
                }
            }
        }

        // Copy the tail of the bundle untouched.
        while (true) sink.write(src.next() ?: break)
    }

    private class Hunk(val removeStart: Int, val removeCount: Int, val addCount: Int)

    private val HUNK_HEADER = Regex("""^@@ -(\d+)(?:,(\d+))? \+\d+(?:,(\d+))? @@""")

    private fun parseHunkHeader(line: String): Hunk {
        val m = HUNK_HEADER.find(line) ?: throw IOException("Malformed delta hunk header: $line")
        return Hunk(
            removeStart = m.groupValues[1].toInt(),
            removeCount = m.groupValues[2].ifEmpty { "1" }.toInt(),
            addCount = m.groupValues[3].ifEmpty { "1" }.toInt(),
        )
    }

    /** Yields the `\n`-separated segments of [reader]; [consumed] counts segments read so far. */
    private class LineSource(private val reader: Reader) {
        var consumed = 0
            private set
        private var eof = false

        fun next(): String? {
            if (eof) return null
            val sb = StringBuilder()
            while (true) {
                val c = reader.read()
                if (c == -1) {
                    eof = true
                    break
                }
                if (c == '\n'.code) break
                sb.append(c.toChar())
            }
            consumed++
            return sb.toString()
        }
    }

    /** Writes segments joined by `\n` (a separator before every segment except the first). */
    private class LineSink(private val out: OutputStream) {
        private var first = true

        fun write(line: String) {
            if (!first) out.write('\n'.code)
            out.write(line.toByteArray(Charsets.UTF_8))
            first = false
        }
    }
}
