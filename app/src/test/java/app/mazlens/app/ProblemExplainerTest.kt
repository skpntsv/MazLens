package app.mazlens.app

import app.mazlens.data.history.AttemptKind
import app.mazlens.data.history.AttemptRecord
import app.mazlens.data.history.AttemptStatus
import org.junit.Assert.*
import org.junit.Test

class ProblemExplainerTest {
    private fun record(
        response: String = "",
        summary: String = "",
        status: AttemptStatus = AttemptStatus.FAILED,
    ) = AttemptRecord("fixture", 0, AttemptKind.ACCESS, "", summary, status, response = response)

    private fun explain(response: String = "", summary: String = "") =
        requireNotNull(ProblemExplainer.forRecord(record(response, summary)))

    private fun text(problem: ProblemExplanation) =
        listOf(problem.title, problem.reason, problem.action, problem.details).joinToString(" ")

    @Test
    fun gpsCodeOverridesMisleadingSummaryAndExplainsPhoneDistinction() {
        val p = explain("""{"rval":-52,"msg_id":257}""", "SocketTimeoutException rval=-29")
        assertTrue(p.reason.contains("GPS"))
        assertTrue(p.reason.contains("движен"))
        assertTrue(p.action.contains("припаркован"))
        assertTrue(p.action.contains("телефон"))
        assertEquals("rval=-52", p.details)
        assertFalse(p.title.contains("парол"))
    }

    @Test
    fun actualRtspStatusesExplainVideoWithoutAccusingPassword() {
        listOf(401, 403, 404).forEach { code ->
            val p = explain("RTSP/1.0 $code Failure\r\nCSeq: 1\r\n\r\n", "rval=-29")
            assertTrue(p.title.lowercase().contains("видео"))
            assertEquals("RTSP $code", p.details)
            assertFalse(p.reason.contains("парол"))
            assertFalse(p.action.contains("парол"))
        }
        assertFalse(text(explain("""{"param":"RTSP/1.0 401 Unauthorized"}""")).contains("RTSP 401"))
    }

    @Test
    fun responseDeadlineHasATimeoutExplanation() {
        val problem = explain(summary = "Время ожидания ответа истекло.")
        assertEquals("Истекло время ожидания", problem.title)
        val localized = record(summary = "Deadline exceeded").copy(timedOut = true)
        assertEquals(problem, ProblemExplainer.forRecord(localized))
    }

    @Test
    fun arbitraryCredentialsNeverAppearInAnyExplanationField() {
        val secret = "sEcReT-password-123"
        val r =
            record("""{"msg_id":257,"rval":-52,"param":"$secret"}""", "Read timed out $secret")
                .copy(passwordCandidate = secret, trace = secret)
        assertFalse(text(requireNotNull(ProblemExplainer.forRecord(r))).contains(secret))
        assertFalse(text(explain(summary = secret)).contains(secret))
        assertNull(ProblemExplainer.forConnection(secret))
        for (kind in
            listOf(AttemptKind.SETTINGS, AttemptKind.PASSWORD_CHANGE, AttemptKind.DELETE)) {
            val problem =
                requireNotNull(ProblemExplainer.forRecord(r.copy(kind = kind, timedOut = true)))
            assertFalse(text(problem).contains(secret))
            assertFalse(problem.action.contains("повторите операцию", ignoreCase = true))
        }
    }

    @Test
    fun controlDenialsExplainValidatedCodesButUncertainResultsDoNotRecommendBlindRetry() {
        for ((kind, id) in
            listOf(
                AttemptKind.SETTINGS to 3,
                AttemptKind.PASSWORD_CHANGE to 268435457,
                AttemptKind.DELETE to 1287,
            )) {
            val denied = record("""{"msg_id":$id,"rval":-52}""").copy(kind = kind)
            assertEquals("rval=-52", ProblemExplainer.forRecord(denied)!!.details)
            val uncertain = denied.copy(response = """{"msg_id":$id,"rval":0}""", timedOut = true)
            val problem = ProblemExplainer.forRecord(uncertain)!!
            assertFalse(problem.action.contains("повторите операцию", ignoreCase = true))
            assertNotNull(
                ProblemExplainer.forRecord(uncertain.copy(status = AttemptStatus.CANCELLED))
            )
        }
    }
}
