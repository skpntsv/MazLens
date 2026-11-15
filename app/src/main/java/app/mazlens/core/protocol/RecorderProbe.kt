package app.mazlens.core.protocol

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import org.json.JSONException
import org.json.JSONObject

/** One manual, unauthenticated settings request; no login or state-changing command. */
class RecorderProbe(private val factory: SocketMaker, private val log: Consumer<String>) :
    AutoCloseable {
    fun interface SocketMaker {
        @Throws(IOException::class) fun create(): Socket
    }

    private val sockets = ArrayList<Socket>()
    @Volatile private var cancelled = false

    @Throws(IOException::class)
    private fun open(host: String, port: Int, timeoutMs: Int): Socket {
        val socket = factory.create()
        synchronized(sockets) {
            if (cancelled) {
                socket.close()
                throw SocketException("Запрос отменён.")
            }
            sockets.add(socket)
        }
        socket.connect(InetSocketAddress(host, port), minOf(timeoutMs, 4000))
        return socket
    }

    @Throws(IOException::class)
    fun query(host: String, commandPort: Int, dataPort: Int, timeoutMs: Int): ProbeResult {
        try {
            log.accept("TCP $host:$commandPort: подключение")
            val command = open(host, commandPort, timeoutMs)
            try {
                open(host, dataPort, timeoutMs)
                log.accept("Канал данных $dataPort открыт.")
            } catch (error: IOException) {
                if (cancelled) throw error
                log.accept(
                    "Канал данных $dataPort недоступен. Пробуем запрос настроек: ${error.message}"
                )
            }
            if (cancelled) throw SocketException("Запрос отменён.")
            val request = "{\"token\":0,\"msg_id\":3}"
            log.accept("Отправлено: $request")
            command.getOutputStream().write(request.toByteArray(StandardCharsets.UTF_8))
            command.getOutputStream().flush()
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
            val input =
                object : FilterInputStream(command.getInputStream()) {
                    private var received = 0

                    private fun prepare() {
                        if (cancelled) throw SocketException("Запрос отменён.")
                        val remaining = deadline - System.nanoTime()
                        if (remaining <= 0)
                            throw SocketTimeoutException("Время ожидания ответа истекло.")
                        command.soTimeout =
                            maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remaining)).toInt()
                    }

                    private fun count(size: Int): Int {
                        if (size > 0) {
                            received += size
                            if (received > MAX_BYTES) throw IOException("Ответ больше 128 КиБ.")
                        }
                        return size
                    }

                    override fun read(): Int {
                        prepare()
                        val value = `in`.read()
                        if (value != -1) count(1)
                        return value
                    }

                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        prepare()
                        return count(`in`.read(bytes, offset, length))
                    }
                }
            val reader =
                JsonObjectReader(InputStreamReader(input, StandardCharsets.UTF_8), MAX_BYTES)
            repeat(32) {
                val raw = reader.next()
                try {
                    val id = JSONObject(raw).opt("msg_id")
                    if (id is Number && id.toDouble() == 3.0) {
                        log.accept("Получен ответ на запрос настроек.")
                        return ProbeResult.parse(raw)
                    }
                    log.accept("Служебное сообщение пропущено.")
                } catch (error: JSONException) {
                    throw IOException("Некорректный JSON-ответ: ${error.message}", error)
                }
            }
            throw IOException("Получено 32 служебных сообщения без ответа на запрос настроек.")
        } finally {
            close()
        }
    }

    override fun close() {
        cancelled = true
        synchronized(sockets) {
            for (socket in sockets) {
                try {
                    socket.close()
                } catch (_: IOException) {}
            }
            sockets.clear()
        }
    }

    companion object {
        private const val MAX_BYTES = 128 * 1024
    }
}
