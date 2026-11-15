package app.mazlens.app

import app.mazlens.core.protocol.AccessProbe
import app.mazlens.core.protocol.ProbeResult
import app.mazlens.data.history.AttemptStatus
import org.json.JSONObject

/** UI classification uses the same strict types as the wire protocol. */
object AccessConclusion {
    fun classify(raw: String, mode: AccessProbe.Mode, sessionOpened: Boolean): AttemptStatus =
        try {
            val json = JSONObject(raw)
            val id = strictInt(json.opt("msg_id"))
            val code = strictInt(json.opt("rval"))
            when {
                id == null || code == null -> AttemptStatus.FAILED
                mode == AccessProbe.Mode.SINGLE_FIELD && id != 1 -> AttemptStatus.FAILED
                mode != AccessProbe.Mode.SINGLE_FIELD && id != 257 && !(id == 3 && sessionOpened) ->
                    AttemptStatus.FAILED
                code != 0 -> AttemptStatus.REJECTED
                mode == AccessProbe.Mode.SINGLE_FIELD -> {
                    if (json.has("type") && json.opt("type") != "w.pass") AttemptStatus.FAILED
                    else {
                        val normalized = JSONObject().put("msg_id", 3).put("rval", 0)
                        if (json.has("param")) normalized.put("w.pass", json.opt("param"))
                        if (ProbeResult.parse(normalized.toString()).success())
                            AttemptStatus.SUCCESS
                        else AttemptStatus.FAILED
                    }
                }
                id == 3 && sessionOpened && ProbeResult.parse(raw).success() ->
                    AttemptStatus.SUCCESS
                else -> AttemptStatus.FAILED
            }
        } catch (_: Exception) {
            AttemptStatus.FAILED
        }

    private fun strictInt(value: Any?): Int? {
        val number = (value as? Number)?.toDouble() ?: return null
        return if (
            number.isFinite() &&
                number == Math.rint(number) &&
                number >= Int.MIN_VALUE &&
                number <= Int.MAX_VALUE
        )
            number.toInt()
        else null
    }
}
