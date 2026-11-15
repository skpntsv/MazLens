package app.mazlens.data.history

import app.mazlens.BuildConfig
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

object ReportExporter {
    fun capture(records: List<AttemptRecord>, diagnosticLog: String, timestamp: Long): ByteArray {
        val root =
            JSONObject()
                .put("formatVersion", 1)
                .put("appVersion", BuildConfig.VERSION_NAME)
                .put("createdAt", timestamp)
                .put(
                    "attempts",
                    JSONArray().apply { records.forEach { put(RecordCodec.encode(it)) } },
                )
                .put("diagnosticLog", diagnosticLog)
        val text = buildString {
            append("MazLens ${BuildConfig.VERSION_NAME}\nОтчёт: ")
                .append(date(timestamp))
                .append("\nПроверок: ")
                .append(records.size)
                .append('\n')
            records.forEach { append("\n====================\n").append(recordText(it)) }
            append("\nЖУРНАЛ ПОДКЛЮЧЕНИЯ\n").append(diagnosticLog)
        }
        return ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                listOf("report.txt" to text, "report.json" to root.toString(2)).forEach {
                    (name, body) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(body.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
            out.toByteArray()
        }
    }

    fun recordText(r: AttemptRecord): String = buildString {
        append(r.title)
            .append("\n")
            .append(date(r.timestamp))
            .append(" · ")
            .append(r.status.name)
            .append("\nВерсия: ")
            .append(r.appVersion)
        append("\n").append(r.summary)
        if (r.passwordCandidate.isNotEmpty()) append("\nw.pass: ").append(r.passwordCandidate)
        append("\n\nСЕТЬ\n").append(r.networkSnapshot)
        append("\n\nОТВЕТ\n").append(r.response)
        append("\n\nОБМЕН\n").append(r.trace).append('\n')
    }

    private fun date(time: Long) =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date(time))
}
