package app.mazlens.app

import app.mazlens.core.protocol.RecorderSettings
import app.mazlens.data.history.AttemptKind
import app.mazlens.data.history.AttemptRecord
import app.mazlens.data.history.AttemptStatus

data class SettingsUiState(
    val recorderId: String = "",
    val baseline: RecorderSettings? = null,
    val draft: RecorderSettings? = null,
    val message: String = "Прочитайте настройки подключённого регистратора.",
    val problem: ProblemExplanation? = null,
) {
    val changed: Boolean
        get() = baseline != null && draft != null && !baseline.matches(draft)
}

internal fun MazLensUiState.completeRecorderSettings(
    record: AttemptRecord,
    recorderId: String,
    result: RecorderSettings?,
    newPassword: String?,
    publish: Boolean,
): MazLensUiState {
    if (!publish || record.kind !in setOf(AttemptKind.SETTINGS, AttemptKind.PASSWORD_CHANGE))
        return this
    val success = record.status == AttemptStatus.SUCCESS
    val passwordChanged = success && record.kind == AttemptKind.PASSWORD_CHANGE
    val verified = result.takeIf { success }
    return copy(
        settings =
            SettingsUiState(
                recorderId = recorderId,
                baseline = verified,
                draft = verified,
                message =
                    when {
                        passwordChanged -> "Новый пароль подтверждён повторным чтением."
                        record.kind == AttemptKind.PASSWORD_CHANGE ->
                            "Смена пароля не подтверждена. Укажите действующий пароль вручную."
                        success -> "Настройки прочитаны и проверены."
                        else -> "Настройки не подтверждены. Прочитайте их заново."
                    },
                problem = ProblemExplainer.forRecord(record),
            ),
        passwordInput = if (passwordChanged) requireNotNull(newPassword) else passwordInput,
        passwordRecorderId = if (passwordChanged) recorderId else passwordRecorderId,
    )
}
