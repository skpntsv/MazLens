package app.mazlens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mazlens.app.MazLensViewModel
import app.mazlens.app.UiEvent
import app.mazlens.data.history.ReportExporter
import app.mazlens.ui.MazLensActions
import app.mazlens.ui.MazLensApp

class MainActivity : ComponentActivity() {
    private val model: MazLensViewModel by viewModels()
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            model.permissionResult()
        }
    private val documentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {
            model.finishExport(it)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            DisposableEffect(state.archive.downloadingId) {
                val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                val previous = window.attributes.flags and flag != 0
                if (state.archive.downloadingId != null) window.addFlags(flag)
                onDispose { if (!previous) window.clearFlags(flag) }
            }
            LaunchedEffect(model) {
                model.events.collect { event ->
                    when (event) {
                        is UiEvent.Message ->
                            Toast.makeText(this@MainActivity, event.text, Toast.LENGTH_SHORT).show()
                        is UiEvent.SaveBundle -> {
                            try {
                                model.markExportLaunched()
                                documentLauncher.launch(event.name)
                            } catch (_: Exception) {
                                model.finishExport(null)
                                model.message("Окно сохранения недоступно")
                            }
                        }
                        is UiEvent.OpenOriginal -> {
                            runCatching {
                                    val uri =
                                        FileProvider.getUriForFile(
                                            this@MainActivity,
                                            "$packageName.recordings",
                                            event.file,
                                        )
                                    val intent =
                                        if (event.share)
                                            Intent(Intent.ACTION_SEND)
                                                .setType(event.mime)
                                                .putExtra(Intent.EXTRA_STREAM, uri)
                                                .apply {
                                                    clipData =
                                                        ClipData.newRawUri(
                                                            "Запись регистратора",
                                                            uri,
                                                        )
                                                }
                                        else
                                            Intent(Intent.ACTION_VIEW)
                                                .setDataAndType(uri, event.mime)
                                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    startActivity(
                                        if (event.share)
                                            Intent.createChooser(
                                                intent,
                                                "Сохранить или отправить запись",
                                            )
                                        else intent
                                    )
                                }
                                .onFailure {
                                    model.message(
                                        "Нет приложения для открытия или отправки этого файла"
                                    )
                                }
                        }
                    }
                }
            }
            MazLensApp(
                state,
                MazLensActions(
                    onDiscover = {
                        if (model.hasPermission()) model.discover()
                        else permissionLauncher.launch(model.requiredPermissions())
                    },
                    onOpenWifi = { openSettings(Settings.ACTION_WIFI_SETTINGS) },
                    onOpenLocation = { openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS) },
                    onDisconnect = model::disconnect,
                    onConnect = model::connect,
                    onSelectMode = model::selectMode,
                    onAccess = model::runAccess,
                    onBaseline = model::runBaseline,
                    onPasswordChange = model::setPasswordInput,
                    onUseRecoveredPassword = model::useRecoveredPassword,
                    onPasswordLogin = model::runPasswordLogin,
                    onCancel = { model.cancelWork() },
                    onVideo = model::runVideo,
                    onStopVideo = model::stopVideo,
                    onExport = model::prepareExport,
                    onClearLog = model::clearLog,
                    onVideoCamera = model::selectVideoCamera,
                    onArchiveRefresh = model::refreshArchive,
                    onDownload = model::downloadRecording,
                    onReadSettings = model::readRecorderSettings,
                    onEditSetting = model::editRecorderSetting,
                    onApplySettings = model::applyRecorderSettings,
                    onDefaultSettings = model::defaultRecorderSettings,
                    onChangeRecorderPassword = model::changeRecorderPassword,
                    onDeleteRecording = model::deleteRecorderRecording,
                    onPlaySaved = model::playSaved,
                    onShareSaved = model::shareSaved,
                    onStopSaved = model::stopSaved,
                    onCopyRecord = { record ->
                        runCatching {
                                getSystemService(ClipboardManager::class.java)
                                    .setPrimaryClip(
                                        ClipData.newPlainText(
                                            "MazLens",
                                            ReportExporter.recordText(record),
                                        )
                                    )
                            }
                            .onFailure {
                                model.message(
                                    "Не удалось скопировать отчёт. Используйте экспорт в файл."
                                )
                            }
                    },
                ),
            )
        }
    }

    private fun openSettings(action: String) {
        runCatching { startActivity(Intent(action)) }
            .onFailure { model.message("Настройки недоступны") }
    }

    override fun onStart() {
        super.onStart()
        model.onForeground()
    }

    override fun onStop() {
        if (!isChangingConfigurations) model.onBackground()
        super.onStop()
    }
}
