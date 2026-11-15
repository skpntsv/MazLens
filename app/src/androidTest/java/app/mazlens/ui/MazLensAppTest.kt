package app.mazlens.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mazlens.app.MazLensUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MazLensAppTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun disconnectedUserCanNavigateButCannotStartRecorderOperations() {
        compose.setContent { MazLensApp(MazLensUiState(loadingHistory = false), MazLensActions()) }
        compose.onNodeWithTag("open_diagnostics").performClick()
        compose.onNodeWithTag("tab_access").performClick()
        compose.onNodeWithTag("access_screen").performScrollToNode(hasTestTag("access_run"))
        compose.onNodeWithTag("access_run").assertIsNotEnabled()
        compose.onNodeWithTag("tab_video").performClick()
        compose.onNodeWithTag("video_screen").performScrollToNode(hasTestTag("video_run"))
        compose.onNodeWithTag("video_run").assertIsNotEnabled()
        compose.onNodeWithTag("tab_connection").performClick()
        compose.onNodeWithTag("discover").assertIsEnabled()
    }

    @Test
    fun cancellationRemainsAvailableOnEveryTabWhileBusy() {
        var cancellations = 0
        compose.setContent {
            MazLensApp(
                MazLensUiState(busy = true, operationLabel = "Проверка доступа"),
                MazLensActions(onCancel = { cancellations++ }),
            )
        }
        compose.onNodeWithTag("open_diagnostics").performClick()
        compose.onNodeWithTag("tab_history").performClick()
        compose.onNodeWithTag("cancel_operation").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, cancellations) }
        compose.onNodeWithTag("tab_connection").performClick()
        compose.onNodeWithTag("discover").assertIsNotEnabled()
    }
}
