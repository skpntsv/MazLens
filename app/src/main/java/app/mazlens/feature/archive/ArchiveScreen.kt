package app.mazlens.feature.archive

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.mazlens.app.MazLensUiState
import app.mazlens.ui.MazLensActions
import app.mazlens.ui.components.FeatureCard
import app.mazlens.ui.components.ProblemCard
import app.mazlens.ui.components.ScreenHeading
import java.util.Locale

@Composable
fun ArchiveScreen(state: MazLensUiState, actions: MazLensActions) {
    var source by rememberSaveable { mutableIntStateOf(0) }
    var camera by rememberSaveable { mutableIntStateOf(0) }
    var category by rememberSaveable { mutableIntStateOf(0) }
    val archive = state.archive
    val savedIds = remember(archive.saved) { archive.saved.mapTo(hashSetOf()) { it.entry.id } }
    val remoteEnabled =
        state.connection.connected &&
            !state.connection.connecting &&
            !state.busy &&
            !state.loadingHistory
    val files = if (source == 0) archive.files else archive.saved.map { it.entry }
    val visible =
        files.filter {
            (camera == 0 || it.camera == camera) && (category == 0 || it.category == category)
        }
    var deleteId by
        remember(
            archive.files,
            archive.recorderId,
            state.connection.recorderId,
            state.recorderActionEpoch,
            remoteEnabled,
            source,
        ) {
            mutableStateOf<String?>(null)
        }
    LazyColumn(
        Modifier.fillMaxSize().testTag("archive_screen"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ScreenHeading("Записи", "Сохраните оригинал, чтобы смотреть его без машины.") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(
                    selected = source == 0,
                    onClick = { source = 0 },
                    label = { Text("Регистратор") },
                )
                FilterChip(
                    selected = source == 1,
                    onClick = { source = 1 },
                    label = { Text("На телефоне") },
                )
            }
        }
        if (source == 0)
            item {
                Button(
                    onClick = actions.onArchiveRefresh,
                    enabled = remoteEnabled,
                    modifier = Modifier.fillMaxWidth().testTag("archive_refresh"),
                ) {
                    Text(if (archive.loading) "Читаю список…" else "Обновить список")
                }
            }
        if (archive.downloadingId != null) {
            item {
                FeatureCard {
                    Text("Скачивание оригинала")
                    Text(
                        "Оставьте приложение открытым до завершения.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (archive.totalBytes > 0)
                        LinearProgressIndicator(
                            progress = {
                                (archive.receivedBytes.toFloat() / archive.totalBytes).coerceIn(
                                    0f,
                                    1f,
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("${sizeLabel(archive.receivedBytes)} / ${sizeLabel(archive.totalBytes)}")
                    OutlinedButton(
                        onClick = actions.onCancel,
                        modifier = Modifier.testTag("download_cancel"),
                    ) {
                        Text("Отменить")
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Все", 1 to "Передняя", 2 to "Задняя").forEach { (value, label) ->
                    FilterChip(
                        selected = camera == value,
                        onClick = { camera = value },
                        label = { Text(label) },
                    )
                }
            }
        }
        if (source == 0) {
            archive.problem?.let { problem -> item { ProblemCard(problem) } }
            if (archive.problem == null)
                item { Text(archive.message, style = MaterialTheme.typography.bodyMedium) }
            if (!state.connection.connected)
                item {
                    Text(
                        "Для списка записей откройте «Подключение» и выберите регистратор.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
        }
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                (0..7).forEach { value ->
                    FilterChip(
                        selected = category == value,
                        onClick = { category = value },
                        label = { Text(if (value == 0) "Все типы" else categoryLabel(value)) },
                        modifier = Modifier.testTag("archive_category_$value"),
                    )
                }
            }
        }
        if (visible.isEmpty()) {
            item {
                Text(
                    if (source == 1 && archive.loadingSaved) "Открываю библиотеку…"
                    else if (source == 1) "Сохранённых записей пока нет"
                    else "Записей в списке пока нет"
                )
            }
        }
        items(visible, key = { it.id }) { entry ->
            val saved = entry.id in savedIds
            FeatureCard {
                Text(entry.recordedAt, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${if (entry.camera == 2) "Задняя" else "Передняя"} · ${categoryLabel(entry.category)} · ${sizeLabel(entry.size)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(entry.displayName, style = MaterialTheme.typography.bodySmall)
                if (saved) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { actions.onPlaySaved(entry.id) },
                            enabled = !state.busy,
                            modifier = Modifier.testTag("play_${entry.id}"),
                        ) {
                            Text("Смотреть")
                        }
                        OutlinedButton(
                            onClick = { actions.onShareSaved(entry.id) },
                            enabled = !state.busy,
                            modifier = Modifier.testTag("share_${entry.id}"),
                        ) {
                            Text("Поделиться")
                        }
                    }
                } else {
                    Button(
                        onClick = { actions.onDownload(entry.id) },
                        enabled = remoteEnabled,
                        modifier = Modifier.testTag("download_${entry.id}"),
                    ) {
                        Text("Скачать оригинал")
                    }
                }
                if (source == 0) {
                    TextButton(
                        onClick = { deleteId = entry.id },
                        enabled =
                            remoteEnabled &&
                                state.connection.recorderId.isNotBlank() &&
                                archive.recorderId == state.connection.recorderId &&
                                entry.recorderId == state.connection.recorderId,
                        modifier = Modifier.testTag("delete_${entry.id}"),
                    ) {
                        Text("Удалить с регистратора")
                    }
                }
            }
        }
    }
    archive.files
        .firstOrNull { it.id == deleteId }
        ?.takeIf { source == 0 && remoteEnabled && it.recorderId == state.connection.recorderId }
        ?.let { entry ->
            AlertDialog(
                onDismissRequest = { deleteId = null },
                title = { Text("Удалить запись с регистратора?") },
                text = {
                    Text(
                        "${entry.displayName}\nВосстановить запись на регистраторе будет нельзя. Сохранённая копия на телефоне останется."
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            deleteId = null
                            actions.onDeleteRecording(entry.id, state.recorderActionEpoch)
                        },
                        modifier = Modifier.testTag("delete_confirm"),
                    ) {
                        Text("Удалить")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { deleteId = null },
                        modifier = Modifier.testTag("delete_dismiss"),
                    ) {
                        Text("Отмена")
                    }
                },
            )
        }
}

private fun categoryLabel(category: Int): String =
    when (category) {
        2 -> "Событие"
        3 -> "Ручная запись"
        4 -> "Парковка"
        5 -> "Парковочное событие"
        6 -> "Фото"
        7 -> "Памятное фото"
        else -> "Обычная запись"
    }

private fun sizeLabel(bytes: Long): String =
    if (bytes <= 0) "0 МБ" else String.format(Locale.ROOT, "%.1f МБ", bytes / 1048576.0)
