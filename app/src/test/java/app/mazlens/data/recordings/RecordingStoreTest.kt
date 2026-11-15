package app.mazlens.data.recordings

import app.mazlens.core.archive.RecorderFile
import java.io.File
import java.io.IOException
import java.nio.file.Files
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
    fun restartRemovesOnlyUnpublishedArtifactsAndPreservesSavedOriginals() {
        val directory = temp.newFolder()
        val store = RecordingStore(directory)
        val saved =
            store.commit(entry, store.createPartial(entry).apply { writeBytes(ByteArray(4)) })
        val abandoned =
            store
                .createPartial(
                    entry.copy(
                        name = "261106235800",
                        remotePath = "/tmp/SD0/NORMAL/261106235800.MP4",
                    )
                )
                .apply { writeBytes(ByteArray(20)) }
        val stage = File(directory, ".metadata-123.tmp").apply { writeText("staged") }
        val orphan = File(directory, "${"0".repeat(64)}.MP4").apply { writeBytes(ByteArray(30)) }
        val unrelated = File(directory, "keep.txt").apply { writeText("keep") }
        val restarted = RecordingStore(directory).apply { recoverAbandoned() }
        val reloaded = restarted.load()
        assertEquals(listOf(saved), reloaded)
        assertFalse(abandoned.exists())
        assertFalse(stage.exists())
        assertFalse(orphan.exists())
        assertTrue(unrelated.exists())
        assertTrue(saved.file.exists())
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
    fun oversizedAndEmptyUnknownSizeDownloadNeverPublish() {
        val store = RecordingStore(temp.newFolder())
        val partial = store.createPartial(entry).apply { writeBytes(ByteArray(5)) }
        fails { store.commit(entry, partial) }
        val unknown = entry.copy(size = 0)
        fails { store.commit(unknown, store.createPartial(unknown)) }
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun unknownPositiveSizeIsRecordedAndStillCheckedAfterRestart() {
        val directory = temp.newFolder()
        val store = RecordingStore(directory)
        val unknown = entry.copy(size = 0)
        val partial = store.createPartial(unknown).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val saved = store.commit(unknown, partial)
        assertEquals(1, RecordingStore(directory).load().size)
        saved.file.writeBytes(byteArrayOf(1))
        assertTrue(RecordingStore(directory).load().isEmpty())
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
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), saved.file.readBytes())
        assertEquals(saved, RecordingStore(directory).load().single())
    }

    @Test
    fun validRedownloadKeepsPreviouslyCommittedOriginal() {
        val store = RecordingStore(temp.newFolder())
        val saved =
            store.commit(
                entry,
                store.createPartial(entry).apply { writeBytes(byteArrayOf(1, 2, 3, 4)) },
            )
        val again =
            store.commit(
                entry,
                store.createPartial(entry).apply { writeBytes(byteArrayOf(9, 9, 9, 9)) },
            )
        assertEquals(saved, again)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), again.file.readBytes())
    }

    @Test
    fun ignoresMissingCorruptAndOrphanFilesOnReload() {
        val directory = temp.newFolder()
        val store = RecordingStore(directory)
        File(directory, "orphan.MP4").writeBytes(ByteArray(4))
        File(directory, "${entry.id}.meta").writeText("broken")
        store.createPartial(entry).writeBytes(ByteArray(4))
        assertTrue(RecordingStore(directory).load().isEmpty())
        val saved =
            store.commit(entry, store.createPartial(entry).apply { writeBytes(ByteArray(4)) })
        saved.file.delete()
        assertTrue(RecordingStore(directory).load().isEmpty())
        assertNull(store.find("../outside"))
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

    @Test
    fun rejectsOutsideUnownedAndAliasedPartialsWithoutTouchingThem() {
        val directory = temp.newFolder()
        val store = RecordingStore(directory)
        val outside = temp.newFile("outside.part").apply { writeBytes(ByteArray(4)) }
        fails { store.commit(entry, outside) }
        fails { store.discard(outside) }
        assertTrue(outside.exists())
        val unowned = File(directory, "foreign.part").apply { writeBytes(ByteArray(4)) }
        fails { store.commit(entry, unowned) }
        fails { store.discard(unowned) }
        assertTrue(unowned.exists())
        val own = store.createPartial(entry).apply { writeBytes(ByteArray(4)) }
        val alias = File(directory, "../${directory.name}/${own.name}")
        fails { store.commit(entry, alias) }
        assertTrue(own.exists())
        fails { RecordingStore(directory).commit(entry, own) }
        assertTrue(own.exists())
    }

    @Test
    fun rejectsEntryMismatchAndUnsafeRemotePaths() {
        val store = RecordingStore(temp.newFolder())
        val partial = store.createPartial(entry).apply { writeBytes(ByteArray(4)) }
        fails { store.commit(entry.copy(camera = 2), partial) }
        for (invalid in
            listOf(
                entry.copy(name = "../../escape"),
                entry.copy(remotePath = "/tmp/SD0/../../etc/passwd"),
                entry.copy(size = -1),
                entry.copy(size = 4294967296L),
                entry.copy(category = 8),
            )) {
            fails { store.createPartial(invalid) }
        }
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun rejectsSymlinkPartialAndSymlinkOriginalOnReload() {
        val directory = temp.newFolder()
        val store = RecordingStore(directory)
        val outside = temp.newFile("outside.mp4").apply { writeBytes(ByteArray(4)) }
        val partial = store.createPartial(entry)
        partial.delete()
        Files.createSymbolicLink(partial.toPath(), outside.toPath())
        fails { store.commit(entry, partial) }
        fails { store.discard(partial) }
        assertTrue(outside.exists())
        val saved =
            store.commit(entry, store.createPartial(entry).apply { writeBytes(ByteArray(4)) })
        saved.file.delete()
        Files.createSymbolicLink(saved.file.toPath(), outside.toPath())
        assertTrue(RecordingStore(directory).load().isEmpty())
    }

    private fun fails(action: () -> Unit) {
        try {
            action()
            fail("Unsafe recording operation accepted")
        } catch (_: IOException) {}
    }
}
