package app.mazlens.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.mazlens.BuildConfig
import app.mazlens.app.MazLensUiState
import app.mazlens.core.protocol.RecorderSetting
import app.mazlens.data.history.AttemptRecord
import app.mazlens.feature.access.AccessScreen
import app.mazlens.feature.archive.ArchiveScreen
import app.mazlens.feature.connection.ConnectionScreen
import app.mazlens.feature.history.HistoryScreen
import app.mazlens.feature.player.SavedPlayerRoute
import app.mazlens.feature.settings.SettingsScreen
import app.mazlens.feature.video.VideoScreen
import app.mazlens.ui.theme.MazLensTheme

data class MazLensActions(
    val onDiscover: () -> Unit = {},
    val onOpenWifi: () -> Unit = {},
    val onOpenLocation: () -> Unit = {},
    val onDisconnect: () -> Unit = {},
    val onConnect: (String) -> Unit = {},
    val onSelectMode: (Int) -> Unit = {},
    val onPasswordChange: (String) -> Unit = {},
    val onUseRecoveredPassword: () -> Unit = {},
    val onPasswordLogin: () -> Unit = {},
    val onAccess: () -> Unit = {},
    val onBaseline: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onVideo: () -> Unit = {},
    val onStopVideo: () -> Unit = {},
    val onExport: () -> Unit = {},
    val onCopyRecord: (AttemptRecord) -> Unit = {},
    val onClearLog: () -> Unit = {},
    val onVideoCamera: (Boolean) -> Unit = {},
    val onArchiveRefresh: () -> Unit = {},
    val onDownload: (String) -> Unit = {},
    val onPlaySaved: (String) -> Unit = {},
    val onShareSaved: (String) -> Unit = {},
    val onStopSaved: () -> Unit = {},
    val onReadSettings: () -> Unit = {},
    val onEditSetting: (RecorderSetting, Int) -> Unit = { _, _ -> },
    val onApplySettings: () -> Unit = {},
    val onDefaultSettings: () -> Unit = {},
    val onChangeRecorderPassword: (String, String) -> Unit = { _, _ -> },
    val onDeleteRecording: (String, Long) -> Unit = { _, _ -> },
)

private enum class Destination(val title: String, val tag: String, val icon: ImageVector) {
    CONNECTION("Подключение", "tab_connection", Icons.Default.Settings),
    ACCESS("Проверки", "tab_access", Icons.Default.Lock),
    VIDEO("Камеры", "tab_video", Icons.Default.PlayArrow),
    ARCHIVE("Записи", "tab_archive", Icons.AutoMirrored.Filled.List),
    SETTINGS("Настройки", "tab_settings", Icons.Default.Settings),
    HISTORY("Журнал", "tab_history", Icons.AutoMirrored.Filled.List),
}

@Composable
fun MazLensApp(state: MazLensUiState, actions: MazLensActions) {
    var selectedTab by rememberSaveable { mutableStateOf(Destination.CONNECTION.name) }
    var lastMainTab by rememberSaveable { mutableStateOf(Destination.CONNECTION.name) }
    val screenState = rememberSaveableStateHolder()
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    val destination =
        Destination.entries.firstOrNull { it.name == selectedTab } ?: Destination.CONNECTION
    val diagnostic = destination == Destination.ACCESS || destination == Destination.HISTORY
    val mainTabs =
        listOf(Destination.CONNECTION, Destination.VIDEO, Destination.ARCHIVE, Destination.SETTINGS)
    val openRecord: (AttemptRecord) -> Unit = { record ->
        detailId = record.id
        selectedTab = Destination.HISTORY.name
    }
    BackHandler(diagnostic && state.archive.player == null) {
        if (detailId != null) detailId = null else selectedTab = lastMainTab
    }

    MazLensTheme {
        state.archive.player?.let { player ->
            SavedPlayerRoute(
                player,
                state.archive.playingLabel,
                state.archive.playingName,
                onClose = actions.onStopSaved,
                onShare = { actions.onShareSaved(state.archive.playingId) },
            )
            return@MazLensTheme
        }
        Scaffold(
            modifier =
                Modifier.windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                ),
            topBar = {
                AppHeader(state, actions) {
                    selectedTab = Destination.HISTORY.name
                    detailId = null
                }
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    mainTabs.forEach { tab ->
                        NavigationBarItem(
                            selected = (if (diagnostic) lastMainTab else selectedTab) == tab.name,
                            onClick = {
                                selectedTab = tab.name
                                lastMainTab = tab.name
                                detailId = null
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = {
                                Text(tab.title, style = MaterialTheme.typography.labelSmall)
                            },
                            modifier = Modifier.testTag(tab.tag),
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        if (diagnostic) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(
                                    onClick = {
                                        selectedTab = lastMainTab
                                        detailId = null
                                    },
                                    modifier = Modifier.testTag("diagnostics_back"),
                                ) {
                                    Text("← Назад")
                                }
                                listOf(Destination.ACCESS, Destination.HISTORY).forEach { tab ->
                                    TextButton(
                                        onClick = {
                                            selectedTab = tab.name
                                            detailId = null
                                        },
                                        modifier = Modifier.testTag(tab.tag),
                                    ) {
                                        Text(
                                            tab.title,
                                            fontWeight =
                                                if (destination == tab) FontWeight.Bold
                                                else FontWeight.Normal,
                                        )
                                    }
                                }
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            when (destination) {
                                Destination.CONNECTION -> ConnectionScreen(state, actions)
                                Destination.ACCESS -> AccessScreen(state, actions, openRecord)
                                Destination.VIDEO -> VideoScreen(state, actions)
                                Destination.SETTINGS -> SettingsScreen(state, actions)
                                Destination.ARCHIVE ->
                                    screenState.SaveableStateProvider("archive") {
                                        ArchiveScreen(state, actions)
                                    }
                                Destination.HISTORY ->
                                    HistoryScreen(
                                        state,
                                        actions,
                                        detailId,
                                        openRecord,
                                        onBack = { detailId = null },
                                    )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppHeader(state: MazLensUiState, actions: MazLensActions, onDiagnostics: () -> Unit) {
    Column(Modifier.background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "MazLens",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                BuildConfig.VERSION_NAME,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onDiagnostics, modifier = Modifier.testTag("open_diagnostics")) {
                Text("Диагностика")
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(9.dp)
                    .background(
                        if (state.connection.connected) Color(0xFF71DBB9) else Color(0xFFAFBFCD),
                        CircleShape,
                    )
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    when {
                        state.connection.connected -> "Телефон подключён к регистратору"
                        state.connection.connecting -> "Подключаемся…"
                        else -> "Регистратор не подключён"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (state.busy || state.connection.connecting) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        state.operationLabel.ifBlank {
                            if (state.connection.connecting) "Устанавливаем соединение…"
                            else "Проверка выполняется…"
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(
                        onClick = actions.onCancel,
                        modifier = Modifier.testTag("cancel_operation"),
                    ) {
                        Text("Отмена")
                    }
                }
            }
        }
    }
}
