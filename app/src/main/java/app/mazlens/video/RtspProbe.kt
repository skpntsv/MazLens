package app.mazlens.video

import app.mazlens.core.protocol.RecorderProbe
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/**
 * Read-only RTSP diagnostic for the caller's verified owner address and /live. The caller keeps
 * [Outcome.response] app-private; the callback receives only fixed text and locally parsed numbers,
 * never server header/body text.
 */
class RtspProbe(private val factory: RecorderProbe.SocketMaker, private val log: Consumer<String>) :
    AutoCloseable {
    class Outcome(
        @JvmField val summary: String,
        @JvmField val trace: String,
        @JvmField val response: String,
        @JvmField val serverResponded: Boolean,
        @JvmField val descriptionReceived: Boolean,
        @JvmField val videoAdvertised: Boolean,
        @JvmField val authRequired: Boolean,
        @JvmField val cancelled: Boolean,
    )

    private val socketLock = Any()
    private val querying = AtomicBoolean()
    @Volatile private var closed = false
    private var activeSocket: Socket? = null

    @JvmOverloads
    fun query(host: String, port: Int = 554, timeoutMs: Int = 8000): Outcome {
        val raw = ByteArrayOutputStream()
        val trace = StringBuilder()
        var serverResponded = false
        var descriptionReceived = false
        var videoAdvertised = false
        var authRequired = false
        var cancelled = false
        var socket: Socket? = null
        var summary = "Ответ RTSP не получен"

        fun event(value: String) {
            trace.append(value).append('\n')
            // UI/log implementations must not prevent socket cleanup.
            try {
                log.accept(value)
            } catch (_: RuntimeException) {}
        }
        fun outcome() =
            Outcome(
                summary,
                trace.toString(),
                raw.toString(StandardCharsets.ISO_8859_1.name()),
                serverResponded,
                descriptionReceived,
                videoAdvertised,
                authRequired,
                cancelled,
            )

        if (closed) {
            cancelled = true
            summary = "Проверка RTSP отменена"
            event("RTSP cancelled")
            return outcome()
        }
        if (!querying.compareAndSet(false, true)) {
            summary = "Проверка RTSP уже выполняется"
            event("RTSP busy")
            return outcome()
        }
        try {
            if (port !in 1..65535 || timeoutMs <= 0) throw InvalidTarget()
            // A verified P2P owner is an IP literal. Avoid DNS, whose lookup
            // cannot be bounded by Socket.connect's timeout.
            val address = literalAddress(host)
            val uriHost =
                if (address.address.size == 16) "[${host.removeSurrounding("[", "]")}]" else host
            val uri = "rtsp://$uriHost${if (port == 554) "" else ":$port"}/live"
            val deadline = System.nanoTime() + timeoutMs.toLong() * 1_000_000L
            checkOpen()
            val connection = factory.create()
            socket = connection
            synchronized(socketLock) {
                if (closed) {
                    closeQuietly(connection)
                    throw Cancelled()
                }
                activeSocket = connection
            }
            connection.connect(InetSocketAddress(address, port), remainingMs(deadline))
            event("RTSP TCP connected")
            val reader = WireReader(connection, raw, deadline)

            fun request(method: String, cseq: Int): Reply {
                checkOpen()
                remainingMs(deadline)
                val accept = if (method == "DESCRIBE") "Accept: application/sdp\r\n" else ""
                val request = "$method $uri RTSP/1.0\r\nCSeq: $cseq\r\n${accept}\r\n"
                connection.getOutputStream().write(request.toByteArray(StandardCharsets.US_ASCII))
                connection.getOutputStream().flush()
                event("RTSP request $method CSeq=$cseq")
                return reader.reply(cseq) { status ->
                    serverResponded = true
                    if (status == 401) authRequired = true
                    event("RTSP response status=$status CSeq=$cseq")
                }
            }

            val options = request("OPTIONS", 1)
            if (authRequired) {
                summary = "Сервер RTSP требует авторизацию"
            } else if (options.status != 200 && options.status != 405 && options.status != 501) {
                summary = "OPTIONS: сервер RTSP ответил ${options.status}"
            } else {
                val description = request("DESCRIBE", 2)
                summary =
                    when {
                        authRequired -> "Сервер RTSP требует авторизацию"
                        description.status != 200 ->
                            "DESCRIBE: сервер RTSP ответил ${description.status}"
                        !description.sdpType || !description.plainEncoding ->
                            "Сервер не вернул описание SDP"
                        else -> {
                            val sdp = parseSdp(description.body)
                            if (sdp == null) {
                                "Получено некорректное описание SDP"
                            } else {
                                descriptionReceived = true
                                videoAdvertised = sdp
                                event("RTSP SDP valid video=${if (sdp) 1 else 0}")
                                if (sdp) "Получено SDP с видеопотоком"
                                else "Получено SDP без видеопотока"
                            }
                        }
                    }
            }
        } catch (_: Cancelled) {
            cancelled = true
            summary = "Проверка RTSP отменена"
            event("RTSP cancelled")
        } catch (_: InvalidTarget) {
            summary = "Некорректный адрес или параметры RTSP"
            event("RTSP invalid target")
        } catch (_: SocketTimeoutException) {
            summary = "Время ожидания RTSP истекло"
            event("RTSP deadline exceeded")
        } catch (_: LimitExceeded) {
            summary = "Ответ RTSP превышает допустимый размер"
            event("RTSP input limit exceeded")
        } catch (_: InvalidReply) {
            summary = "Получен некорректный ответ RTSP"
            event("RTSP invalid response")
        } catch (_: EOFException) {
            summary = "Соединение RTSP закрыто до полного ответа"
            event("RTSP incomplete response")
        } catch (_: IOException) {
            summary = "Ошибка соединения RTSP"
            event("RTSP transport error")
        } finally {
            synchronized(socketLock) { if (activeSocket === socket) activeSocket = null }
            closeQuietly(socket)
            querying.set(false)
        }
        // close() can interrupt connect/read as an ordinary SocketException.
        if (closed) {
            cancelled = true
            descriptionReceived = false
            videoAdvertised = false
            summary = "Проверка RTSP отменена"
            if (!trace.endsWith("RTSP cancelled\n")) event("RTSP cancelled")
        }
        return outcome()
    }

    override fun close() {
        synchronized(socketLock) {
            closed = true
            closeQuietly(activeSocket)
            activeSocket = null
        }
    }

    private fun checkOpen() {
        if (closed || Thread.currentThread().isInterrupted) throw Cancelled()
    }

    private fun remainingMs(deadline: Long): Int {
        checkOpen()
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) throw SocketTimeoutException()
        return ((remaining + 999_999L) / 1_000_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun literalAddress(host: String): InetAddress {
        val value = host.removeSurrounding("[", "]")
        if (value.isEmpty() || value.length > 45 || host.any { it <= ' ' || it.code >= 127 })
            throw InvalidTarget()
        if (':' in value) {
            if (value.any { it !in "0123456789abcdefABCDEF:." }) throw InvalidTarget()
            val parsed =
                try {
                    InetAddress.getByName(value)
                } catch (_: IOException) {
                    throw InvalidTarget()
                }
            if (parsed.address.size != 16) throw InvalidTarget()
            return parsed
        }
        if (host != value) throw InvalidTarget()
        val octets = value.split('.')
        if (octets.size != 4) throw InvalidTarget()
        val bytes = ByteArray(4)
        for (i in octets.indices) {
            val part = octets[i]
            if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' })
                throw InvalidTarget()
            val number = part.toIntOrNull() ?: throw InvalidTarget()
            if (number !in 0..255) throw InvalidTarget()
            bytes[i] = number.toByte()
        }
        return InetAddress.getByAddress(bytes)
    }

    /** Captures every byte read, with one budget shared across both replies. */
    private inner class WireReader(
        private val socket: Socket,
        private val raw: ByteArrayOutputStream,
        private val deadline: Long,
    ) {
        private val input = socket.getInputStream()
        private val buffer = ByteArray(4096)
        private var cursor = 0
        private var buffered = 0

        private fun fill(maximum: Int) {
            checkOpen()
            socket.soTimeout = remainingMs(deadline)
            val allowance = minOf(buffer.size, maximum, MAX_INPUT - raw.size())
            if (allowance <= 0) throw LimitExceeded()
            var count: Int
            do {
                count = input.read(buffer, 0, allowance)
                if (count == -1) throw EOFException()
                if (count == 0) socket.soTimeout = remainingMs(deadline)
            } while (count == 0)
            raw.write(buffer, 0, count)
            cursor = 0
            buffered = count
        }

        private fun next(headerRemaining: Int): Int {
            remainingMs(deadline)
            if (cursor == buffered) fill(headerRemaining)
            return buffer[cursor++].toInt() and 255
        }

        fun reply(expectedCseq: Int, received: (Int) -> Unit): Reply {
            val header = ByteArrayOutputStream()
            var suffix = 0
            while (header.size() < MAX_HEADER) {
                val next = next(MAX_HEADER - header.size())
                header.write(next)
                suffix = (suffix shl 8) or next
                if (suffix == 0x0d0a0d0a) break
            }
            if (suffix != 0x0d0a0d0a) throw LimitExceeded()
            val lines =
                header.toString(StandardCharsets.ISO_8859_1.name()).dropLast(4).split("\r\n")
            val statusMatch = STATUS.matchEntire(lines[0]) ?: throw InvalidReply()
            val status = statusMatch.groupValues[1].toInt()
            if (status !in 100..599) throw InvalidReply()
            val fields = mutableMapOf<String, MutableList<String>>()
            for (line in lines.drop(1)) {
                val colon = line.indexOf(':')
                if (colon <= 0 || !TOKEN.matches(line.substring(0, colon))) throw InvalidReply()
                val name = line.substring(0, colon).lowercase(Locale.ROOT)
                val value = line.substring(colon + 1).trim(' ', '\t')
                if (value.any { (it.code < 32 && it != '\t') || it.code == 127 })
                    throw InvalidReply()
                fields.getOrPut(name) { mutableListOf() }.add(value)
            }
            fun single(name: String): String? {
                val values = fields[name] ?: return null
                if (values.size != 1) throw InvalidReply()
                return values[0]
            }
            val cseq = decimal(single("cseq") ?: throw InvalidReply())
            if (cseq != expectedCseq) throw InvalidReply()
            received(status)
            // A challenge is terminal. Do not wait for its body and never retry.
            if (status == 401) return Reply(status, false, true, ByteArray(0))
            if (fields.containsKey("transfer-encoding")) throw InvalidReply()
            val length = single("content-length")?.let(::decimal) ?: 0
            if (length > MAX_INPUT - raw.size() + buffered - cursor) throw LimitExceeded()
            val contentType = single("content-type")?.substringBefore(';')?.trim()
            val encoding = single("content-encoding")
            val body = ByteArray(length)
            var written = 0
            while (written < length) {
                remainingMs(deadline)
                if (cursor == buffered) fill(length - written)
                val count = minOf(length - written, buffered - cursor)
                buffer.copyInto(body, written, cursor, cursor + count)
                cursor += count
                written += count
            }
            return Reply(
                status,
                contentType.equals("application/sdp", ignoreCase = true),
                encoding == null || encoding.equals("identity", ignoreCase = true),
                body,
            )
        }
    }

    /** A small structural validator, not a media player or remote-URL resolver. */
    private fun parseSdp(body: ByteArray): Boolean? {
        val text =
            try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString()
            } catch (_: CharacterCodingException) {
                return null
            }
        if (text.isEmpty() || text.any { (it.code < 32 && it !in "\r\n\t") || it.code == 127 })
            return null
        val lines = text.replace("\r\n", "\n").removeSuffix("\n").split('\n')
        if (lines.size < 4 || lines[0] != "v=0") return null
        if (
            lines.any { it.length < 2 || it[0] !in "vosiuepcbtrzkam" || it[1] != '=' || '\r' in it }
        )
            return null
        if (!lines[1].startsWith("o=") || !lines[2].startsWith("s=") || lines[2].length == 2)
            return null
        val origin = lines[1].substring(2).split(' ')
        if (
            origin.size != 6 ||
                origin.any { it.isEmpty() } ||
                !DIGITS.matches(origin[1]) ||
                !DIGITS.matches(origin[2]) ||
                origin[3] != "IN" ||
                origin[4] !in setOf("IP4", "IP6")
        )
            return null
        if (
            lines.count { it.startsWith("v=") } != 1 ||
                lines.count { it.startsWith("o=") } != 1 ||
                lines.count { it.startsWith("s=") } != 1
        )
            return null
        var timing = false
        val mediaSections = mutableListOf<SdpMedia>()
        var currentMedia: SdpMedia? = null
        for (line in lines.drop(3)) {
            if (line.startsWith("t=")) {
                if (currentMedia != null) return null
                val times = line.substring(2).split(' ')
                if (times.size != 2 || times.any { !DIGITS.matches(it) }) return null
                timing = true
            } else if (line.startsWith("m=")) {
                if (!timing) return null
                val media = line.substring(2).split(' ')
                if (
                    media.size < 4 ||
                        media.any { it.isEmpty() } ||
                        !TOKEN.matches(media[0]) ||
                        !MEDIA_PORT.matches(media[1])
                )
                    return null
                val port = media[1].substringBefore('/').toIntOrNull() ?: return null
                // RTSP commonly leaves port zero for later SETUP negotiation.
                if (port !in 0..65535) return null
                if ('/' in media[1] && (media[1].substringAfter('/').toIntOrNull() ?: 0) <= 0)
                    return null
                val protocol = media[2]
                if (protocol.split('/').any { !TOKEN.matches(it) }) return null
                val rtp = protocol.startsWith("RTP/") || protocol.contains("/RTP/")
                val formats = media.drop(3)
                if (formats.any { !TOKEN.matches(it) }) return null
                val payloads =
                    if (rtp) {
                        val numbers =
                            formats.map {
                                if (!DIGITS.matches(it)) return null
                                val number = it.toIntOrNull() ?: return null
                                if (number !in 0..127) return null
                                number
                            }
                        if (numbers.distinct().size != numbers.size) return null
                        numbers
                    } else emptyList()
                currentMedia = SdpMedia(media[0], protocol, rtp, payloads)
                mediaSections.add(currentMedia)
            } else if (line.startsWith("a=rtpmap:")) {
                val section = currentMedia ?: return null
                if (!section.rtp) return null
                val mapping = line.substring(9).split(' ')
                if (mapping.size != 2 || !DIGITS.matches(mapping[0])) return null
                val payload = mapping[0].toIntOrNull() ?: return null
                if (payload !in 0..127 || payload in section.mappedPayloads) return null
                val encoding = mapping[1].split('/')
                if (encoding.size !in 2..3 || !TOKEN.matches(encoding[0])) return null
                if (encoding.drop(1).any { !DIGITS.matches(it) || (it.toIntOrNull() ?: 0) <= 0 })
                    return null
                section.mappedPayloads.add(payload)
            }
        }
        if (!timing) return null
        // A well-formed description can still lack the media-specific codec
        // mapping needed for a playback handoff. Never borrow another track's
        // rtpmap, or guess an unassigned/dynamic payload's encoding.
        return mediaSections.any { media ->
            media.name == "video" &&
                media.protocol in PLAIN_RTP &&
                media.payloads.any { payload ->
                    payload in STATIC_VIDEO ||
                        (payload in 96..127 && payload in media.mappedPayloads)
                }
        }
    }

    private class SdpMedia(
        val name: String,
        val protocol: String,
        val rtp: Boolean,
        val payloads: List<Int>,
        val mappedPayloads: MutableSet<Int> = mutableSetOf(),
    )

    private fun decimal(value: String): Int {
        if (!DIGITS.matches(value)) throw InvalidReply()
        return value.toIntOrNull() ?: throw InvalidReply()
    }

    private data class Reply(
        val status: Int,
        val sdpType: Boolean,
        val plainEncoding: Boolean,
        val body: ByteArray,
    )

    private class InvalidTarget : IOException()

    private class InvalidReply : IOException()

    private class LimitExceeded : IOException()

    private class Cancelled : IOException()

    private companion object {
        const val MAX_HEADER = 32 * 1024
        const val MAX_INPUT = 128 * 1024
        val STATUS = Regex("RTSP/1\\.0 ([0-9]{3})(?: [^\\r\\n]*)?")
        val DIGITS = Regex("[0-9]+")
        val TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        val MEDIA_PORT = Regex("[0-9]+(?:/[0-9]+)?")
        val PLAIN_RTP = setOf("RTP/AVP", "RTP/AVP/TCP", "RTP/AVPF")
        // RFC 3551 static video and combined audio/video payload assignments.
        val STATIC_VIDEO = setOf(25, 26, 28, 31, 32, 33, 34)

        fun closeQuietly(socket: Socket?) {
            try {
                socket?.close()
            } catch (_: IOException) {}
        }
    }
}
