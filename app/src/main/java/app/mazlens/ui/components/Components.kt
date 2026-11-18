package app.mazlens.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.mazlens.data.history.AttemptKind
import app.mazlens.data.history.AttemptStatus
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ScreenHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun FeatureCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
fun Notice(text: String, warning: Boolean = false) {
    Surface(
        color =
            if (warning) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer,
        contentColor =
            if (warning) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun StatusPill(text: String, positive: Boolean = false) {
    Surface(
        color =
            if (positive) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        contentColor =
            if (positive) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Full persisted values are passed unchanged to copy/export, independently of display bounds. */
@Composable
fun ExpandableReport(
    title: String,
    value: String,
    identity: String,
    emptyText: String = "Нет данных",
) {
    var expanded by rememberSaveable(identity, title) { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text(
                    if (expanded) "Свернуть −" else "Показать +",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        if (expanded) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    SelectionContainer {
                        Text(
                            value.ifBlank { emptyText }.take(16_000),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (value.length > 16_000) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Показаны первые 16 000 символов. Копирование записи и экспорт содержат полный текст.",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

fun attemptKindLabel(kind: AttemptKind): String =
    when (kind) {
        AttemptKind.ARCHIVE -> "Архив записей"
        AttemptKind.BASELINE -> "Исходная проверка"
        AttemptKind.ACCESS -> "Доступ"
        AttemptKind.VIDEO -> "Видео"
        AttemptKind.SETTINGS -> "Настройки"
        AttemptKind.PASSWORD_CHANGE -> "Смена пароля"
        AttemptKind.DELETE -> "Удаление записи"
    }

fun attemptStatusLabel(status: AttemptStatus): String =
    when (status) {
        AttemptStatus.RUNNING -> "В процессе"
        AttemptStatus.SUCCESS -> "Завершено успешно"
        AttemptStatus.REJECTED -> "Доступ отклонён"
        AttemptStatus.FAILED -> "Не выполнено"
        AttemptStatus.CANCELLED -> "Отменено"
        AttemptStatus.INTERRUPTED -> "Прервано"
    }

fun attemptTime(timestamp: Long): String =
    DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT,
            Locale.forLanguageTag("ru-RU"),
        )
        .format(Date(timestamp))
