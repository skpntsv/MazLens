package app.mazlens.feature.history

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import app.mazlens.data.history.AttemptRecord
import app.mazlens.data.history.AttemptStatus
import app.mazlens.ui.MazLensActions
import app.mazlens.ui.components.DetailRow
import app.mazlens.ui.components.ExpandableReport
import app.mazlens.ui.components.FeatureCard
import app.mazlens.ui.components.Notice
import app.mazlens.ui.components.ProblemCard
import app.mazlens.ui.components.ScreenHeading
import app.mazlens.ui.components.SectionTitle
import app.mazlens.ui.components.StatusPill
import app.mazlens.ui.components.attemptKindLabel
import app.mazlens.ui.components.attemptStatusLabel
import app.mazlens.ui.components.attemptTime

@Composable
fun HistoryScreen(
    state: MazLensUiState,
    actions: MazLensActions,
    detailId: String?,
    onOpenRecord: (AttemptRecord) -> Unit,
    onBack: () -> Unit,
) {
    val selectedRecord =
        detailId?.let { id ->
            state.history.firstOrNull { it.id == id } ?: state.latestAccess?.takeIf { it.id == id }
        }
    if (detailId != null) {
        AttemptDetails(selectedRecord, state.loadingHistory, actions.onCopyRecord, onBack)
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag("history_screen"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ScreenHeading(
                "История проверок",
                "Результаты сохраняются на этом телефоне между запусками.",
            )
        }
        if (state.storageWarning.isNotBlank()) {
            item { Notice(state.storageWarning, warning = true) }
        }
        item {
            FeatureCard {
                SectionTitle("Все результаты одним файлом")
                Text(
                    "Отчёт для чтения и исходные данные в ZIP. Вы сами выбираете, куда его сохранить.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = actions.onExport,
                    enabled =
                        !state.loadingHistory &&
                            !state.busy &&
                            !state.connection.connecting &&
                            !state.exporting,
                    modifier = Modifier.fillMaxWidth().testTag("export_all"),
                ) {
                    Text(if (state.exporting) "Подготовка отчёта…" else "Экспортировать всё")
                }
                Text(
                    "Отчёт может содержать пароль или токен из ответа регистратора.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.loadingHistory) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Text(
                        "Загружаем сохранённые проверки…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else if (state.history.isEmpty()) {
            item {
                FeatureCard {
                    SectionTitle("Проверок пока нет")
                    Text(
                        "Запустите проверку доступа или видео — здесь появятся время, результат и полный отчёт.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            item { SectionTitle("Сохранено · ${state.history.size}") }
            items(state.history, key = { it.id }) { record ->
                AttemptCard(record) { onOpenRecord(record) }
            }
        }
        item {
            FeatureCard {
                SectionTitle("Журнал подключения")
                Text(
                    "События поиска и подключения.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ExpandableReport(
                    "События подключения",
                    state.log,
                    "connection-journal",
                    "Журнал пока пуст",
                )
                TextButton(
                    onClick = actions.onClearLog,
                    enabled = state.log.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Очистить журнал")
                }
            }
        }
    }
}

@Composable
private fun AttemptCard(record: AttemptRecord, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("attempt_${record.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(
                attemptTime(record.timestamp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                record.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                attemptKindLabel(record.kind),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            StatusPill(attemptStatusLabel(record.status), record.status == AttemptStatus.SUCCESS)
            val problem = ProblemExplainer.forRecord(record)
            Text(problem?.title ?: record.summary, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Подробности →",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun AttemptDetails(
    record: AttemptRecord?,
    loading: Boolean,
    onCopy: (AttemptRecord) -> Unit,
    onBack: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("attempt_detail"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            TextButton(onClick = onBack, modifier = Modifier.testTag("history_back")) {
                Text("← Все проверки")
            }
        }
        if (record == null) {
            item {
                Notice(
                    if (loading) "Загружаем запись…"
                    else "Эта запись больше недоступна в истории. Откройте другую проверку."
                )
            }
        } else {
            item { ScreenHeading(record.title, attemptTime(record.timestamp)) }
            item {
                FeatureCard {
                    StatusPill(
                        attemptStatusLabel(record.status),
                        record.status == AttemptStatus.SUCCESS,
                    )
                    val problem = ProblemExplainer.forRecord(record)
                    if (problem != null) ProblemCard(problem)
                    else Text(record.summary, style = MaterialTheme.typography.bodyLarge)
                    DetailRow("Тип проверки", attemptKindLabel(record.kind))
                    if (record.passwordCandidate.isNotEmpty()) {
                        HorizontalDivider()
                        DetailRow("Кандидат пароля из ответа", record.passwordCandidate)
                        Text(
                            "Полученное значение ещё не подтверждает, что это пароль подключения Wi-Fi.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = { onCopy(record) },
                        modifier = Modifier.fillMaxWidth().testTag("copy_record"),
                    ) {
                        Text("Скопировать полный отчёт")
                    }
                }
            }
            item {
                FeatureCard {
                    SectionTitle("Технические подробности")
                    ExpandableReport(
                        "Соединение во время проверки",
                        record.networkSnapshot,
                        record.id,
                    )
                    ExpandableReport("Ответ регистратора", record.response, record.id)
                    ExpandableReport("Полный ход проверки", record.trace, record.id)
                    DetailRow("Версия приложения", record.appVersion)
                }
            }
        }
    }
}
