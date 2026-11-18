package app.mazlens.core.protocol

import app.mazlens.core.archive.RecorderFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Runs at the socket boundary; no phone, Wi-Fi, network or Android framework is used. */
class RecorderSessionTest {
    @Test
    fun settingsUpdateChecksBaselineAndReadbackWithHeartbeatsOnTheSameSession() {
        val recorder = Recorder()
        var settings = settingsReply()
        recorder.onCommand = { request ->
            when (val id = request.getInt("msg_id")) {
                257 -> recorder.command.feed("{\"msg_id\":257,\"rval\":0,\"param\":23}")
                3 -> recorder.command.feed("{\"msg_id\":1793}" + settings)
                268435457 -> {
                    assertEquals(23, request.getInt("token"))
                    settings = JSONObject(request.toString()).put("msg_id", 3).put("rval", 0)
                    recorder.command.feed("{\"msg_id\":268435457,\"rval\":0}")
                }
                258 -> recorder.command.feed("{\"msg_id\":$id,\"rval\":0}")
            }
        }
        val session = RecorderSession(recorder, java.util.function.Consumer {})
        try {
            assertTrue(session.open("recorder", 7878, 8787, "model", "OwnerPwd", 700).opened)
            val before = session.readSettings()
            val desired = before.with(RecorderSetting.PARKING_START, 6)
            assertTrue(session.updateSettings(before, desired).matches(desired))
            assertEquals(1, recorder.commands.count { it.optInt("msg_id") == 257 })
            assertEquals(1, recorder.commands.count { it.optInt("msg_id") == 268435457 })
            assertEquals(3, recorder.commands.count { it.optInt("msg_id") == 3 })
            assertEquals(3, recorder.commands.count { it.optInt("msg_id") == 1793 })
            session.finish()
        } finally {
            session.close()
        }
    }

    @Test
    fun refusesConflictsRejectionsAndUnconfirmedReadbackWithoutRetryingWrite() {
        for (failure in listOf("conflict", "rejection", "readback")) {
            val recorder = Recorder()
            var reads = 0
            recorder.onCommand = { request ->
                when (request.getInt("msg_id")) {
                    257 -> recorder.command.feed("{\"msg_id\":257,\"rval\":0,\"param\":23}")
                    3 -> {
                        val reply = settingsReply()
                        if (failure == "conflict" && reads++ > 0) reply.put("m.qual", 0)
                        recorder.command.feed(reply.toString())
                    }
                    268435457 ->
                        recorder.command.feed(
                            "{\"msg_id\":268435457,\"rval\":${if (failure == "rejection") -29 else 0}}"
                        )
                }
            }
            val session = RecorderSession(recorder, java.util.function.Consumer {})
            try {
                assertTrue(session.open("recorder", 7878, 8787, "model", "OwnerPwd", 700).opened)
                val before = session.readSettings()
                assertThrows(IOException::class.java) {
                    session.updateSettings(before, before.with(RecorderSetting.QUALITY, 0))
                }
                assertEquals(
                    if (failure == "conflict") 0 else 1,
                    recorder.commands.count { it.optInt("msg_id") == 268435457 },
                )
                assertTrue(recorder.command.closed && recorder.data.closed)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun settingsReadTimeoutAndWriteCancellationCloseOwnedSockets() {
        val recorder = Recorder()
        val session = RecorderSession(recorder, java.util.function.Consumer {})
        try {
            assertTrue(session.open("recorder", 7878, 8787, "model", "OwnerPwd", 150).opened)
            assertThrows(SocketTimeoutException::class.java) { session.readSettings() }
            assertThrows(IOException::class.java) { session.readSettings() }
            assertEquals(1, recorder.commands.count { it.optInt("msg_id") == 3 })
        } finally {
            session.close()
        }
        val second = Recorder()
        val writing = CountDownLatch(1)
        second.onCommand = { request ->
            when (request.getInt("msg_id")) {
                257 -> second.command.feed("{\"msg_id\":257,\"rval\":0,\"param\":23}")
                3 -> second.command.feed(settingsReply().toString())
                268435457 -> writing.countDown()
            }
        }
        val active = RecorderSession(second, java.util.function.Consumer {})
        val worker = Executors.newSingleThreadExecutor()
        try {
            assertTrue(active.open("recorder", 7878, 8787, "model", "OwnerPwd", 700).opened)
            val before = active.readSettings()
            val result =
                worker.submit<Boolean> {
                    try {
                        active.updateSettings(before, before.withPassword("NewPwd123"))
                        false
                    } catch (_: IOException) {
                        true
                    }
                }
            assertTrue(writing.await(2, TimeUnit.SECONDS))
            active.close()
            assertTrue(result.get(2, TimeUnit.SECONDS))
            assertEquals(1, second.commands.count { it.optInt("msg_id") == 268435457 })
            assertTrue(second.command.closed && second.data.closed)
        } finally {
            active.close()
            worker.shutdownNow()
        }
    }

    @Test
    fun deleteUsesAuthenticatedValidatedPathAndDoesNotRetryAnUncertainCommand() {
        val entry =
            RecorderFile(
                "000000000001",
                1,
                1,
                10,
                "2026-01-01 00:00:00",
                "/tmp/SD0/NORMAL/000000000001.MP4",
                "recorder",
            )
        for (status in listOf(0, -29, null)) {
            val recorder = Recorder()
            val session = RecorderSession(recorder, java.util.function.Consumer {})
            try {
                assertTrue(session.open("recorder", 7878, 8787, "model", "OwnerPwd", 150).opened)
                recorder.onCommand = { request ->
                    if (request.getInt("msg_id") == 1287 && status != null)
                        recorder.command.feed("{\"msg_id\":1287,\"rval\":$status}")
                }
                assertThrows(IOException::class.java) {
                    session.deleteRecording(entry.copy(remotePath = "/tmp/SD0/../private"))
                }
                if (status == 0) session.deleteRecording(entry)
                else {
                    assertThrows(IOException::class.java) { session.deleteRecording(entry) }
                    assertThrows(IOException::class.java) { session.deleteRecording(entry) }
                }
                val request = recorder.commands.single { it.optInt("msg_id") == 1287 }
                assertEquals(23, request.getInt("token"))
                assertEquals(entry.remotePath, request.getString("param"))
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun answersIdleAndInterleavedHeartbeatsOnlyWithItsValidatedToken() {
        val recorder = Recorder()
        val callbacks = CopyOnWriteArrayList<String>()
        val session = RecorderSession(recorder, java.util.function.Consumer {}, callbacks::add)
        recorder.onCommand = { json ->
            when (json.getInt("msg_id")) {
                257 ->
                    recorder.command.feed(
                        "{\"msg_id\":1793,\"token\":999}{\"msg_id\":257,\"rval\":0,\"param\":23}{\"msg_id\":1793,\"token\":999}"
                    )
                2 ->
                    recorder.command.feed(
                        "{\"msg_id\":1793}{\"msg_id\":3,\"rval\":-29}{\"msg_id\":2,\"rval\":0}"
                    )
                258 -> recorder.command.feed("{\"msg_id\":258,\"rval\":0}")
            }
        }
        try {
            assertTrue(session.open("recorder", 7878, 8787, "model", "OwnerPwd", 1000).opened)
            await { recorder.commands.count { it.optInt("msg_id") == 1793 } == 1 }
            recorder.command.feed("{\"msg_id\":1793}")
            await { recorder.commands.count { it.optInt("msg_id") == 1793 } == 2 }
            assertTrue(session.selectCamera(true).success)
            assertTrue(session.selectCamera(false).success)
            assertEquals(
                "Changing sources must keep one authenticated session",
                1,
                recorder.commands.count { it.optInt("msg_id") == 257 },
            )
            assertEquals(
                "Changing sources must not STOP the current session",
                0,
                recorder.commands.count { it.optInt("msg_id") == 258 },
            )
            val cameras = recorder.commands.filter { it.optInt("msg_id") == 2 }
            assertEquals(listOf("vin_1", "vin_0"), cameras.map { it.getString("param") })
            assertTrue(
                cameras.all {
                    it.getString("type") == "streaming_source" && it.getInt("token") == 23
                }
            )
            val beats = recorder.commands.filter { it.optInt("msg_id") == 1793 }
            assertEquals(4, beats.size)
            beats.forEach {
                assertEquals(setOf("rval", "token", "msg_id"), it.keys().asSequence().toSet())
                assertEquals(23, it.getInt("token"))
                assertEquals(0, it.getInt("rval"))
            }
            assertEquals("192.0.2.2", session.localAddress)
            session.finish()
            session.finish()
            assertEquals(1, recorder.commands.count { it.optInt("msg_id") == 258 })
            assertTrue(recorder.command.closed && recorder.data.closed)
            assertTrue(callbacks.isEmpty())
            assertNull(session.disconnectReason)
        } finally {
            session.close()
        }
    }

    @Test
    fun cameraTimeoutClosesSessionSoALateReplyCannotApproveAnotherCamera() {
        val recorder = Recorder()
        val session = RecorderSession(recorder, java.util.function.Consumer {})
        assertTrue(session.open("recorder", 7878, 8787, "model", "OwnerPwd", 150).opened)
        recorder.onCommand = {}
        try {
            val result = session.selectCamera(true)
            assertFalse(result.success)
            assertTrue(result.timedOut)
            assertTrue(recorder.command.closed && recorder.data.closed)
            assertFalse(session.selectCamera(false).success)
            assertEquals(1, recorder.commands.count { it.optInt("msg_id") == 2 })
        } finally {
            session.close()
        }
    }

    @Test
    fun rejectsInvalidTokensAndDenialsWithoutFallbackOrHeartbeat() {
        for (reply in
            listOf(
                "{\"msg_id\":257,\"rval\":-29}",
                "{\"msg_id\":257,\"rval\":-51}",
                "{\"msg_id\":257,\"rval\":-52}",
                "{\"msg_id\":257,\"rval\":0,\"param\":0}",
                "{\"msg_id\":257,\"rval\":0,\"param\":-1}",
                "{\"msg_id\":257,\"rval\":0,\"param\":1.5}",
                "{\"msg_id\":257,\"rval\":0,\"param\":\"23\"}",
                "{\"msg_id\":257,\"rval\":0,\"param\":2147483648}",
                "{\"msg_id\":257,\"rval\":0,\"param\":23.0000000000000000001}",
            )) {
            val recorder = Recorder()
            recorder.onCommand = {
                recorder.command.feed(reply + "{\"msg_id\":1793,\"token\":999}")
            }
            val session = RecorderSession(recorder, java.util.function.Consumer {})
            assertFalse(
                reply,
                session.open("recorder", 7878, 8787, "model", "OwnerPwd", 700).opened,
            )
            session.finish()
            session.close()
            assertEquals(reply, 1, recorder.commands.size)
            assertTrue(recorder.command.closed && recorder.data.closed)
        }
    }

    @Test
    fun completesDownloadWithHeartbeatAndCompletionBeforeOrAfterTheSizeReply() {
        for (early in listOf(true, false)) {
            val recorder = Recorder()
            val part = File.createTempFile("recorder-protocol-", ".part")
            val session = RecorderSession(recorder, java.util.function.Consumer {})
            val progress = CopyOnWriteArrayList<Pair<Long, Long>>()
            assertTrue(session.open("recorder", 7878, 8787, "model", "OwnerPwd", 1000).opened)
            recorder.onCommand = { json ->
                when (json.optInt("msg_id")) {
                    261 -> recorder.command.feed("{\"msg_id\":261,\"rval\":0}")
                    1285 -> {
                        val size = "{\"msg_id\":1285,\"rval\":0,\"size\":3}"
                        val completion =
                            "{\"msg_id\":7,\"type\":\"get_file_complete\",\"param\":[{\"bytes sent\":3},{\"md5sum\":\"900150983cd24fb0d6963f7d28e17f72\"}]}"
                        recorder.command.feed(
                            (if (early) completion + size else size + completion) +
                                "{\"msg_id\":1793}"
                        )
                        recorder.data.feed("abc")
                    }
                }
            }
            try {
                val downloaded =
                    session.download("/tmp/SD0/SYSTEM/19DVR_FileListInfo.dat", part, 1024) {
                        bytes,
                        total ->
                        progress.add(bytes to total)
                    }
                assertEquals(3L, downloaded.bytes)
                assertEquals("900150983cd24fb0d6963f7d28e17f72", downloaded.md5)
                assertEquals("abc", part.readText())
                assertEquals(3L to 3L, progress.last())
                val client = recorder.commands.first { it.optInt("msg_id") == 261 }
                assertEquals("TCP", client.getString("type"))
                assertEquals("192.0.2.2", client.getString("param"))
                val request = recorder.commands.first { it.optInt("msg_id") == 1285 }
                assertEquals(0, request.getInt("offset"))
                assertEquals(0, request.getInt("fetch_size"))
                assertEquals(23, request.getInt("token"))
                assertEquals("/tmp/SD0/SYSTEM/19DVR_FileListInfo.dat", request.getString("param"))
                await { recorder.commands.any { it.optInt("msg_id") == 1793 } }
            } finally {
                session.close()
                part.delete()
            }
        }
    }

    @Test
    fun rejectsTruncationChecksumMismatchCompletionSizeMismatchAndTransferFailure() {
        val cases =
            listOf(
                Triple(
                    4,
                    "abc",
                    "{\"bytes sent\":4},{\"md5sum\":\"900150983cd24fb0d6963f7d28e17f72\"}",
                ),
                Triple(
                    3,
                    "abc",
                    "{\"bytes sent\":3},{\"md5sum\":\"00000000000000000000000000000000\"}",
                ),
                Triple(
                    3,
                    "abc",
                    "{\"bytes sent\":2},{\"md5sum\":\"900150983cd24fb0d6963f7d28e17f72\"}",
                ),
                Triple(3, "abc", "FAIL"),
            )
        for ((size, bytes, completion) in cases) {
            val recorder = Recorder()
            val part = File.createTempFile("recorder-failure-", ".part")
            val session = RecorderSession(recorder, java.util.function.Consumer {})
            assertTrue(session.open("recorder", 7878, 8787, "model", "OwnerPwd", 700).opened)
            recorder.onCommand = {
                when (it.optInt("msg_id")) {
                    261 -> recorder.command.feed("{\"msg_id\":261,\"rval\":0}")
                    1285 -> {
                        recorder.command.feed(
                            "{\"msg_id\":1285,\"rval\":0,\"size\":$size}" +
                                if (completion == "FAIL")
                                    "{\"msg_id\":7,\"type\":\"get_file_fail\"}"
                                else
                                    "{\"msg_id\":7,\"type\":\"get_file_complete\",\"param\":[$completion]}"
                        )
                        recorder.data.feed(bytes)
                        recorder.data.end()
                    }
                }
            }
            try {
                session.download("/tmp/SD0/MOVIE/20260101010101.MP4", part, 1024)
                fail("Invalid transfer accepted: $completion")
            } catch (expected: IOException) {
                assertTrue(part.name.endsWith(".part"))
            } finally {
                session.close()
                part.delete()
            }
        }
    }

    @Test
    fun cancelImmediatelyUnblocksABinaryReadWithoutStopOrDisconnectCallback() {
        val recorder = Recorder()
        val part = File.createTempFile("recorder-cancel-", ".part")
        val callbacks = CopyOnWriteArrayList<String>()
        val session = RecorderSession(recorder, java.util.function.Consumer {}, callbacks::add)
        val worker = Executors.newSingleThreadExecutor()
        assertTrue(session.open("recorder", 7878, 8787, "model", "OwnerPwd", 700).opened)
        recorder.onCommand = {
            when (it.optInt("msg_id")) {
                261 -> recorder.command.feed("{\"msg_id\":261,\"rval\":0}")
                1285 -> recorder.command.feed("{\"msg_id\":1285,\"rval\":0,\"size\":3}")
            }
        }
        try {
            val pending =
                worker.submit<Boolean> {
                    try {
                        session.download("/tmp/SD0/MOVIE/20260101010101.MP4", part, 1024)
                        false
                    } catch (expected: IOException) {
                        true
                    }
                }
            assertTrue(recorder.data.waiting.await(1, TimeUnit.SECONDS))
            val started = System.nanoTime()
            session.close()
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 100)
            assertTrue(pending.get(500, TimeUnit.MILLISECONDS))
            session.finish()
            assertEquals(0, recorder.commands.count { it.optInt("msg_id") == 258 })
            assertTrue(callbacks.isEmpty())
        } finally {
            session.close()
            worker.shutdownNow()
            part.delete()
        }
    }

    @Test
    fun forcedDisconnectDuringDownloadImmediatelyUnblocksDataRead() {
        val recorder = Recorder()
        val part = File.createTempFile("recorder-forced-", ".part")
        val callbacks = CopyOnWriteArrayList<String>()
        val session = RecorderSession(recorder, java.util.function.Consumer {}, callbacks::add)
        val worker = Executors.newSingleThreadExecutor()
        assertTrue(session.open("recorder", 7878, 8787, "model", "OwnerPwd", 700).opened)
        recorder.onCommand = {
            when (it.optInt("msg_id")) {
                261 -> recorder.command.feed("{\"msg_id\":261,\"rval\":0}")
                1285 -> recorder.command.feed("{\"msg_id\":1285,\"rval\":0,\"size\":3}")
            }
        }
        try {
            val pending =
                worker.submit<Boolean> {
                    try {
                        session.download("/tmp/SD0/MOVIE/20260101010101.MP4", part, 1024)
                        false
                    } catch (expected: IOException) {
                        true
                    }
                }
            assertTrue(recorder.data.waiting.await(1, TimeUnit.SECONDS))
            recorder.command.feed(
                "{\"msg_id\":1793}{\"msg_id\":7,\"type\":\"force_disconnect\",\"param\":\"session_timeout\"}"
            )
            assertTrue(pending.get(500, TimeUnit.MILLISECONDS))
            await { callbacks.size == 1 }
            assertEquals("session_timeout", session.disconnectReason)
            assertTrue(recorder.commands.any { it.optInt("msg_id") == 1793 })
        } finally {
            session.close()
            worker.shutdownNow()
            part.delete()
        }
    }

    @Test
    fun masksSentPasswordAndKeepsRawRepliesAndPartialFramesOutOfLogger() {
        val recorder = Recorder()
        val logs = CopyOnWriteArrayList<String>()
        recorder.onCommand = {
            if (it.optInt("msg_id") == 257)
                recorder.command.feed(
                    "{\"msg_id\":257,\"rval\":0,\"param\":23,\"secret\":\"private-reply\"}"
                )
        }
        val session = RecorderSession(recorder, java.util.function.Consumer(logs::add))
        try {
            val opened = session.open("recorder", 7878, 8787, "model", "OwnerPwd", 700)
            assertTrue(opened.opened)
            assertTrue(opened.trace.contains("private-reply"))
            assertFalse(opened.trace.contains("OwnerPwd"))
            recorder.command.feed("{\"msg_id\":7,\"secret\":\"partial-private-marker\"")
            await { session.snapshotTrace().contains("partial-private-marker") }
            assertFalse(logs.joinToString().contains("private-reply"))
            assertFalse(logs.joinToString().contains("partial-private-marker"))
            assertFalse(logs.joinToString().contains("OwnerPwd"))
        } finally {
            session.close()
        }
    }

    private class Recorder : RecorderProbe.SocketMaker {
        val command = FakeSocket()
        val data = FakeSocket()
        val commands = CopyOnWriteArrayList<JSONObject>()
        var created = 0
        @Volatile
        var onCommand: (JSONObject) -> Unit = { json ->
            when (json.optInt("msg_id")) {
                257 -> command.feed("{\"msg_id\":257,\"rval\":0,\"param\":23}")
                258 -> command.feed("{\"msg_id\":258,\"rval\":0}")
            }
        }

        init {
            command.sent = { raw ->
                val json = JSONObject(raw)
                commands.add(json)
                onCommand(json)
            }
        }

        override fun create(): Socket = if (created++ == 0) command else data
    }

    private class FakeSocket : Socket() {
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN") private val lock = Object()
        private val incoming = java.util.ArrayDeque<Int>()
        private val outgoing = ByteArrayOutputStream()
        val waiting = CountDownLatch(1)
        @Volatile var closed = false
        @Volatile var timeoutMs = 250
        private var eof = false
        var sent: (String) -> Unit = {}

        override fun connect(endpoint: SocketAddress, timeout: Int) {}

        override fun getLocalAddress(): InetAddress = InetAddress.getByName("192.0.2.2")

        override fun setSoTimeout(timeout: Int) {
            timeoutMs = timeout
        }

        fun feed(value: String) =
            synchronized(lock) {
                value.toByteArray(UTF_8).forEach { incoming.add(it.toInt() and 255) }
                lock.notifyAll()
            }

        fun end() =
            synchronized(lock) {
                eof = true
                lock.notifyAll()
            }

        override fun getInputStream(): InputStream =
            object : InputStream() {
                override fun read(): Int =
                    synchronized(lock) {
                        val deadline =
                            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
                        while (incoming.isEmpty() && !closed && !eof) {
                            waiting.countDown()
                            val left = deadline - System.nanoTime()
                            if (left <= 0) {
                                throw SocketTimeoutException()
                            }
                            try {
                                lock.wait(maxOf(1, TimeUnit.NANOSECONDS.toMillis(left)))
                            } catch (e: InterruptedException) {
                                throw IOException(e)
                            }
                        }
                        if (closed || incoming.isEmpty()) -1 else incoming.removeFirst()
                    }

                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    if (length == 0) return 0
                    val first = read()
                    if (first < 0) return -1
                    bytes[offset] = first.toByte()
                    return synchronized(lock) {
                        var n = 1
                        while (n < length && incoming.isNotEmpty()) bytes[offset + n++] =
                            incoming.removeFirst().toByte()
                        n
                    }
                }
            }

        override fun getOutputStream(): OutputStream =
            object : OutputStream() {
                override fun write(value: Int) {
                    outgoing.write(value)
                }

                override fun write(bytes: ByteArray, off: Int, len: Int) {
                    outgoing.write(bytes, off, len)
                }

                override fun flush() {
                    val raw = outgoing.toString(UTF_8)
                    outgoing.reset()
                    sent(raw)
                }
            }

        override fun close() =
            synchronized(lock) {
                closed = true
                lock.notifyAll()
            }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition()) {
            if (System.nanoTime() >= deadline) fail("Timed out awaiting protocol event")
            Thread.sleep(5)
        }
    }
}
