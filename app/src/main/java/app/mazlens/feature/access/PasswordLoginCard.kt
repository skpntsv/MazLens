package app.mazlens.feature.access

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.mazlens.app.MazLensUiState
import app.mazlens.ui.MazLensActions
import app.mazlens.ui.components.FeatureCard
import app.mazlens.ui.components.SectionTitle

@Composable
fun PasswordLoginCard(state: MazLensUiState, actions: MazLensActions, compact: Boolean = false) {
    var visible by rememberSaveable { mutableStateOf(false) }
    val editable = !state.busy && !state.connection.connecting
    FeatureCard {
        SectionTitle(if (compact) "Пароль регистратора" else "Вход с паролем")
        Text(
            if (compact) "Этот пароль используется для камер и записей."
            else "Введите пароль регистратора. Приложение проверит вход и прочитает настройки.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.passwordInput,
            onValueChange = actions.onPasswordChange,
            modifier = Modifier.fillMaxWidth().testTag("recorder_password"),
            label = { Text("Пароль регистратора") },
            singleLine = true,
            enabled = editable,
            isError = state.passwordInput.isNotEmpty() && !state.passwordValid,
            visualTransformation =
                if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
            supportingText = {
                Text(
                    if (state.passwordInput.isNotEmpty() && !state.passwordValid)
                        "Нужно от 8 до 31 символа"
                    else "Регистр букв учитывается"
                )
            },
            trailingIcon = {
                TextButton(
                    onClick = { visible = !visible },
                    enabled = editable,
                    modifier = Modifier.testTag("password_visibility"),
                ) {
                    Text(if (visible) "Скрыть" else "Показать")
                }
            },
        )
        if (state.recoveredPasswordCandidate != null) {
            TextButton(
                onClick = actions.onUseRecoveredPassword,
                enabled = editable,
                modifier = Modifier.fillMaxWidth().testTag("use_recovered_password"),
            ) {
                Text("Использовать найденный пароль")
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = actions.onPasswordLogin,
                enabled =
                    editable &&
                        state.connection.connected &&
                        !state.loadingHistory &&
                        state.passwordValid,
                modifier = Modifier.fillMaxWidth().testTag("password_login"),
            ) {
                Text(if (compact) "Проверить пароль" else "Войти с паролем")
            }
            if (!compact)
                Text(
                    "После чтения проверочная сессия закрывается. Результат останется в истории.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    }
}
