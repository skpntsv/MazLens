package app.mazlens.app

import androidx.media3.common.Player
import app.mazlens.core.archive.RecorderFile
import app.mazlens.data.recordings.SavedRecording

data class ArchiveUiState(
    val recorderName: String = "",
    val files: List<RecorderFile> = emptyList(),
    val saved: List<SavedRecording> = emptyList(),
    val loadingSaved: Boolean = true,
    val loading: Boolean = false,
    val downloadingId: String? = null,
    val receivedBytes: Long = 0,
    val totalBytes: Long = 0,
    val message: String = "Обновите список рядом с включённым регистратором",
    val player: Player? = null,
    val playingName: String = "",
    val playingId: String = "",
    val playingLabel: String = "",
    val problem: ProblemExplanation? = null,
)
