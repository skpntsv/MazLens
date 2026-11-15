package app.mazlens.core.archive

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.DateTimeException
import java.time.LocalDateTime

/**
 * Parses the recorder's 39-byte header and fixed-size archive entries. The signature and reserved
 * fields are opaque; supported versions are constrained by exact length. Valid rows for cameras
 * outside 1..2 or categories outside 1..7 are skipped, never guessed.
 */
object ArchiveIndex {
    const val PATH = "/tmp/SD0/SYSTEM/19DVR_FileListInfo.dat"
    private const val HEADER_BYTES = 39
    private const val ROW_BYTES = 63
    private const val MAX_ENTRIES = 32767

    @Throws(IOException::class)
    fun parse(bytes: ByteArray): List<RecorderFile> {
        if (bytes.size < HEADER_BYTES) throw IOException("Каталог записей обрезан")
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val count = input.getShort(9).toInt() and 0xffff
        if (count > MAX_ENTRIES) throw IOException("В каталоге слишком много записей")
        if (bytes.size != HEADER_BYTES + count * ROW_BYTES)
            throw IOException("Некорректная длина каталога записей")
        val result = ArrayList<RecorderFile>(count)
        repeat(count) { index ->
            val start = HEADER_BYTES + index * ROW_BYTES
            val size = input.getInt(start + 24).toLong() and MAX_RECORDING_BYTES
            // The recorder index can contain empty/reserved slots with no meaningful name or date.
            if (size == 0L) return@repeat
            val name = String(bytes, start + 2, 12, Charsets.US_ASCII)
            validateArchiveName(name)
            val camera = bytes[start + 22].toInt() and 0xff
            val category = bytes[start + 23].toInt() and 0xff
            val recordedAt = readDate(bytes, start + 28)
            // End timestamps at offsets 34..39 can be unset; the start timestamp identifies a clip.
            if (camera in 1..2 && category in 1..7) {
                result.add(
                    RecorderFile(
                        name,
                        camera,
                        category,
                        size,
                        recordedAt,
                        archiveRemotePath(name, camera, category),
                    )
                )
            }
        }
        val unique = LinkedHashMap<String, RecorderFile>()
        for (entry in result) {
            val previous = unique.putIfAbsent(entry.remotePath, entry)
            if (previous != null && previous != entry)
                throw IOException("В каталоге противоречивые данные одной записи")
        }
        return unique.values.toList()
    }

    private fun readDate(bytes: ByteArray, start: Int): String {
        fun bcd(offset: Int): Int {
            val value = bytes[start + offset].toInt() and 0xff
            val tens = value ushr 4
            val ones = value and 0xf
            if (tens > 9 || ones > 9) throw IOException("Некорректное BCD-время в каталоге")
            return tens * 10 + ones
        }
        try {
            return LocalDateTime.of(2000 + bcd(0), bcd(1), bcd(2), bcd(3), bcd(4), bcd(5))
                .format(archiveDateFormat)
        } catch (failure: DateTimeException) {
            throw IOException("Некорректная календарная дата в каталоге", failure)
        }
    }
}
