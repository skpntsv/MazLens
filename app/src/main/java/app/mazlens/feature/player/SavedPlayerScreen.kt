package app.mazlens.feature.player

import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import java.util.Locale
import kotlinx.coroutines.delay

private val PlayerBackground = Color(0xFF080E15)
private val PlayerControls = Color(0xFF15212E)
private val PlayerAccent = Color(0xFF86DEC4)

/** Controls only: the ViewModel owns the player; this screen never opens files or sockets. */
@OptIn(UnstableApi::class)
@Composable
fun SavedPlayerScreen(
    player: Player,
    title: String,
    subtitle: String,
    fullscreen: Boolean,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onToggleFullscreen: () -> Unit,
) {
    var position by remember(player) { mutableLongStateOf(0L) }
    var duration by remember(player) { mutableLongStateOf(0L) }
    var seekable by remember(player) { mutableStateOf(false) }
    var playing by remember(player) { mutableStateOf(player.playWhenReady) }
    var playbackState by remember(player) { mutableIntStateOf(player.playbackState) }
    var scrub by remember(player) { mutableStateOf<Float?>(null) }
    DisposableEffect(player) {
        fun refresh() {
            duration = player.duration.coerceAtLeast(0)
            seekable =
                duration > 0 &&
                    player.isCurrentMediaItemSeekable &&
                    player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED
            playbackState = player.playbackState
            position = player.currentPosition.coerceAtLeast(0)
        }
        val listener =
            object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    refresh()
                }
            }
        player.addListener(listener)
        refresh()
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0)
            delay(200)
        }
    }
    fun seek(target: Long) {
        if (seekable) {
            val value = target.coerceIn(0, duration)
            player.seekTo(value)
            position = value
        }
    }
    val header: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose, modifier = Modifier.testTag("saved_stop")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "К записям", tint = Color.White)
            }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    color = Color(0xFFAFBFCD),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onShare, modifier = Modifier.testTag("saved_share")) {
                Icon(Icons.Default.Share, "Поделиться оригиналом", tint = Color.White)
            }
        }
    }
    val video: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        useController = false
                        keepScreenOn = true
                    }
                },
                modifier = Modifier.fillMaxSize().testTag("saved_player"),
                update = { it.player = player },
                onReset = null,
                onRelease = {
                    it.player = null
                    it.keepScreenOn = false
                },
            )
            if (playbackState == Player.STATE_BUFFERING)
                CircularProgressIndicator(color = PlayerAccent, modifier = Modifier.size(36.dp))
        }
    }
    val controls: @Composable () -> Unit = {
        Box(
            Modifier.fillMaxWidth()
                .background(if (fullscreen) PlayerControls.copy(alpha = .88f) else PlayerControls),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.widthIn(max = 800.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 2.dp, bottom = 8.dp)
            ) {
                Slider(
                    value =
                        scrub
                            ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f)
                            else 0f,
                    onValueChange = { scrub = it },
                    onValueChangeFinished = {
                        scrub?.let { seek((it * duration).toLong()) }
                        scrub = null
                    },
                    enabled = seekable,
                    colors =
                        SliderDefaults.colors(
                            thumbColor = PlayerAccent,
                            activeTrackColor = PlayerAccent,
                            inactiveTrackColor = Color(0xFF43515F),
                        ),
                    modifier =
                        Modifier.fillMaxWidth().testTag("saved_seek").semantics {
                            contentDescription = "Позиция видео"
                        },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        timeLabel(scrub?.let { (it * duration).toLong() } ?: position),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.testTag("saved_position"),
                    )
                    Text(
                        if (duration > 0) timeLabel(duration) else "—:—",
                        color = Color(0xFFAFBFCD),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Row(
                    Modifier.fillMaxWidth().height(72.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Spacer(Modifier.size(44.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TextButton(
                            onClick = { seek(player.currentPosition - 10_000) },
                            enabled = seekable,
                            contentPadding = PaddingValues(0.dp),
                            colors =
                                ButtonDefaults.textButtonColors(
                                    contentColor = Color.White,
                                    disabledContentColor = Color.Gray,
                                ),
                            modifier =
                                Modifier.size(48.dp, 56.dp).testTag("saved_back_10").semantics {
                                    contentDescription = "Назад на 10 секунд"
                                },
                        ) {
                            Text("−10 с", maxLines = 1)
                        }
                        FilledIconButton(
                            onClick = {
                                if (playing) player.pause()
                                else {
                                    if (player.playbackState == Player.STATE_ENDED) seek(0)
                                    player.play()
                                }
                                playing =
                                    player.playWhenReady &&
                                        player.playbackState != Player.STATE_ENDED
                            },
                            shape = CircleShape,
                            colors =
                                IconButtonDefaults.filledIconButtonColors(
                                    containerColor = PlayerAccent,
                                    contentColor = PlayerBackground,
                                ),
                            modifier = Modifier.size(60.dp).testTag("saved_play_pause"),
                        ) {
                            if (playing)
                                Canvas(
                                    Modifier.size(26.dp).semantics { contentDescription = "Пауза" }
                                ) {
                                    val w = size.width
                                    drawLine(
                                        PlayerBackground,
                                        Offset(w * .3f, w * .15f),
                                        Offset(w * .3f, w * .85f),
                                        strokeWidth = w * .19f,
                                    )
                                    drawLine(
                                        PlayerBackground,
                                        Offset(w * .7f, w * .15f),
                                        Offset(w * .7f, w * .85f),
                                        strokeWidth = w * .19f,
                                    )
                                }
                            else
                                Icon(
                                    Icons.Default.PlayArrow,
                                    if (playbackState == Player.STATE_ENDED) "Смотреть сначала"
                                    else "Продолжить",
                                    modifier = Modifier.size(34.dp),
                                )
                        }
                        TextButton(
                            onClick = { seek(player.currentPosition + 10_000) },
                            enabled = seekable,
                            contentPadding = PaddingValues(0.dp),
                            colors =
                                ButtonDefaults.textButtonColors(
                                    contentColor = Color.White,
                                    disabledContentColor = Color.Gray,
                                ),
                            modifier =
                                Modifier.size(48.dp, 56.dp).testTag("saved_forward_10").semantics {
                                    contentDescription = "Вперёд на 10 секунд"
                                },
                        ) {
                            Text("+10 с", maxLines = 1)
                        }
                    }
                    IconButton(
                        onClick = onToggleFullscreen,
                        modifier = Modifier.size(44.dp).testTag("saved_fullscreen"),
                    ) {
                        Canvas(
                            Modifier.size(22.dp).semantics {
                                contentDescription =
                                    if (fullscreen) "Выйти из полного экрана" else "На весь экран"
                            }
                        ) {
                            val w = size.width
                            val h = size.height
                            val arm = w * .3f
                            val stroke = 2.dp.toPx()
                            listOf(0f to 0f, w to 0f, 0f to h, w to h).forEach { (x, y) ->
                                val dx = if (x == 0f) 1 else -1
                                val dy = if (y == 0f) 1 else -1
                                val origin =
                                    if (fullscreen) Offset(x + dx * arm, y + dy * arm)
                                    else Offset(x, y)
                                val sign = if (fullscreen) -1 else 1
                                drawLine(
                                    Color.White,
                                    origin,
                                    origin + Offset(dx * arm * sign, 0f),
                                    stroke,
                                )
                                drawLine(
                                    Color.White,
                                    origin,
                                    origin + Offset(0f, dy * arm * sign),
                                    stroke,
                                )
                            }
                        }
                    }
                }
                if (playbackState == Player.STATE_READY && !seekable)
                    Text(
                        "В этом файле перемотка недоступна",
                        color = Color(0xFFAFBFCD),
                        style = MaterialTheme.typography.bodySmall,
                    )
            }
        }
    }
    var showControls by rememberSaveable(fullscreen) { mutableStateOf(!fullscreen) }
    Box(
        Modifier.fillMaxSize()
            .background(PlayerBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("saved_player_screen")
    ) {
        if (fullscreen) {
            video(Modifier.fillMaxSize())
            Box(
                Modifier.matchParentSize().testTag("saved_controls_toggle").clickable(
                    onClickLabel = if (showControls) "Скрыть управление" else "Показать управление"
                ) {
                    showControls = !showControls
                }
            )
            if (showControls)
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.background(PlayerBackground.copy(alpha = .8f))) { header() }
                    Spacer(Modifier.weight(1f))
                    controls()
                }
        } else
            Column(Modifier.fillMaxSize()) {
                header()
                video(Modifier.weight(1f).fillMaxWidth())
                controls()
            }
    }
}

private fun timeLabel(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600)
        String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
