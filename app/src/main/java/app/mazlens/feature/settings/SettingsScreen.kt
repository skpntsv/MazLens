package app.mazlens.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.mazlens.app.MazLensUiState
import app.mazlens.core.protocol.RecorderSetting
import app.mazlens.ui.MazLensActions
import app.mazlens.ui.components.DetailRow
import app.mazlens.ui.components.FeatureCard
import app.mazlens.ui.components.ProblemCard
import app.mazlens.ui.components.ScreenHeading
import app.mazlens.ui.components.SectionTitle

private enum class Confirmation {
    APPLY,
    DEFAULTS,
    PASSWORD,
}

@Composable
fun SettingsScreen(state: MazLensUiState, actions: MazLensActions) {
    val settings = state.settings
    val available =
        state.connection.connected &&
            state.connection.recorderId.isNotBlank() &&
            !state.connection.connecting &&
            !state.busy &&
            !state.loadingHistory
    val draft = settings.draft
    val loaded = available && draft != null && settings.recorderId == state.connection.recorderId
    val context = listOf(settings.baseline, state.connection.recorderId, available)
    var selected by remember(context) { mutableStateOf<RecorderSetting?>(null) }
    var confirmation by remember(context) { mutableStateOf<Confirmation?>(null) }
    var oldPassword by remember(context) { mutableStateOf("") }
    var newPassword by remember(context) { mutableStateOf("") }
    var repeatedPassword by remember(context) { mutableStateOf("") }

    LazyColumn(
        Modifier.fillMaxSize().testTag("settings_screen"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ScreenHeading(
                "Настройки регистратора",
                "Изменения в форме сохраняются только по кнопке «Применить».",
            )
        }
        item {
            Button(
                onClick = actions.onReadSettings,
                enabled = available,
                modifier = Modifier.fillMaxWidth().testTag("settings_read"),
            ) {
                Text("Прочитать настройки")
            }
        }
        item {
            if (settings.problem == null) Text(settings.message)
            if (!state.connection.connected)
                Text("Подключитесь к регистратору и укажите пароль в разделе «Подключение».")
        }
        settings.problem?.let { item { ProblemCard(it) } }
        if (draft != null) {
            item {
                FeatureCard {
                    DetailRow("Прошивка", draft.firmware.ifEmpty { "Не определена" })
                    DetailRow("Камеры", if (draft.cameraMode == 1) "Две" else "Одна")
                }
            }
            items(RecorderSetting.entries, key = { it.name }) { setting ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (setting) {
                        RecorderSetting.PARKING_DURATION -> SectionTitle("На парковке")
                        RecorderSetting.QUALITY -> SectionTitle("Запись в движении")
                        RecorderSetting.VOLUME -> SectionTitle("Звук")
                        else -> {}
                    }
                    OutlinedButton(
                        onClick = { selected = setting },
                        enabled = loaded,
                        modifier = Modifier.fillMaxWidth().testTag("setting_${setting.name}"),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(settingTitle(setting), style = MaterialTheme.typography.labelLarge)
                            Text(settingValue(setting, draft[setting]))
                        }
                    }
                    if (setting == RecorderSetting.PARKING_END)
                        Text(
                            "Не учитывать события за выбранный интервал перед возвращением в автомобиль.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                }
            }
            item {
                Button(
                    onClick = { confirmation = Confirmation.APPLY },
                    enabled = loaded && settings.changed,
                    modifier = Modifier.fillMaxWidth().testTag("settings_apply"),
                ) {
                    Text("Применить")
                }
            }
            item {
                OutlinedButton(
                    onClick = { confirmation = Confirmation.DEFAULTS },
                    enabled = loaded,
                    modifier = Modifier.fillMaxWidth().testTag("settings_defaults"),
                ) {
                    Text("Стандартные настройки")
                }
            }
            item {
                FeatureCard {
                    SectionTitle("Пароль регистратора")
                    Text(
                        "Меняется отдельно от параметров записи. При неопределённом результате потребуется ввести действующий пароль вручную.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    PasswordField("Текущий пароль", oldPassword, loaded, "settings_old_password") {
                        oldPassword = it
                    }
                    PasswordField("Новый пароль", newPassword, loaded, "settings_new_password") {
                        newPassword = it
                    }
                    PasswordField(
                        "Повторите новый пароль",
                        repeatedPassword,
                        loaded,
                        "settings_repeat_password",
                    ) {
                        repeatedPassword = it
                    }
                    Text("От 8 до 31 символа", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        onClick = { confirmation = Confirmation.PASSWORD },
                        enabled =
                            loaded &&
                                oldPassword.length in 8..31 &&
                                newPassword.length in 8..31 &&
                                newPassword == repeatedPassword &&
                                newPassword != oldPassword,
                        modifier = Modifier.fillMaxWidth().testTag("settings_change_password"),
                    ) {
                        Text("Изменить пароль")
                    }
                }
            }
        }
    }
    selected
        ?.takeIf { loaded }
        ?.let { setting ->
            AlertDialog(
                onDismissRequest = { selected = null },
                title = { Text(settingTitle(setting)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        setting.options.forEach { value ->
                            TextButton(
                                onClick = {
                                    selected = null
                                    actions.onEditSetting(setting, value)
                                },
                                modifier = Modifier.fillMaxWidth().testTag("setting_option_$value"),
                            ) {
                                Text(settingValue(setting, value))
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { selected = null }) { Text("Отмена") } },
            )
        }
    confirmation
        ?.takeIf { loaded }
        ?.let { choice ->
            AlertDialog(
                onDismissRequest = { confirmation = null },
                title = {
                    Text(
                        when (choice) {
                            Confirmation.APPLY -> "Сохранить настройки?"
                            Confirmation.DEFAULTS -> "Заполнить стандартными значениями?"
                            Confirmation.PASSWORD -> "Изменить пароль регистратора?"
                        }
                    )
                },
                text = {
                    Text(
                        when (choice) {
                            Confirmation.APPLY ->
                                "Параметры будут отправлены на регистратор и проверены повторным чтением."
                            Confirmation.DEFAULTS ->
                                "Изменится только форма. Пароль и записи останутся без изменений. Для сохранения нажмите «Применить»."
                            Confirmation.PASSWORD ->
                                "Сохраните новый пароль. При потере связи результат может остаться неизвестным. Несохранённые параметры формы не применяются."
                        }
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmation = null
                            when (choice) {
                                Confirmation.APPLY -> actions.onApplySettings()
                                Confirmation.DEFAULTS -> actions.onDefaultSettings()
                                Confirmation.PASSWORD ->
                                    actions.onChangeRecorderPassword(oldPassword, newPassword)
                            }
                        },
                        modifier = Modifier.testTag("settings_confirm"),
                    ) {
                        Text("Подтвердить")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { confirmation = null },
                        modifier = Modifier.testTag("settings_dismiss"),
                    ) {
                        Text("Отмена")
                    }
                },
            )
        }
}

@Composable
private fun PasswordField(
    label: String,
    value: String,
    enabled: Boolean,
    tag: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(31)) },
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

private fun settingTitle(setting: RecorderSetting): String =
    when (setting) {
        RecorderSetting.PARKING_DURATION -> "Запись после удара"
        RecorderSetting.PARKING_START -> "Начало парковочного наблюдения"
        RecorderSetting.PARKING_END -> "Исключать события перед возвращением"
        RecorderSetting.PARKING_OVERWRITE -> "Перезапись парковочных записей"
        RecorderSetting.PARKING_SENSITIVITY -> "Чувствительность на парковке"
        RecorderSetting.QUALITY -> "Качество видео"
        RecorderSetting.MICROPHONE -> "Микрофон"
        RecorderSetting.MANUAL_OVERWRITE -> "Перезапись ручных записей"
        RecorderSetting.EVENT_OVERWRITE -> "Перезапись записей событий"
        RecorderSetting.EVENT_SENSITIVITY -> "Чувствительность к удару"
        RecorderSetting.VOLUME -> "Громкость уведомлений"
    }

private fun settingValue(setting: RecorderSetting, value: Int): String =
    when (setting) {
        RecorderSetting.PARKING_DURATION ->
            when (value) {
                0 -> "Выключена"
                1 -> "15 секунд"
                2 -> "30 секунд"
                else -> "60 секунд"
            }
        RecorderSetting.PARKING_START ->
            when (value) {
                0 -> "Сразу"
                1 -> "Через 30 секунд"
                2 -> "Через 1 минуту"
                else -> "Через 3 минуты"
            }
        RecorderSetting.PARKING_END ->
            when (value) {
                0 -> "До возвращения"
                1 -> "Последние 30 секунд"
                2 -> "Последнюю минуту"
                else -> "Последние 3 минуты"
            }
        RecorderSetting.QUALITY -> listOf("UXP", "XP", "SP", "LP")[value]
        RecorderSetting.PARKING_SENSITIVITY,
        RecorderSetting.EVENT_SENSITIVITY -> "${value + 1} из 5"
        RecorderSetting.VOLUME -> if (value == 0) "Выключен" else "$value из 5"
        else -> if (value == 0) "Выключено" else "Включено"
    }
