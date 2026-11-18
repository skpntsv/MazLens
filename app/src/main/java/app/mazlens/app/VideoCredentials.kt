package app.mazlens.app

import app.mazlens.data.history.AttemptKind
import app.mazlens.data.history.AttemptRecord
import app.mazlens.data.history.AttemptStatus

object VideoCredentials {
    fun retainEntered(entered: String, ownerId: String, recorderId: String): String =
        if (recorderId.isBlank() || recorderId == ownerId) entered else ""

    fun enteredForRecorder(entered: String, ownerId: String, recorderId: String): String? =
        entered.takeIf { recorderId.isNotBlank() && recorderId == ownerId && it.length in 8..31 }

    fun select(
        entered: String,
        ownerId: String,
        recorderId: String,
        history: List<AttemptRecord>,
    ): String? {
        if (entered.isNotEmpty()) return enteredForRecorder(entered, ownerId, recorderId)
        if (recorderId.isBlank()) return null
        for (record in history) {
            if (record.recorderId != recorderId) continue
            val candidate = record.passwordCandidate
            if (
                record.status == AttemptStatus.SUCCESS &&
                    candidate.length in 8..31 &&
                    !candidate.matches(Regex("[*•●]+"))
            )
                return candidate
            // An interrupted rotation may have reached the recorder; older credentials are unsafe.
            if (record.kind == AttemptKind.PASSWORD_CHANGE) return null
        }
        return null
    }
}
