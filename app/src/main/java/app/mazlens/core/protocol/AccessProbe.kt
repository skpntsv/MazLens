package app.mazlens.core.protocol

import java.io.ByteArrayOutputStream
import java.io.EOFException
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

class AccessProbe(
    private val factory: RecorderProbe.SocketMaker,
    private val log: Consumer<String>,
) : AutoCloseable {
    enum class Mode {
        LEGACY_SESSION,
        MODEL_ONLY,
        EMPTY_PASSWORD,
        SINGLE_FIELD,
        PASSWORD_SESSION,
    }

    class Outcome(
        @JvmField val summary: String,
        @JvmField val trace: String,
        @JvmField val password: String,
        @JvmField val response: String,
        @JvmField val sessionOpened: Boolean,
    )

    private val sockets = ArrayList<Socket>()
    private val trace = StringBuilder()
    @Volatile private var cancelled = false
    private var command: Socket? = null
    private var input: TimedInput? = null
    private var reader: JsonObjectReader? = null

    @Throws(IOException::class)
    fun queryWithPassword(
        host: String,
        commandPort: Int,
        dataPort: Int,
        model: String,
        password: String,
        timeoutMs: Int,
    ): Outcome {
        require(password.length in 8..31) { "Пароль должен содержать от 8 до 31 символа" }
        return queryInternal(
            host,
            commandPort,
            dataPort,
            Mode.PASSWORD_SESSION,
            model,
            timeoutMs,
            password,
        )
    }

    @Throws(IOException::class)
    fun query(
        host: String,
        commandPort: Int,
        dataPort: Int,
        mode: Mode,
        model: String,
        timeoutMs: Int,
    ): Outcome {
        require(mode != Mode.PASSWORD_SESSION) {
            "Для входа с паролем используйте queryWithPassword"
        }
        return queryInternal(host, commandPort, dataPort, mode, model, timeoutMs, null)
    }

    private fun queryInternal(
        host: String,
        commandPort: Int,
        dataPort: Int,
        mode: Mode,
        model: String,
        timeoutMs: Int,
        suppliedPassword: String?,
    ): Outcome {
        var token = 0
        var sessionOpened = false
        var rawNeeded = false
        var summary = "Проверка не завершена."
        var password = ""
        var response = ""
        note("Режим=$mode, модель=$model, адрес=$host")
        try {
            command = open(host, commandPort, timeoutMs)
            note(
                "Командный TCP-канал открыт; локальный адрес=${command!!.localAddress.hostAddress}"
            )
            try {
                open(host, dataPort, timeoutMs)
                note("TCP $dataPort открыт.")
            } catch (error: IOException) {
                if (cancelled) throw error
                note("TCP $dataPort недоступен: ${error.javaClass.simpleName}")
            }
            input = TimedInput(command!!)
            reader = JsonObjectReader(InputStreamReader(input!!, StandardCharsets.UTF_8), MAX_BYTES)
            if (mode == Mode.SINGLE_FIELD) {
                val reply =
                    exchange(
                        JSONObject().put("token", 0).put("msg_id", 1).put("type", "w.pass"),
                        1,
                        timeoutMs,
                    )
                response = reply.raw
                val code = status(reply.json)
                if (code != 0) summary = "Чтение w.pass отклонено: ${errorName(code)}"
                else if (reply.json.has("type") && reply.json.optString("type") != "w.pass") {
                    summary = "Ответ относится к другому полю; смотрите полный ответ."
                } else {
                    val normalized = JSONObject().put("msg_id", 3).put("rval", 0)
                    if (reply.json.has("param")) normalized.put("w.pass", reply.json.opt("param"))
                    val result = ProbeResult.parse(normalized.toString())
                    summary = result.description()
                    password = result.passwordValue()
                }
            } else {
                val start = JSONObject().put("token", 0).put("msg_id", 257)
                if (mode != Mode.LEGACY_SESSION) start.put("model_name", model)
                if (mode == Mode.EMPTY_PASSWORD) start.put("password", "")
                if (mode == Mode.PASSWORD_SESSION) start.put("password", suppliedPassword)
                val reply = exchange(start, 257, timeoutMs)
                response = reply.raw
                val code = status(reply.json)
                if (code != 0) summary = "Сессия не открыта: ${errorName(code)}"
                else {
                    val returned = reply.json.opt("param")
                    if (
                        returned !is Number ||
                            returned.toDouble() <= 0 ||
                            returned.toDouble() > Int.MAX_VALUE ||
                            returned.toDouble() != Math.rint(returned.toDouble())
                    ) {
                        summary =
                            "Ответ успешный, но корректный положительный токен не получен. Чтение не выполнялось."
                    } else {
                        token = returned.toInt()
                        sessionOpened = true
                        note(
                            "Получен собственный токен сессии; читаю настройки на том же соединении."
                        )
                        val settings =
                            exchange(
                                JSONObject().put("token", token).put("msg_id", 3),
                                3,
                                timeoutMs,
                            )
                        response = settings.raw
                        val result = ProbeResult.parse(response)
                        summary = "Сессия открыта. ${result.description()}"
                        password = result.passwordValue()
                    }
                }
            }
        } catch (error: Exception) {
            if (error !is IOException && error !is JSONException) throw error
            summary =
                when {
                    cancelled -> "Проверка отменена; соединения закрыты."
                    error is SocketTimeoutException ->
                        "Ответ не получен за отведённое время. Смотрите журнал обмена."
                    else ->
                        "Проверка прервана: ${error.javaClass.simpleName}. Смотрите полный обмен."
                }
            rawNeeded = true
            val detail = error.message.toString()
            trace
                .append("PRIVATE ERROR: ")
                .append(detail, 0, minOf(detail.length, 2048))
                .append('\n')
            note(summary)
        } finally {
            if (token > 0 && !cancelled && command != null && reader != null) {
                try {
                    val cleanup =
                        exchange(JSONObject().put("token", token).put("msg_id", 258), 258, 1000)
                    note(
                        if (status(cleanup.json) == 0) "Собственная сессия закрыта."
                        else "Закрытие собственной сессии отклонено."
                    )
                } catch (error: Exception) {
                    if (error !is IOException && error !is JSONException) throw error
                    rawNeeded = true
                    note("Закрытие сессии не подтверждено: ${error.javaClass.simpleName}")
                }
            }
            val captured = input?.captured
            if (rawNeeded && captured != null && captured.size() > 0) {
                trace
                    .append("RAW INPUT (включая неполные сообщения):\n")
                    .append(String(captured.toByteArray(), StandardCharsets.UTF_8))
                    .append('\n')
            }
            closeSockets()
        }
        return Outcome(summary, trace.toString(), password, response, sessionOpened)
    }

    @Throws(IOException::class)
    private fun open(host: String, port: Int, timeoutMs: Int): Socket {
        val socket = factory.create()
        synchronized(sockets) {
            if (cancelled) {
                socket.close()
                throw SocketException("Отменено")
            }
            sockets.add(socket)
        }
        val began = System.nanoTime()
        socket.connect(InetSocketAddress(host, port), minOf(timeoutMs, 4000))
        note(
            "TCP $port: подключён за ${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)} мс"
        )
        return socket
    }

    @Throws(IOException::class, JSONException::class)
    private fun exchange(request: JSONObject, expected: Int, timeoutMs: Int): Reply {
        if (cancelled) throw SocketException("Отменено")
        input!!.deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
        val wire = request.toString()
        val displayWire =
            if (request.has("password") && request.optString("password").isNotEmpty())
                JSONObject(wire).put("password", "<скрыто>").toString()
            else wire
        trace.append("SEND ").append(displayWire).append('\n')
        log.accept("Отправлен msg_id=$expected; ожидание ответа $timeoutMs мс")
        command!!.getOutputStream().write(wire.toByteArray(StandardCharsets.UTF_8))
        command!!.getOutputStream().flush()
        val began = System.nanoTime()
        repeat(32) {
            val raw: String
            val json: JSONObject
            try {
                raw = reader!!.next()
                trace.append("RECV ").append(raw).append('\n')
                json = JSONObject(raw)
            } catch (error: IOException) {
                if (
                    error !is SocketException &&
                        error !is SocketTimeoutException &&
                        error !is EOFException
                ) {
                    input!!.captureDiagnosticTail()
                }
                throw error
            } catch (error: JSONException) {
                input!!.captureDiagnosticTail()
                throw error
            }
            val id = json.opt("msg_id")
            log.accept(
                "Ответ msg_id=${safeNumber(id)}, rval=${safeNumber(json.opt("rval"))}, символов=${raw.length}" +
                    ", через ${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)} мс"
            )
            if (id is Number && id.toDouble() == expected.toDouble()) return Reply(json, raw)
        }
        throw IOException("32 сообщения без ожидаемого ответа msg_id=$expected")
    }

    private fun note(message: String) {
        trace.append(message).append('\n')
        log.accept(message)
    }

    private inner class TimedInput(socket: Socket) : FilterInputStream(socket.getInputStream()) {
        var deadline = 0L
        private var received = 0
        val captured = ByteArrayOutputStream()

        /** Retains a fragmented invalid reply privately without extending its deadline. */
        fun captureDiagnosticTail() {
            if (cancelled || received >= MAX_BYTES) return
            val originalDeadline = deadline
            deadline =
                minOf(originalDeadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(150))
            val bytes = ByteArray(4096)
            try {
                while (!cancelled && received < MAX_BYTES && System.nanoTime() < deadline) {
                    if (read(bytes, 0, minOf(bytes.size, MAX_BYTES - received)) == -1) return
                }
            } catch (_: IOException) {
                // The original parse failure remains the reported error; partial bytes are kept.
            } finally {
                deadline = originalDeadline
            }
        }

        private fun prepare() {
            if (cancelled) throw SocketException("Отменено")
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw SocketTimeoutException("Время ожидания истекло")
            command!!.soTimeout = maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remaining)).toInt()
        }

        private fun count(size: Int): Int {
            if (size > 0) {
                received += size
                if (received > MAX_BYTES) throw IOException("Превышен общий лимит 128 КиБ")
            }
            return size
        }

        override fun read(): Int {
            prepare()
            val value = `in`.read()
            if (value != -1) {
                if (captured.size() < MAX_BYTES) captured.write(value)
                count(1)
            }
            return value
        }

        override fun read(bytes: ByteArray, start: Int, size: Int): Int {
            prepare()
            val actual = `in`.read(bytes, start, size)
            if (actual > 0) captured.write(bytes, start, minOf(actual, MAX_BYTES - captured.size()))
            return count(actual)
        }
    }

    private class Reply(val json: JSONObject, val raw: String)

    override fun close() {
        cancelled = true
        closeSockets()
    }

    private fun closeSockets() {
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

        @Throws(IOException::class)
        private fun status(json: JSONObject): Int {
            val value = json.opt("rval")
            if (
                value !is Number ||
                    value.toDouble() != Math.rint(value.toDouble()) ||
                    value.toDouble() < Int.MIN_VALUE ||
                    value.toDouble() > Int.MAX_VALUE
            ) {
                throw IOException("В ответе нет корректного числового rval")
            }
            return value.toInt()
        }

        private fun errorName(code: Int): String =
            when (code) {
                -51,
                -52,
                -53 -> RecorderErrors.describe(code)
                -4 -> "TokenFailed (-4)"
                -29 -> "ResponseFailedPassword (-29)"
                -50 -> "ResponseFailedModelName (-50)"
                -21 -> "CommandBusy (-21)"
                else -> "rval=$code"
            }

        private fun safeNumber(value: Any?): String {
            if (value !is Number) return "<нет числового значения>"
            val number = value.toDouble()
            if (
                number.isNaN() ||
                    number.isInfinite() ||
                    number != Math.rint(number) ||
                    number < Int.MIN_VALUE ||
                    number > Int.MAX_VALUE
            )
                return "<некорректное число>"
            return number.toInt().toString()
        }
    }
}
