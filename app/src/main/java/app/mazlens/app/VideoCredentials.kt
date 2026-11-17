package app.mazlens.app

import app.mazlens.data.history.AttemptRecord
import app.mazlens.data.history.AttemptStatus

object VideoCredentials {
    fun retainEntered(entered: String, ownerId: String, recorderId: String): String =
        if (recorderId.isBlank() || recorderId == ownerId) entered else ""

    fun select(entered: String, recorderId: String, history: List<AttemptRecord>): String? {
        if (entered.isNotEmpty()) return entered.takeIf { it.length in 8..31 }
        if (recorderId.isBlank()) return null
        return history
            .asSequence()
            .filter { it.status == AttemptStatus.SUCCESS }
            .filter { it.recorderId == recorderId }
            .map { it.passwordCandidate }
            .firstOrNull { it.length in 8..31 && !it.matches(Regex("[*•●]+")) }
    }
}
