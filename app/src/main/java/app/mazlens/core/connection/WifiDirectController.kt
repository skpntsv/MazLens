package app.mazlens.core.connection

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import java.util.Locale

/** Wi-Fi Direct discovery and group verification; never issues recorder commands. */
@Suppress("DEPRECATION")
class WifiDirectController(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun status(message: String)

        @JvmSuppressWildcards fun peers(devices: List<WifiP2pDevice>)

        fun connection(
            name: String,
            address: String?,
            networkInterface: String?,
            recorderId: String = "",
        )

        fun diagnostic(message: String)
    }

    private val handler = Handler(context.mainLooper)
    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager?
    private var channel: WifiP2pManager.Channel? = null
    private var registered = false
    private var closed = false
    private val attempts = ConnectionAttempt()
    private var cancelling = false
    private var disconnecting = false
    private var selectedAddress = ""
    private var refreshGeneration = 0
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent ?: return
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val network =
                            intent.getParcelableExtra<NetworkInfo>(
                                WifiP2pManager.EXTRA_NETWORK_INFO
                            )
                        listener.diagnostic(
                            "P2P broadcast: connection=${network?.detailedState ?: "unknown"}"
                        )
                        refresh()
                    }
                    WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                        listener.diagnostic(
                            "P2P broadcast: discovery=${intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1)}"
                        )
                    }
                    WifiP2pManager.ACTION_WIFI_P2P_REQUEST_RESPONSE_CHANGED -> {
                        if (Build.VERSION.SDK_INT >= 33) {
                            listener.diagnostic(
                                "P2P broadcast: pairing response=${intent.getIntExtra(WifiP2pManager.EXTRA_REQUEST_RESPONSE, -1)}"
                            )
                        }
                    }
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        if (
                            intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                                WifiP2pManager.WIFI_P2P_STATE_DISABLED
                        ) {
                            attempts.invalidate()
                            cancelling = false
                            disconnecting = false
                            ++refreshGeneration
                            listener.connection("", "", "")
                            listener.status(
                                "Wi-Fi Direct выключен. Включите Wi-Fi в настройках телефона."
                            )
                        } else {
                            listener.diagnostic("P2P broadcast: enabled")
                            refresh()
                        }
                    }
                }
            }
        }

    init {
        if (manager != null && supported()) initialize()
    }

    fun supported(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)

    private fun initialize() {
        channel =
            manager!!.initialize(context, context.mainLooper) {
                if (!closed) {
                    channel = null
                    attempts.invalidate()
                    cancelling = false
                    disconnecting = false
                    ++refreshGeneration
                    listener.connection("", "", "")
                    listener.status("Служба Wi-Fi Direct отключилась. Откройте приложение заново.")
                }
            }
    }

    fun restoreSelection(address: String?) {
        selectedAddress = address ?: ""
    }

    fun operationPending(): Boolean = attempts.pending()

    // Below API 33 the filter contains only protected system broadcasts. The
    // newer action is added only on the branch with an explicit receiver flag.
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun start() {
        if (!supported() || manager == null || channel == null) {
            listener.status("На этом устройстве Wi-Fi Direct недоступен.")
            return
        }
        if (!registered) {
            val filter =
                IntentFilter().apply {
                    addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                    addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                    addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                    addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
                }
            if (Build.VERSION.SDK_INT >= 33) {
                filter.addAction(WifiP2pManager.ACTION_WIFI_P2P_REQUEST_RESPONSE_CHANGED)
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else context.registerReceiver(receiver, filter)
            registered = true
        }
    }

    fun stop() {
        if (registered) {
            context.unregisterReceiver(receiver)
            registered = false
        }
    }

    fun discover() {
        if (!available()) return
        if (attempts.pending()) {
            listener.status(
                "Сначала дождитесь завершения подключения или нажмите «Отключить Wi-Fi Direct»."
            )
            return
        }
        try {
            listener.diagnostic("discoverPeers: запрос к Android")
            manager!!.discoverPeers(
                channel,
                action("Поиск устройств запущен. Выберите регистратор в списке."),
            )
            requestPeers()
        } catch (_: SecurityException) {
            permissionError()
        }
    }

    private fun requestPeers() {
        if (!available()) return
        try {
            manager!!.requestPeers(channel) { devices ->
                if (!closed) {
                    val result = ArrayList(devices.deviceList)
                    listener.diagnostic("requestPeers: найдено устройств=${result.size}")
                    result.sortBy { it.deviceName ?: "" }
                    listener.peers(result)
                }
            }
        } catch (_: SecurityException) {
            permissionError()
        }
    }

    fun connect(device: WifiP2pDevice) {
        if (!available()) return
        val attempt = attempts.begin(SystemClock.elapsedRealtime() + 30000)
        if (attempt == 0) {
            listener.diagnostic(
                "connect: повторное нажатие пропущено; предыдущая операция ещё выполняется."
            )
            return
        }
        selectedAddress = device.deviceAddress ?: ""
        ++refreshGeneration
        listener.connection("", "", "")
        listener.diagnostic(
            "connect #$attempt: peer=${device.deviceName}, mac=${device.deviceAddress}" +
                ", status=${device.status}, isGO=${device.isGroupOwner}, WPS-PBC=${device.wpsPbcSupported()}" +
                ", WPS-display=${device.wpsDisplaySupported()}, WPS-keypad=${device.wpsKeypadSupported()}"
        )
        listener.status(
            "Подключение к ${device.deviceName}… Подтвердите запрос Android, если он появится."
        )
        val config =
            WifiP2pConfig().apply {
                deviceAddress = device.deviceAddress
                wps.setup = WpsInfo.PBC
                groupOwnerIntent = 0
            }
        try {
            // STOP_DISCOVERY flushes the peer cache in Android's InactiveState.
            // CONNECT stops discovery itself after validating the selected peer.
            listener.diagnostic(
                "connect #$attempt: прямой CONNECT без предварительного STOP_DISCOVERY"
            )
            manager!!.connect(
                channel,
                config,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (!closed && attempts.isCurrent(attempt) && !cancelling) {
                            listener.diagnostic(
                                "connect #$attempt: Android принял запрос; это ещё не установленное соединение."
                            )
                            listener.status(
                                "Android начал сопряжение. Ожидаем соединение до 30 секунд…"
                            )
                            refresh()
                        }
                    }

                    override fun onFailure(reason: Int) {
                        if (!closed && !cancelling && attempts.finish(attempt)) {
                            ++refreshGeneration
                            listener.diagnostic("connect #$attempt: onFailure=$reason")
                            listener.status("Подключение не удалось: ${reasonMessage(reason)}")
                        }
                    }
                },
            )
            scheduleTimeout(attempt)
        } catch (_: SecurityException) {
            permissionError()
        }
    }

    private fun scheduleTimeout(attempt: Int) {
        val remaining = maxOf(0, attempts.deadline() - SystemClock.elapsedRealtime())
        listener.diagnostic("connect #$attempt: до отмены осталось $remaining мс")
        handler.postDelayed(
            {
                if (!closed && attempts.isCurrent(attempt) && !cancelling)
                    cancelTimedOutAttempt(attempt)
            },
            remaining,
        )
    }

    private fun cancelTimedOutAttempt(attempt: Int) {
        cancelling = true
        ++refreshGeneration
        listener.status("За 30 секунд сопряжение не завершилось. Отменяю запрос Android…")
        listener.diagnostic("connect #$attempt: timeout -> cancelConnect")
        if (channel == null) {
            attempts.invalidate()
            cancelling = false
            listener.status("Служба Wi-Fi Direct отключилась.")
            return
        }
        try {
            manager!!.cancelConnect(
                channel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (closed || !attempts.finish(attempt)) return
                        cancelling = false
                        listener.connection("", "", "")
                        listener.diagnostic("cancelConnect #$attempt: onSuccess")
                        listener.status("Сопряжение отменено по таймауту. Можно повторить поиск.")
                    }

                    override fun onFailure(reason: Int) {
                        if (closed || !attempts.isCurrent(attempt)) return
                        listener.diagnostic("cancelConnect #$attempt: onFailure=$reason")
                        listener.status(
                            "Android не отменил сопряжение: ${reasonMessage(reason)} Нажмите «Отключить Wi-Fi Direct»."
                        )
                    }
                },
            )
        } catch (_: SecurityException) {
            permissionError()
        }
    }

    fun refresh() {
        if (!available()) return
        val generation = ++refreshGeneration
        try {
            manager!!.requestConnectionInfo(channel) connectionInfo@{ info ->
                if (closed || cancelling || generation != refreshGeneration) return@connectionInfo
                listener.diagnostic(
                    "requestConnectionInfo: formed=${info != null && info.groupFormed}" +
                        ", phoneIsGO=${info != null && info.isGroupOwner}" +
                        ", ownerIP=${info?.groupOwnerAddress?.hostAddress ?: "none"}"
                )
                if (info == null || !info.groupFormed) {
                    listener.connection("", "", "")
                    return@connectionInfo
                }
                if (info.isGroupOwner) {
                    attempts.invalidate()
                    listener.connection("", "", "")
                    listener.status(
                        "Телефон стал владельцем группы. Отключитесь и подключитесь заново: владельцем должен быть регистратор."
                    )
                    return@connectionInfo
                }
                try {
                    manager.requestGroupInfo(channel) groupInfo@{ group ->
                        if (closed || cancelling || generation != refreshGeneration)
                            return@groupInfo
                        listener.diagnostic(
                            "requestGroupInfo: " +
                                if (group == null) "null"
                                else "network=${group.networkName}, interface=${group.`interface`}"
                        )
                        if (group == null || group.owner == null) {
                            listener.connection("", "", "")
                            return@groupInfo
                        }
                        val owner = group.owner
                        val name = owner.deviceName ?: ""
                        val networkName = group.networkName ?: ""
                        if (
                            !acceptsOwner(selectedAddress, owner.deviceAddress, name, networkName)
                        ) {
                            listener.connection("", "", "")
                            listener.status(
                                "Уже есть другое соединение Wi-Fi Direct. Выберите свой регистратор или отключитесь."
                            )
                            return@groupInfo
                        }
                        attempts.invalidate()
                        val address =
                            if (info.groupOwnerAddress == null) "192.168.42.1"
                            else info.groupOwnerAddress.hostAddress
                        listener.connection(
                            if (name.isEmpty()) networkName else name,
                            address,
                            group.`interface`,
                            owner.deviceAddress.orEmpty().lowercase(Locale.ROOT),
                        )
                    }
                } catch (_: SecurityException) {
                    permissionError()
                }
            }
        } catch (_: SecurityException) {
            permissionError()
        }
    }

    fun disconnect() {
        if (!available()) return
        if (disconnecting) {
            listener.diagnostic("disconnect: повтор отмены пропущен.")
            return
        }
        attempts.invalidate()
        val operation = attempts.begin()
        cancelling = true
        disconnecting = true
        ++refreshGeneration
        listener.connection("", "", "")
        listener.status("Отменяю сопряжение и отключаю Wi-Fi Direct…")
        try {
            manager!!.cancelConnect(
                channel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        removeAfterCancel(operation, true, "onSuccess")
                    }

                    override fun onFailure(reason: Int) {
                        removeAfterCancel(operation, false, "onFailure=$reason")
                    }
                },
            )
        } catch (_: SecurityException) {
            permissionError()
        }
    }

    private fun removeAfterCancel(
        operation: Int,
        cancellationSucceeded: Boolean,
        cancellationResult: String,
    ) {
        if (closed || !attempts.isCurrent(operation)) return
        listener.diagnostic(
            "disconnect #$operation: cancelConnect $cancellationResult; removeGroup"
        )
        try {
            manager!!.removeGroup(
                channel,
                object : WifiP2pManager.ActionListener {
                    private fun finish(result: String, success: Boolean) {
                        if (closed || !attempts.isCurrent(operation)) return
                        disconnecting = false
                        listener.diagnostic("disconnect #$operation: removeGroup $result")
                        if (!attempts.completeCleanup(operation, cancellationSucceeded, success)) {
                            listener.status(
                                "Android не подтвердил ни отмену, ни отключение. Повторите «Отключить Wi-Fi Direct» или выключите и включите Wi-Fi телефона."
                            )
                            return
                        }
                        cancelling = false
                        listener.connection("", "", "")
                        listener.status(
                            if (success) "Android принял запрос отключения Wi-Fi Direct."
                            else
                                "Android не удалил группу ($result). Если поиск занят, выключите и включите Wi-Fi телефона."
                        )
                        refresh()
                    }

                    override fun onSuccess() {
                        finish("onSuccess", true)
                    }

                    override fun onFailure(reason: Int) {
                        finish("onFailure=$reason", false)
                    }
                },
            )
        } catch (_: SecurityException) {
            permissionError()
        }
    }

    private fun available(): Boolean {
        if (closed) return false
        if (!supported() || manager == null || channel == null) {
            listener.status("Служба Wi-Fi Direct недоступна. Закройте и откройте приложение.")
            return false
        }
        return true
    }

    private fun action(success: String): WifiP2pManager.ActionListener =
        object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (!closed) listener.status(success)
            }

            override fun onFailure(reason: Int) {
                if (!closed) {
                    listener.diagnostic("discoverPeers: onFailure=$reason")
                    listener.status(reasonMessage(reason))
                }
            }
        }

    private fun permissionError() {
        attempts.invalidate()
        cancelling = false
        disconnecting = false
        ++refreshGeneration
        listener.connection("", "", "")
        listener.status(
            "Не хватает разрешения на поиск устройств. Нажмите «Найти регистратор» и разрешите доступ."
        )
    }

    fun close() {
        closed = true
        stop()
        handler.removeCallbacksAndMessages(null)
        if (Build.VERSION.SDK_INT >= 27) channel?.close()
    }

    companion object {
        private fun reasonMessage(code: Int): String =
            when (code) {
                WifiP2pManager.P2P_UNSUPPORTED -> "Телефон не поддерживает Wi-Fi Direct."
                WifiP2pManager.BUSY ->
                    "Служба Wi-Fi Direct выполняет другую операцию (BUSY, код 2)."
                else ->
                    "Android сообщил общую ошибку Wi-Fi Direct (код $code), без подробной причины."
            }

        @JvmStatic
        fun acceptsOwner(
            selectedAddress: String,
            ownerAddress: String?,
            name: String,
            networkName: String,
        ): Boolean {
            val selected =
                selectedAddress.isNotEmpty() &&
                    selectedAddress.equals(ownerAddress, ignoreCase = true)
            val known =
                name.contains("C9K1") ||
                    name.contains("C9KA") ||
                    networkName.contains("C9K1") ||
                    networkName.contains("C9KA")
            return if (selectedAddress.isEmpty()) known else selected
        }
    }
}
