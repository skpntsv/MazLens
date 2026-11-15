package app.mazlens.video

import android.graphics.SurfaceTexture
import android.util.Base64
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Media3 + loopback RTSP; no camera or external network is contacted. */
@RunWith(AndroidJUnit4::class)
class VideoPreviewControllerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun stopClosesPendingControlSocketAndSuppressesLateReplyAndDeadline() {
        RtspFixture(stall = true).use { server ->
            val events = Events()
            val controller = VideoPreviewController(instrumentation.targetContext)
            try {
                onMain { controller.start(server.uri, SocketFactory.getDefault(), events) }
                assertTrue(
                    "No RTSP request reached loopback",
                    server.request.await(5, TimeUnit.SECONDS),
                )
                onMain { controller.stop() }
                server.releaseReply.countDown()
                assertTrue(
                    "stop did not close the RTSP socket",
                    server.closed.await(3, TimeUnit.SECONDS),
                )
                assertFalse(
                    "Cancellation produced a terminal callback",
                    events.terminal.await(13, TimeUnit.SECONDS),
                )
                assertTrue(events.values.toString(), events.values.isEmpty())
            } finally {
                onMain { controller.stop() }
            }
            server.assertHealthy()
        }
    }

    @Test
    fun actualH264FrameIsReportedOnceThenPreviewClosesAfterThirtySeconds() {
        RtspFixture().use { server ->
            val events = Events()
            val controller = VideoPreviewController(instrumentation.targetContext)
            val sockets = CountingSocketFactory()
            var texture: SurfaceTexture? = null
            var surface: Surface? = null
            try {
                onMain {
                    texture = SurfaceTexture(false).apply { setDefaultBufferSize(64, 64) }
                    surface = Surface(texture)
                    val player = controller.start(server.uri, sockets, events)
                    player.setVideoSurface(surface)
                }
                assertTrue(
                    "No real video frame rendered: ${events.values}",
                    events.frame.await(10, TimeUnit.SECONDS),
                )
                assertTrue("RTSP SETUP did not force RTP/TCP", server.interleaved.get())
                assertTrue("Provided socket binding was ignored", sockets.created.get() > 0)
                assertFalse(
                    "Preview ended before its window",
                    events.terminal.await(28, TimeUnit.SECONDS),
                )
                assertEquals(listOf("frame"), events.values.toList())
                assertTrue("Preview window did not end", events.terminal.await(5, TimeUnit.SECONDS))
                assertTrue(
                    "Preview left active RTSP socket",
                    server.closed.await(3, TimeUnit.SECONDS),
                )
                assertEquals(listOf("frame", "ended"), events.values.toList())
            } finally {
                onMain {
                    controller.stop()
                    surface?.release()
                    texture?.release()
                }
            }
            server.assertHealthy()
        }
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    /** Returns real sockets, while detecting accidental use of Media3's default route. */
    private class CountingSocketFactory : SocketFactory() {
        val created = AtomicInteger()

        private fun counted(socket: Socket): Socket {
            created.incrementAndGet()
            return socket
        }

        override fun createSocket(): Socket = counted(Socket())

        override fun createSocket(host: String, port: Int): Socket = counted(Socket(host, port))

        override fun createSocket(
            host: String,
            port: Int,
            local: InetAddress,
            localPort: Int,
        ): Socket = counted(Socket(host, port, local, localPort))

        override fun createSocket(host: InetAddress, port: Int): Socket =
            counted(Socket(host, port))

        override fun createSocket(
            host: InetAddress,
            port: Int,
            local: InetAddress,
            localPort: Int,
        ): Socket = counted(Socket(host, port, local, localPort))
    }

    private class Events : VideoPreviewController.Listener {
        val values: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val terminal = CountDownLatch(1)
        val frame = CountDownLatch(1)

        override fun firstFrame() {
            values.add("frame")
            frame.countDown()
        }

        override fun failure(summary: String, detail: String) {
            values.add("failure")
            terminal.countDown()
        }

        override fun ended() {
            values.add("ended")
            terminal.countDown()
        }
    }

    /** Minimal one-track H.264/RTP-over-RTSP server using generated, non-copyrighted pixels. */
    private class RtspFixture(private val stall: Boolean = false) : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val uri = "rtsp://127.0.0.1:${server.localPort}/live"
        val request = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val releaseReply = CountDownLatch(1)
        val interleaved = AtomicBoolean(false)
        private val stopping = AtomicBoolean(false)
        private val outputLock = Any()
        @Volatile private var client: Socket? = null
        @Volatile private var problem: Throwable? = null
        private var sender: Thread? = null
        private val worker =
            thread(name = "preview-rtsp-fixture", isDaemon = true) {
                try {
                    server.accept().use { socket ->
                        client = socket
                        socket.soTimeout = 45_000
                        val input = BufferedInputStream(socket.getInputStream())
                        while (!stopping.get()) {
                            val lines = readRequest(input) ?: break
                            val verb = lines.first().substringBefore(' ')
                            val cseq =
                                lines
                                    .first { it.startsWith("CSeq:", ignoreCase = true) }
                                    .substringAfter(':')
                                    .trim()
                            request.countDown()
                            if (stall) releaseReply.await(5, TimeUnit.SECONDS)
                            if (stall) {
                                reply(socket, cseq, "404 Not Found")
                                // Observe the peer closing rather than closing on behalf of the
                                // controller.
                                while (input.read() >= 0) {}
                                break
                            }
                            when (verb) {
                                "OPTIONS" ->
                                    reply(
                                        socket,
                                        cseq,
                                        extra =
                                            "Public: OPTIONS, DESCRIBE, SETUP, PLAY, GET_PARAMETER, TEARDOWN\r\n",
                                    )
                                "DESCRIBE" ->
                                    reply(
                                        socket,
                                        cseq,
                                        extra =
                                            "Content-Type: application/sdp\r\nContent-Base: $uri/\r\n",
                                        body = sdp(),
                                    )
                                "SETUP" -> {
                                    interleaved.set(
                                        lines.any {
                                            it.startsWith("Transport:", true) &&
                                                it.contains("RTP/AVP/TCP")
                                        }
                                    )
                                    reply(
                                        socket,
                                        cseq,
                                        extra =
                                            "Session: fixture;timeout=60\r\nTransport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n",
                                    )
                                }
                                "PLAY" -> {
                                    reply(
                                        socket,
                                        cseq,
                                        extra =
                                            "Session: fixture\r\nRange: npt=0.000-\r\nRTP-Info: url=$uri/trackID=0;seq=1;rtptime=0\r\n",
                                    )
                                    sender =
                                        thread(name = "preview-rtp-fixture", isDaemon = true) {
                                            sendVideo(socket)
                                        }
                                }
                                "TEARDOWN" -> {
                                    reply(socket, cseq)
                                    break
                                }
                                else -> reply(socket, cseq, extra = "Session: fixture\r\n")
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!stopping.get() && e !is java.net.SocketException && e !is EOFException)
                        problem = e
                } finally {
                    closed.countDown()
                }
            }

        private fun sdp() =
            "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=Preview fixture\r\nc=IN IP4 127.0.0.1\r\nt=0 0\r\na=range:npt=0-\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\na=fmtp:96 packetization-mode=1;profile-level-id=42c01e;sprop-parameter-sets=$SPS,$PPS\r\na=control:trackID=0\r\n"

        private fun reply(
            socket: Socket,
            cseq: String,
            status: String = "200 OK",
            extra: String = "",
            body: String = "",
        ) {
            val bytes = body.toByteArray(Charsets.US_ASCII)
            val headers =
                "RTSP/1.0 $status\r\nCSeq: $cseq\r\n${extra}Content-Length: ${bytes.size}\r\n\r\n"
            synchronized(outputLock) {
                socket.getOutputStream().apply {
                    write(headers.toByteArray(Charsets.US_ASCII))
                    write(bytes)
                    flush()
                }
            }
        }

        private fun sendVideo(socket: Socket) {
            try {
                val nal = Base64.decode(IDR, Base64.DEFAULT)
                var sequence = 1
                var timestamp = 0
                while (!stopping.get() && !socket.isClosed) {
                    val payload = ByteArray(12 + nal.size)
                    payload[0] = 0x80.toByte()
                    payload[1] = 0xe0.toByte() // RTP marker + H.264 payload type 96.
                    payload[2] = (sequence shr 8).toByte()
                    payload[3] = sequence.toByte()
                    for (i in 0..3) payload[4 + i] = (timestamp ushr (24 - 8 * i)).toByte()
                    payload[11] = 1
                    nal.copyInto(payload, 12)
                    synchronized(outputLock) {
                        socket.getOutputStream().apply {
                            write(
                                byteArrayOf(
                                    0x24,
                                    0,
                                    (payload.size shr 8).toByte(),
                                    payload.size.toByte(),
                                )
                            )
                            write(payload)
                            flush()
                        }
                    }
                    sequence++
                    timestamp += 18_000
                    Thread.sleep(200)
                }
            } catch (_: java.io.IOException) {
                // Normal once the real player releases its socket.
            } catch (_: InterruptedException) {}
        }

        private fun readRequest(input: InputStream): List<String>? {
            val buffer = ByteArrayOutputStream()
            var matched = 0
            while (buffer.size() < 16_384) {
                val b = input.read()
                if (b < 0) return null
                if (buffer.size() == 0 && b == 0x24) {
                    input.read() // RTP/RTCP channel.
                    val high = input.read()
                    val low = input.read()
                    if (low < 0) throw EOFException()
                    repeat((high shl 8) or low) { if (input.read() < 0) throw EOFException() }
                    continue
                }
                buffer.write(b)
                matched =
                    if (b == intArrayOf(13, 10, 13, 10)[matched]) matched + 1
                    else if (b == 13) 1 else 0
                if (matched == 4) return buffer.toString("US-ASCII").trim().split("\r\n")
            }
            throw IllegalStateException("Oversized RTSP fixture request")
        }

        fun assertHealthy() {
            problem?.let { throw AssertionError("RTSP fixture failed", it) }
        }

        override fun close() {
            stopping.set(true)
            releaseReply.countDown()
            client?.close()
            server.close()
            sender?.interrupt()
            worker.join(2_000)
            sender?.join(2_000)
        }

        companion object {
            // ffmpeg color source, 64x64, libx264 baseline, one intra frame; metadata NAL omitted.
            private const val SPS = "Z0LAHtxCbARAAAADAEAAAAMCg8WL4A=="
            private const val PPS = "aM4PLIA="
            private const val IDR = "ZYiEBLxGKAAJ08cAAQjI4AAl9ycnJ1111111111114A="
        }
    }
}
