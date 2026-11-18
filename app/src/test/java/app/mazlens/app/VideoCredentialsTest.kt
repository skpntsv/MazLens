package app.mazlens.app

import app.mazlens.data.history.*
import org.junit.Assert.*
import org.junit.Test

class VideoCredentialsTest {
    @get:org.junit.Rule val temporary = org.junit.rules.TemporaryFolder()

    @Test
    fun rotationIsADurableBarrierAgainstOldCredentialsEvenAfterInterruption() {
        val old = record("recorder-a", "OldPwd123").copy(id = "old", timestamp = 1)
        for (status in AttemptStatus.entries) {
            val rotation =
                old.copy(
                    id = "rotation",
                    timestamp = 2,
                    kind = AttemptKind.PASSWORD_CHANGE,
                    status = status,
                    passwordCandidate = if (status == AttemptStatus.SUCCESS) "NewPwd123" else "",
                )
            val history = app.mazlens.data.history.HistoryStore(temporary.newFolder())
            history.save(old)
            history.save(rotation)
            val recovered = history.recoverInterrupted()
            assertEquals(
                if (status == AttemptStatus.SUCCESS) "NewPwd123" else null,
                VideoCredentials.select("", "", "recorder-a", recovered),
            )
            assertEquals(
                "TypedPwd",
                VideoCredentials.select("TypedPwd", "recorder-a", "recorder-a", recovered),
            )
            assertEquals(
                "OtherPwd",
                VideoCredentials.select(
                    "",
                    "",
                    "recorder-b",
                    listOf(rotation, record("recorder-b", "OtherPwd"), old),
                ),
            )
        }
    }

    @Test
    fun enteredPasswordSurvivesDisconnectionOnlyForItsOwner() {
        val disconnected = VideoCredentials.retainEntered("Camera77", "recorder-a", "")
        assertEquals("Camera77", disconnected)
        assertEquals(
            "Camera77",
            VideoCredentials.retainEntered(disconnected, "recorder-a", "recorder-a"),
        )
        assertEquals("", VideoCredentials.retainEntered(disconnected, "recorder-a", "recorder-b"))
    }

    @Test
    fun enteredPasswordCannotFollowAnotherRecorderWithTheSameName() {
        val first = ConnectionUiState(name = "Mazda", recorderId = "recorder-a")
        val second = first.copy(recorderId = "recorder-b")
        assertEquals(
            "",
            VideoCredentials.retainEntered("Camera77", first.recorderId, second.recorderId),
        )
    }

    @Test
    fun offlineInputCannotBecomeAnotherRecordersPassword() {
        assertEquals("Camera77", VideoCredentials.retainEntered("Camera77", "", ""))
        assertEquals("", VideoCredentials.retainEntered("Camera77", "", "recorder-a"))
        assertNull(
            VideoCredentials.select(
                "Camera77",
                "",
                "recorder-a",
                listOf(record("recorder-a", "SavedPwd")),
            )
        )
    }

    @Test
    fun unidentifiedActiveEndpointCannotUseRetainedPasswordBeforeOwnerReconnects() {
        val owner =
            ConnectionUiState(connected = true, address = "192.0.2.1", recorderId = "recorder-a")
        val unidentified = owner.copy(recorderId = "")
        val retained =
            VideoCredentials.retainEntered("Camera77", owner.recorderId, unidentified.recorderId)
        assertTrue(unidentified.connected)
        assertEquals("Camera77", retained)
        assertNull(
            VideoCredentials.enteredForRecorder(retained, owner.recorderId, unidentified.recorderId)
        )
        assertNull(
            VideoCredentials.select(
                retained,
                owner.recorderId,
                unidentified.recorderId,
                emptyList(),
            )
        )

        val restored = VideoCredentials.retainEntered(retained, owner.recorderId, owner.recorderId)
        assertEquals(
            "Camera77",
            VideoCredentials.enteredForRecorder(restored, owner.recorderId, owner.recorderId),
        )
        assertEquals(
            "Camera77",
            VideoCredentials.select(restored, owner.recorderId, owner.recorderId, emptyList()),
        )
    }

    @Test
    fun passwordFromAnotherOwnerCannotOverrideOrFallBackToStoredCredential() {
        val history = listOf(record("recorder-b", "SavedPwd"))
        assertNull(VideoCredentials.enteredForRecorder("Camera77", "recorder-a", "recorder-b"))
        assertNull(VideoCredentials.select("Camera77", "recorder-a", "recorder-b", history))
    }

    private fun record(
        recorderId: String,
        pw: String,
        status: AttemptStatus = AttemptStatus.SUCCESS,
        name: String = "Mazda",
    ) =
        AttemptRecord(
            "id",
            1,
            AttemptKind.ACCESS,
            "title",
            "summary",
            status,
            networkSnapshot = "Регистратор: $name\n",
            passwordCandidate = pw,
            recorderId = recorderId,
        )

    @Test
    fun invalidExplicitCredentialNeverFallsBackToStoredOne() {
        assertNull(
            VideoCredentials.select(
                "short",
                "my-dvr",
                "my-dvr",
                listOf(record("my-dvr", "SavedPwd")),
            )
        )
    }

    @Test
    fun recoveryRequiresSameRecorderSuccessfulReplyAndUnmaskedValue() {
        val old =
            listOf(
                record("other-dvr", "OtherPwd"),
                record("my-dvr", "********"),
                record("my-dvr", "DeniedPw", AttemptStatus.REJECTED),
                record("my-dvr", "Camera77"),
            )
        assertEquals("Camera77", VideoCredentials.select("", "", "my-dvr", old))
        val state =
            MazLensUiState(
                connection =
                    ConnectionUiState(connected = true, name = "Mazda", recorderId = "my-dvr"),
                history = old,
            )
        assertEquals("Camera77", state.recoveredPasswordCandidate)
        assertNull(
            state
                .copy(
                    connection =
                        ConnectionUiState(connected = true, name = "Mazda", recorderId = "new-dvr")
                )
                .recoveredPasswordCandidate
        )
        assertEquals(
            "Camera77",
            state
                .copy(connection = state.connection.copy(name = "Renamed"))
                .recoveredPasswordCandidate,
        )
        assertNull(state.copy(connection = ConnectionUiState()).recoveredPasswordCandidate)
    }

    @Test
    fun unscopedHistoryCannotProvideCredentialsEvenWithAMatchingName() {
        val legacy = listOf(record("", "Camera77", name = "my-dvr"))
        assertNull(VideoCredentials.select("", "", "my-dvr", legacy))
        assertNull(VideoCredentials.select("", "", "", legacy))
        assertNull(VideoCredentials.select("", "", " ", legacy))
    }
}
