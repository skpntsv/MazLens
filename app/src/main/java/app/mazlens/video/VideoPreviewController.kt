package app.mazlens.video

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import javax.net.SocketFactory

/**
 * Owns one explicitly requested preview. The caller supplies its already verified URI and P2P-bound
 * sockets, owns the operation gate, and stops this controller on background/disconnect. The UI may
 * attach the returned Player to a surface, but must never release it itself.
 */
@OptIn(UnstableApi::class)
class VideoPreviewController(context: Context) {
    interface Listener {
        fun firstFrame()

        fun failure(summary: String, detail: String)

        fun ended()
    }

    private val applicationContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0L
    private var active: Session? = null

    /** Synchronous construction/prepare exceptions are cleaned up and rethrown to the caller. */
    @MainThread
    fun start(uri: String, sockets: SocketFactory, listener: Listener): Player {
        requireMainThread()
        stop()
        val currentGeneration = generation
        var created: ExoPlayer? = null
        try {
            val source =
                RtspMediaSource.Factory()
                    .setSocketFactory(sockets)
                    .setForceUseRtpTcp(true)
                    .setDebugLoggingEnabled(false)
                    .setTimeoutMs(FIRST_FRAME_TIMEOUT_MS)
                    .createMediaSource(MediaItem.fromUri(uri))
            created =
                ExoPlayer.Builder(applicationContext).setLooper(Looper.getMainLooper()).build()
            val session = Session(currentGeneration, created, listener)
            active = session
            created.addListener(session.playerListener)
            handler.postDelayed(session.startupTimeout, FIRST_FRAME_TIMEOUT_MS)
            created.setMediaSource(source)
            created.prepare()
            created.play()
            return created
        } catch (error: Exception) {
            // Invalidate before release: release itself can flush pending player events.
            val session = active
            if (session != null) {
                dispose(session)?.let { error.addSuppressed(it) }
            } else {
                created?.let { player ->
                    try {
                        player.release()
                    } catch (cleanup: RuntimeException) {
                        error.addSuppressed(cleanup)
                    }
                }
            }
            throw error
        }
    }

    /** Cancelling is deliberately silent; the owner decides which history result to persist. */
    @MainThread
    fun stop() {
        requireMainThread()
        generation++
        active?.let { dispose(it) }
    }

    private inner class Session(
        val generation: Long,
        val player: ExoPlayer,
        val listener: Listener,
    ) {
        var renderedFrame = false
        val startupTimeout = Runnable {
            if (isCurrent(this) && !renderedFrame) {
                fail(this, "Первый кадр не получен", "За 12 секунд Media3 не вывел видеокадр.")
            }
        }
        val previewTimeout = Runnable { if (isCurrent(this)) finish(this) }
        val playerListener =
            object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    if (!isCurrent(this@Session) || renderedFrame) return
                    renderedFrame = true
                    handler.removeCallbacks(startupTimeout)
                    handler.postDelayed(previewTimeout, PREVIEW_DURATION_MS)
                    listener.firstFrame()
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (!isCurrent(this@Session)) return
                    fail(
                        this@Session,
                        "Не удалось воспроизвести видео",
                        "Media3: ${error.errorCodeName}\n${error.message.orEmpty()}",
                    )
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (!isCurrent(this@Session) || playbackState != Player.STATE_ENDED) return
                    if (renderedFrame) {
                        finish(this@Session)
                    } else {
                        fail(
                            this@Session,
                            "Первый кадр не получен",
                            "Поток завершился до вывода первого видеокадра.",
                        )
                    }
                }
            }
    }

    private fun isCurrent(session: Session): Boolean =
        active === session && generation == session.generation

    private fun fail(session: Session, summary: String, detail: String) {
        if (!isCurrent(session)) return
        val cleanup = dispose(session)
        session.listener.failure(summary, detail + cleanupDetail(cleanup))
    }

    private fun finish(session: Session) {
        if (!isCurrent(session)) return
        val cleanup = dispose(session)
        if (cleanup == null) {
            session.listener.ended()
        } else {
            session.listener.failure("Не удалось завершить просмотр", cleanupDetail(cleanup).trim())
        }
    }

    /**
     * Returns cleanup failure privately, so terminal callbacks still let the owner free its gate.
     */
    private fun dispose(session: Session): RuntimeException? {
        if (active === session) active = null
        generation++
        handler.removeCallbacks(session.startupTimeout)
        handler.removeCallbacks(session.previewTimeout)
        var failure: RuntimeException? = null
        try {
            session.player.removeListener(session.playerListener)
        } catch (error: RuntimeException) {
            failure = error
        }
        try {
            session.player.release()
        } catch (error: RuntimeException) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        return failure
    }

    private fun cleanupDetail(error: RuntimeException?): String =
        if (error == null) ""
        else
            "\nОшибка освобождения Media3: ${error.javaClass.simpleName}: ${error.message.orEmpty()}"

    private fun requireMainThread() {
        check(Looper.myLooper() === Looper.getMainLooper()) {
            "VideoPreviewController must run on the main thread"
        }
    }

    private companion object {
        const val FIRST_FRAME_TIMEOUT_MS = 12_000L
        const val PREVIEW_DURATION_MS = 30_000L
    }
}
