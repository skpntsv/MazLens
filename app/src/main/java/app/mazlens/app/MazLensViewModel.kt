package app.mazlens.app

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.mazlens.core.archive.ArchiveIndex
import app.mazlens.core.archive.RecorderFile
import app.mazlens.core.connection.WifiDirectController
import app.mazlens.core.network.NetworkSnapshot
import app.mazlens.core.network.P2pSocketProvider
import app.mazlens.core.operation.OperationGate
import app.mazlens.core.operation.PlaybackRequestGate
import app.mazlens.core.protocol.AccessProbe
import app.mazlens.core.protocol.RecorderErrors
import app.mazlens.core.protocol.RecorderProbe
import app.mazlens.core.protocol.RecorderSession
import app.mazlens.data.history.AttemptKind
import app.mazlens.data.history.AttemptRecord
import app.mazlens.data.history.AttemptStatus
import app.mazlens.data.history.HistoryStore
import app.mazlens.data.recordings.RecordingStore
import app.mazlens.video.RtspProbe
import app.mazlens.video.SavedPlaybackController
import app.mazlens.video.VideoPreviewController
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent

    data class SaveBundle(val name: String) : UiEvent

    data class OpenOriginal(val file: File, val mime: String, val share: Boolean) : UiEvent
}

/** Coordinates features; composables never own a socket, file or connection. */
class MazLensViewModel(application: Application, savedState: SavedStateHandle) :
    AndroidViewModel(application), WifiDirectController.Listener {
    private val app = application
    private val preferences = app.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    private val _state =
        MutableStateFlow(
            MazLensUiState(
                accessMode = preferences.getInt("accessMode", 0).coerceIn(0, 3),
                log = preferences.getString("diagnosticLog", "").orEmpty(),
                exporting = savedState.get<String>("exportId") != null,
            )
        )
    val state = _state.asStateFlow()
    private val eventChannel = Channel<UiEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()
    private val main = Handler(Looper.getMainLooper())
    private val wifi = WifiDirectController(app, this)
    private val preview = VideoPreviewController(app)
    private val savedPlayback = SavedPlaybackController(app, ::diagnostic)
    private val playbackRequests = PlaybackRequestGate()
    private val recordings by lazy { RecordingStore(File(app.filesDir, "recordings")) }
    private val gate = OperationGate()
    private val devices = mutableMapOf<String, WifiP2pDevice>()
    private val unsavedRecords = ConcurrentHashMap<String, AttemptRecord>()
    @Volatile private var store: HistoryStore? = null
    @Volatile private var connectionEpoch = 0L
    @Volatile private var activeProbe: AutoCloseable? = null
    @Volatile private var activeVideoApi: RecorderSession? = null
    private var current: Operation? = null
    private var foreground = false
    private var cleared = false
    private val exports =
        ReportExportCoordinator(
            app,
            savedState,
            viewModelScope,
            onExporting = { exporting -> _state.update { it.copy(exporting = exporting) } },
            onSaveDocument = { name -> eventChannel.trySend(UiEvent.SaveBundle(name)) },
            onMessage = ::message,
        )

    private class Operation(
        val ticket: OperationGate.Ticket,
        var record: AttemptRecord,
        val connection: ConnectionUiState,
    ) {
        val modelName = if (connection.name.contains("C9KA")) "C9KA_V6_580" else "C9K1_V6_580"
        var route: P2pSocketProvider? = null
        var videoApi: RecorderSession? = null
        var archiveEntry: RecorderFile? = null
        var cameraRear = false
        var checkingPassword = false
        @Volatile var disconnectReason: String? = null
        var playerStarted = false
        var switchingCamera = false
        @Volatile var switchingProbe: AutoCloseable? = null
        var firstFrame = false
        @Volatile var finalizing = false
    }

    private data class WorkResult(val record: AttemptRecord, val rtsp: RtspProbe.Outcome? = null)

    init {
        wifi.restoreSelection(preferences.getString("selectedRecorderAddress", "").orEmpty())
        refreshPrerequisites()
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val history = HistoryStore(File(app.filesDir, "history"))
                        history to history.recoverInterrupted()
                    }
                }
            result
                .onSuccess { (history, records) ->
                    store = history
                    _state.update {
                        it.copy(
                            loadingHistory = false,
                            history = records,
                            latestAccess =
                                records.firstOrNull { r ->
                                    r.kind == AttemptKind.ACCESS || r.kind == AttemptKind.BASELINE
                                },
                            storageWarning = history.warnings.joinToString(" "),
                        )
                    }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            loadingHistory = false,
                            storageWarning =
                                "Не удалось открыть историю. Освободите место и перезапустите приложение.",
                        )
                    }
                }
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                    recordings.recoverAbandoned()
                    File(app.cacheDir, "archive-index")
                        .listFiles()
                        .orEmpty()
                        .filter { it.name.startsWith("index-") && it.name.endsWith(".part") }
                        .forEach { it.delete() }
                }
                .onFailure {
                    diagnostic("Не удалось удалить временные файлы: ${it.javaClass.simpleName}")
                }
            reloadSaved()
        }
    }

    fun onForeground() {
        foreground = true
        refreshPrerequisites()
        wifi.start()
        if (hasPermission()) wifi.refresh()
        exports.resume()
    }

    fun onBackground() {
        foreground = false
        stopSaved()
        cancelActive("Приложение свёрнуто")
        wifi.stop()
    }

    fun hasPermission(): Boolean =
        requiredPermissions().all {
            app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )

    fun permissionResult() {
        refreshPrerequisites()
        if (hasPermission()) discover()
        else message("Разрешите поиск устройств поблизости, чтобы найти регистратор.")
    }

    private fun refreshPrerequisites() {
        val enabled = app.getSystemService(WifiManager::class.java)?.isWifiEnabled == true
        val lm = app.getSystemService(LocationManager::class.java)
        val location =
            if (Build.VERSION.SDK_INT >= 28) lm?.isLocationEnabled == true
            else
                runCatching {
                        lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                            lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
                    }
                    .getOrDefault(false)
        _state.update {
            it.copy(
                connection =
                    it.connection.copy(
                        supported = wifi.supported(),
                        permissionGranted = hasPermission(),
                        wifiEnabled = enabled,
                        locationEnabled = location,
                    )
            )
        }
    }

    fun discover() {
        if (gate.active()) return
        refreshPrerequisites()
        wifi.discover()
    }

    fun connect(address: String) {
        if (gate.active()) return
        devices[address]?.let {
            preferences.edit { putString("selectedRecorderAddress", address) }
            wifi.connect(it)
        }
    }

    fun disconnect() {
        cancelActive("Wi-Fi Direct отключён")
        wifi.disconnect()
    }

    override fun status(message: String) {
        _state.update {
            it.copy(
                connection =
                    it.connection.copy(status = message, connecting = wifi.operationPending())
            )
        }
        diagnostic(message)
    }

    override fun peers(devices: List<WifiP2pDevice>) {
        this.devices.clear()
        devices.forEach { this.devices[it.deviceAddress.orEmpty()] = it }
        _state.update {
            it.copy(
                connection =
                    it.connection.copy(
                        peers =
                            devices.map { d ->
                                PeerUi(
                                    d.deviceName.orEmpty().ifBlank { "Устройство Wi-Fi Direct" },
                                    d.deviceAddress.orEmpty(),
                                    d.status,
                                )
                            }
                    )
            )
        }
    }

    override fun connection(name: String, address: String?, networkInterface: String?) {
        val host = address.orEmpty()
        val iface = networkInterface.orEmpty()
        val before = _state.value.connection
        val changed =
            before.address != host || before.networkInterface != iface || before.name != name
        if (changed) {
            connectionEpoch++
            cancelActive("Соединение с регистратором изменилось")
        }
        _state.update {
            it.copy(
                connection =
                    it.connection.copy(
                        connected = host.isNotEmpty(),
                        name = name,
                        address = host,
                        networkInterface = iface,
                        connecting = wifi.operationPending(),
                        status =
                            if (host.isNotEmpty()) "Подключено к регистратору"
                            else "Нет подключения к регистратору",
                    ),
                video =
                    if (changed)
                        VideoUiState(
                            message =
                                if (host.isEmpty()) "Подключитесь к регистратору для проверки видео"
                                else "Видеопоток этого регистратора ещё не проверялся"
                        )
                    else it.video,
                archive =
                    if (changed)
                        it.archive.copy(
                            files = emptyList(),
                            recorderName = "",
                            loading = false,
                            message = "Обновите список этого регистратора",
                            problem = null,
                        )
                    else it.archive,
                passwordCheck = if (changed) null else it.passwordCheck,
            )
        }
    }

    override fun diagnostic(message: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { diagnostic(message) }
            return
        }
        if (cleared) return
        Log.i("MazLens", message)
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date())
        val log = (_state.value.log + "$stamp  $message\n").takeLast(64000)
        preferences.edit { putString("diagnosticLog", log) }
        _state.update { it.copy(log = log) }
    }

    fun clearLog() {
        preferences.edit { remove("diagnosticLog") }
        _state.update { it.copy(log = "") }
        message("Журнал очищен")
    }

    fun selectMode(index: Int) {
        if (gate.active()) return
        val value = index.coerceIn(0, 3)
        preferences.edit { putInt("accessMode", value) }
        _state.update { it.copy(accessMode = value) }
    }

    fun setPasswordInput(value: String) {
        if (!gate.active())
            _state.update { it.copy(passwordInput = value.take(128), passwordCheck = null) }
    }

    fun useRecoveredPassword() {
        _state.value.recoveredPasswordCandidate?.let(::setPasswordInput)
    }

    fun runPasswordLogin() {
        val state = _state.value
        if (!state.passwordValid) {
            message("Введите пароль длиной от 8 до 31 символа")
            return
        }
        runOperation(AttemptKind.ACCESS, "Вход с паролем", state.passwordInput)
    }

    fun runAccess() =
        runOperation(
            AttemptKind.ACCESS,
            listOf(
                "Сессия без пароля",
                "Сессия с моделью",
                "Сессия с пустым паролем",
                "Чтение поля w.pass",
            )[_state.value.accessMode],
        )

    fun runBaseline() = runOperation(AttemptKind.BASELINE, "Базовое чтение настроек")

    fun runVideo() {
        val state = _state.value
        val password =
            VideoCredentials.select(state.passwordInput, state.connection.name, state.history)
        if (password == null) {
            _state.update {
                it.copy(
                    video =
                        it.video.copy(
                            problem =
                                ProblemExplanation(
                                    "Укажите пароль регистратора",
                                    "Для просмотра камер нужен пароль этого регистратора.",
                                    "Откройте «Подключение» и укажите пароль. Затем вернитесь к камерам.",
                                )
                        )
                )
            }
            return
        }
        stopSaved()
        runOperation(
            AttemptKind.VIDEO,
            if (state.videoCameraRear) "Видео задней камеры" else "Видео передней камеры",
            password,
        )
    }

    fun selectVideoCamera(rear: Boolean) {
        if (_state.value.videoCameraRear == rear) return
        val op = current
        if (
            gate.active() &&
                (op?.record?.kind != AttemptKind.VIDEO ||
                    !op.playerStarted ||
                    op.finalizing ||
                    op.switchingCamera)
        )
            return
        _state.update { it.copy(videoCameraRear = rear) }
        if (op != null) switchVideoCamera(op, rear)
    }

    private fun switchVideoCamera(op: Operation, rear: Boolean) {
        val api = op.videoApi ?: return
        val route = op.route ?: return
        // Keep the owned API session and heartbeat reader; replace only the RTSP player.
        op.switchingCamera = true
        preview.stop()
        op.firstFrame = false
        op.record =
            op.record.copy(
                title = "Видео передней и задней камер",
                trace =
                    op.record.trace +
                        "\nPLAYER: switching to ${if(rear)"vin_1" else "vin_0"} in current session\n",
            )
        _state.update {
            it.copy(
                video =
                    it.video.copy(
                        player = null,
                        checking = true,
                        previewing = false,
                        firstFrame = false,
                        problem = null,
                        message = "Переключаю камеру…",
                    )
            )
        }
        diagnostic("Переключение камеры внутри текущей сессии")
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        if (op.finalizing || !gate.canContinue(op.ticket, connectionEpoch))
                            throw java.io.IOException("Переключение отменено")
                        val selected = api.selectCamera(rear)
                        if (!selected.success) throw java.io.IOException(selected.summary)
                        if (op.finalizing || !gate.canContinue(op.ticket, connectionEpoch))
                            throw java.io.IOException("Переключение отменено")
                        val probe = RtspProbe(route.socketMaker, Consumer { diagnostic(it) })
                        op.switchingProbe = probe
                        try {
                            if (op.finalizing || !gate.canContinue(op.ticket, connectionEpoch))
                                probe.close()
                            probe.query(op.connection.address, 554, 8000)
                        } finally {
                            probe.close()
                            if (op.switchingProbe === probe) op.switchingProbe = null
                        }
                    }
                }
            if (
                current?.ticket != op.ticket ||
                    op.finalizing ||
                    !gate.canContinue(op.ticket, connectionEpoch)
            )
                return@launch
            op.switchingCamera = false
            if (!foreground) {
                cancelActive("Приложение свёрнуто")
                return@launch
            }
            val reason = op.disconnectReason
            if (reason != null) {
                finishVideo(op, reason, false)
                return@launch
            }
            val answer =
                result.getOrElse {
                    finishVideo(op, it.message?.take(220) ?: "Не удалось переключить камеру", false)
                    return@launch
                }
            op.record =
                op.record.copy(
                    trace = op.record.trace + "\nRTSP after camera switch:\n" + answer.trace,
                    response = answer.response,
                )
            _state.update {
                it.copy(
                    video =
                        it.video.copy(
                            serverResponded = answer.serverResponded,
                            descriptionReceived = answer.descriptionReceived,
                            videoAdvertised = answer.videoAdvertised,
                        )
                )
            }
            if (answer.videoAdvertised) {
                op.cameraRear = rear
                startPreview(op)
            } else finishVideo(op, answer.summary, false)
        }
    }

    fun refreshArchive() = runArchive(null)

    fun downloadRecording(id: String) {
        val state = _state.value
        if (state.archive.recorderName != state.connection.name) return
        val entry = state.archive.files.firstOrNull { it.id == id } ?: return
        runArchive(entry)
    }

    private fun runArchive(entry: RecorderFile?) {
        val state = _state.value
        val password =
            VideoCredentials.select(state.passwordInput, state.connection.name, state.history)
        if (password == null) {
            _state.update {
                it.copy(
                    archive =
                        it.archive.copy(
                            problem =
                                ProblemExplanation(
                                    "Укажите пароль регистратора",
                                    "Для чтения списка и скачивания записей нужен пароль этого регистратора.",
                                    "Откройте «Подключение» и укажите пароль. Затем вернитесь к записям.",
                                )
                        )
                )
            }
            return
        }
        stopSaved()
        runOperation(
            AttemptKind.ARCHIVE,
            if (entry == null) "Список записей" else "Скачивание ${entry.displayName}",
            password,
            entry,
        )
    }

    private fun runOperation(
        kind: AttemptKind,
        title: String,
        password: String? = null,
        entry: RecorderFile? = null,
    ) {
        val state = _state.value
        if (
            !state.connection.connected ||
                state.connection.connecting ||
                gate.active() ||
                state.loadingHistory ||
                state.archive.loadingSaved ||
                store == null
        )
            return
        val ticket = gate.begin(connectionEpoch) ?: return
        val op =
            Operation(
                ticket,
                AttemptRecord(
                    UUID.randomUUID().toString(),
                    System.currentTimeMillis(),
                    kind,
                    title,
                    "Проверка выполняется",
                    AttemptStatus.RUNNING,
                ),
                state.connection,
            )
        op.archiveEntry = entry
        op.cameraRear = state.videoCameraRear
        op.checkingPassword = kind == AttemptKind.ACCESS && password != null
        val mode =
            if (password != null) AccessProbe.Mode.PASSWORD_SESSION
            else AccessProbe.Mode.entries[state.accessMode]
        current = op
        _state.update {
            it.copy(
                busy = true,
                operationKind = kind,
                operationLabel = title,
                passwordCheck = if (op.checkingPassword) null else it.passwordCheck,
                video =
                    if (kind == AttemptKind.VIDEO)
                        VideoUiState(
                            checking = true,
                            message = "Открываю сессию регистратора для видео…",
                        )
                    else it.video,
                archive =
                    if (kind == AttemptKind.ARCHIVE)
                        it.archive.copy(
                            loading = entry == null,
                            downloadingId = entry?.id,
                            receivedBytes = 0,
                            totalBytes = entry?.size ?: 0,
                            message = title,
                            problem = null,
                        )
                    else it.archive,
            )
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { execute(op, mode, password) }
            if (current?.ticket != ticket) return@launch
            op.record = result.record
            if (kind == AttemptKind.VIDEO && result.rtsp != null) {
                val r = result.rtsp
                _state.update {
                    if (op.ticket.connectionEpoch == connectionEpoch)
                        it.copy(
                            video =
                                it.video.copy(
                                    serverResponded = r.serverResponded,
                                    descriptionReceived = r.descriptionReceived,
                                    videoAdvertised = r.videoAdvertised,
                                    message = r.summary,
                                )
                        )
                    else it
                }
                if (r.videoAdvertised && gate.canContinue(ticket, connectionEpoch) && foreground) {
                    startPreview(op)
                    return@launch
                }
                if (result.record.status == AttemptStatus.RUNNING) {
                    finishVideo(op, "Проверка отменена", false)
                    return@launch
                }
            }
            complete(op, result.record)
        }
    }

    private fun execute(op: Operation, mode: AccessProbe.Mode, password: String?): WorkResult {
        var record = op.record
        var rtsp: RtspProbe.Outcome? = null
        try {
            val history = store ?: error("history unavailable")
            history.save(record)
            reloadHistory()
            if (!gate.canContinue(op.ticket, connectionEpoch)) return saveCancelled(op, record)
            val route = P2pSocketProvider(app, op.connection.networkInterface)
            op.route = route
            record =
                record.copy(
                    networkSnapshot =
                        NetworkSnapshot.capture(
                            app,
                            op.connection.name,
                            op.connection.address,
                            op.connection.networkInterface,
                            route.routeDescription,
                        )
                )
            val log = Consumer<String> { diagnostic(it) }
            when (record.kind) {
                AttemptKind.BASELINE -> {
                    val probe = RecorderProbe(route.socketMaker, log)
                    activeProbe = probe
                    if (!gate.canContinue(op.ticket, connectionEpoch)) probe.close()
                    val answer = probe.query(op.connection.address, 7878, 8787, 8000)
                    record =
                        record.copy(
                            summary = answer.description(),
                            response = answer.raw(),
                            trace = "SEND {\"token\":0,\"msg_id\":3}\nRECV ${answer.raw()}\n",
                            passwordCandidate =
                                if (answer.hasCandidate()) answer.passwordValue() else "",
                            status =
                                when {
                                    answer.success() -> AttemptStatus.SUCCESS
                                    answer.state() == "REJECTED" -> AttemptStatus.REJECTED
                                    else -> AttemptStatus.FAILED
                                },
                        )
                }
                AttemptKind.ACCESS -> {
                    val probe = AccessProbe(route.socketMaker, log)
                    activeProbe = probe
                    if (!gate.canContinue(op.ticket, connectionEpoch)) probe.close()
                    val model = op.modelName
                    val answer =
                        if (password != null)
                            probe.queryWithPassword(
                                op.connection.address,
                                7878,
                                8787,
                                model,
                                password,
                                8000,
                            )
                        else probe.query(op.connection.address, 7878, 8787, mode, model, 8000)
                    val status =
                        AccessConclusion.classify(answer.response, mode, answer.sessionOpened)
                    val candidate =
                        answer.password
                            .takeUnless { it.isBlank() || it.matches(Regex("[*•●]+")) }
                            .orEmpty()
                    record =
                        record.copy(
                            summary = answer.summary,
                            trace = answer.trace,
                            response = answer.response,
                            passwordCandidate = candidate,
                            status = status,
                        )
                }
                AttemptKind.VIDEO -> {
                    val api = newSession(op, route, log)
                    if (!gate.canContinue(op.ticket, connectionEpoch)) api.close()
                    val model = op.modelName
                    val auth =
                        api.open(
                            op.connection.address,
                            7878,
                            8787,
                            model,
                            requireNotNull(password),
                            8000,
                        )
                    record =
                        record.copy(
                            summary = auth.summary,
                            trace = auth.trace,
                            response = auth.response,
                            status =
                                if (auth.code != null && auth.code != 0) AttemptStatus.REJECTED
                                else AttemptStatus.FAILED,
                        )
                    if (auth.opened && gate.canContinue(op.ticket, connectionEpoch)) {
                        val camera = api.selectCamera(op.cameraRear)
                        if (!camera.success)
                            record =
                                record.copy(
                                    summary = camera.summary,
                                    status = AttemptStatus.REJECTED,
                                )
                        else {
                            _state.update {
                                if (gate.canContinue(op.ticket, connectionEpoch))
                                    it.copy(
                                        video =
                                            it.video.copy(
                                                message = "Сессия открыта. Проверяю видеопоток…"
                                            )
                                    )
                                else it
                            }
                            val probe = RtspProbe(route.socketMaker, log)
                            activeProbe = probe
                            if (!gate.canContinue(op.ticket, connectionEpoch)) probe.close()
                            val answer = probe.query(op.connection.address, 554, 8000)
                            rtsp = answer
                            record =
                                record.copy(
                                    summary = answer.summary,
                                    trace = auth.trace + "\nRTSP:\n" + answer.trace,
                                    response = answer.response,
                                    status =
                                        when {
                                            answer.cancelled -> AttemptStatus.CANCELLED
                                            answer.authRequired -> AttemptStatus.REJECTED
                                            answer.videoAdvertised -> AttemptStatus.RUNNING
                                            else -> AttemptStatus.FAILED
                                        },
                                )
                        }
                        api.disconnectReason?.let { reason ->
                            rtsp = null
                            record =
                                record.copy(
                                    summary = RecorderErrors.disconnected(reason),
                                    status = AttemptStatus.FAILED,
                                )
                        }
                    }
                }
                AttemptKind.ARCHIVE -> {
                    val api = newSession(op, route, log)
                    if (!gate.canContinue(op.ticket, connectionEpoch)) api.close()
                    val model = op.modelName
                    val auth =
                        api.open(
                            op.connection.address,
                            7878,
                            8787,
                            model,
                            requireNotNull(password),
                            8000,
                        )
                    record =
                        record.copy(
                            summary = auth.summary,
                            trace = auth.trace,
                            response = auth.response,
                            status =
                                if (auth.code != null && auth.code != 0) AttemptStatus.REJECTED
                                else AttemptStatus.FAILED,
                        )
                    if (auth.opened && gate.canContinue(op.ticket, connectionEpoch))
                        record = readArchive(op, api, record)
                }
            }
            if (!gate.canContinue(op.ticket, connectionEpoch))
                record =
                    record.copy(
                        status = AttemptStatus.CANCELLED,
                        summary = "Проверка отменена. Полный обмен сохранён.",
                    )
        } catch (error: Exception) {
            record =
                record.copy(
                    status =
                        if (gate.canContinue(op.ticket, connectionEpoch)) AttemptStatus.FAILED
                        else AttemptStatus.CANCELLED,
                    summary =
                        if (gate.canContinue(op.ticket, connectionEpoch))
                            op.disconnectReason
                                ?: when (error) {
                                    is java.io.IOException ->
                                        error.message?.take(220) ?: "Ошибка чтения регистратора"
                                    else -> "Проверка прервана: ${error.javaClass.simpleName}"
                                }
                        else "Проверка отменена",
                    trace = record.trace + "\nPRIVATE ERROR: " + Log.getStackTraceString(error),
                )
        } finally {
            runCatching { activeProbe?.close() }
            activeProbe = null
            if (
                op.record.kind == AttemptKind.ARCHIVE ||
                    (op.record.kind == AttemptKind.VIDEO &&
                        (rtsp?.videoAdvertised != true ||
                            !gate.canContinue(op.ticket, connectionEpoch)))
            )
                record = finishVideoApi(op, record)
            if (op.record.kind == AttemptKind.ARCHIVE) reloadSaved()
        }
        persist(record)
        return WorkResult(record, rtsp)
    }

    private fun newSession(
        op: Operation,
        route: P2pSocketProvider,
        log: Consumer<String>,
    ): RecorderSession {
        val api =
            RecorderSession(route.socketMaker, log) { reason ->
                val readable = RecorderErrors.disconnected(reason)
                op.disconnectReason = readable
                main.post {
                    if (current?.ticket == op.ticket && !op.finalizing && op.playerStarted) {
                        finishVideo(op, readable, false)
                    }
                }
            }
        op.videoApi = api
        activeVideoApi = api
        return api
    }

    private fun readArchive(
        op: Operation,
        api: RecorderSession,
        record: AttemptRecord,
    ): AttemptRecord {
        val entry = op.archiveEntry
        var partial: File? = null
        try {
            if (entry == null) {
                val folder = File(app.cacheDir, "archive-index").apply { mkdirs() }
                partial = File.createTempFile("index-", ".part", folder)
                api.download(ArchiveIndex.PATH, partial, 39L + 32767L * 63L)
                if (!gate.canContinue(op.ticket, connectionEpoch))
                    throw java.io.IOException("Чтение списка отменено")
                val files =
                    ArchiveIndex.parse(partial.readBytes()).sortedByDescending { it.recordedAt }
                _state.update {
                    if (gate.canContinue(op.ticket, connectionEpoch))
                        it.copy(
                            archive =
                                it.archive.copy(files = files, recorderName = op.connection.name)
                        )
                    else it
                }
                return record.copy(
                    summary = "Записей в архиве: ${files.size}",
                    status = AttemptStatus.SUCCESS,
                )
            }
            partial = recordings.createPartial(entry)
            var lastUpdate = 0L
            api.download(
                entry.remotePath,
                partial,
                if (entry.size > 0) entry.size else 128L * 1024 * 1024,
            ) { received, total ->
                val now = System.nanoTime()
                if (now - lastUpdate > 100_000_000L || received == total) {
                    lastUpdate = now
                    _state.update {
                        if (gate.canContinue(op.ticket, connectionEpoch))
                            it.copy(
                                archive =
                                    it.archive.copy(receivedBytes = received, totalBytes = total)
                            )
                        else it
                    }
                }
            }
            if (!gate.canContinue(op.ticket, connectionEpoch))
                throw java.io.IOException("Скачивание отменено")
            _state.update {
                if (gate.canContinue(op.ticket, connectionEpoch))
                    it.copy(archive = it.archive.copy(message = "Сохраняю оригинал…"))
                else it
            }
            recordings.commit(entry, partial) { publication ->
                gate.publishIfCurrent(op.ticket, { connectionEpoch }, publication)
            }
            return record.copy(
                summary = "Оригинал сохранён на телефоне: ${entry.displayName}",
                status = AttemptStatus.SUCCESS,
            )
        } finally {
            partial?.let { file ->
                if (entry == null) file.delete() else runCatching { recordings.discard(file) }
            }
        }
    }

    private fun reloadSaved() {
        runCatching { recordings.load() }
            .onSuccess { saved ->
                _state.update {
                    it.copy(archive = it.archive.copy(saved = saved, loadingSaved = false))
                }
            }
            .onFailure {
                _state.update {
                    it.copy(
                        archive =
                            it.archive.copy(
                                loadingSaved = false,
                                message =
                                    "Не удалось открыть сохранённые записи. Освободите место на телефоне.",
                            )
                    )
                }
            }
    }

    fun playSaved(id: String) {
        if (gate.active()) return
        stopSaved()
        val request = playbackRequests.begin()
        viewModelScope.launch {
            val saved =
                withContext(Dispatchers.IO) { runCatching { recordings.find(id) }.getOrNull() }
            if (!playbackRequests.canStart(request, foreground, gate.active())) return@launch
            if (saved == null) {
                message("Сохранённый файл не найден")
                return@launch
            }
            if (!saved.entry.isVideo) {
                eventChannel.trySend(UiEvent.OpenOriginal(saved.file, "image/jpeg", false))
                return@launch
            }
            runCatching {
                    val player =
                        savedPlayback.start(saved.file) {
                            _state.update {
                                it.copy(
                                    archive =
                                        it.archive.copy(
                                            player = null,
                                            playingName = "",
                                            playingId = "",
                                            playingLabel = "",
                                        )
                                )
                            }
                            message("Не удалось воспроизвести сохранённый файл")
                        }
                    _state.update {
                        it.copy(
                            archive =
                                it.archive.copy(
                                    player = player,
                                    playingName = saved.entry.displayName,
                                    playingId = saved.entry.id,
                                    playingLabel =
                                        "${saved.entry.recordedAt} · ${if(saved.entry.camera==2)"Задняя камера" else "Передняя камера"}",
                                )
                        )
                    }
                }
                .onFailure { message("Не удалось открыть сохранённое видео") }
        }
    }

    fun stopSaved() {
        playbackRequests.cancel()
        savedPlayback.stop()
        _state.update {
            it.copy(
                archive =
                    it.archive.copy(
                        player = null,
                        playingName = "",
                        playingId = "",
                        playingLabel = "",
                    )
            )
        }
    }

    fun shareSaved(id: String) {
        if (gate.active()) return
        val request = playbackRequests.begin()
        viewModelScope.launch {
            val saved =
                withContext(Dispatchers.IO) { runCatching { recordings.find(id) }.getOrNull() }
            if (!playbackRequests.canStart(request, foreground, gate.active())) return@launch
            if (saved == null) message("Сохранённый файл не найден")
            else
                eventChannel.trySend(
                    UiEvent.OpenOriginal(
                        saved.file,
                        if (saved.entry.isVideo) "video/mp4" else "image/jpeg",
                        true,
                    )
                )
        }
    }

    private fun saveCancelled(op: Operation, record: AttemptRecord): WorkResult {
        val r =
            record.copy(
                status = AttemptStatus.CANCELLED,
                summary = "Проверка отменена до отправки запроса",
            )
        persist(r)
        return WorkResult(r)
    }

    private fun startPreview(op: Operation) {
        val route =
            op.route
                ?: run {
                    finishVideo(op, "Маршрут Wi-Fi Direct недоступен", false)
                    return
                }
        op.videoApi?.disconnectReason?.let {
            finishVideo(op, RecorderErrors.disconnected(it), false)
            return
        }
        try {
            op.playerStarted = true
            val player =
                preview.start(
                    "rtsp://${op.connection.address}/live",
                    route,
                    object : VideoPreviewController.Listener {
                        override fun firstFrame() {
                            if (
                                current?.ticket != op.ticket ||
                                    !gate.canContinue(op.ticket, connectionEpoch)
                            )
                                return
                            op.firstFrame = true
                            op.record =
                                op.record.copy(
                                    summary = "Первый кадр получен. Предпросмотр работает.",
                                    trace =
                                        op.record.trace +
                                            "\nPLAYER: rendered first frame (${if(op.cameraRear)"vin_1" else "vin_0"})\n",
                                )
                            _state.update {
                                it.copy(
                                    video =
                                        it.video.copy(
                                            checking = false,
                                            previewing = true,
                                            firstFrame = true,
                                            message = "Видео доступно. Предпросмотр — до 30 секунд.",
                                        )
                                )
                            }
                            viewModelScope.launch(Dispatchers.IO) { persist(op.record) }
                        }

                        override fun failure(summary: String, detail: String) {
                            if (current?.ticket != op.ticket || op.finalizing) return
                            op.record =
                                op.record.copy(
                                    trace = op.record.trace + "\nPLAYER ERROR: $detail\n"
                                )
                            finishVideo(op, summary, false)
                        }

                        override fun ended() {
                            if (current?.ticket == op.ticket)
                                finishVideo(
                                    op,
                                    if (op.firstFrame) "Видео получено. Предпросмотр завершён."
                                    else "Плеер завершился без кадра",
                                    op.firstFrame,
                                )
                        }
                    },
                )
            _state.update {
                it.copy(
                    video =
                        it.video.copy(
                            player = player,
                            checking = true,
                            previewing = true,
                            message = "Описание получено. Ожидаю первый кадр…",
                        )
                )
            }
        } catch (error: Exception) {
            op.record =
                op.record.copy(
                    trace = op.record.trace + "\nPLAYER ERROR: " + Log.getStackTraceString(error)
                )
            finishVideo(op, "Не удалось запустить видеоплеер", false)
        }
    }

    fun stopVideo() {
        val op = current
        if (op?.switchingCamera == true) cancelActive("Просмотр остановлен")
        else if (op?.record?.kind == AttemptKind.VIDEO && op.playerStarted)
            finishVideo(op, "Просмотр остановлен", op.firstFrame, userStopped = true)
        else cancelWork("Предпросмотр остановлен")
    }

    fun cancelWork(reason: String = "Проверка отменена") {
        if (current == null) {
            if (wifi.operationPending()) wifi.disconnect()
            return
        }
        cancelActive(reason)
    }

    private fun cancelActive(reason: String) {
        val op = current ?: return
        gate.cancel(op.ticket)
        runCatching { activeProbe?.close() }
        runCatching { activeVideoApi?.close() }
        if (op.playerStarted)
            finishVideo(
                op,
                if (op.firstFrame) "Видео получено. $reason." else reason,
                op.firstFrame,
            )
    }

    private fun finishVideo(
        op: Operation,
        summary: String,
        success: Boolean,
        userStopped: Boolean = false,
    ) {
        if (current?.ticket != op.ticket || op.finalizing) return
        op.finalizing = true
        preview.stop()
        runCatching { op.switchingProbe?.close() }
        val record =
            op.record.copy(
                summary = summary,
                status =
                    VideoConclusion.status(
                        success,
                        gate.canContinue(op.ticket, connectionEpoch),
                        userStopped,
                    ),
            )
        _state.update {
            if (op.ticket.connectionEpoch == connectionEpoch)
                it.copy(
                    video =
                        it.video.copy(
                            player = null,
                            checking = false,
                            previewing = false,
                            message = summary,
                        )
                )
            else it
        }
        viewModelScope.launch {
            val finalRecord =
                withContext(Dispatchers.IO) { finishVideoApi(op, record).also { persist(it) } }
            complete(op, finalRecord)
        }
    }

    private fun finishVideoApi(op: Operation, record: AttemptRecord): AttemptRecord {
        val api = op.videoApi ?: return record
        val cleanup =
            runCatching { api.finish() }
                .exceptionOrNull()
                ?.let { "API cleanup: ${it.javaClass.simpleName}\n" }
                .orEmpty()
        op.videoApi = null
        if (activeVideoApi === api) activeVideoApi = null
        return record.copy(
            trace = record.trace + "\nПолный обмен сессии:\n" + api.snapshotTrace() + cleanup
        )
    }

    private fun complete(op: Operation, record: AttemptRecord) {
        if (current?.ticket != op.ticket) return
        gate.finish(op.ticket)
        current = null
        _state.update {
            it.copy(
                busy = false,
                operationKind = null,
                operationLabel = "",
                latestAccess =
                    if (record.kind == AttemptKind.ACCESS || record.kind == AttemptKind.BASELINE)
                        record
                    else it.latestAccess,
                passwordCheck =
                    if (
                        op.checkingPassword &&
                            op.ticket.connectionEpoch == connectionEpoch &&
                            record.status != AttemptStatus.CANCELLED
                    )
                        record
                    else it.passwordCheck,
                video =
                    if (
                        record.kind == AttemptKind.VIDEO &&
                            op.ticket.connectionEpoch == connectionEpoch
                    )
                        it.video.copy(
                            checking = false,
                            previewing = false,
                            player = null,
                            message = record.summary,
                            problem = ProblemExplainer.forRecord(record),
                        )
                    else it.video,
                archive =
                    if (
                        record.kind == AttemptKind.ARCHIVE &&
                            op.ticket.connectionEpoch == connectionEpoch
                    )
                        it.archive.copy(
                            loading = false,
                            downloadingId = null,
                            message = record.summary,
                            problem = ProblemExplainer.forRecord(record),
                        )
                    else it.archive.copy(loading = false, downloadingId = null),
            )
        }
    }

    private fun persist(record: AttemptRecord) {
        try {
            store?.save(record)
            unsavedRecords.remove(record.id)
            reloadHistory()
        } catch (_: Exception) {
            unsavedRecords[record.id] = record
            unsavedRecords.values
                .sortedByDescending { it.timestamp }
                .drop(50)
                .forEach { unsavedRecords.remove(it.id) }
            _state.update {
                it.copy(
                    history =
                        (listOf(record) + it.history.filter { old -> old.id != record.id })
                            .sortedByDescending { r -> r.timestamp }
                            .take(50),
                    storageWarning =
                        "Результат пока только в памяти. Сохраните отчёт перед закрытием приложения.",
                )
            }
        }
    }

    private fun reloadHistory() {
        val history = store ?: return
        val records =
            (unsavedRecords.values + history.load().filter { !unsavedRecords.containsKey(it.id) })
                .sortedByDescending { it.timestamp }
                .take(50)
        _state.update {
            it.copy(
                history = records,
                latestAccess =
                    records.firstOrNull { r ->
                        r.kind == AttemptKind.ACCESS || r.kind == AttemptKind.BASELINE
                    },
                storageWarning =
                    if (history.warnings.isNotEmpty()) history.warnings.joinToString(" ")
                    else it.storageWarning,
            )
        }
    }

    fun prepareExport() {
        val state = _state.value
        if (state.exporting || state.loadingHistory || gate.active()) return
        exports.prepare(state.history.toList(), state.log)
    }

    fun markExportLaunched() = exports.markLaunched()

    fun finishExport(uri: Uri?) = exports.finish(uri)

    fun message(text: String) {
        eventChannel.trySend(UiEvent.Message(text))
    }

    override fun onCleared() {
        cleared = true
        current?.let {
            gate.cancel(it.ticket)
            runCatching { it.switchingProbe?.close() }
        }
        runCatching { activeProbe?.close() }
        runCatching { activeVideoApi?.close() }
        preview.stop()
        savedPlayback.stop()
        wifi.close(false)
        main.removeCallbacksAndMessages(null)
    }
}
