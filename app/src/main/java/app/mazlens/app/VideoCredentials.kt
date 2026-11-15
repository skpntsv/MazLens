package app.mazlens.app

import app.mazlens.data.history.AttemptRecord
import app.mazlens.data.history.AttemptStatus

object VideoCredentials {
    fun select(entered: String, recorder: String, history: List<AttemptRecord>): String? {
        if (entered.isNotEmpty()) return entered.takeIf { it.length in 8..31 }
        if (recorder.isEmpty()) return null
        return history
            .asSequence()
            .filter { it.status == AttemptStatus.SUCCESS }
            .filter {
                it.networkSnapshot.lineSequence().any { line -> line == "Регистратор: $recorder" }
            }
            .map { it.passwordCandidate }
            .firstOrNull { it.length in 8..31 && !it.matches(Regex("[*•●]+")) }
    }
}
