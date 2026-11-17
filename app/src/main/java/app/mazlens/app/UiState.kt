package app.mazlens.app

import androidx.media3.common.Player
import app.mazlens.data.history.AttemptKind
import app.mazlens.data.history.AttemptRecord

data class PeerUi(val name: String, val address: String, val status: Int)

data class ConnectionUiState(
    val supported: Boolean = true,
    val permissionGranted: Boolean = false,
    val wifiEnabled: Boolean = true,
    val locationEnabled: Boolean = true,
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val name: String = "",
    val address: String = "",
    val networkInterface: String = "",
    val recorderId: String = "",
    val status: String = "Найдите регистратор рядом с машиной",
    val peers: List<PeerUi> = emptyList(),
)

data class VideoUiState(
    val serverResponded: Boolean = false,
    val descriptionReceived: Boolean = false,
    val videoAdvertised: Boolean = false,
    val firstFrame: Boolean = false,
    val checking: Boolean = false,
    val previewing: Boolean = false,
    val message: String = "Видеопоток ещё не проверялся",
    val player: Player? = null,
    val problem: ProblemExplanation? = null,
)

data class MazLensUiState(
    val connection: ConnectionUiState = ConnectionUiState(),
    val busy: Boolean = false,
    val operationLabel: String = "",
    val operationKind: AttemptKind? = null,
    val accessMode: Int = 0,
    val passwordInput: String = "",
    val history: List<AttemptRecord> = emptyList(),
    val latestAccess: AttemptRecord? = null,
    val video: VideoUiState = VideoUiState(),
    val log: String = "",
    val loadingHistory: Boolean = true,
    val exporting: Boolean = false,
    val storageWarning: String = "",
    val videoCameraRear: Boolean = false,
    val archive: ArchiveUiState = ArchiveUiState(),
    val passwordCheck: AttemptRecord? = null,
    val passwordRecorderId: String = "",
) {
    val passwordValid: Boolean
        get() = passwordInput.length in 8..31

    val recoveredPasswordCandidate: String?
        get() =
            if (connection.connected) VideoCredentials.select("", connection.name, history)
            else null
}
