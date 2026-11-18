package app.mazlens.app

import app.mazlens.core.operation.OperationGate
import app.mazlens.core.protocol.RecorderSettings
import app.mazlens.core.protocol.settingsReply
import app.mazlens.data.history.AttemptKind
import app.mazlens.data.history.AttemptRecord
import app.mazlens.data.history.AttemptStatus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class SettingsCompletionTest {
    @Test
    fun backgroundThenForegroundDuringFinalizationCannotRestoreDraftOrNewPassword() {
        for (kind in listOf(AttemptKind.SETTINGS, AttemptKind.PASSWORD_CHANGE)) {
            val gate = OperationGate()
            val ticket = gate.begin(7)!!
            val snapshot = RecorderSettings.parse(settingsReply())
            val state =
                AtomicReference(
                    MazLensUiState(
                        settings = SettingsUiState("recorder-a", snapshot, snapshot),
                        passwordInput = "",
                    )
                )
            val foreground = AtomicBoolean(true)
            val cleanup = CountDownLatch(1)
            val persisted = CountDownLatch(1)
            val worker = Executors.newSingleThreadExecutor()
            try {
                val completion =
                    worker.submit {
                        cleanup.countDown()
                        check(persisted.await(2, TimeUnit.SECONDS))
                        val publish = gate.finish(ticket, 7) && foreground.get()
                        state.updateAndGet {
                            it.completeRecorderSettings(
                                record(kind, AttemptStatus.SUCCESS),
                                "recorder-a",
                                snapshot.withPassword("NewPwd123"),
                                "NewPwd123",
                                publish,
                            )
                        }
                    }
                assertTrue(cleanup.await(2, TimeUnit.SECONDS))
                foreground.set(false)
                state.updateAndGet { it.backgrounded() }
                gate.cancel(ticket)
                assertTrue(gate.active())
                assertNull(gate.begin(7))
                foreground.set(true)
                persisted.countDown()
                completion.get(2, TimeUnit.SECONDS)
                assertNull(state.get().settings.baseline)
                assertNull(state.get().settings.draft)
                assertEquals("", state.get().passwordInput)
                assertEquals("", state.get().passwordRecorderId)
                assertEquals(1L, state.get().recorderActionEpoch)
                assertFalse(gate.active())
                assertNotNull(gate.begin(7))
            } finally {
                persisted.countDown()
                worker.shutdownNow()
            }
        }
    }

    @Test
    fun currentCompletionPublishesButPrivateFailureSummaryNeverBecomesPublicState() {
        val snapshot = RecorderSettings.parse(settingsReply())
        val gate = OperationGate()
        val ticket = gate.begin(7)!!
        val completed =
            MazLensUiState()
                .completeRecorderSettings(
                    record(AttemptKind.PASSWORD_CHANGE, AttemptStatus.SUCCESS),
                    "recorder-a",
                    snapshot.withPassword("NewPwd123"),
                    "NewPwd123",
                    gate.finish(ticket, 7),
                )
        assertTrue(completed.settings.baseline!!.hasPassword("NewPwd123"))
        assertEquals("NewPwd123", completed.passwordInput)
        assertEquals("recorder-a", completed.passwordRecorderId)
        val failed =
            completed.completeRecorderSettings(
                record(AttemptKind.SETTINGS, AttemptStatus.FAILED)
                    .copy(summary = "PRIVATE-ERROR-123"),
                "recorder-a",
                null,
                null,
                true,
            )
        assertFalse(failed.settings.message.contains("PRIVATE-ERROR-123"))
        assertNotNull(failed.settings.problem)
        assertNull(failed.settings.draft)
    }

    private fun record(kind: AttemptKind, status: AttemptStatus) =
        AttemptRecord("attempt", 1, kind, "", "", status, recorderId = "recorder-a")
}
