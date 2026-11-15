package app.mazlens.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mazlens.app.*
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
}
