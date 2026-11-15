package app.mazlens.core.protocol

import org.json.JSONException
import org.json.JSONObject

class ProbeResult
private constructor(
    private val state: String,
    private val password: String,
    private val raw: String,
    private val description: String,
) {
    fun success(): Boolean = state != "REJECTED" && state != "INVALID"

    fun state(): String = state

    fun passwordValue(): String = password

    fun hasCandidate(): Boolean = state == "VALUE"

    fun raw(): String = raw

    fun description(): String = description

    companion object {
        @JvmStatic
        fun parse(raw: String): ProbeResult {
            try {
                val json = JSONObject(raw)
                val id = json.opt("msg_id")
                val status = json.opt("rval")
                if (id !is Number || id.toDouble() != 3.0 || status !is Number) {
                    return invalid(raw, "В ответе нет корректных msg_id=3 и rval.")
                }
                if (status.toDouble() != 0.0) {
                    return ProbeResult(
                        "REJECTED",
                        "",
                        raw,
                        "Регистратор отклонил запрос: rval=$status.",
                    )
                }
                if (!json.has("w.pass")) {
                    return ProbeResult(
                        "MISSING",
                        "",
                        raw,
                        "Настройки получены. Поля w.pass в ответе нет.",
                    )
                }
                if (json.isNull("w.pass")) {
                    return ProbeResult("NULL", "", raw, "Поле w.pass вернулось со значением null.")
                }
                val field = json.get("w.pass")
                if (field !is String) return invalid(raw, "Поле w.pass имеет неожиданный тип.")
                if (field.trim { it <= ' ' }.isEmpty()) {
                    return ProbeResult("EMPTY", field, raw, "Поле w.pass пустое.")
                }
                if (field.matches(Regex("[*•●]+"))) {
                    return ProbeResult(
                        "MASKED",
                        field,
                        raw,
                        "Поле w.pass похоже на скрытое значение.",
                    )
                }
                return ProbeResult(
                    "VALUE",
                    field,
                    raw,
                    "Регистратор вернул непустое w.pass. Работоспособность этого пароля ещё не проверена.",
                )
            } catch (error: JSONException) {
                return invalid(raw, "Не удалось разобрать JSON: ${error.message}")
            }
        }

        private fun invalid(raw: String, reason: String): ProbeResult =
            ProbeResult("INVALID", "", raw, reason)
    }
}
