package app.mazlens.data.recordings

import app.mazlens.core.archive.MAX_RECORDING_BYTES
import app.mazlens.core.archive.RecorderFile
import app.mazlens.core.archive.validateForStorage
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID

data class SavedRecording(val entry: RecorderFile, val file: File, val savedAt: Long)

/** App-private originals. A metadata rename is the publication point; partials are never listed. */
class RecordingStore(directory: File) {
    private val root: File

    private data class Partial(val entry: RecorderFile, var active: Boolean = true)

    private val partials = mutableMapOf<String, Partial>()

    init {
        if (Files.isSymbolicLink(directory.toPath()))
            throw IOException("Каталог записей не должен быть ссылкой")
        if (!directory.isDirectory && !directory.mkdirs())
            throw IOException("Не удалось открыть сохранённые записи")
        root = directory.canonicalFile
        checkRoot()
    }

    @Synchronized
    fun load(): List<SavedRecording> {
        checkRoot()
        return root
            .listFiles()
            .orEmpty()
            .filter { META_NAME.matches(it.name) }
            .mapNotNull { readSaved(it) }
            .sortedWith(compareByDescending<SavedRecording> { it.savedAt }.thenBy { it.entry.id })
    }

    /** Run once at app startup, before allowing any new download. */
    @Synchronized
    fun recoverAbandoned() {
        checkRoot()
        for (file in root.listFiles().orEmpty()) {
            val stale =
                PART_NAME.matches(file.name) ||
                    STAGE_NAME.matches(file.name) ||
                    (ORIGINAL_NAME.matches(file.name) &&
                        !child("${file.name.substringBefore('.')}.meta").exists())
            if (
                stale &&
                    !Files.isDirectory(file.toPath(), NOFOLLOW_LINKS) &&
                    !file.delete() &&
                    file.exists()
            )
                throw IOException("Не удалось удалить незавершённую загрузку")
        }
    }

    @Synchronized
    fun createPartial(entry: RecorderFile): File {
        checkRoot()
        entry.validateForStorage()
        if (entry.recorderId.isBlank()) throw IOException("Не удалось определить регистратор")
        checkSpace(entry.size)
        val partial = child("${entry.id}.${UUID.randomUUID()}.part")
        if (!partial.createNewFile()) throw IOException("Не удалось создать временный файл записи")
        try {
            makePrivate(partial)
        } catch (failure: IOException) {
            partial.delete()
            throw failure
        }
        partials[partial.path] = Partial(entry)
        return partial
    }

    @Synchronized
    fun commit(
        entry: RecorderFile,
        partial: File,
        publish: (() -> Unit) -> Boolean = { action ->
            action()
            true
        },
    ): SavedRecording {
        checkRoot()
        entry.validateForStorage()
        val owned = ownPartial(partial)
        if (!owned.active || owned.entry != entry)
            throw IOException("Временный файл относится к другой загрузке")
        requireRegular(partial)
        val length = partial.length()
        validateLength(entry, length)
        find(entry.id)?.let { previous ->
            if (!MessageDigest.isEqual(checksum(previous.file), checksum(partial)))
                throw IOException("Содержимое записи изменилось; прежний оригинал сохранён")
            if (!publish {}) throw IOException("Сохранение записи отменено")
            discard(partial)
            return previous
        }
        checkSpace(
            0
        ) // Finalization renames the owned partial; only small metadata needs extra space.
        val original = child("${entry.id}${if (entry.isVideo) ".MP4" else ".JPG"}")
        val metadata = child("${entry.id}.meta")
        rejectSymlink(original)
        rejectSymlink(metadata)
        val stagedMetadata = newStage("metadata")
        var installed = false
        var published = false
        try {
            // Flush the fully downloaded original before its same-directory rename.
            FileOutputStream(partial, true).use { it.fd.sync() }
            val saved = SavedRecording(entry, original, System.currentTimeMillis())
            FileOutputStream(stagedMetadata).use { stream ->
                val output = DataOutputStream(stream)
                output.writeInt(METADATA_MAGIC)
                output.writeInt(2)
                output.writeUTF(entry.name)
                output.writeInt(entry.camera)
                output.writeInt(entry.category)
                output.writeLong(entry.size)
                output.writeUTF(entry.recordedAt)
                output.writeUTF(entry.remotePath)
                output.writeUTF(entry.recorderId)
                output.writeLong(saved.savedAt)
                output.writeLong(length)
                output.flush()
                stream.fd.sync()
            }
            // Only the two same-directory renames are inside the owner's cancellation gate.
            // Potentially slow fsync/metadata preparation above remains cancellable.
            val accepted = publish {
                checkRoot()
                rejectSymlink(original)
                rejectSymlink(metadata)
                requireRegular(partial)
                if (partial.length() != length)
                    throw IOException("Размер загрузки изменился при сохранении")
                if (metadata.exists() && !metadata.delete())
                    throw IOException("Не удалось заменить повреждённое описание записи")
                move(partial, original)
                installed = true
                move(stagedMetadata, metadata)
                published = true
            }
            if (!accepted || !published) throw IOException("Сохранение записи отменено")
            owned.active = false
            return saved
        } finally {
            stagedMetadata.delete()
            if (installed && !published) original.delete()
        }
    }

    @Synchronized
    fun discard(partial: File) {
        checkRoot()
        val owned = ownPartial(partial)
        rejectSymlink(partial)
        if (!owned.active && partial.exists()) throw IOException("Временный файл уже закрыт")
        if (partial.exists()) {
            requireRegular(partial)
            if (!partial.delete()) throw IOException("Не удалось удалить временный файл записи")
        }
        owned.active = false
    }

    @Synchronized
    fun find(id: String): SavedRecording? {
        checkRoot()
        if (!ID.matches(id)) return null
        return readSaved(child("$id.meta"))
    }

    private fun readSaved(metadata: File): SavedRecording? =
        try {
            requireRegular(metadata)
            if (metadata.length() !in 1..MAX_METADATA_BYTES)
                throw IOException("Некорректное описание записи")
            DataInputStream(Files.newInputStream(metadata.toPath(), NOFOLLOW_LINKS)).use { input ->
                if (input.readInt() != METADATA_MAGIC)
                    throw IOException("Неизвестный формат описания записи")
                val version = input.readInt()
                if (version !in 1..2) throw IOException("Неизвестный формат описания записи")
                val entry =
                    RecorderFile(
                        input.readUTF(),
                        input.readInt(),
                        input.readInt(),
                        input.readLong(),
                        input.readUTF(),
                        input.readUTF(),
                        if (version >= 2) input.readUTF() else "",
                    )
                entry.validateForStorage()
                val savedAt = input.readLong()
                val length = input.readLong()
                if (savedAt <= 0 || input.read() != -1 || metadata.name != "${entry.id}.meta")
                    throw IOException("Повреждённое описание записи")
                validateLength(entry, length)
                val file = child("${entry.id}${if (entry.isVideo) ".MP4" else ".JPG"}")
                requireRegular(file)
                if (file.length() != length) throw IOException("Сохранённая запись обрезана")
                SavedRecording(entry, file, savedAt)
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun validateLength(entry: RecorderFile, length: Long) {
        if (length !in 1..MAX_RECORDING_BYTES || (entry.size > 0 && length != entry.size)) {
            throw IOException("Размер скачанной записи не совпадает с каталогом")
        }
    }

    private fun checksum(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var count = input.read(buffer)
            while (count != -1) {
                digest.update(buffer, 0, count)
                count = input.read(buffer)
            }
        }
        return digest.digest()
    }

    private fun checkSpace(bytes: Long) {
        if (root.usableSpace < bytes + SPACE_MARGIN)
            throw IOException("Недостаточно свободного места для записи")
    }

    private fun ownPartial(file: File): Partial {
        if (
            file.absoluteFile.parentFile != root ||
                file.absoluteFile.path != file.canonicalFile.path
        ) {
            throw IOException("Временный файл находится вне каталога записей")
        }
        return partials[file.absolutePath] ?: throw IOException("Неизвестный временный файл записи")
    }

    private fun checkRoot() {
        if (root.canonicalFile != root || !Files.isDirectory(root.toPath(), NOFOLLOW_LINKS)) {
            throw IOException("Каталог сохранённых записей недоступен")
        }
    }

    private fun child(name: String): File {
        if (name.contains('/') || name.contains('\\') || name == "." || name == "..")
            throw IOException("Некорректное имя локального файла")
        return File(root, name)
    }

    private fun rejectSymlink(file: File) {
        if (Files.isSymbolicLink(file.toPath())) throw IOException("Ссылка вместо файла записи")
    }

    private fun requireRegular(file: File) {
        rejectSymlink(file)
        if (
            file.canonicalFile.parentFile != root ||
                !Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)
        ) {
            throw IOException("Файл записи недоступен")
        }
    }

    private fun newStage(kind: String): File {
        val file = File.createTempFile(".$kind-", ".tmp", root)
        try {
            makePrivate(file)
            return file
        } catch (failure: IOException) {
            file.delete()
            throw failure
        }
    }

    private fun makePrivate(file: File) {
        if (
            !file.setReadable(false, false) ||
                !file.setWritable(false, false) ||
                !file.setReadable(true, true) ||
                !file.setWritable(true, true)
        )
            throw IOException("Не удалось ограничить доступ к записи")
    }

    private fun move(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), REPLACE_EXISTING)
        }
    }

    private companion object {
        const val METADATA_MAGIC = 0x44565231
        const val MAX_METADATA_BYTES = 4096L
        const val SPACE_MARGIN = 1024L * 1024
        val ID = Regex("[0-9a-f]{64}")
        val META_NAME = Regex("[0-9a-f]{64}\\.meta")
        val PART_NAME = Regex("[0-9a-f]{64}\\.[0-9a-f-]{36}\\.part")
        val STAGE_NAME = Regex("\\.metadata-.+\\.tmp")
        val ORIGINAL_NAME = Regex("[0-9a-f]{64}\\.(MP4|JPG)")
    }
}
