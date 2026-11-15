package app.mazlens.data.history

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

/** One durable private file per operation; never relies on a live Activity. */
class HistoryStore(
    private val directory: File,
    private val maxRecords: Int = 50,
    private val maxBytes: Long = 8L * 1024 * 1024,
) {
    @Volatile
    var warnings: List<String> = emptyList()
        private set

    init {
        require(maxRecords > 0 && maxBytes > 0)
        if (!directory.isDirectory && !directory.mkdirs())
            throw IOException("Не удалось открыть историю")
    }

    @Synchronized
    fun save(record: AttemptRecord) {
        if (!record.id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
            throw IOException("Некорректный идентификатор проверки")
        val bytes = RecordCodec.encode(record).toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > maxBytes) throw IOException("Результат превышает лимит истории")
        val target = File(directory, "${record.id}.json")
        if (record.status == AttemptStatus.RUNNING && target.exists()) {
            val previous =
                runCatching { RecordCodec.decode(JSONObject(target.readText(Charsets.UTF_8))) }
                    .getOrNull()
            if (previous != null && previous.status != AttemptStatus.RUNNING) return
        }
        atomicWrite(target, bytes)
        prune(record.id)
    }

    private fun prune(keepId: String) {
        val decoded = load().associateBy { "${it.id}.json" }
        val files =
            directory.listFiles().orEmpty().filter {
                it.name.endsWith(".json") || it.name.endsWith(".json.tmp")
            }
        var count = files.count { it.name.endsWith(".json") }
        var total = files.sumOf { it.length() }
        val ordered =
            files.sortedWith(
                compareBy<File> {
                        if (it.name.endsWith(".tmp")) 0 else if (it.name !in decoded) 1 else 2
                    }
                    .thenBy { decoded[it.name]?.timestamp ?: it.lastModified() }
            )
        for (file in ordered) {
            if (count <= maxRecords && total <= maxBytes) break
            if (file.name == "$keepId.json") continue
            val size = file.length()
            if (!file.delete())
                throw IOException("Результат сохранён, но старый файл истории не удалось удалить")
            total -= size
            if (file.name.endsWith(".json")) count--
        }
    }

    @Synchronized
    fun load(): List<AttemptRecord> {
        val issues = mutableListOf<String>()
        val result =
            directory
                .listFiles()
                .orEmpty()
                .filter { it.name.endsWith(".json") }
                .mapNotNull { file ->
                    try {
                        if (file.length() > maxBytes) throw IOException("too large")
                        val record = RecordCodec.decode(JSONObject(file.readText(Charsets.UTF_8)))
                        if (
                            file.name != "${record.id}.json" ||
                                !record.id.matches(Regex("[A-Za-z0-9_-]{1,80}"))
                        )
                            throw IOException("id mismatch")
                        record
                    } catch (_: Exception) {
                        issues.add("Один файл истории повреждён и не показан.")
                        null
                    }
                }
                .sortedWith(
                    compareByDescending<AttemptRecord> { it.timestamp }.thenByDescending { it.id }
                )
        warnings = issues
        return result
    }

    @Synchronized
    fun recoverInterrupted(): List<AttemptRecord> {
        load()
            .filter { it.status == AttemptStatus.RUNNING }
            .forEach {
                save(
                    it.copy(
                        status = AttemptStatus.INTERRUPTED,
                        summary = "Проверка прервалась при закрытии приложения.",
                        trace = it.trace + "\nПроцесс завершился до сохранения результата.\n",
                    )
                )
            }
        return load()
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val pending = File(directory, target.name + ".tmp")
        FileOutputStream(pending).use {
            it.write(bytes)
            it.fd.sync()
        }
        try {
            Files.move(
                pending.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
