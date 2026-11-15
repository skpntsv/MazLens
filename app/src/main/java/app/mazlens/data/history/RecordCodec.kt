package app.mazlens.data.history

import org.json.JSONObject

internal object RecordCodec {
    fun encode(r: AttemptRecord): JSONObject =
        JSONObject().apply {
            put("id", r.id)
            put("timestamp", r.timestamp)
            put("kind", r.kind.name)
            put("title", r.title)
            put("summary", r.summary)
            put("status", r.status.name)
            put("trace", r.trace)
            put("response", r.response)
            put("networkSnapshot", r.networkSnapshot)
            put("passwordCandidate", r.passwordCandidate)
            put("appVersion", r.appVersion)
            put("timedOut", r.timedOut)
        }

    fun decode(o: JSONObject) =
        AttemptRecord(
            o.getString("id"),
            o.getLong("timestamp"),
            AttemptKind.valueOf(o.getString("kind")),
            o.getString("title"),
            o.getString("summary"),
            AttemptStatus.valueOf(o.getString("status")),
            o.optString("trace"),
            o.optString("response"),
            o.optString("networkSnapshot"),
            o.optString("passwordCandidate"),
            o.optString("appVersion", "unknown"),
            o.optBoolean("timedOut", false),
        )
}
