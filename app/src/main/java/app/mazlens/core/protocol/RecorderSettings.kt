package app.mazlens.core.protocol

import java.io.IOException
import java.math.BigDecimal
import org.json.JSONObject

enum class RecorderSetting(val wireKey: String, val options: List<Int>, val defaultValue: Int) {
    PARKING_DURATION("p.time.e.15s", listOf(0, 1, 2, 4), 2),
    PARKING_START("p.start.e.30s", listOf(0, 1, 2, 6), 1),
    PARKING_END("p.stop.e.30s", listOf(0, 1, 2, 6), 1),
    PARKING_OVERWRITE("p.write.e", listOf(0, 1), 1),
    PARKING_SENSITIVITY("p.g.sens.e.val", (0..4).toList(), 2),
    QUALITY("m.qual", (0..3).toList(), 2),
    MICROPHONE("m.mic", listOf(0, 1), 1),
    MANUAL_OVERWRITE("m.write.m", listOf(0, 1), 1),
    EVENT_OVERWRITE("m.write.e", listOf(0, 1), 1),
    EVENT_SENSITIVITY("m.sens.val", (0..4).toList(), 2),
    VOLUME("s.vol.n", (0..5).toList(), 3),
}

/** A complete snapshot: bulk updates must also preserve camera configuration and credentials. */
class RecorderSettings
private constructor(
    private val values: Map<RecorderSetting, Int>,
    val cameraMode: Int,
    private val password: String,
    val firmware: String,
) {
    operator fun get(setting: RecorderSetting): Int = values.getValue(setting)

    fun with(setting: RecorderSetting, value: Int): RecorderSettings {
        require(value in setting.options)
        return RecorderSettings(values + (setting to value), cameraMode, password, firmware)
    }

    fun withDefaults(): RecorderSettings =
        RecorderSettings(
            RecorderSetting.entries.associateWith { it.defaultValue },
            cameraMode,
            password,
            firmware,
        )

    fun withPassword(value: String): RecorderSettings {
        require(validPassword(value))
        return RecorderSettings(values, cameraMode, value, firmware)
    }

    fun matches(other: RecorderSettings): Boolean =
        values == other.values && cameraMode == other.cameraMode && password == other.password

    fun hasPassword(value: String): Boolean = password == value

    internal fun request(token: Int): JSONObject {
        require(token > 0)
        return JSONObject()
            .put("msg_id", WRITE_SETTINGS)
            .put("token", token)
            .put("s.cam", cameraMode)
            .put("w.pass", password)
            .also { json ->
                values.forEach { (setting, value) -> json.put(setting.wireKey, value) }
            }
    }

    companion object {
        internal const val WRITE_SETTINGS = 268435457

        @Throws(IOException::class)
        fun parse(json: JSONObject): RecorderSettings {
            val values =
                RecorderSetting.entries.associateWith { setting ->
                    integer(json.opt(setting.wireKey))?.takeIf { it in setting.options }
                        ?: throw IOException("Получены неполные или неподдерживаемые настройки.")
                }
            val camera =
                integer(json.opt("s.cam"))?.takeIf { it in 0..1 }
                    ?: throw IOException("Неизвестная конфигурация камер.")
            val password =
                (json.opt("w.pass") as? String)?.takeIf(::validPassword)
                    ?: throw IOException("Настройки не содержат пригодный пароль.")
            val firmware =
                (json.opt("s.ver") as? String)
                    ?.takeIf { it.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){1,3}")) }
                    .orEmpty()
            return RecorderSettings(values, camera, password, firmware)
        }

        private fun validPassword(value: String): Boolean =
            value.length in 8..31 && !value.matches(Regex("[*•●]+"))

        private fun integer(value: Any?): Int? =
            if (value is String) value.toIntOrNull()?.takeIf { it.toString() == value }
            else if (value !is Number) null
            else
                try {
                    BigDecimal(value.toString()).intValueExact()
                } catch (_: ArithmeticException) {
                    null
                } catch (_: NumberFormatException) {
                    null
                }
    }
}
