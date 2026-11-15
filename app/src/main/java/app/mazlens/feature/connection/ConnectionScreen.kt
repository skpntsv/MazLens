package app.mazlens.feature.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.mazlens.app.MazLensUiState
import app.mazlens.app.ProblemExplainer
import app.mazlens.data.history.AttemptStatus
import app.mazlens.feature.access.PasswordLoginCard
import app.mazlens.ui.MazLensActions
import app.mazlens.ui.components.DetailRow
import app.mazlens.ui.components.FeatureCard
import app.mazlens.ui.components.Notice
import app.mazlens.ui.components.ProblemCard
import app.mazlens.ui.components.ScreenHeading
import app.mazlens.ui.components.SectionTitle
import app.mazlens.ui.components.StatusPill
import app.mazlens.ui.components.attemptTime

@Composable
fun ConnectionScreen(state: MazLensUiState, actions: MazLensActions) {
    val connection = state.connection
    val available = !state.busy && !connection.connecting
    val problem =
        if (!connection.connected) ProblemExplainer.forConnection(connection.status) else null
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("connection_screen"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            ScreenHeading("Подключение", "Включите регистратор и останьтесь рядом с автомобилем.")
        }
        if (!connection.supported) {
            item { Notice("Этот телефон не поддерживает Wi-Fi Direct.", warning = true) }
        }
        item {
            FeatureCard {
                SectionTitle(
                    if (connection.connected) "Соединение установлено"
                    else "Найдите свой регистратор"
                )
                if (problem == null && !connection.connected)
                    Text(
                        connection.status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                if (connection.connected) {
                    DetailRow("Устройство", connection.name)
                    OutlinedButton(
                        onClick = actions.onDisconnect,
                        enabled = available,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Отключиться")
                    }
                } else {
                    Button(
                        onClick = actions.onDiscover,
                        enabled = available && connection.supported,
                        modifier = Modifier.fillMaxWidth().testTag("discover"),
                    ) {
                        Text(if (connection.connecting) "Подключение…" else "Найти регистратор")
                    }
                }
            }
        }
        problem?.let { item { ProblemCard(it) } }
        if (!connection.connected) {
            if (
                !connection.wifiEnabled ||
                    !connection.locationEnabled ||
                    !connection.permissionGranted
            ) {
                item {
                    FeatureCard {
                        SectionTitle("Нужно для подключения")
                        if (!connection.wifiEnabled)
                            Prerequisite(
                                "Wi-Fi",
                                false,
                                "Выключен",
                                "Включить",
                                available,
                                actions.onOpenWifi,
                            )
                        if (!connection.locationEnabled)
                            Prerequisite(
                                "Геолокация",
                                false,
                                "Нужна для поиска",
                                "Открыть",
                                available,
                                actions.onOpenLocation,
                            )
                        if (!connection.permissionGranted)
                            Prerequisite(
                                "Поиск устройств",
                                false,
                                "Нужно разрешение",
                                "Разрешить",
                                available,
                                actions.onDiscover,
                            )
                    }
                }
            }
            if (connection.peers.isNotEmpty()) {
                item { SectionTitle("Устройства рядом · ${connection.peers.size}") }
                items(connection.peers, key = { it.address }) { peer ->
                    Card(
                        onClick = { actions.onConnect(peer.address) },
                        enabled = available && connection.supported,
                        modifier = Modifier.fillMaxWidth().testTag("peer_${peer.address}"),
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                    ) {
                        Row(
                            Modifier.padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Text(
                                    peer.name.ifBlank { "Устройство Wi-Fi Direct" },
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    peerStatus(peer.status),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Text(
                                "Подключить →",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            } else {
                item {
                    Text(
                        "После поиска выберите регистратор в списке. Разрешите подключение на телефоне, если Android попросит.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            item {
                Notice(
                    "Можно смотреть камеры и скачивать записи. При необходимости укажите пароль ниже."
                )
            }
            item { PasswordLoginCard(state, actions, compact = true) }
            state.passwordCheck?.let { record ->
                val explanation = ProblemExplainer.forRecord(record)
                item {
                    Text(
                        "Последняя проверка · ${attemptTime(record.timestamp)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (explanation != null) {
                    item { ProblemCard(explanation) }
                } else if (record.status == AttemptStatus.SUCCESS) {
                    item { Notice("Пароль принят при проверке.") }
                }
            }
        }
    }
}

@Composable
private fun Prerequisite(
    title: String,
    ready: Boolean,
    missing: String,
    action: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (!ready)
                Text(
                    missing,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
        }
        if (ready) StatusPill("Готово", positive = true)
        else TextButton(onClick = onClick, enabled = enabled) { Text(action) }
    }
}

private fun peerStatus(status: Int): String =
    when (status) {
        0 -> "Соединение установлено"
        1 -> "Ожидает подключения"
        2 -> "Предыдущая попытка не удалась"
        3 -> "Доступен для подключения"
        4 -> "Сейчас недоступен"
        else -> "Устройство найдено"
    }
