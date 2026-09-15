package org.osservatorionessuno.bugbane.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.osservatorionessuno.bugbane.update.IndicatorFileInspector.Problem
import org.osservatorionessuno.libmvt.common.Indicators
import java.io.File
import java.io.StringReader
import java.security.MessageDigest

class CustomIndicatorStoreTest {

    @TempDir
    lateinit var tmp: File

    private val bundle = """
        {"type":"bundle","id":"bundle--1","objects":[
          {"type":"malware","id":"malware--1","name":"TestWare"},
          {"type":"indicator","id":"indicator--1","pattern":"[file:path = '/data/local/tmp/evil/marker.so']"},
          {"type":"indicator","id":"indicator--2","pattern":"[app:id = 'com.example.evil']"},
          {"type":"indicator","id":"indicator--3","pattern":"[unknown:thing = 'ignored']"},
          {"type":"indicator","id":"indicator--4","pattern":"not a stix pattern"},
          {"type":"relationship","id":"relationship--1","relationship_type":"indicates","source_ref":"indicator--1","target_ref":"malware--1"}
        ]}
    """.trimIndent()

    private fun sha256(s: String) =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    // --- inspector ---------------------------------------------------------------------------------

    @Test
    fun inspectorCountsOnlyWhatLibmvtLoads() {
        val s = IndicatorFileInspector.inspect(StringReader(bundle))
        assertEquals(2, s.indicators)
        assertEquals(listOf("TestWare"), s.families)
    }

    @Test
    fun inspectorAcceptsBareArrayAndMvtCollections() {
        assertEquals(1, IndicatorFileInspector.inspect(StringReader("""[{"type":"indicator","pattern":"[domain-name:value='evil.com']"}]""")).indicators)
        val mvt = """{"indicators":[{"domain-name:value":["a.com","b.com"],"process:name":"evil","bogus":["x"]}]}"""
        assertEquals(3, IndicatorFileInspector.inspect(StringReader(mvt)).indicators)
    }

    @Test
    fun inspectorRejectsBadFiles() {
        fun problem(text: String) = assertThrows(IndicatorFileInspector.InvalidIndicatorFile::class.java) {
            IndicatorFileInspector.inspect(StringReader(text))
        }.problem
        assertEquals(Problem.MALFORMED_JSON, problem("{not json"))
        assertEquals(Problem.MALFORMED_JSON, problem("""{"objects":[]} trailing"""))
        assertEquals(Problem.UNSUPPORTED_FORMAT, problem("""{"hello":"world"}"""))
        assertEquals(Problem.UNSUPPORTED_FORMAT, problem("\"just a string\""))
        assertEquals(Problem.NO_INDICATORS, problem("""{"objects":[{"type":"malware","id":"m","name":"X"}]}"""))
        assertEquals(Problem.NO_INDICATORS, problem("[]"))
    }

    // --- store -------------------------------------------------------------------------------------

    @Test
    fun importValidatesCopiesAndLists() {
        val store = CustomIndicatorStore(tmp)
        val result = store.import("pegasus.stix2", bundle.byteInputStream(), nowEpoch = 1234)
        val entry = assertInstanceOf(CustomIndicatorStore.ImportResult.Imported::class.java, result).entry

        assertEquals(sha256(bundle), entry.sha256)
        assertEquals("pegasus.stix2", entry.name)
        assertEquals(2, entry.indicators)
        assertEquals(listOf("TestWare"), entry.families)
        assertEquals(1234, entry.importedEpoch)

        val dir = IndicatorStore(tmp).indicatorsDir
        val file = File(dir, "custom-${sha256(bundle)}.stix2")
        assertTrue(file.exists())
        assertTrue(IndicatorStore.isCustomFile(file))
        assertEquals(bundle, file.readText())
        assertEquals(file, store.file(entry))
        assertEquals(listOf(entry), store.list())
        assertEquals(listOf(entry), CustomIndicatorStore(tmp).list()) // persisted
        assertTrue(tmp.walk().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun manifestRowsWithNonHexHashAreIgnored() {
        val store = CustomIndicatorStore(tmp)
        store.import("ok", bundle.byteInputStream())
        File(tmp, "custom_indicators.json").writeText(
            """{"imports":[{"sha256":"../../evil","name":"x","indicators":1,"families":[],"importedEpoch":0},""" +
                """{"sha256":"${sha256(bundle)}","name":"ok","indicators":2,"families":["TestWare"],"importedEpoch":0}]}""",
        )
        assertEquals(listOf("ok"), store.list().map { it.name })
    }

    @Test
    fun longNamesAndFamilyListsAreBounded() {
        val many = (1..20).joinToString(",") { """{"type":"malware","id":"malware--$it","name":"${"F$it".padEnd(100, 'x')}"}""" }
        val text = """{"objects":[$many,{"type":"indicator","pattern":"[app:id='a.b']"}]}"""
        val entry = (CustomIndicatorStore(tmp).import("n".repeat(500), text.byteInputStream()) as CustomIndicatorStore.ImportResult.Imported).entry
        assertEquals(80, entry.name.length)
        assertEquals(5, entry.families.size)
        assertTrue(entry.families.all { it.length == 64 })
    }

    @Test
    fun libmvtLoadsCustomSetNextToFeed() {
        val store = CustomIndicatorStore(tmp)
        store.import("x", bundle.byteInputStream())
        val feed = IndicatorStore(tmp)
        val staged = feed.stage { it.write("""{"objects":[{"type":"indicator","pattern":"[domain-name:value='feed.example']"}]}""".toByteArray()) }
        feed.adoptStaged(staged)

        val indicators = Indicators()
        indicators.loadFromDirectory(feed.indicatorsDir)
        assertEquals(1, indicators.matchString("/data/local/tmp/evil/marker.so", Indicators.IndicatorType.FILE_PATH).size)
        assertEquals(1, indicators.matchString("feed.example", Indicators.IndicatorType.DOMAIN).size)
        assertEquals(1, store.list().size) // feed adoption kept the custom file
    }

    @Test
    fun duplicateInvalidAndTooLargeImportsLeaveNothingBehind() {
        val store = CustomIndicatorStore(tmp)
        val dir = IndicatorStore(tmp).indicatorsDir
        store.import("first", bundle.byteInputStream())

        val dup = store.import("again", bundle.byteInputStream())
        assertInstanceOf(CustomIndicatorStore.ImportResult.Duplicate::class.java, dup)
        assertEquals("first", (dup as CustomIndicatorStore.ImportResult.Duplicate).entry.name)

        val bad = store.import("bad", "{oops".byteInputStream())
        assertEquals(Problem.MALFORMED_JSON, (bad as CustomIndicatorStore.ImportResult.Rejected).problem)

        val empty = store.import("empty", """{"objects":[]}""".byteInputStream())
        assertEquals(Problem.NO_INDICATORS, (empty as CustomIndicatorStore.ImportResult.Rejected).problem)

        val huge = object : java.io.InputStream() {
            var left = CustomIndicatorStore.MAX_BYTES + 1
            override fun read(): Int = if (left-- > 0) '0'.code else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = minOf(len.toLong(), left).toInt()
                java.util.Arrays.fill(b, off, off + n, '0'.code.toByte())
                left -= n
                return n
            }
        }
        assertEquals(CustomIndicatorStore.ImportResult.Rejected(Problem.TOO_LARGE), store.import("huge", huge))

        assertEquals(1, store.list().size)
        assertEquals(listOf("custom-${sha256(bundle)}.stix2"), dir.listFiles()!!.map { it.name })
    }

    /** The CI fixture (integration/fixtures) must import cleanly and match what run.sh plants. */
    @Test
    fun integrationFixtureImportsAndMatches() {
        val fixture = File("../integration/fixtures/e2e-iocs.stix2")
        assertTrue(fixture.exists(), "fixture missing at ${fixture.absolutePath}")
        val store = CustomIndicatorStore(tmp)
        val entry = (store.import(fixture.name, fixture.inputStream()) as CustomIndicatorStore.ImportResult.Imported).entry
        assertEquals(2, entry.indicators)
        assertEquals(listOf("Bugbane E2E test set"), entry.families)

        val indicators = Indicators()
        indicators.loadFromDirectory(IndicatorStore(tmp).indicatorsDir)
        val marker = "/data/local/tmp/bugbane-e2e/custom-marker.so"
        // Detection value is [family, type, matched string].
        assertEquals(
            listOf("Bugbane E2E test set", "FILE_PATH", marker),
            indicators.matchString(marker, Indicators.IndicatorType.FILE_PATH).single().value,
        )
        assertEquals(1, indicators.matchString("org.osservatorionessuno.fixture.suspicious", Indicators.IndicatorType.APP_ID).size)
    }

    @Test
    fun removeDeletesFileAndEntry() {
        val store = CustomIndicatorStore(tmp)
        val entry = (store.import("x", bundle.byteInputStream()) as CustomIndicatorStore.ImportResult.Imported).entry
        store.remove(entry)
        assertFalse(store.file(entry).exists())
        assertTrue(store.list().isEmpty())
        store.remove(entry) // idempotent
        // Re-import after removal is a fresh import, not a duplicate.
        assertInstanceOf(CustomIndicatorStore.ImportResult.Imported::class.java, store.import("x", bundle.byteInputStream()))
    }

    @Test
    fun listDropsEntriesWhoseFileIsGone() {
        val store = CustomIndicatorStore(tmp)
        val entry = (store.import("x", bundle.byteInputStream()) as CustomIndicatorStore.ImportResult.Imported).entry
        assertTrue(store.file(entry).delete())
        assertTrue(store.list().isEmpty())
        assertInstanceOf(CustomIndicatorStore.ImportResult.Imported::class.java, store.import("x", bundle.byteInputStream()))
    }
}
