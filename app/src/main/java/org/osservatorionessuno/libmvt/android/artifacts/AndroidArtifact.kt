package org.osservatorionessuno.libmvt.android.artifacts

import android.content.Context
import org.osservatorionessuno.libmvt.common.Artifact
import java.io.InputStream
import java.io.IOException

/**
 * Base class for Android-related artifact parsers.
 * Operates on Strings and InputStreams.
 */
abstract class AndroidArtifact : Artifact() {

    var context: Context? = null
        @JvmName("setContext") set

    /**
     * Return the file name(s) this module reads (e.g. "dumpsys.txt", "packages.json").
     * Used to build file -> module index mapping.
     */
    abstract fun paths(): List<String>

    @Throws(IOException::class)
    protected fun collectText(content: InputStream): String {
        return content.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    @Throws(IOException::class)
    protected fun collectLines(content: InputStream): List<String> {
        return content.bufferedReader(Charsets.UTF_8).useLines { it.toList() }
    }
}
