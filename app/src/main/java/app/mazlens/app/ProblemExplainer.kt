package app.mazlens.app

import app.mazlens.core.protocol.RecorderErrors
import app.mazlens.data.history.AttemptRecord
import app.mazlens.data.history.AttemptStatus
import java.math.BigDecimal
import org.json.JSONObject

/** Public text is composed only from fixed phrases and validated protocol numbers. */
data class ProblemExplanation(
    val title: String,
    val reason: String,
    val action: String,
    val details: String = "",
)

object ProblemExplainer {
    fun forRecord(record: AttemptRecord): ProblemExplanation? {
        if (
            record.status !in
                setOf(AttemptStatus.FAILED, AttemptStatus.REJECTED, AttemptStatus.INTERRUPTED)
        )
            return null
        if (record.timedOut) return timeout()
        primaryCode(record.response)
            ?.takeIf { it != 0 }
            ?.let {
                return recorder(it)
            }
        rtspFailure(record.response)?.let { code ->
            return if (code == 401 || code == 403)
                ProblemExplanation(
                    "Видеопоток недоступен",
                    "Видеосервер ограничил доступ к потоку. Причина ограничения не установлена.",
                    "Повторите подключение и просмотр. Если ошибка повторяется, сохраните отчёт в диагностике.",
                    "RTSP $code",
                )
            else
                ProblemExplanation(
                    "Видеопоток не найден",
                    "Видеосервер не предоставил запрошенный поток.",
                    "Повторите просмотр после подключения. Если поток по-прежнему недоступен, сохраните отчёт в диагностике.",
                    "RTSP $code",
                )
        }
        if (record.status == AttemptStatus.INTERRUPTED)
            return ProblemExplanation(
                "Операция прервана",
                "Приложение не получило окончательный результат операции.",
                "Проверьте подключение и повторите операцию. Незавершённую загрузку нужно скачать заново.",
            )
        val summary = record.summary.trim()
        // Exact application-generated summaries can describe a later command than the saved opening
        // response.
        for (code in listOf(-4, -21, -29, -50, -51, -52, -53)) {
            if (summary == RecorderErrors.describe(code)) return recorder(code)
        }
        if (summary == RecorderErrors.disconnected("speed_err"))
            return recorder(-52).copy(details = "")
        if (summary == RecorderErrors.disconnected("sdcard_err"))
            return recorder(-53).copy(details = "")
        return when {
            summary.startsWith("SocketTimeoutException") ||
                summary.startsWith("java.net.SocketTimeoutException") ||
                summary.equals("Read timed out", true) ||
                summary in
                    setOf(
                        "Регистратор не ответил за отведённое время.",
                        "Ответ не получен за отведённое время. Смотрите журнал обмена.",
                        "Ответ на открытие видеосессии не получен за отведённое время.",
                        "Выбор камеры не подтверждён: SocketTimeoutException.",
                        "Время ожидания RTSP истекло",
                        "Время ожидания ответа истекло",
                        "Время ожидания ответа истекло.",
                        "Превышено время загрузки файла",
                        "Регистратор перестал передавать файл",
                    ) -> timeout()
            summary == "Содержимое записи изменилось; прежний оригинал сохранён" ->
                ProblemExplanation(
                    "Запись изменилась",
                    "Под тем же именем и размером регистратор вернул другое содержимое.",
                    "Прежний оригинал доступен в разделе «На телефоне». Сохраните отчёт в диагностике для разбора новой записи.",
                )
            summary.startsWith("Connection refused", true) ||
                summary.startsWith("No route to host", true) ||
                summary.startsWith("java.net.ConnectException") ||
                summary.startsWith("java.net.NoRouteToHostException") ||
                summary in
                    setOf(
                        "Ошибка соединения RTSP",
                        "Не удалось открыть сессию: ConnectException.",
                        "Не удалось открыть сессию: NoRouteToHostException.",
                    ) ->
                ProblemExplanation(
                    "Нет соединения с регистратором",
                    "Сетевое соединение не удалось установить или оно стало недоступно.",
                    "Убедитесь, что регистратор включён. На вкладке «Подключение» подключитесь к нему заново и повторите операцию.",
                )
            summary == "Недостаточно свободного места для записи" ||
                summary.startsWith("No space left on device") ->
                ProblemExplanation(
                    "Недостаточно места на телефоне",
                    "На телефоне не хватает свободного места для сохранения записи.",
                    "Освободите место на телефоне и скачайте запись заново.",
                )
            summary in indexErrors ->
                ProblemExplanation(
                    "Не удалось прочитать каталог",
                    "Полученный каталог записей имеет неполные или некорректные данные.",
                    "Повторите загрузку списка. Если ошибка повторяется, сохраните отчёт в диагностике.",
                )
            summary in fileErrors ->
                ProblemExplanation(
                    "Файл не загружен полностью",
                    "Целостность полученного файла не подтверждена. Загрузка не считается сохранённой записью.",
                    "Проверьте подключение и скачайте файл заново.",
                )
            summary == "Первый кадр не получен" ->
                ProblemExplanation(
                    "Видеокадр не получен",
                    "Сведения о потоке получены, но воспроизведение реального кадра не подтверждено.",
                    "Повторите просмотр. Если кадр не появляется, сохраните отчёт в диагностике.",
                )
            summary.startsWith("Ошибка проигрывателя") ||
                summary in
                    setOf("Не удалось завершить просмотр", "Не удалось воспроизвести видео") ->
                ProblemExplanation(
                    "Не удалось воспроизвести видео",
                    "Видеопроигрыватель не смог продолжить просмотр. Точная причина неизвестна.",
                    "Запустите просмотр заново. Если ошибка повторяется, сохраните отчёт в диагностике.",
                )
            else -> unknown()
        }
    }

    fun forConnection(status: String): ProblemExplanation? =
        when {
            status.contains("(BUSY, код 2)") ->
                ProblemExplanation(
                    "Wi-Fi Direct занят",
                    "Служба Wi-Fi Direct занята другой операцией.",
                    "Подождите и повторите поиск. Включённая точка доступа телефона может мешать: попробуйте выключить её.",
                    "Android Wi-Fi Direct: 2",
                )
            status.contains("общую ошибку Wi-Fi Direct (код ") ->
                ProblemExplanation(
                    "Не удалось подключиться",
                    "Android не уточнил причину ошибки Wi-Fi Direct.",
                    "Повторите поиск и подключение. Если ошибка повторяется, выключите и включите Wi-Fi телефона, затем сохраните отчёт в диагностике.",
                    Regex("\\(код (-?\\d{1,9})\\)")
                        .find(status)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()
                        ?.let { "Android Wi-Fi Direct: $it" } ?: "",
                )
            status.startsWith("Не хватает разрешения на поиск устройств") ->
                ProblemExplanation(
                    "Нужно разрешение на поиск",
                    "Android не разрешил приложению искать устройства поблизости.",
                    "Нажмите «Найти регистратор» и предоставьте запрошенное разрешение. При необходимости откройте разрешения приложения в настройках телефона.",
                )
            status == "Wi-Fi Direct выключен. Включите Wi-Fi в настройках телефона." ->
                ProblemExplanation(
                    "Wi-Fi выключен",
                    "Для поиска и подключения требуется Wi-Fi телефона.",
                    "Включите Wi-Fi в настройках телефона и повторите поиск.",
                )
            status == "Включите геолокацию телефона для поиска устройств." ->
                ProblemExplanation(
                    "Нужна геолокация телефона",
                    "Android требует включить геолокацию телефона для поиска устройств Wi-Fi Direct.",
                    "Включите геолокацию телефона и повторите поиск. Это не заменяет сигнал GPS регистратора.",
                )
            status in
                setOf(
                    "Телефон не поддерживает Wi-Fi Direct.",
                    "На этом устройстве Wi-Fi Direct недоступен.",
                ) ->
                ProblemExplanation(
                    "Wi-Fi Direct недоступен",
                    "Телефон или его служба не предоставляют Wi-Fi Direct.",
                    "Проверьте поддержку Wi-Fi Direct на телефоне. Если поддержка есть, откройте приложение заново и сохраните отчёт при повторной ошибке.",
                )
            status.startsWith("Служба Wi-Fi Direct отключилась.") ||
                status.startsWith("Служба Wi-Fi Direct недоступна.") ->
                ProblemExplanation(
                    "Служба подключения недоступна",
                    "Приложение потеряло доступ к службе Wi-Fi Direct телефона.",
                    "Закройте и откройте приложение, затем повторите поиск.",
                )
            status.startsWith("За 30 секунд сопряжение не завершилось.") ||
                status.startsWith("Сопряжение отменено по таймауту.") ->
                ProblemExplanation(
                    "Подключение не завершилось",
                    "Android не подтвердил соединение за отведённое время.",
                    "Дождитесь отмены запроса, повторите поиск и подтвердите запрос Android, если он появится.",
                )
            status.startsWith("Телефон стал владельцем группы.") ->
                ProblemExplanation(
                    "Подключение нужно повторить",
                    "Владельцем соединения стал телефон, а приложению нужен регистратор в этой роли.",
                    "Отключите Wi-Fi Direct и подключитесь к регистратору заново.",
                )
            status.startsWith("Уже есть другое соединение Wi-Fi Direct.") ->
                ProblemExplanation(
                    "Выбрано другое соединение",
                    "Текущее соединение Wi-Fi Direct не соответствует выбранному регистратору.",
                    "Отключитесь и выберите свой регистратор на вкладке «Подключение».",
                )
            status.startsWith("Android не подтвердил ни отмену, ни отключение.") ||
                status.startsWith("Android не отменил сопряжение:") ||
                status.startsWith("Android не удалил группу (") ->
                ProblemExplanation(
                    "Отключение не подтверждено",
                    "Android не подтвердил завершение предыдущего соединения.",
                    "Повторите «Отключить Wi-Fi Direct». Если это не помогает, выключите и включите Wi-Fi телефона.",
                )
            else -> null
        }

    private fun recorder(code: Int): ProblemExplanation {
        val p =
            when (code) {
                -52 ->
                    ProblemExplanation(
                        "Регистратор ограничил доступ",
                        "Регистратор сообщает об отсутствии сигнала GPS или возможном движении автомобиля.",
                        "В припаркованном автомобиле дождитесь сигнала GPS регистратора и повторите запрос. Геолокация телефона не заменяет GPS регистратора.",
                    )
                -51 ->
                    ProblemExplanation(
                        "Регистратор обрабатывает данные",
                        "Регистратор ещё занят обработкой данных и пока не принимает этот запрос.",
                        "Подождите и повторите операцию.",
                    )
                -29 ->
                    ProblemExplanation(
                        "Пароль не принят",
                        "Регистратор отклонил переданный пароль.",
                        "На вкладке «Подключение» проверьте пароль, регистр букв и пробелы, затем повторите запрос.",
                    )
                -4 ->
                    ProblemExplanation(
                        "Сессия больше не действует",
                        "Регистратор не принимает текущую сессию приложения.",
                        "Повторите операцию, чтобы открыть новую сессию. Если ошибка повторяется, подключитесь заново.",
                    )
                -21 ->
                    ProblemExplanation(
                        "Регистратор занят",
                        "Регистратор выполняет другую команду.",
                        "Подождите завершения другой операции и повторите запрос.",
                    )
                -50 ->
                    ProblemExplanation(
                        "Модель не принята",
                        "Регистратор не принял указанное приложением название модели.",
                        "Сохраните отчёт в диагностике и сообщите модель регистратора для проверки совместимости.",
                    )
                -53 ->
                    ProblemExplanation(
                        "Ошибка SD-карты регистратора",
                        "Регистратор сообщил об ошибке своей SD-карты.",
                        "Выключите питание регистратора перед проверкой установки и состояния SD-карты. Затем включите его и повторите запрос.",
                    )
                else -> unknown()
            }
        return p.copy(details = "rval=$code")
    }

    private fun timeout() =
        ProblemExplanation(
            "Истекло время ожидания",
            "Ответ или данные не поступили за отведённое время. Точная причина задержки неизвестна.",
            "Проверьте подключение к регистратору, подождите и повторите операцию.",
        )

    private fun unknown() =
        ProblemExplanation(
            "Операция не выполнена",
            "Точная причина ошибки неизвестна по имеющимся данным.",
            "Повторите операцию. Если ошибка повторяется, сохраните отчёт в диагностике для разбора.",
        )

    private fun strictInt(value: Any?): Int? {
        if (value !is Number) return null
        return try {
            BigDecimal(value.toString()).intValueExact()
        } catch (_: ArithmeticException) {
            null
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun primaryCode(response: String): Int? =
        try {
            val json = JSONObject(response)
            if (strictInt(json.opt("msg_id")) in setOf(257, 3, 2)) strictInt(json.opt("rval"))
            else null
        } catch (_: Exception) {
            null
        }

    /** Walk actual RTSP frames, skipping bodies rather than searching arbitrary response text. */
    private fun rtspFailure(response: String): Int? {
        var offset = 0
        while (offset < response.length) {
            val end = response.indexOf("\r\n\r\n", offset)
            if (end < 0) return null
            val headers = response.substring(offset, end).split("\r\n")
            val code =
                Regex("RTSP/1\\.0 ([0-9]{3})(?: [^\\r\\n]*)?")
                    .matchEntire(headers.first())
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull() ?: return null
            if (code in setOf(401, 403, 404)) return code
            val lengthHeaders = headers.drop(1).filter { it.startsWith("Content-Length:", true) }
            if (lengthHeaders.size > 1) return null
            val length =
                if (lengthHeaders.isEmpty()) 0
                else
                    lengthHeaders.single().substringAfter(':').trim().toIntOrNull()?.takeIf {
                        it >= 0
                    } ?: return null
            val next = end.toLong() + 4 + length
            if (next > response.length) return null
            offset = next.toInt()
        }
        return null
    }

    private val indexErrors =
        setOf(
            "Каталог записей обрезан",
            "В каталоге слишком много записей",
            "Некорректная длина каталога записей",
            "В каталоге противоречивые данные одной записи",
            "Некорректное BCD-время в каталоге",
            "Некорректная календарная дата в каталоге",
            "Некорректное имя записи в каталоге",
            "Неподдерживаемый тип записи",
            "Некорректный размер записи",
            "Некорректный путь записи",
            "Некорректное время записи",
        )
    private val fileErrors =
        setOf(
            "Получен неполный файл регистратора",
            "Контрольная сумма загруженного файла не совпала",
            "Итоговый размер файла не совпал с объявленным",
            "Некорректное завершение передачи",
            "Некорректный итоговый размер файла",
            "Некорректная контрольная сумма файла",
        )
}
