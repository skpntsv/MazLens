package app.mazlens.data.history

import app.mazlens.BuildConfig

enum class AttemptKind {
    BASELINE,
    ACCESS,
    VIDEO,
    ARCHIVE,
}

enum class AttemptStatus {
    RUNNING,
    SUCCESS,
    REJECTED,
    FAILED,
    CANCELLED,
    INTERRUPTED,
}

data class AttemptRecord(
    val id: String,
    val timestamp: Long,
    val kind: AttemptKind,
    val title: String,
    val summary: String,
    val status: AttemptStatus,
    val trace: String = "",
    val response: String = "",
    val networkSnapshot: String = "",
    val passwordCandidate: String = "",
    val appVersion: String = BuildConfig.VERSION_NAME,
    val timedOut: Boolean = false,
    val recorderId: String = "",
)
