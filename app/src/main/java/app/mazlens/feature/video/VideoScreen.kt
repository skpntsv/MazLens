package app.mazlens.feature.video

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import app.mazlens.app.MazLensUiState
import app.mazlens.app.VideoUiState
import app.mazlens.ui.MazLensActions
import app.mazlens.ui.components.Notice
import app.mazlens.ui.components.ProblemCard
import app.mazlens.ui.components.ScreenHeading
import app.mazlens.ui.theme.RecorderNavy

@Composable
fun VideoScreen(state: MazLensUiState, actions: MazLensActions) {
    val video = state.video
    val canRun =
        state.connection.connected &&
            !state.busy &&
            !state.connection.connecting &&
            !video.previewing
    LazyColumn(
        Modifier.fillMaxSize().testTag("video_screen"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item { ScreenHeading("Камеры", "Выберите камеру. Просмотр — до 30 секунд.") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(
                    selected = !state.videoCameraRear,
                    onClick = { actions.onVideoCamera(false) },
                    enabled = !state.busy || video.previewing,
                    label = { Text("Передняя") },
                    modifier = Modifier.testTag("camera_front"),
                )
                FilterChip(
                    selected = state.videoCameraRear,
                    onClick = { actions.onVideoCamera(true) },
                    enabled = !state.busy || video.previewing,
                    label = { Text("Задняя") },
                    modifier = Modifier.testTag("camera_rear"),
                )
            }
        }
        item { VideoPreview(video) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (video.checking || video.previewing || video.player != null)
                    Button(
                        onClick = actions.onStopVideo,
                        modifier = Modifier.fillMaxWidth().testTag("video_stop"),
                    ) {
                        Text("Остановить")
                    }
                else
                    Button(
                        onClick = actions.onVideo,
                        enabled = canRun,
                        modifier = Modifier.fillMaxWidth().testTag("video_run"),
                    ) {
                        Text(if (video.problem != null) "Попробовать снова" else "Смотреть")
                    }
            }
        }
        video.problem?.let { problem -> item { ProblemCard(problem) } }
        if (!state.connection.connected) {
            item { Notice("Подключитесь к регистратору на экране «Подключение».") }
        } else if (video.problem == null) {
            item {
                Text(
                    when {
                        video.checking -> "Подключаем камеру…"
                        video.previewing -> "Идёт просмотр"
                        video.firstFrame -> "Просмотр завершён"
                        else -> "Для просмотра используется пароль с экрана «Подключение»."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item {
            Text(
                "Просмотр доступен на стоящей машине с приёмом GPS и может приостанавливать запись регистратора.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPreview(video: VideoUiState) {
    Box(
        Modifier.fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(MaterialTheme.shapes.large)
            .background(RecorderNavy),
        contentAlignment = Alignment.Center,
    ) {
        if (video.player != null) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        useController = false
                        keepScreenOn = true
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { view -> view.player = video.player },
                onReset = null,
                onRelease = { view -> view.player = null },
            )
        } else {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(44.dp), tint = Color(0xFF8DCFC4))
                Text(
                    if (video.checking) "Подключаем камеру…" else "Здесь появится изображение",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "Изображение с регистратора",
                    color = Color(0xFFB8CCD9),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
