package app.mazlens.core.archive

import java.io.IOException
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

data class RecorderFile(
    val name: String,
    val camera: Int,
    val category: Int,
    val size: Long,
    val recordedAt: String,
    val remotePath: String,
) {
    /** Remote path identifies the original independently of index ordering or date formatting. */
    val id: String
        get() =
            MessageDigest.getInstance("SHA-256")
                .digest(remotePath.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    val isVideo: Boolean
        get() = category !in 6..7

    val displayName: String
        get() = name + if (isVideo) ".MP4" else ".JPG"
}

internal const val MAX_RECORDING_BYTES = 0xffff_ffffL
internal val archiveDateFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)
private val safeName = Regex("[A-Za-z0-9_-]{12}")
private val categories =
    listOf("NORMAL", "EVENT", "MANUAL", "PARKING", "JUNCTION", "PICTURE", "MEMORIAL")

internal fun validateArchiveName(name: String) {
    if (!safeName.matches(name)) throw IOException("Некорректное имя записи в каталоге")
}

internal fun archiveRemotePath(name: String, camera: Int, category: Int): String {
    validateArchiveName(name)
    if (camera !in 1..2 || category !in 1..7) throw IOException("Неподдерживаемый тип записи")
    val cameraDirectory = if (camera == 2) "REAR/" else ""
    val extension = if (category in 6..7) ".JPG" else ".MP4"
    return "/tmp/SD0/$cameraDirectory${categories[category - 1]}/$name$extension"
}

/** Validate again at the disk boundary: a public data-class constructor is not a trust boundary. */
internal fun RecorderFile.validateForStorage() {
    if (size !in 0..MAX_RECORDING_BYTES) throw IOException("Некорректный размер записи")
    if (remotePath != archiveRemotePath(name, camera, category))
        throw IOException("Некорректный путь записи")
    try {
        if (!recordedAt.matches(Regex("20[0-9]{2}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}"))) {
            throw IOException("Некорректное время записи")
        }
        LocalDateTime.parse(recordedAt, archiveDateFormat)
    } catch (failure: DateTimeException) {
        throw IOException("Некорректное время записи", failure)
    }
}
