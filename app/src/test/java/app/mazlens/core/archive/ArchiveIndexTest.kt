package app.mazlens.core.archive

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class ArchiveIndexTest {

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
    fun rejectsShortHeaderTruncatedRowAndTrailingBytes() {
        fails { ArchiveIndex.parse(ByteArray(38)) }
        fails { ArchiveIndex.parse(index(row()).dropLast(1).toByteArray()) }
        fails { ArchiveIndex.parse(index(row()) + 0) }
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
