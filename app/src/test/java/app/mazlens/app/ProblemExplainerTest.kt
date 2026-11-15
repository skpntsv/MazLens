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
    fun recorderCodesHaveDistinctSafeNextSteps() {
        val expected =
            mapOf(
                -51 to "подожд",
                -29 to "регистр",
                -4 to "повтор",
                -21 to "подожд",
                -50 to "отчёт",
                -53 to "питани",
            )
        expected.forEach { (code, action) ->
            val p = explain("""{"msg_id":3,"rval":$code}""")
            assertTrue("$code: ${p.action}", p.action.lowercase().contains(action))
            assertEquals("rval=$code", p.details)
        }
        assertTrue(explain("""{"msg_id":2,"rval":-29}""").action.contains("Подключение"))
        val sd = text(explain("""{"msg_id":257,"rval":-53}""")).lowercase()
        assertFalse(sd.contains("формат"))
        assertFalse(sd.contains("сброс"))
    }

    @Test
    fun onlyValidatedPrimaryNumericCodeIsEvidence() {
        listOf("\"-52\"", "true", "null", "-52.5", "-52.00000000000001", "4294967244").forEach {
            assertEquals("", explain("""{"msg_id":257,"rval":$it}""").details)
        }
        listOf(
                """{"msg_id":1,"rval":-52,"type":"w.pass"}""",
                """{"msg_id":999,"rval":-52}""",
                """{"param":"{\"msg_id\":257,\"rval\":-52}"}""",
            )
            .forEach { assertFalse(text(explain(it)).contains("GPS")) }
        val p =
            requireNotNull(
                ProblemExplainer.forRecord(
                    record(summary = "rval=-52").copy(trace = "RECV {\"msg_id\":257,\"rval\":-52}")
                )
            )
        assertFalse(text(p).contains("GPS"))
    }

    @Test
    fun integralJsonNumbersMatchProtocolValidation() {
        listOf("-52.0", "-52e0").forEach {
            assertEquals("rval=-52", explain("""{"msg_id":257.0,"rval":$it}""").details)
        }
    }

    @Test
    fun unknownNumericCodeDoesNotBecomeSummaryDiagnosis() {
        val p = explain("""{"msg_id":257,"rval":-987}""", "Read timed out")
        assertEquals("rval=-987", p.details)
        assertTrue(p.reason.contains("неизвест"))
        assertTrue(p.action.contains("отчёт"))
        assertFalse(p.reason.contains("врем"))
    }

    @Test
    fun nonErrorStatusesNeverInventAnError() {
        listOf(AttemptStatus.SUCCESS, AttemptStatus.RUNNING, AttemptStatus.CANCELLED).forEach {
            assertNull(
                ProblemExplainer.forRecord(
                    record("""{"msg_id":257,"rval":-52}""", "Read timed out", it)
                )
            )
        }
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
    fun rtspFramesSkipBodiesAndReadLaterActualReplies() {
        val body = "RTSP/1.0 401 Unauthorized\r\n\r\n"
        val first = "RTSP/1.0 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body"
        assertEquals("", explain(first).details)
        assertEquals(
            "RTSP 404",
            explain(first + "RTSP/1.0 404 Not Found\r\nCSeq: 2\r\n\r\n").details,
        )
    }

    @Test
    fun interruptedAndKnownFailuresGiveActionWithoutRawText() {
        assertTrue(
            requireNotNull(ProblemExplainer.forRecord(record(status = AttemptStatus.INTERRUPTED)))
                .title
                .contains("прерван")
        )
        val summaries =
            mapOf(
                "SocketTimeoutException: Read timed out" to "время",
                "Connection refused" to "соединение",
                "No route to host" to "соединение",
                "Недостаточно свободного места для записи" to "мест",
                "Некорректное BCD-время в каталоге" to "каталог",
                "Получен неполный файл регистратора" to "файл",
                "Контрольная сумма загруженного файла не совпала" to "файл",
                "Первый кадр не получен" to "кадр",
                "Ошибка проигрывателя Media3" to "видео",
                "Не удалось воспроизвести видео" to "видеопроигрыватель",
                "Регистратор не ответил за отведённое время." to "время",
                "Не удалось открыть сессию: ConnectException." to "сетевое соединение",
                "Ответ не получен за отведённое время. Смотрите журнал обмена." to "время",
                "Ответ на открытие видеосессии не получен за отведённое время." to "время",
                "Выбор камеры не подтверждён: SocketTimeoutException." to "время",
            )
        summaries.forEach { (summary, word) ->
            assertTrue(summary, text(explain(summary = summary)).lowercase().contains(word))
        }
        val timeout = explain(summary = "SocketTimeoutException: Read timed out")
        assertFalse(text(timeout).contains("GPS"))
        assertFalse(text(timeout).contains("парол"))
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
    }

    @Test
    fun connectionGeneralErrorDoesNotInventSpecificCause() {
        val p =
            requireNotNull(
                ProblemExplainer.forConnection(
                    "Подключение не удалось: Android сообщил общую ошибку Wi-Fi Direct (код 0), без подробной причины."
                )
            )
        assertTrue(p.reason.contains("причин"))
        assertTrue(p.reason.contains("не уточнил"))
        assertFalse(p.reason.contains("парол"))
        assertEquals("Android Wi-Fi Direct: 0", p.details)
    }

    @Test
    fun connectionBusyAndPrerequisitesHavePracticalActions() {
        val busy =
            requireNotNull(
                ProblemExplainer.forConnection(
                    "Служба Wi-Fi Direct выполняет другую операцию (BUSY, код 2)."
                )
            )
        assertTrue(busy.reason.contains("занят"))
        assertTrue(busy.action.contains("подожд", true))
        assertTrue(busy.action.contains("точк"))
        assertTrue(busy.action.contains("может"))
        listOf(
                "Не хватает разрешения на поиск устройств. Нажмите «Найти регистратор» и разрешите доступ.",
                "Wi-Fi Direct выключен. Включите Wi-Fi в настройках телефона.",
                "Включите геолокацию телефона для поиска устройств.",
            )
            .forEach { assertNotNull(it, ProblemExplainer.forConnection(it)) }
    }

    @Test
    fun normalConnectionStatesStayQuiet() {
        listOf(
                "",
                "Не подключено",
                "Подключено",
                "Поиск устройств…",
                "Android начал сопряжение. Ожидаем соединение до 30 секунд…",
                "Подключение к C9K1… Подтвердите запрос Android, если он появится.",
            )
            .forEach { assertNull(it, ProblemExplainer.forConnection(it)) }
    }
}
