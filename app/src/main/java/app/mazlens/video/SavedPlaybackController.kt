package app.mazlens.video

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/** Local originals only; this player never opens a recorder connection. */
@OptIn(UnstableApi::class)
class SavedPlaybackController(context: Context, private val diagnostic: (String) -> Unit = {}) {
    private val app = context.applicationContext
    private var player: ExoPlayer? = null

    fun start(file: File, onError: () -> Unit): Player {
        check(Looper.myLooper() === Looper.getMainLooper())
        stop()
        val created = ExoPlayer.Builder(app).build()
        player = created
        try {
            created.addListener(
                object : Player.Listener {
                    private var lastMetadata = ""

                    override fun onEvents(player: Player, events: Player.Events) {
                        if (this@SavedPlaybackController.player !== created) return
                        if (player.playbackState == Player.STATE_READY) {
                            val metadata =
                                "Локальное видео: durationMs=${player.duration}, seekable=${player.isCurrentMediaItemSeekable}"
                            if (metadata != lastMetadata) {
                                lastMetadata = metadata
                                diagnostic(metadata)
                            }
                        }
                    }

                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int,
                    ) {
                        if (
                            this@SavedPlaybackController.player === created &&
                                reason == Player.DISCONTINUITY_REASON_SEEK
                        )
                            diagnostic("Локальное видео: перемотка к ${newPosition.positionMs} мс")
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (player === created) {
                            diagnostic("Локальное видео: ошибка ${error.errorCodeName}")
                            stop()
                            onError()
                        }
                    }
                }
            )
            created.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            created.prepare()
            created.play()
            return created
        } catch (error: Exception) {
            stop()
            throw error
        }
    }

    fun stop() {
        check(Looper.myLooper() === Looper.getMainLooper())
        val old = player
        player = null
        old?.release()
    }
}
