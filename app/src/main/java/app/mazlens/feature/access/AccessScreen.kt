package app.mazlens.feature.access

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.mazlens.app.MazLensUiState
import app.mazlens.app.ProblemExplainer
import app.mazlens.data.history.AttemptRecord
import app.mazlens.data.history.AttemptStatus
import app.mazlens.ui.MazLensActions
import app.mazlens.ui.components.FeatureCard
import app.mazlens.ui.components.Notice
import app.mazlens.ui.components.ProblemCard
import app.mazlens.ui.components.ScreenHeading
import app.mazlens.ui.components.SectionTitle
import app.mazlens.ui.components.StatusPill
import app.mazlens.ui.components.attemptStatusLabel
import app.mazlens.ui.components.attemptTime

private data class AccessVariant(val title: String, val description: String)

private val variants =
    listOf(
        AccessVariant("Обычная сессия", "Без дополнительных полей при открытии сессии."),
        AccessVariant("С указанием модели", "Передаётся только модель регистратора."),
        AccessVariant("С пустым паролем", "Запрос с моделью и пустым полем пароля."),
        AccessVariant("Прямое чтение поля", "Запрос поля пароля без открытия сессии."),
    )

@Composable
fun AccessScreen(
    state: MazLensUiState,
    actions: MazLensActions,
    onOpenRecord: (AttemptRecord) -> Unit,
) {
    val available = !state.busy && !state.connection.connecting
    val canRun = state.connection.connected && available
    LazyColumn(
        Modifier.fillMaxSize().testTag("access_screen"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ScreenHeading("Проверки доступа", "Дополнительные проверки для разбора подключения.")
        }
        if (!state.connection.connected) {
            item { Notice("Сначала подключитесь к регистратору на вкладке «Подключение».") }
        }
        item { PasswordLoginCard(state, actions) }
        state.latestAccess?.let { record ->
            item {
                FeatureCard {
                    SectionTitle("Последний результат")
                    StatusPill(
                        attemptStatusLabel(record.status),
                        record.status == AttemptStatus.SUCCESS,
                    )
                    val problem = ProblemExplainer.forRecord(record)
                    if (problem != null) ProblemCard(problem)
                    else Text(record.summary, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        attemptTime(record.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = { onOpenRecord(record) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Открыть запись в истории →")
                    }
                }
            }
        }
        item { SectionTitle("Диагностика") }
        itemsIndexed(variants) { index, variant ->
            VariantCard(variant, index, state.accessMode == index, available) {
                actions.onSelectMode(index)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = actions.onAccess,
                    enabled = canRun,
                    modifier = Modifier.fillMaxWidth().testTag("access_run"),
                ) {
                    Text("Проверить выбранный вариант")
                }
                OutlinedButton(
                    onClick = actions.onBaseline,
                    enabled = canRun,
                    modifier = Modifier.fillMaxWidth().testTag("baseline_run"),
                ) {
                    Text("Исходная проверка чтения")
                }
                Text(
                    "Проверка читает ответ устройства и сохраняет результат в истории.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun VariantCard(
    variant: AccessVariant,
    index: Int,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier =
            Modifier.fillMaxWidth()
                .testTag("access_mode_$index")
                .selectable(
                    selected = selected,
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = onClick,
                ),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface
            ),
        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 14.dp, end = 10.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    variant.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    variant.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RadioButton(selected = selected, onClick = null, enabled = enabled)
        }
    }
}
