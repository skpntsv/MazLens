package app.mazlens.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mazlens.app.ConnectionUiState
import app.mazlens.app.MazLensUiState
import app.mazlens.app.SettingsUiState
import app.mazlens.app.completeRecorderSettings
import app.mazlens.core.protocol.RecorderSetting
import app.mazlens.core.protocol.RecorderSettings
import app.mazlens.data.history.AttemptKind
import app.mazlens.data.history.AttemptRecord
import app.mazlens.data.history.AttemptStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun sensitiveExceptionSummaryNeverAppearsOnTheFeatureScreen() {
        val secret = "PRIVATE-EXCEPTION-PASSWORD-123"
        val failure =
            AttemptRecord(
                "failure",
                1,
                AttemptKind.SETTINGS,
                "Чтение настроек",
                secret,
                AttemptStatus.FAILED,
                trace = secret,
            )
        val completed =
            connectedState().completeRecorderSettings(failure, "recorder-a", null, null, true)
        val state = completed.copy(settings = completed.settings.copy(message = secret))
        compose.setContent { MazLensApp(state, MazLensActions()) }
        compose.onNodeWithTag("tab_settings").performClick()
        compose.onNodeWithText(secret, substring = true).assertDoesNotExist()
        compose.onNodeWithText("Настройки не подтверждены").assertExists()
    }

    @Test
    fun readingEditingAndDefaultsNeverApplyWithoutExplicitConfirmation() {
        var state by mutableStateOf(connectedState())
        var reads = 0
        var writes = 0
        var defaults = 0
        compose.setContent {
            MazLensApp(
                state,
                MazLensActions(
                    onReadSettings = {
                        reads++
                        val snapshot = snapshot()
                        state =
                            state.copy(settings = SettingsUiState("recorder-a", snapshot, snapshot))
                    },
                    onEditSetting = { setting, value ->
                        state =
                            state.copy(
                                settings =
                                    state.settings.copy(
                                        draft = state.settings.draft!!.with(setting, value)
                                    )
                            )
                    },
                    onDefaultSettings = {
                        defaults++
                        state =
                            state.copy(
                                settings =
                                    state.settings.copy(
                                        draft = state.settings.draft!!.withDefaults()
                                    )
                            )
                    },
                    onApplySettings = { writes++ },
                ),
            )
        }
        compose.onNodeWithTag("tab_settings").performClick()
        compose.runOnIdle {
            assertEquals(0, reads)
            assertEquals(0, writes)
        }
        compose.onNodeWithTag("settings_read").performClick()
        scroll("setting_PARKING_DURATION")
        compose.onNodeWithTag("setting_PARKING_DURATION").performClick()
        compose.onNodeWithTag("setting_option_4").performClick()
        compose.runOnIdle {
            assertEquals(4, state.settings.draft!![RecorderSetting.PARKING_DURATION])
            assertEquals(0, writes)
        }
        scroll("settings_defaults")
        compose.onNodeWithTag("settings_defaults").performClick()
        compose.onNodeWithTag("settings_dismiss").performClick()
        compose.runOnIdle { assertEquals(0, defaults) }
        compose.onNodeWithTag("settings_defaults").performClick()
        compose.onNodeWithTag("settings_confirm").performClick()
        compose.runOnIdle {
            assertEquals(1, defaults)
            assertEquals(0, writes)
        }
        scroll("settings_apply")
        compose.onNodeWithTag("settings_apply").assertIsNotEnabled()
        scroll("setting_PARKING_DURATION")
        compose.onNodeWithTag("setting_PARKING_DURATION").performClick()
        compose.onNodeWithTag("setting_option_4").performClick()
        scroll("settings_apply")
        compose.onNodeWithTag("settings_apply").performClick()
        compose.runOnIdle { assertEquals(0, writes) }
        compose.onNodeWithTag("settings_confirm").performClick()
        compose.runOnIdle {
            assertEquals(1, writes)
            assertEquals(1, reads)
        }
    }

    @Test
    fun passwordChangeRequiresConfirmationAndStaleDialogsCannotSurviveRecorderChanges() {
        val snapshot = snapshot()
        var state by
            mutableStateOf(
                connectedState().copy(settings = SettingsUiState("recorder-a", snapshot, snapshot))
            )
        var changes = 0
        compose.setContent {
            MazLensApp(
                state,
                MazLensActions(
                    onChangeRecorderPassword = { old, new ->
                        assertEquals("OwnerPwd", old)
                        assertEquals("NewPwd123", new)
                        changes++
                        state = state.copy(busy = true)
                    }
                ),
            )
        }
        compose.onNodeWithTag("tab_settings").performClick()
        scroll("settings_old_password")
        compose.onNodeWithTag("settings_old_password").performTextInput("OwnerPwd")
        scroll("settings_new_password")
        compose.onNodeWithTag("settings_new_password").performTextInput("NewPwd123")
        scroll("settings_repeat_password")
        compose.onNodeWithTag("settings_repeat_password").performTextInput("NewPwd123")
        scroll("settings_change_password")
        compose.onNodeWithTag("settings_change_password").performClick()
        compose.runOnIdle { assertEquals(0, changes) }
        compose.onNodeWithTag("settings_dismiss").performClick()
        compose.onNodeWithTag("settings_change_password").performClick()
        compose.onNodeWithTag("settings_confirm").performClick()
        compose.runOnIdle {
            assertEquals(1, changes)
            state = state.copy(busy = false)
        }
        scroll("settings_defaults")
        compose.onNodeWithTag("settings_defaults").performClick()
        compose.runOnIdle {
            state = state.copy(connection = state.connection.copy(recorderId = "recorder-b"))
        }
        compose.onNodeWithTag("settings_confirm").assertDoesNotExist()
        compose.runOnIdle {
            state = state.copy(connection = state.connection.copy(recorderId = "recorder-a"))
        }
        compose.onNodeWithTag("settings_confirm").assertDoesNotExist()
        scroll("settings_change_password")
        compose.onNodeWithTag("settings_change_password").assertIsNotEnabled()
    }

    private fun scroll(tag: String) =
        compose.onNodeWithTag("settings_screen").performScrollToNode(hasTestTag(tag))

    private fun connectedState() =
        MazLensUiState(
            connection = ConnectionUiState(connected = true, recorderId = "recorder-a"),
            loadingHistory = false,
        )

    private fun snapshot(): RecorderSettings =
        RecorderSettings.parse(
            JSONObject().put("s.cam", 1).put("s.ver", "1.01").put("w.pass", "OwnerPwd").also { json
                ->
                RecorderSetting.entries.forEach { json.put(it.wireKey, it.defaultValue) }
            }
        )
}
