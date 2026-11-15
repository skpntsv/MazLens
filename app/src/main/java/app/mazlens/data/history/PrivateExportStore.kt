package app.mazlens.data.history

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Owns the private snapshot while Android's document picker is open. */
class PrivateExportStore(private val directory: File) {
    /** Retains only the snapshot still owned by the document picker after process recreation. */
    fun removeAbandoned(keepId: String?) {
        val keepName = keepId?.let { target(it).name }
        for (file in directory.listFiles().orEmpty()) {
            if (!file.isFile || file.name == keepName) continue
            if (!file.name.matches(Regex("[A-Za-z0-9_-]{1,80}\\.zip(?:\\.tmp)?"))) continue
            if (!file.delete()) throw IOException("Не удалось удалить старый снимок отчёта")
        }
    }

    private fun target(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Invalid export ID" }
        return File(directory, "$id.zip")
    }

    fun prepare(id: String, bytes: ByteArray) {
        val destination = target(id)
        if (!directory.isDirectory && !directory.mkdirs())
            throw IOException("Не удалось открыть папку экспорта")
        if (destination.exists()) throw IOException("Снимок уже существует")
        val temporary = File(directory, "$id.zip.tmp")
        try {
            FileOutputStream(temporary).use {
                it.write(bytes)
                it.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath())
            }
        } finally {
            temporary.delete()
        }
    }

    fun writeTo(id: String, sink: () -> OutputStream?) {
        var failure: Throwable? = null
        try {
            val snapshot = target(id).readBytes()
            sink()?.use { it.write(snapshot) } ?: throw IOException("Файл недоступен")
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                discard(id)
            } catch (cleanup: IOException) {
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    fun discard(id: String) {
        val file = target(id)
        if (file.exists() && !file.delete()) throw IOException("Не удалось удалить снимок")
    }
}
