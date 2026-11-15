package app.mazlens.history

import app.mazlens.data.history.*
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private fun item(id: String, time: Long = 10, trace: String = "SEND private\nRECV secret") =
        AttemptRecord(
            id,
            time,
            AttemptKind.ACCESS,
            "Проверка $id",
            "Получен отказ",
            AttemptStatus.REJECTED,
            trace,
            "{rval:-4}",
            "network snapshot",
            "candidate",
        )

    @Test
    fun keepsIndependentAttemptsAcrossRestart() {
        val dir = temp.newFolder()
        val store = HistoryStore(dir)
        store.save(item("first", 10))
        store.save(item("second", 20))
        assertEquals(listOf("second", "first"), HistoryStore(dir).load().map { it.id })
        assertEquals("SEND private\nRECV secret", HistoryStore(dir).load().last().trace)
        assertEquals("candidate", HistoryStore(dir).load().last().passwordCandidate)
    }

    @Test
    fun updatingSameAttemptDoesNotCreateDuplicate() {
        val store = HistoryStore(temp.newFolder())
        store.save(item("same").copy(status = AttemptStatus.RUNNING))
        store.save(item("same").copy(summary = "Готово"))
        assertEquals(1, store.load().size)
        assertEquals("Готово", store.load().single().summary)
    }

    @Test
    fun pendingAttemptRecoversAsInterrupted() {
        val dir = temp.newFolder()
        HistoryStore(dir).save(item("pending").copy(status = AttemptStatus.RUNNING))
        val recovered = HistoryStore(dir).recoverInterrupted().single()
        assertEquals(AttemptStatus.INTERRUPTED, recovered.status)
        assertFalse(recovered.summary.isBlank())
        assertEquals(AttemptStatus.INTERRUPTED, HistoryStore(dir).load().single().status)
    }

    @Test
    fun pruningKeepsNewestAndDoesNotEraseNewWrite() {
        val store = HistoryStore(temp.newFolder(), maxRecords = 2, maxBytes = 100_000)
        store.save(item("one", 1))
        store.save(item("two", 2))
        store.save(item("three", 3))
        assertEquals(listOf("three", "two"), store.load().map { it.id })
    }

    @Test
    fun byteBudgetPrunesOldestAndRejectsOversizedWithoutErasingHistory() {
        val dir = temp.newFolder()
        val store = HistoryStore(dir, maxRecords = 50, maxBytes = 1400)
        store.save(item("one", 1, "x".repeat(600)))
        store.save(item("two", 2, "y".repeat(600)))
        assertEquals(listOf("two"), store.load().map { it.id })
        try {
            store.save(item("too-large", 3, "z".repeat(3000)))
            fail("oversized accepted")
        } catch (_: java.io.IOException) {}
        assertEquals(listOf("two"), HistoryStore(dir).load().map { it.id })
    }

    @Test
    fun interruptedTemporaryWriteCannotReplaceCompletedRecord() {
        val dir = temp.newFolder()
        val store = HistoryStore(dir)
        store.save(item("one"))
        java.io.File(dir, "one.json.tmp").writeText("{unfinished")
        assertEquals("one", HistoryStore(dir).load().single().id)
    }

    @Test
    fun corruptRecordDoesNotHideOtherResults() {
        val dir = temp.newFolder()
        val store = HistoryStore(dir)
        store.save(item("good"))
        java.io.File(dir, "bad.json").writeText("{invalid")
        assertEquals(listOf("good"), store.load().map { it.id })
        assertTrue(store.warnings.isNotEmpty())
    }

    @Test
    fun zipExportIsCapturedSnapshotWithReadableAndStructuredReports() {
        val store = HistoryStore(temp.newFolder())
        store.save(item("first"))
        val bytes = ReportExporter.capture(store.load(), "events", 123)
        store.save(item("later"))
        val entries = mutableMapOf<String, String>()
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                entries[e.name] = z.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals(setOf("report.txt", "report.json"), entries.keys)
        assertTrue(entries.getValue("report.txt").contains("candidate"))
        val json = org.json.JSONObject(entries.getValue("report.json"))
        assertEquals(1, json.getJSONArray("attempts").length())
        assertEquals("first", json.getJSONArray("attempts").getJSONObject(0).getString("id"))
        assertFalse(entries.getValue("report.txt").contains("Проверка later"))
        assertTrue(entries.getValue("report.txt").contains("network snapshot"))
    }

    @Test
    fun quotaIncludesOrphanTemporaryAndCorruptFiles() {
        val dir = temp.newFolder()
        val store = HistoryStore(dir, maxRecords = 50, maxBytes = 1400)
        store.save(item("old", 1))
        java.io.File(dir, "orphan.json.tmp").writeText("x".repeat(2000))
        java.io.File(dir, "corrupt.json").writeText("?".repeat(2000))
        store.save(item("new", 2))
        assertTrue("history exceeded disk budget", dir.listFiles()!!.sumOf { it.length() } <= 1400)
        assertTrue(store.load().any { it.id == "new" })
    }

    @Test
    fun terminalResultCannotRegressToLateRunningSnapshot() {
        val store = HistoryStore(temp.newFolder())
        store.save(
            item("same").copy(status = AttemptStatus.SUCCESS, summary = "Первый кадр получен")
        )
        store.save(item("same").copy(status = AttemptStatus.RUNNING, summary = "Ожидание"))
        assertEquals(AttemptStatus.SUCCESS, store.load().single().status)
        assertEquals("Первый кадр получен", store.load().single().summary)
    }
}
