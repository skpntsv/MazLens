package app.mazlens.history

import app.mazlens.data.history.PrivateExportStore
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrivateExportStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun failedProviderWriteStillRemovesPrivateSnapshot() {
        val dir = temp.newFolder()
        val store = PrivateExportStore(dir)
        store.prepare("report", byteArrayOf(1, 2, 3))
        try {
            store.writeTo("report") { throw IOException("provider unavailable") }
            fail("expected failure")
        } catch (_: IOException) {}
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun outputUsesCapturedBytesEvenWhenProviderChangesPendingFile() {
        val dir = temp.newFolder()
        val store = PrivateExportStore(dir)
        store.prepare("report", "original".toByteArray())
        val out = ByteArrayOutputStream()
        store.writeTo("report") {
            java.io.File(dir, "report.zip").writeText("changed")
            out
        }
        assertEquals("original", out.toString("UTF-8"))
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun cancellingPickerDiscardsSnapshotAndPathTraversalIsRejected() {
        val dir = temp.newFolder()
        val store = PrivateExportStore(dir)
        store.prepare("report", byteArrayOf(1))
        store.discard("report")
        assertEquals(0, dir.listFiles()!!.size)
        try {
            store.prepare("../escape", byteArrayOf(2))
            fail("invalid id accepted")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun recoveryRemovesAbandonedSnapshotsButPreservesPendingExportAndUnrelatedFiles() {
        val dir = temp.newFolder()
        val store = PrivateExportStore(dir)
        store.prepare("pending", byteArrayOf(1))
        store.prepare("abandoned", byteArrayOf(2))
        java.io.File(dir, "interrupted.zip.tmp").writeText("incomplete")
        java.io.File(dir, "unrelated.txt").writeText("keep")
        store.removeAbandoned("pending")
        assertEquals(
            setOf("pending.zip", "unrelated.txt"),
            dir.listFiles()!!.map { it.name }.toSet(),
        )
        store.removeAbandoned(null)
        assertEquals(listOf("unrelated.txt"), dir.listFiles()!!.map { it.name })
    }
}
