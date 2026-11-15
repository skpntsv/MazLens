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
    fun exportsReportAndRemovesPrivateSnapshot() {
        val dir = temp.newFolder()
        val store = PrivateExportStore(dir)
        store.prepare("report", "original".toByteArray())
        val out = ByteArrayOutputStream()
        store.writeTo("report") { out }
        assertEquals("original", out.toString("UTF-8"))
        assertEquals(0, dir.listFiles()!!.size)
    }
}
