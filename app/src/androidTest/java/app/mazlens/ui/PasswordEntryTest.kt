package app.mazlens.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mazlens.app.*
import app.mazlens.data.history.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PasswordEntryTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun typingCredentialNeverLogsInUntilExplicitClick() {
        var input by mutableStateOf("")
        var logins = 0
        var submitted = ""
        compose.setContent {
            MazLensApp(
                MazLensUiState(
                    connection = ConnectionUiState(connected = true),
                    passwordInput = input,
                    loadingHistory = false,
                ),
                MazLensActions(
                    onPasswordChange = { input = it },
                    onPasswordLogin = {
                        logins++
                        submitted = input
                    },
                ),
            )
        }
        compose.onNodeWithTag("open_diagnostics").performClick()
        compose.onNodeWithTag("tab_access").performClick()
        compose.onNodeWithTag("access_screen").performScrollToNode(hasTestTag("recorder_password"))
        compose.onNodeWithTag("recorder_password").performTextInput("Synthetic9")
        compose.runOnIdle { assertEquals(0, logins) }
        compose.onNodeWithTag("access_screen").performScrollToNode(hasTestTag("password_login"))
        compose.onNodeWithTag("password_login").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, logins)
            assertEquals("Synthetic9", submitted)
        }
    }

    @Test
    fun disconnectedAndInvalidCredentialCannotBeSubmitted() {
        var connected by mutableStateOf(false)
        var input by mutableStateOf("ValidPwd")
        compose.setContent {
            MazLensApp(
                MazLensUiState(
                    connection = ConnectionUiState(connected = connected),
                    passwordInput = input,
                    loadingHistory = false,
                ),
                MazLensActions(onPasswordChange = { input = it }),
            )
        }
        compose.onNodeWithTag("open_diagnostics").performClick()
        compose.onNodeWithTag("tab_access").performClick()
        compose.onNodeWithTag("access_screen").performScrollToNode(hasTestTag("password_login"))
        compose.onNodeWithTag("password_login").assertIsNotEnabled()
        compose.runOnIdle { connected = true }
        compose.onNodeWithTag("password_login").assertIsEnabled()
        compose.onNodeWithTag("access_screen").performScrollToNode(hasTestTag("recorder_password"))
        compose.onNodeWithTag("recorder_password").performTextReplacement("short")
        compose.onNodeWithTag("access_screen").performScrollToNode(hasTestTag("password_login"))
        compose.onNodeWithTag("password_login").assertIsNotEnabled()
        compose.runOnIdle { input = "x".repeat(32) }
        compose.onNodeWithTag("password_login").assertIsNotEnabled()
    }

    @Test
    fun recoveredCredentialIsFilledOnlyOnRequestAndCanBeRevealed() {
        var input by mutableStateOf("")
        var logins = 0
        val record =
            AttemptRecord(
                "saved-pass",
                1,
                AttemptKind.ACCESS,
                "Сессия с моделью",
                "Ответ получен",
                AttemptStatus.SUCCESS,
                networkSnapshot = "Регистратор: synthetic-recorder\n",
                passwordCandidate = "Recovered9",
            )
        compose.setContent {
            MazLensApp(
                MazLensUiState(
                    connection = ConnectionUiState(connected = true, name = "synthetic-recorder"),
                    passwordInput = input,
                    history = listOf(record),
                    loadingHistory = false,
                ),
                MazLensActions(
                    onUseRecoveredPassword = { input = record.passwordCandidate },
                    onPasswordChange = { input = it },
                    onPasswordLogin = { logins++ },
                ),
            )
        }
        compose.onNodeWithTag("open_diagnostics").performClick()
        compose.onNodeWithTag("tab_access").performClick()
        compose.onNodeWithTag("access_screen").performScrollToNode(hasTestTag("recorder_password"))
        assertEquals(
            "",
            compose
                .onNodeWithTag("recorder_password")
                .fetchSemanticsNode()
                .config[SemanticsProperties.EditableText]
                .text,
        )
        compose
            .onNodeWithTag("access_screen")
            .performScrollToNode(hasTestTag("use_recovered_password"))
        compose.onNodeWithTag("use_recovered_password").performClick()
        compose.onNodeWithTag("access_screen").performScrollToNode(hasTestTag("recorder_password"))
        val field = compose.onNodeWithTag("recorder_password")
        compose.runOnIdle { assertEquals("Recovered9", input) }
        assertNotEquals(
            "Recovered9",
            field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text,
        )
        assertTrue(field.fetchSemanticsNode().config.contains(SemanticsProperties.Password))
        compose.onNodeWithTag("password_visibility").performClick()
        assertEquals(
            "Recovered9",
            field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text,
        )
        // Android retains password semantics for the password keyboard even while text is revealed.
        compose.onNodeWithTag("password_visibility").performClick()
        assertNotEquals(
            "Recovered9",
            field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text,
        )
        assertTrue(field.fetchSemanticsNode().config.contains(SemanticsProperties.Password))
        compose.runOnIdle { assertEquals(0, logins) }
    }
}
