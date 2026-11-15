package app.mazlens.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import app.mazlens.core.protocol.RecorderProbe
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import javax.net.SocketFactory

/** Captures one verified P2P route for an entire probe/player operation. */
class P2pSocketProvider(context: Context, val interfaceName: String) : SocketFactory() {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val network =
        connectivity.allNetworks.firstOrNull {
            connectivity.getLinkProperties(it)?.interfaceName == interfaceName &&
                interfaceName.isNotBlank()
        }
    private val localAddress =
        if (network == null && interfaceName.isNotBlank()) {
            NetworkInterface.getByName(interfaceName)
                ?.inetAddresses
                ?.let { Collections.list(it) }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress }
        } else null
    val routeDescription: String =
        when {
            network != null -> "Сеть Android: $interfaceName"
            localAddress != null -> "Интерфейс $interfaceName, адрес ${localAddress.hostAddress}"
            else -> "Маршрут Wi-Fi Direct не найден"
        }
    val socketMaker = RecorderProbe.SocketMaker { createSocket() }

    override fun createSocket(): Socket {
        network?.let {
            return it.socketFactory.createSocket()
        }
        val address =
            localAddress
                ?: throw IOException("Нет адреса интерфейса Wi-Fi Direct. Подключитесь заново.")
        return Socket().also { socket ->
            try {
                socket.bind(InetSocketAddress(address, 0))
            } catch (error: IOException) {
                socket.close()
                throw error
            }
        }
    }

    private fun connected(
        host: InetAddress,
        port: Int,
        localHost: InetAddress? = null,
        localPort: Int = 0,
    ): Socket {
        val socket =
            if (localHost == null) createSocket()
            else {
                if (localAddress != null && localHost != localAddress)
                    throw IOException("Адрес не относится к Wi-Fi Direct")
                if (network != null)
                    network.socketFactory.createSocket().also {
                        it.bind(InetSocketAddress(localHost, localPort))
                    }
                else Socket().also { it.bind(InetSocketAddress(localHost, localPort)) }
            }
        try {
            socket.connect(InetSocketAddress(host, port), 4000)
            return socket
        } catch (error: IOException) {
            socket.close()
            throw error
        }
    }

    override fun createSocket(host: String, port: Int) =
        connected(InetAddress.getByName(host), port)

    override fun createSocket(host: InetAddress, port: Int) = connected(host, port)

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int) =
        connected(InetAddress.getByName(host), port, localHost, localPort)

    override fun createSocket(
        host: InetAddress,
        port: Int,
        localHost: InetAddress,
        localPort: Int,
    ) = connected(host, port, localHost, localPort)
}

object NetworkSnapshot {
    fun capture(
        context: Context,
        name: String,
        owner: String,
        iface: String,
        route: String,
    ): String {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val vpn =
            cm.allNetworks.any {
                cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ==
                    true
            }
        val addresses =
            runCatching {
                    NetworkInterface.getByName(iface)
                        ?.inetAddresses
                        ?.let { Collections.list(it) }
                        ?.filterIsInstance<Inet4Address>()
                        ?.joinToString { it.hostAddress ?: "" }
                }
                .getOrNull()
                .orEmpty()
        return "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n" +
            "Телефон: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "Регистратор: $name\nВладелец группы: $owner\nРоль телефона: клиент\n" +
            "Интерфейс: $iface\nАдреса телефона: $addresses\nVPN активен: $vpn\nМаршрут: $route"
    }
}
