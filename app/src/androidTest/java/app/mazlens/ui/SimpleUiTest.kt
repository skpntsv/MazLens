package app.mazlens.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mazlens.app.ConnectionUiState
import app.mazlens.app.MazLensUiState
import app.mazlens.app.ProblemExplainer
import app.mazlens.app.VideoUiState
import app.mazlens.data.history.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SimpleUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun threeMainTabsKeepDiagnosticsOutOfEverydayNavigation() {
        compose.setContent { MazLensApp(MazLensUiState(loadingHistory = false), MazLensActions()) }
        compose.onNodeWithTag("tab_connection").assertIsDisplayed()
        compose.onNodeWithTag("tab_video").assertIsDisplayed()
        compose.onNodeWithTag("tab_archive").assertIsDisplayed()
        compose.onNodeWithTag("tab_access").assertDoesNotExist()
        compose.onNodeWithTag("tab_history").assertDoesNotExist()
        compose.onNodeWithTag("open_diagnostics").assertIsDisplayed()
    }

    @Test
    fun diagnosticsBackRestoresPreviousCameraScreenAfterRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MazLensApp(MazLensUiState(loadingHistory = false), MazLensActions())
        }
        compose.onNodeWithTag("tab_video").performClick()
        compose.onNodeWithTag("open_diagnostics").performClick()
        compose.onNodeWithTag("tab_history").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("diagnostics_back").performClick()
        compose.onNodeWithTag("video_screen").assertIsDisplayed()
    }

    @Test
    fun cameraStartIsVisibleWithoutProtocolStages() {
        compose.setContent {
            MazLensApp(
                MazLensUiState(
                    loadingHistory = false,
                    connection = ConnectionUiState(connected = true),
                ),
                MazLensActions(),
            )
        }
        compose.onNodeWithTag("tab_video").performClick()
        compose.onNodeWithTag("camera_rear").assertIsDisplayed()
        compose.onNodeWithTag("video_run").assertIsDisplayed()
        compose.onNodeWithText("Состояние потока").assertDoesNotExist()
    }

    @Test
    fun gpsFailureExplainsWhatToDoAndKeepsCodeCollapsed() {
        val record =
            AttemptRecord(
                "gps",
                1,
                AttemptKind.VIDEO,
                "Видео",
                "Сессия не открыта: rval=-52",
                AttemptStatus.REJECTED,
                response = "{\"msg_id\":257,\"rval\":-52}",
            )
        val problem = ProblemExplainer.forRecord(record)!!
        compose.setContent {
            MazLensApp(
                MazLensUiState(loadingHistory = false, video = VideoUiState(problem = problem)),
                MazLensActions(),
            )
        }
        compose.onNodeWithTag("tab_video").performClick()
        compose.onNodeWithTag("video_screen").performScrollToNode(hasTestTag("problem_card"))
        compose.onNodeWithText("Что сделать").assertIsDisplayed()
        compose.onNodeWithText("rval=-52").assertDoesNotExist()
        compose.onNodeWithTag("video_screen").performScrollToNode(hasTestTag("problem_details"))
        compose.onNodeWithTag("problem_details").performClick()
        compose.onNodeWithTag("video_screen").performScrollToNode(hasTestTag("problem_card"))
        compose.onNodeWithText("rval=-52").assertExists()
    }

    @Test
    fun historicalPasswordSuccessIsNotGuidanceForCurrentRecorderOrEditedPassword() {
        val old =
            AttemptRecord(
                "old-pass",
                1,
                AttemptKind.ACCESS,
                "Вход с паролем",
                "Ответ получен",
                AttemptStatus.SUCCESS,
                networkSnapshot = "Регистратор: OLD_C9K1",
                passwordCandidate = "Previous9",
            )
        compose.setContent {
            MazLensApp(
                MazLensUiState(
                    loadingHistory = false,
                    connection = ConnectionUiState(connected = true, name = "CURRENT_C9K1"),
                    passwordInput = "Changed9",
                    latestAccess = old,
                ),
                MazLensActions(),
            )
        }
        compose.onNodeWithTag("connection_screen").performScrollToNode(hasTestTag("password_login"))
        compose.onNodeWithTag("connection_screen").performTouchInput { swipeUp() }
        compose.onNodeWithText("Пароль принят", substring = true).assertDoesNotExist()
    }
}
