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
        store.save(item("first", 10).copy(timedOut = true))
        store.save(item("second", 20))
        assertEquals(listOf("second", "first"), HistoryStore(dir).load().map { it.id })
        assertEquals("SEND private\nRECV secret", HistoryStore(dir).load().last().trace)
        assertEquals("candidate", HistoryStore(dir).load().last().passwordCandidate)
        assertTrue(HistoryStore(dir).load().last().timedOut)
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
}
