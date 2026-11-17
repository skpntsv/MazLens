package app.mazlens.app

import app.mazlens.data.history.*
import org.junit.Assert.*
import org.junit.Test

class VideoCredentialsTest {
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
    }

    private fun record(name: String, pw: String, status: AttemptStatus = AttemptStatus.SUCCESS) =
        AttemptRecord(
            "id",
            1,
            AttemptKind.ACCESS,
            "title",
            "summary",
            status,
            networkSnapshot = "Регистратор: $name\n",
            passwordCandidate = pw,
        )

    @Test
    fun invalidExplicitCredentialNeverFallsBackToStoredOne() {
        assertNull(VideoCredentials.select("short", "my-dvr", listOf(record("my-dvr", "SavedPwd"))))
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
        assertEquals("Camera77", VideoCredentials.select("", "my-dvr", old))
        val state =
            MazLensUiState(
                connection = ConnectionUiState(connected = true, name = "my-dvr"),
                history = old,
            )
        assertEquals("Camera77", state.recoveredPasswordCandidate)
        assertNull(
            state
                .copy(connection = ConnectionUiState(connected = true, name = "new-dvr"))
                .recoveredPasswordCandidate
        )
        assertNull(state.copy(connection = ConnectionUiState()).recoveredPasswordCandidate)
    }
}
