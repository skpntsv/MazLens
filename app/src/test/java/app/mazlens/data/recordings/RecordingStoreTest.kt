package app.mazlens.data.recordings

import app.mazlens.core.archive.RecorderFile
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val entry =
        RecorderFile(
            "261106235859",
            1,
            1,
            4,
            "2026-11-06 23:58:59",
            "/tmp/SD0/NORMAL/261106235859.MP4",
            recorderId = "recorder-a",
        )

    @Test
    fun cancellationAtPublicationLeavesNoSavedOriginal() {
        val directory = temp.newFolder()
        val store = RecordingStore(directory)
        val partial = store.createPartial(entry).apply { writeBytes(ByteArray(4)) }
        fails { store.commit(entry, partial) { false } }
        assertTrue(store.load().isEmpty())
        assertTrue(partial.exists())
        assertFalse(File(directory, "${entry.id}.MP4").exists())
    }

    @Test
    fun commitPersistsOriginalAndMetadataAcrossStoreRecreation() {
        val directory = temp.newFolder()
        val store = RecordingStore(directory)
        val partial = store.createPartial(entry).apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        assertTrue(store.load().isEmpty())
        val saved = store.commit(entry, partial)
        assertEquals(entry, saved.entry)
        assertEquals("${entry.id}.MP4", saved.file.name)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), saved.file.readBytes())
        assertFalse(partial.exists())
        assertTrue(saved.savedAt > 0)
        val reloaded = RecordingStore(directory).load().single()
        assertEquals(saved, reloaded)
        assertEquals(reloaded, RecordingStore(directory).find(entry.id))
        val otherRecorder = entry.copy(recorderId = "recorder-b")
        val other =
            store.commit(
                otherRecorder,
                store.createPartial(otherRecorder).apply { writeBytes(byteArrayOf(5, 6, 7, 8)) },
            )
        assertNotEquals(saved.entry.id, other.entry.id)
        val restored = RecordingStore(directory)
        assertEquals(2, restored.load().size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), restored.find(entry.id)!!.file.readBytes())
        assertArrayEquals(
            byteArrayOf(5, 6, 7, 8),
            restored.find(otherRecorder.id)!!.file.readBytes(),
        )
        assertNotEquals(entry.id, entry.copy(size = 8).id)
    }

    @Test
    fun truncatedDownloadNeverPublishesAndDiscardRemovesPartial() {
        val directory = temp.newFolder()
        val store = RecordingStore(directory)
        val partial = store.createPartial(entry).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        fails { store.commit(entry, partial) }
        assertTrue(store.load().isEmpty())
        assertTrue(RecordingStore(directory).load().isEmpty())
        store.discard(partial)
        assertFalse(partial.exists())
        store.discard(partial)
    }

    @Test
    fun failedRedownloadCannotReplaceExistingOriginal() {
        val directory = temp.newFolder()
        val store = RecordingStore(directory)
        val saved =
            store.commit(
                entry,
                store.createPartial(entry).apply { writeBytes(byteArrayOf(1, 2, 3, 4)) },
            )
        val bad = store.createPartial(entry).apply { writeBytes(byteArrayOf(9)) }
        fails { store.commit(entry, bad) }
        val changed = store.createPartial(entry).apply { writeBytes(byteArrayOf(9, 9, 9, 9)) }
        fails { store.commit(entry, changed) }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), saved.file.readBytes())
        assertEquals(saved, RecordingStore(directory).load().single())
    }

    @Test
    fun readsLegacyOriginalWithoutAssociatingItWithARecorder() {
        val directory = temp.newFolder()
        val legacy = entry.copy(recorderId = "")
        File(directory, "${legacy.id}.MP4").writeBytes(byteArrayOf(1, 2, 3, 4))
        DataOutputStream(File(directory, "${legacy.id}.meta").outputStream()).use { output ->
            output.writeInt(0x44565231)
            output.writeInt(1)
            output.writeUTF(legacy.name)
            output.writeInt(legacy.camera)
            output.writeInt(legacy.category)
            output.writeLong(legacy.size)
            output.writeUTF(legacy.recordedAt)
            output.writeUTF(legacy.remotePath)
            output.writeLong(1)
            output.writeLong(4)
        }
        val store = RecordingStore(directory)
        val saved = store.load().single()
        assertEquals("", saved.entry.recorderId)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), saved.file.readBytes())
        assertNull(store.find(entry.id))
    }

    @Test
    fun metadataPublicationFailureLeavesNoOriginalOrStagingFiles() {
        val directory = temp.newFolder()
        val store = RecordingStore(directory)
        val blocked = File(directory, "${entry.id}.meta").apply { mkdir() }
        File(blocked, "keep").writeText("cannot replace this directory")
        val partial = store.createPartial(entry).apply { writeBytes(ByteArray(4)) }
        fails { store.commit(entry, partial) }
        assertTrue(partial.exists())
        assertFalse(File(directory, "${entry.id}.MP4").exists())
        assertFalse(directory.listFiles()!!.any { it.name.endsWith(".tmp") })
        assertTrue(RecordingStore(directory).load().isEmpty())
    }

    private fun fails(action: () -> Unit) {
        try {
            action()
            fail("Unsafe recording operation accepted")
        } catch (_: IOException) {}
    }
}
