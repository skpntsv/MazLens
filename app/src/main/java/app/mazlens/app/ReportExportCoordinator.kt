package app.mazlens.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import app.mazlens.data.history.AttemptRecord
import app.mazlens.data.history.PrivateExportStore
import app.mazlens.data.history.ReportExporter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Keeps a report snapshot alive while Android's document picker is open. */
internal class ReportExportCoordinator(
    private val app: Application,
    private val savedState: SavedStateHandle,
    private val scope: CoroutineScope,
    private val onExporting: (Boolean) -> Unit,
    private val onSaveDocument: (String) -> Unit,
    private val onMessage: (String) -> Unit,
) {
    private var exportEventSent = false
    private val exports = PrivateExportStore(File(app.filesDir, "exports"))
    private val pendingAtStart =
        savedState.get<String>("exportId")?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) }
    private val cleanup =
        scope.async(Dispatchers.IO) { runCatching { exports.removeAbandoned(pendingAtStart) } }

    fun resume() {
        val id = savedState.get<String>("exportId")?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) }
        if (id != null && savedState.get<Boolean>("exportLaunched") != true && !exportEventSent) {
            exportEventSent = true
            onSaveDocument(savedState.get<String>("exportName") ?: "MazLens-report.zip")
        }
    }

    fun prepare(records: List<AttemptRecord>, log: String) {
        onExporting(true)
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val name =
            "MazLens-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(now)) + ".zip"
        scope.launch {
            var handedToPicker = false
            try {
                val result =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            cleanup.await().getOrThrow()
                            exports.prepare(id, ReportExporter.capture(records, log, now))
                        }
                    }
                result
                    .onSuccess {
                        savedState["exportId"] = id
                        savedState["exportName"] = name
                        savedState["exportLaunched"] = false
                        exportEventSent = true
                        handedToPicker = true
                        onSaveDocument(name)
                    }
                    .onFailure {
                        onExporting(false)
                        onMessage("Не удалось подготовить отчёт")
                    }
            } finally {
                if (!handedToPicker)
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { exports.discard(id) }
                    }
            }
        }
    }

    fun markLaunched() {
        savedState["exportLaunched"] = true
    }

    fun finish(uri: Uri?) {
        val id =
            savedState.get<String>("exportId")?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) }
                ?: return
        scope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        if (uri != null)
                            exports.writeTo(id) { app.contentResolver.openOutputStream(uri, "wt") }
                        else exports.discard(id)
                    }
                }
            savedState.remove<String>("exportId")
            savedState.remove<String>("exportName")
            savedState.remove<Boolean>("exportLaunched")
            exportEventSent = false
            onExporting(false)
            if (uri != null)
                onMessage(if (result.isSuccess) "Отчёт сохранён" else "Не удалось сохранить отчёт")
        }
    }
}
