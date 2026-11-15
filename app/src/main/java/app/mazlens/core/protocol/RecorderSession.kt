package app.mazlens.core.protocol

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import org.json.JSONException
import org.json.JSONObject

/** One continuous command reader owns all replies, heartbeats and recorder callbacks. */
class RecorderSession(
    private val factory: RecorderProbe.SocketMaker,
    private val log: Consumer<String>,
    private val onDisconnected: (String) -> Unit = {},
) : AutoCloseable {
    data class OpenResult(
        val opened: Boolean,
        val summary: String,
        val response: String,
        val trace: String,
        val code: Int?,
    )

    data class CommandResult(val success: Boolean, val summary: String, val code: Int?)

    data class DownloadResult(val bytes: Long, val md5: String)

    private val operation = Any()
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN") private val state = Object()
    private val writes = Any()
    private val sockets = ArrayList<Socket>()
    private val trace = StringBuilder()
    private val partial = ByteArrayOutputStream()
    @Volatile private var cancelled = false
    @Volatile private var finishing = false
    @Volatile private var stopAcknowledged = false
    @Volatile private var finished = false
    @Volatile private var token = 0
    @Volatile private var command: Socket? = null
    @Volatile private var data: Socket? = null
    @Volatile
    var disconnectReason: String? = null
        private set

    val localAddress: String
        get() = command?.localAddress?.hostAddress.orEmpty()

    private var started = false
    private var requestTimeoutMs = 1000
    private var pending: Pending? = null
    private var transfer: Transfer? = null

    fun open(
        host: String,
        commandPort: Int,
        dataPort: Int,
        model: String,
        password: String,
        timeoutMs: Int,
    ): OpenResult =
        synchronized(operation) {
            require(password.length in 8..31) { "Пароль должен содержать от 8 до 31 символа" }
            require(timeoutMs > 0) { "Время ожидания должно быть положительным" }
            check(!started && !finished) { "Для нового подключения создайте новый RecorderSession" }
            started = true
            requestTimeoutMs = timeoutMs.coerceIn(1, 8000)
            var response = ""
            var code: Int? = null
            var opened = false
            var summary: String
            try {
                ensureConnected()
                command = connect(host, commandPort, timeoutMs)
                note("Командный канал регистратора открыт.")
                try {
                    data = connect(host, dataPort, timeoutMs)
                    note("Канал данных регистратора открыт.")
                } catch (error: IOException) {
                    ensureConnected()
                    privateError(error)
                    note("Канал данных недоступен; живое видео использует командный канал.")
                }
                command!!.soTimeout = READER_POLL_MS
                Thread({ readCommands(command!!) }, "recorder-command-reader").apply {
                    isDaemon = true
                    start()
                }
                val reply =
                    exchange(
                        JSONObject()
                            .put("token", 0)
                            .put("msg_id", 257)
                            .put("model_name", model)
                            .put("password", password),
                        257,
                        requestTimeoutMs,
                    )
                response = reply.raw
                code =
                    int(reply.json.opt("rval"))
                        ?: throw IOException("В ответе нет корректного числового rval")
                ensureConnected()
                if (code == 0 && token > 0) {
                    opened = true
                    summary = "Собственная сессия регистратора открыта."
                } else {
                    summary =
                        if (code != 0) RecorderErrors.describe(code)
                        else "Регистратор не выдал корректный положительный токен."
                }
                note(summary)
            } catch (error: IOException) {
                privateError(error)
                summary =
                    when {
                        cancelled -> "Подключение отменено."
                        disconnectReason != null -> RecorderErrors.disconnected(disconnectReason!!)
                        error is SocketTimeoutException ->
                            "Регистратор не ответил за отведённое время."
                        else -> "Не удалось открыть сессию: ${error.javaClass.simpleName}."
                    }
                note(summary)
            } finally {
                if (!opened) {
                    finished = true
                    close()
                }
            }
            OpenResult(opened, summary, response, snapshotTrace(), code)
        }

    fun selectCamera(rear: Boolean): CommandResult =
        synchronized(operation) {
            try {
                requireOwned()
                val reply =
                    exchange(
                        JSONObject()
                            .put("token", token)
                            .put("msg_id", 2)
                            .put("type", "streaming_source")
                            .put("param", if (rear) "vin_1" else "vin_0"),
                        2,
                        requestTimeoutMs,
                    )
                val code =
                    int(reply.json.opt("rval"))
                        ?: throw IOException("В ответе нет корректного числового rval")
                val summary =
                    if (code == 0) "Выбрана ${if (rear) "задняя" else "передняя"} камера."
                    else RecorderErrors.describe(code)
                note(summary)
                CommandResult(code == 0, summary, code)
            } catch (error: IOException) {
                privateError(error)
                val summary =
                    when {
                        cancelled -> "Выбор камеры отменён."
                        disconnectReason != null -> RecorderErrors.disconnected(disconnectReason!!)
                        else -> "Выбор камеры не подтверждён: ${error.javaClass.simpleName}."
                    }
                // Protocol replies have no request sequence: a late msg2 must never approve a
                // retry.
                close()
                CommandResult(false, summary, null)
            }
        }

    /**
     * Writes only the caller's private uncommitted file; successful return is not a storage commit.
     */
    @Throws(IOException::class)
    fun download(
        path: String,
        destination: File,
        maxBytes: Long,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): DownloadResult =
        synchronized(operation) {
            require(maxBytes > 0) { "Лимит файла должен быть положительным" }
            require(
                path.startsWith("/tmp/SD0/") &&
                    path.length <= 512 &&
                    path.split('/').none { it == ".." || it == "." } &&
                    path.none { it.code < 32 }
            ) {
                "Некорректный путь файла регистратора"
            }
            requireOwned()
            val binary = data ?: throw IOException("Канал данных регистратора недоступен")
            val download = Transfer()
            try {
                requireSuccess(
                    exchange(
                        JSONObject()
                            .put("token", token)
                            .put("msg_id", 261)
                            .put("type", "TCP")
                            .put("param", localAddress),
                        261,
                        requestTimeoutMs,
                    )
                )
                synchronized(state) { transfer = download }
                val reply =
                    exchange(
                        JSONObject()
                            .put("token", token)
                            .put("msg_id", 1285)
                            .put("offset", 0)
                            .put("fetch_size", 0)
                            .put("param", path),
                        1285,
                        requestTimeoutMs,
                    )
                requireSuccess(reply)
                val size =
                    long(reply.json.opt("size"))
                        ?: throw IOException("Регистратор не сообщил корректный размер файла")
                if (size < 0 || size > maxBytes)
                    throw IOException("Размер файла превышает разрешённый лимит")
                synchronized(state) {
                    download.size = size
                    validateCompletion(download)
                }
                binary.soTimeout = READER_POLL_MS
                val input = binary.getInputStream()
                val digest = MessageDigest.getInstance("MD5")
                val buffer = ByteArray(32 * 1024)
                var received = 0L
                var idleDeadline = deadline(DATA_IDLE_MS)
                val totalDeadline = deadline(MAX_TRANSFER_MS)
                onProgress(0, size)
                FileOutputStream(destination, false).use { output ->
                    while (received < size) {
                        checkTransfer(download)
                        if (System.nanoTime() >= totalDeadline)
                            throw SocketTimeoutException("Превышено время загрузки файла")
                        val count =
                            try {
                                input.read(
                                    buffer,
                                    0,
                                    minOf(buffer.size.toLong(), size - received).toInt(),
                                )
                            } catch (error: SocketTimeoutException) {
                                checkTransfer(download)
                                if (System.nanoTime() >= idleDeadline)
                                    throw SocketTimeoutException(
                                        "Регистратор перестал передавать файл"
                                    )
                                continue
                            }
                        if (count < 0) throw EOFException("Получен неполный файл регистратора")
                        if (count == 0) continue
                        checkTransfer(download)
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        received += count
                        idleDeadline = deadline(DATA_IDLE_MS)
                        onProgress(received, size)
                    }
                    output.fd.sync()
                }
                awaitCompletion(download, requestTimeoutMs.coerceAtLeast(1000))
                val md5 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                synchronized(state) {
                    validateCompletion(download)
                    if (download.md5 != null && !download.md5.equals(md5, ignoreCase = true))
                        throw IOException("Контрольная сумма загруженного файла не совпала")
                }
                checkTransfer(download)
                note("Файл регистратора получен и проверен: $received байт.")
                DownloadResult(received, md5)
            } catch (error: IOException) {
                privateError(error)
                // A partial binary stream cannot be reused for a second file safely.
                close()
                throw error
            } finally {
                synchronized(state) { if (transfer === download) transfer = null }
            }
        }

    /** Graceful cleanup is serialized after a foreground operation and sends STOP once. */
    fun finish(): String =
        synchronized(operation) {
            if (finished) return@synchronized ""
            finished = true
            finishing = true
            val cleanup =
                try {
                    if (token > 0 && !cancelled && disconnectReason == null) {
                        val reply =
                            exchange(JSONObject().put("token", token).put("msg_id", 258), 258, 1000)
                        if (int(reply.json.opt("rval")) == 0)
                            "Собственная сессия закрыта (msg_id=258)."
                        else "Закрытие собственной сессии отклонено (msg_id=258)."
                    } else ""
                } catch (error: IOException) {
                    privateError(error)
                    "Закрытие собственной сессии не подтверждено (msg_id=258)."
                } finally {
                    token = 0
                    close()
                }
            if (cleanup.isNotEmpty()) note(cleanup)
            cleanup
        }

    /** Immediate cancellation never waits for the foreground operation or a reply. */
    override fun close() {
        cancelled = true
        synchronized(state) { state.notifyAll() }
        closeSockets()
    }

    /** Bounded private diagnostic content. Only explicitly exported reports consume this. */
    fun snapshotTrace(): String {
        val saved = synchronized(trace) { trace.toString() }
        val tail =
            synchronized(partial) {
                if (partial.size() == 0) ""
                else "PARTIAL INPUT:\n${partial.toString(UTF_8.name())}\n"
            }
        return saved + tail
    }

    private fun connect(host: String, port: Int, timeoutMs: Int): Socket {
        val socket = factory.create()
        synchronized(sockets) {
            if (cancelled) {
                socket.close()
                throw SocketException("Отменено")
            }
            sockets.add(socket)
        }
        try {
            socket.connect(InetSocketAddress(host, port), timeoutMs.coerceIn(1, 4000))
            ensureConnected()
            return socket
        } catch (error: IOException) {
            synchronized(sockets) { sockets.remove(socket) }
            try {
                socket.close()
            } catch (_: IOException) {}
            throw error
        }
    }

    private fun exchange(request: JSONObject, id: Int, timeoutMs: Int): Reply {
        val waiting = Pending(id)
        synchronized(state) {
            ensureConnected()
            check(pending == null) { "Операции регистратора должны выполняться последовательно" }
            pending = waiting
        }
        try {
            send(request)
            val until = deadline(timeoutMs.toLong())
            synchronized(state) {
                while (true) {
                    ensureConnected()
                    if (waiting.replies.isNotEmpty()) return waiting.replies.removeFirst()
                    waitUntil(until)
                }
            }
        } finally {
            synchronized(state) { if (pending === waiting) pending = null }
        }
    }

    private fun send(request: JSONObject) =
        synchronized(writes) {
            ensureConnected()
            val id = int(request.opt("msg_id"))
            val wire = request.toString()
            val display =
                if (request.has("password")) JSONObject(wire).put("password", "<скрыто>").toString()
                else wire
            appendTrace("SEND $display\n")
            safeLog("Отправлен msg_id=$id.")
            val output =
                command?.getOutputStream() ?: throw SocketException("Командный канал закрыт")
            output.write(wire.toByteArray(UTF_8))
            output.flush()
        }

    /** Framing state survives each socket timeout, including a timeout inside a JSON string. */
    private fun readCommands(socket: Socket) {
        var depth = 0
        var quoted = false
        var escaped = false
        val buffer = ByteArray(4096)
        try {
            val input = socket.getInputStream()
            while (!cancelled && disconnectReason == null) {
                val count =
                    try {
                        input.read(buffer)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                if (count < 0) {
                    if (finishing && stopAcknowledged) return
                    throw EOFException("Регистратор закрыл командный канал")
                }
                for (index in 0 until count) {
                    val value = buffer[index].toInt() and 255
                    val char = value.toChar()
                    var complete: String? = null
                    synchronized(partial) {
                        if (partial.size() == 0 && char.isWhitespace()) continue
                        if (partial.size() == 0 && char != '{')
                            throw IOException("Ответ начинается не с JSON-объекта")
                        if (partial.size() >= MAX_FRAME_BYTES)
                            throw IOException("Превышен допустимый размер JSON-ответа")
                        partial.write(value)
                        if (quoted) {
                            if (escaped) escaped = false
                            else if (char == '\\') escaped = true
                            else if (char == '"') quoted = false
                        } else if (char == '"') quoted = true
                        else if (char == '{' || char == '[') depth++
                        else if (char == '}' || char == ']') {
                            depth--
                            if (depth == 0) {
                                complete = partial.toString(UTF_8.name())
                                partial.reset()
                            }
                        }
                    }
                    complete?.let { receive(it) }
                    if (cancelled || disconnectReason != null) return
                }
            }
        } catch (error: IOException) {
            if (!cancelled) {
                privateError(error)
                disconnected(
                    if (error is EOFException) "Регистратор закрыл командный канал"
                    else "Ошибка командного канала: ${error.javaClass.simpleName}"
                )
            }
        } catch (error: JSONException) {
            if (!cancelled) {
                privateError(error)
                disconnected("Регистратор прислал некорректный JSON")
            }
        }
    }

    private fun receive(raw: String) {
        appendTrace("RECV $raw\n")
        val json = JSONObject(raw)
        val id = int(json.opt("msg_id"))
        safeLog(
            "Получен msg_id=${id ?: "?"}, rval=${int(json.opt("rval")) ?: "?"}, байт=${raw.toByteArray(UTF_8).size}."
        )
        if (id == 1793) {
            val own = token
            if (own > 0 && !cancelled)
                send(JSONObject().put("rval", 0).put("token", own).put("msg_id", 1793))
            return
        }
        if (id == 7) {
            when (json.opt("type")) {
                "force_disconnect" -> {
                    val remote = json.opt("param") as? String
                    disconnected(if (remote in FORCE_REASONS) remote!! else "force_disconnect")
                }
                "get_file_complete",
                "get_file_fail" ->
                    synchronized(state) {
                        transfer?.let { active ->
                            if (json.optString("type") == "get_file_fail")
                                active.failure = "Регистратор не смог передать файл"
                            else if (!active.completed) {
                                try {
                                    completion(active, json)
                                    active.completed = true
                                } catch (error: IOException) {
                                    active.failure =
                                        error.message ?: "Некорректное завершение передачи"
                                }
                            } else active.failure = "Регистратор повторил завершение передачи файла"
                            state.notifyAll()
                        }
                    }
            }
            return
        }
        synchronized(state) {
            val waiting = pending ?: return
            if (waiting.id != id) return
            if (json.has("token") && token > 0 && int(json.opt("token")) != token) return
            if (waiting.replies.size >= MAX_QUEUED_REPLIES)
                throw IOException("Слишком много ответов на одну команду")
            if (id == 257 && int(json.opt("rval")) == 0 && token == 0)
                token = int(json.opt("param"))?.takeIf { it > 0 } ?: 0
            if (id == 258 && finishing && int(json.opt("rval")) != null) stopAcknowledged = true
            waiting.replies.addLast(Reply(json, raw))
            state.notifyAll()
        }
    }

    private fun completion(active: Transfer, json: JSONObject) {
        if (!json.has("param")) return
        val params =
            json.optJSONArray("param")
                ?: throw IOException("Некорректные параметры завершения файла")
        if (params.length() > 16) throw IOException("Слишком много параметров завершения файла")
        for (index in 0 until params.length()) {
            val field =
                params.optJSONObject(index)
                    ?: throw IOException("Некорректный параметр завершения файла")
            if (field.has("bytes sent")) {
                val size =
                    long(field.opt("bytes sent"))
                        ?: throw IOException("Некорректный итоговый размер файла")
                if (size < 0 || active.sent != null)
                    throw IOException("Некорректный итоговый размер файла")
                active.sent = size
            }
            if (field.has("md5sum")) {
                val checksum =
                    field.opt("md5sum") as? String
                        ?: throw IOException("Некорректная контрольная сумма файла")
                if (!checksum.matches(Regex("[a-fA-F0-9]{32}")) || active.md5 != null)
                    throw IOException("Некорректная контрольная сумма файла")
                active.md5 = checksum
            }
        }
        validateCompletion(active)
    }

    private fun validateCompletion(active: Transfer) {
        active.failure?.let { throw IOException(it) }
        if (active.size != null && active.sent != null && active.size != active.sent)
            throw IOException("Итоговый размер файла не совпал с объявленным")
    }

    private fun checkTransfer(active: Transfer) {
        ensureConnected()
        synchronized(state) { validateCompletion(active) }
    }

    private fun awaitCompletion(active: Transfer, timeoutMs: Int) =
        synchronized(state) {
            val until = deadline(timeoutMs.toLong())
            while (!active.completed) {
                checkTransfer(active)
                waitUntil(until)
            }
            checkTransfer(active)
        }

    private fun requireSuccess(reply: Reply) {
        val result =
            int(reply.json.opt("rval"))
                ?: throw IOException("В ответе нет корректного числового rval")
        if (result != 0) throw IOException(RecorderErrors.describe(result))
    }

    private fun requireOwned() {
        ensureConnected()
        if (finished || token <= 0)
            throw SocketException("Собственная сессия регистратора не открыта")
    }

    private fun ensureConnected() {
        if (cancelled) throw SocketException("Отменено")
        disconnectReason?.let { throw SocketException(it) }
    }

    private fun waitUntil(until: Long) {
        val remaining = until - System.nanoTime()
        if (remaining <= 0) throw SocketTimeoutException("Время ожидания ответа истекло")
        try {
            state.wait(maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remaining)))
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Операция прервана", error)
        }
    }

    private fun disconnected(reason: String) {
        synchronized(state) {
            if (cancelled || disconnectReason != null) return
            disconnectReason = reason
            token = 0
            state.notifyAll()
        }
        closeSockets()
        if (!finishing)
            try {
                onDisconnected(reason)
            } catch (_: RuntimeException) {}
    }

    private fun closeSockets() {
        val closing = synchronized(sockets) { sockets.toList().also { sockets.clear() } }
        for (socket in closing) try {
            socket.close()
        } catch (_: IOException) {}
    }

    private fun appendTrace(value: String) =
        synchronized(trace) {
            if (value.length >= MAX_TRACE_CHARS) {
                trace.setLength(0)
                trace.append(value.takeLast(MAX_TRACE_CHARS))
            } else {
                val extra = trace.length + value.length - MAX_TRACE_CHARS
                if (extra > 0) trace.delete(0, extra)
                trace.append(value)
            }
        }

    private fun note(message: String) {
        appendTrace("$message\n")
        safeLog(message)
    }

    private fun safeLog(message: String) {
        try {
            log.accept(message)
        } catch (_: RuntimeException) {}
    }

    private fun privateError(error: Exception) {
        appendTrace(
            "PRIVATE ERROR: ${error.javaClass.simpleName}: ${error.message.orEmpty().take(2048)}\n"
        )
    }

    private class Pending(val id: Int) {
        val replies = ArrayDeque<Reply>()
    }

    private class Reply(val json: JSONObject, val raw: String)

    private class Transfer {
        var size: Long? = null
        var sent: Long? = null
        var md5: String? = null
        var completed = false
        var failure: String? = null
    }

    companion object {
        private const val READER_POLL_MS = 250
        private const val MAX_FRAME_BYTES = 128 * 1024
        private const val MAX_TRACE_CHARS = 256 * 1024
        private const val MAX_QUEUED_REPLIES = 32
        private const val DATA_IDLE_MS = 15_000L
        private const val MAX_TRANSFER_MS = 30L * 60 * 1000
        private val FORCE_REASONS =
            setOf(
                "speed_err",
                "session_timeout",
                "battery_down",
                "sdcard_err",
                "acc_off",
                "unexpected app",
                "navi_priority",
                "module_reset",
            )

        private fun deadline(ms: Long): Long = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)

        private fun long(value: Any?): Long? =
            if (value !is Number) null
            else
                try {
                    BigDecimal(value.toString()).longValueExact()
                } catch (_: ArithmeticException) {
                    null
                } catch (_: NumberFormatException) {
                    null
                }

        private fun int(value: Any?): Int? =
            if (value !is Number) null
            else
                try {
                    BigDecimal(value.toString()).intValueExact()
                } catch (_: ArithmeticException) {
                    null
                } catch (_: NumberFormatException) {
                    null
                }
    }
}
