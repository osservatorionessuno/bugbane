package org.osservatorionessuno.bugbane.update

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import org.osservatorionessuno.libmvt.common.Indicators
import java.io.File
import java.io.InputStreamReader
import java.io.Reader
import java.util.regex.Pattern

/**
 * Validates an indicator file the way libmvt's `Indicators` will read it, and counts what it
 * would actually load, so a user importing a file learns up front whether it is usable.
 *
 * Accepts the same shapes as libmvt: a STIX 2 bundle (`{"objects": [...]}`), a bare array of
 * STIX objects, or an MVT-style collection (`{"indicators": [{"domain-name:value": [...]}]}`).
 * Streamed with Gson's pull parser, so file size does not matter for memory.
 */
object IndicatorFileInspector {

    /** Why a file was rejected ([TOO_LARGE] is raised by [CustomIndicatorStore], not here). */
    enum class Problem { MALFORMED_JSON, UNSUPPORTED_FORMAT, NO_INDICATORS, TOO_LARGE }

    class InvalidIndicatorFile(val problem: Problem) : Exception(problem.name)

    /** [indicators] libmvt would load; [families] the first few malware names declared in the file. */
    data class Summary(val indicators: Int, val families: List<String>)

    private const val MAX_FAMILIES = 5
    private const val MAX_FAMILY_CHARS = 64

    // Same grammar libmvt applies to STIX patterns: one [key = 'value'] comparison.
    private val STIX_EQUALITY: Pattern =
        Pattern.compile("^\\[\\s*(?<key>[^=\\s]+)\\s*=\\s*'(?<value>(?:\\\\.|[^'])*)'\\s*\\]$")

    @Throws(InvalidIndicatorFile::class)
    fun inspect(file: File): Summary =
        InputStreamReader(file.inputStream().buffered(), Charsets.UTF_8).use { inspect(it) }

    @Throws(InvalidIndicatorFile::class)
    fun inspect(source: Reader): Summary {
        val count = Counter()
        try {
            JsonReader(source).use { reader ->
                when (reader.peek()) {
                    JsonToken.BEGIN_ARRAY -> readStixObjects(reader, count)
                    JsonToken.BEGIN_OBJECT -> {
                        var recognised = false
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val name = reader.nextName()
                            if (name == "objects" && reader.peek() == JsonToken.BEGIN_ARRAY) {
                                recognised = true
                                readStixObjects(reader, count)
                            } else if (name == "indicators" && reader.peek() == JsonToken.BEGIN_ARRAY) {
                                recognised = true
                                readMvtCollections(reader, count)
                            } else {
                                reader.skipValue()
                            }
                        }
                        reader.endObject()
                        if (!recognised) throw InvalidIndicatorFile(Problem.UNSUPPORTED_FORMAT)
                    }
                    else -> throw InvalidIndicatorFile(Problem.UNSUPPORTED_FORMAT)
                }
                // Trailing garbage after the document is not a valid file either.
                if (reader.peek() != JsonToken.END_DOCUMENT) throw InvalidIndicatorFile(Problem.MALFORMED_JSON)
            }
        } catch (e: InvalidIndicatorFile) {
            throw e
        } catch (e: Exception) {
            // Gson signals syntax errors with several exception types (IOException subclasses,
            // IllegalStateException, NumberFormatException); all mean "not valid JSON".
            throw InvalidIndicatorFile(Problem.MALFORMED_JSON)
        }
        if (count.indicators == 0) throw InvalidIndicatorFile(Problem.NO_INDICATORS)
        return Summary(count.indicators, count.families.toList())
    }

    private class Counter {
        var indicators = 0
        val families = LinkedHashSet<String>()
    }

    private fun readStixObjects(reader: JsonReader, count: Counter) {
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                continue
            }
            var type: String? = null
            var name: String? = null
            var pattern: String? = null
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "type" -> type = nextStringOrSkip(reader)
                    "name" -> name = nextStringOrSkip(reader)
                    "pattern" -> pattern = nextStringOrSkip(reader)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            when (type) {
                "indicator" -> if (isLoadablePattern(pattern)) count.indicators++
                "malware" -> if (count.families.size < MAX_FAMILIES) {
                    name?.trim()?.take(MAX_FAMILY_CHARS)?.takeIf { it.isNotEmpty() }?.let { count.families.add(it) }
                }
            }
        }
        reader.endArray()
    }

    private fun readMvtCollections(reader: JsonReader, count: Counter) {
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                continue
            }
            reader.beginObject()
            while (reader.hasNext()) {
                val known = Indicators.typeForKey(reader.nextName()) != null
                if (!known) {
                    reader.skipValue()
                } else if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                    reader.beginArray()
                    while (reader.hasNext()) if (nextScalarOrNull(reader)?.isNotBlank() == true) count.indicators++
                    reader.endArray()
                } else if (nextScalarOrNull(reader)?.isNotBlank() == true) {
                    count.indicators++
                }
            }
            reader.endObject()
        }
        reader.endArray()
    }

    private fun isLoadablePattern(pattern: String?): Boolean {
        if (pattern == null) return false
        val m = STIX_EQUALITY.matcher(pattern.trim())
        if (!m.matches()) return false
        return Indicators.typeForKey(m.group("key")) != null && m.group("value").isNotEmpty()
    }

    private fun nextStringOrSkip(reader: JsonReader): String? {
        if (reader.peek() != JsonToken.STRING) {
            reader.skipValue()
            return null
        }
        return reader.nextString()
    }

    private fun nextScalarOrNull(reader: JsonReader): String? = when (reader.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
        JsonToken.BOOLEAN -> reader.nextBoolean().toString()
        else -> {
            reader.skipValue()
            null
        }
    }
}
