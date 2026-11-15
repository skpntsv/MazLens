package app.mazlens.app

import app.mazlens.data.history.AttemptStatus
import org.junit.Assert.*
import org.junit.Test

class VideoConclusionTest {
    @Test
    fun stopBeforeFirstFrameIsCancellationWithoutAnErrorCard() {
        val status = VideoConclusion.status(false, true, true)
        assertEquals(AttemptStatus.CANCELLED, status)
        assertNull(
            ProblemExplainer.forRecord(
                app.mazlens.data.history.AttemptRecord(
                    "stop",
                    1,
                    app.mazlens.data.history.AttemptKind.VIDEO,
                    "Камера",
                    "Просмотр остановлен",
                    status,
                )
            )
        )
    }

    @Test
    fun receivedFrameRemainsConfirmedWhenUserStops() {
        assertEquals(AttemptStatus.SUCCESS, VideoConclusion.status(true, true, true))
    }

    @Test
    fun realFailureWhileActiveStillExplainsAnError() {
        assertEquals(AttemptStatus.FAILED, VideoConclusion.status(false, true, false))
    }

    @Test
    fun lostOperationOwnershipCannotPublishAFailureForUserCancellation() {
        assertEquals(AttemptStatus.CANCELLED, VideoConclusion.status(false, false, false))
    }
}
