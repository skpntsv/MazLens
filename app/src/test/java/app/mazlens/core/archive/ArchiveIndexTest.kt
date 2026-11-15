package app.mazlens.core.archive

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

/** Synthetic binary fixtures from the decompiled layout; not a hardware capture. */
class ArchiveIndexTest {
    @Test
    fun duplicateRemoteFilesHaveOneStableListIdentity() {
        val files = ArchiveIndex.parse(index(row(), row(), row(camera = 2)))
        assertEquals(2, files.size)
        assertEquals(files.size, files.map { it.id }.toSet().size)
    }

    @Test
    fun conflictingMetadataForOneRemotePathIsACatalogueError() {
        fails { ArchiveIndex.parse(index(row(size = 4), row(size = 5))) }
    }

    @Test
    fun readsBigEndianUnsignedSizeAndBcdLocalDate() {
        val entry = ArchiveIndex.parse(index(row(size = 0xf1234567L))).single()
        assertEquals("261106235859", entry.name)
        assertEquals(4045620583L, entry.size)
        assertEquals("2026-11-06 23:58:59", entry.recordedAt)
        assertEquals("/tmp/SD0/NORMAL/261106235859.MP4", entry.remotePath)
        assertEquals("261106235859.MP4", entry.displayName)
        assertTrue(entry.isVideo)
    }

    @Test
    fun mapsAllFrontAndRearCategoryPathsIncludingPictures() {
        val categories =
            listOf("NORMAL", "EVENT", "MANUAL", "PARKING", "JUNCTION", "PICTURE", "MEMORIAL")
        for (camera in 1..2) for (category in 1..7) {
            val entry =
                ArchiveIndex.parse(index(row(camera = camera, category = category))).single()
            val extension = if (category >= 6) ".JPG" else ".MP4"
            assertEquals(
                "/tmp/SD0/${if (camera == 2) "REAR/" else ""}${categories[category-1]}/261106235859$extension",
                entry.remotePath,
            )
            assertEquals(category < 6, entry.isVideo)
        }
    }

    @Test
    fun stableIdUsesRemotePathAndSeparatesCameras() {
        val front = ArchiveIndex.parse(index(row())).single()
        val later = front.copy(size = 90L, recordedAt = "2026-11-07 00:00:00")
        val rear = ArchiveIndex.parse(index(row(camera = 2))).single()
        assertEquals("ecfb323fa2c145e0dddddffbe806c715e4e09bb7c514cfc70149852a5ec7e5a4", front.id)
        assertEquals(front.id, later.id)
        assertNotEquals(front.id, rear.id)
    }

    @Test
    fun ignoresEightNameSuffixBytesAndOpaqueMagic() {
        val data = index(row())
        for (i in 0..7) data[i] = 0xff.toByte()
        for (i in 53..60) data[i] = '/'.code.toByte() // row offset14..21 are intentionally unused.
        assertEquals("261106235859", ArchiveIndex.parse(data).single().name)
    }

    @Test
    fun acceptsEmptyIndexAndSkipsValidUnsupportedCameraOrCategory() {
        assertTrue(ArchiveIndex.parse(index()).isEmpty())
        assertTrue(ArchiveIndex.parse(index(row(camera = 3), row(category = 8))).isEmpty())
    }

    @Test
    fun rejectsShortHeaderTruncatedRowAndTrailingBytes() {
        fails { ArchiveIndex.parse(ByteArray(38)) }
        fails { ArchiveIndex.parse(index(row()).dropLast(1).toByteArray()) }
        fails { ArchiveIndex.parse(index(row()) + 0) }
    }

    @Test
    fun rejectsMismatchedAndExcessiveCountsBeforeReadingRows() {
        val mismatch = index(row()).apply { this[10] = 2 }
        fails { ArchiveIndex.parse(mismatch) }
        val excessive = ByteArray(39 + 32768 * 63).apply { this[9] = 0x80.toByte() }
        fails { ArchiveIndex.parse(excessive) }
    }

    @Test
    fun rejectsTraversalControlAndNonAsciiNamesEvenForUnsupportedRows() {
        for (name in
            listOf(
                "../106235859",
                "261106/35859",
                "261106\\35859",
                "261106\u000035859",
                "261106é35859",
            )) {
            fails { ArchiveIndex.parse(index(row(name = name))) }
            fails { ArchiveIndex.parse(index(row(name = name, camera = 3))) }
        }
    }

    @Test
    fun rejectsInvalidBcdAndImpossibleCalendarInStartTimestamp() {
        for (relativeOffset in 28..33) {
            fails {
                ArchiveIndex.parse(index(row()).apply { this[39 + relativeOffset] = 0xfa.toByte() })
            }
        }
        fails {
            ArchiveIndex.parse(
                index(row()).apply {
                    this[39 + 29] = 0x02
                    this[39 + 30] = 0x30
                }
            )
        }
        fails { ArchiveIndex.parse(index(row()).apply { this[39 + 31] = 0x24 }) }
        fails { ArchiveIndex.parse(index(row()).apply { this[39 + 32] = 0x60 }) }
    }

    @Test
    fun handlesValidLeapDay() {
        val leap =
            index(row()).apply {
                this[39 + 28] = 0x24
                this[39 + 29] = 0x02
                this[39 + 30] = 0x29
            }
        assertEquals("2024-02-29 23:58:59", ArchiveIndex.parse(leap).single().recordedAt)
    }

    @Test
    fun skipsZeroSizeReservedRowsBeforeInterpretingNamesAndDates() {
        assertTrue(ArchiveIndex.parse(index(ByteArray(63))).isEmpty())
        val reserved =
            ByteArray(63) { 0xff.toByte() }.apply { for (offset in 24..27) this[offset] = 0 }
        val parsed = ArchiveIndex.parse(index(reserved, row()))
        assertEquals(1, parsed.size)
        assertEquals("/tmp/SD0/NORMAL/261106235859.MP4", parsed.single().remotePath)
    }

    @Test
    fun ignoresUnusedEndTimestampWhenStartAndOriginalAreValid() {
        for (unused in listOf(0, 0xff)) {
            val data =
                index(row()).apply { for (offset in 34..39) this[39 + offset] = unused.toByte() }
            assertEquals("2026-11-06 23:58:59", ArchiveIndex.parse(data).single().recordedAt)
        }
    }

    private fun fails(action: () -> Unit) {
        try {
            action()
            fail("Malformed archive accepted")
        } catch (_: IOException) {}
    }

    private fun index(vararg rows: ByteArray): ByteArray =
        ByteBuffer.allocate(39 + rows.size * 63)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                put("FIXTURE!".toByteArray(Charsets.US_ASCII))
                put(1)
                putShort(rows.size.toShort())
                repeat(14) { putShort(0) }
                rows.forEach { put(it) }
            }
            .array()

    private fun row(
        name: String = "261106235859",
        camera: Int = 1,
        category: Int = 1,
        size: Long = 4,
    ): ByteArray =
        ByteBuffer.allocate(63)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                putShort(1)
                put(name.toByteArray(Charsets.UTF_8).copyOf(12))
                put(ByteArray(8))
                put(camera.toByte())
                put(category.toByte())
                putInt(size.toInt())
                repeat(2) { put(byteArrayOf(0x26, 0x11, 0x06, 0x23, 0x58, 0x59)) }
                put(0)
                putDouble(35.0)
                putDouble(139.0)
                putShort(0)
                putInt(0)
            }
            .array()
}
