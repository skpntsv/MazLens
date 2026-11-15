package app.mazlens.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mazlens.app.ConnectionUiState
import app.mazlens.app.MazLensUiState
import app.mazlens.data.history.AttemptKind
import app.mazlens.data.history.AttemptRecord
import app.mazlens.data.history.AttemptStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MazLensAppTest {
    @get:Rule val compose = createComposeRule()

    // Catches an accidentally enabled disconnected command or navigation loss.
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

    // Catches automatic requests on mode selection and the wrong callback argument.
    @Test
    fun selectingVariantDoesNotRunItUntilUserPressesRun() {
        var selected by mutableStateOf(0)
        val operations = mutableListOf<String>()
        compose.setContent {
            MazLensApp(
                MazLensUiState(
                    connection = ConnectionUiState(connected = true),
                    accessMode = selected,
                ),
                MazLensActions(
                    onSelectMode = {
                        selected = it
                        operations.add("mode:$it")
                    },
                    onAccess = { operations.add("run:$selected") },
                ),
            )
        }
        compose.onNodeWithTag("open_diagnostics").performClick()
        compose.onNodeWithTag("tab_access").performClick()
        compose.onNodeWithTag("access_screen").performScrollToNode(hasTestTag("access_mode_2"))
        compose.onNodeWithTag("access_mode_2").performClick()
        compose.runOnIdle { assertEquals(listOf("mode:2"), operations) }
        compose.onNodeWithTag("access_screen").performScrollToNode(hasTestTag("access_run"))
        compose.onNodeWithTag("access_run").performClick()
        compose.runOnIdle { assertEquals(listOf("mode:2", "run:2"), operations) }
    }

    // Catches detail navigation that loses its record on Activity recreation.
    @Test
    fun historyDetailSurvivesSavedStateAndCopyUsesWholeRecord() {
        val record = savedRecord()
        var copied: AttemptRecord? = null
        var exports = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MazLensApp(
                MazLensUiState(history = listOf(record), loadingHistory = false),
                MazLensActions(onCopyRecord = { copied = it }, onExport = { exports++ }),
            )
        }
        compose.onNodeWithTag("open_diagnostics").performClick()
        compose.onNodeWithTag("tab_history").performClick()
        compose.onNodeWithTag("history_screen").performScrollToNode(hasTestTag("attempt_saved-1"))
        compose.onNodeWithTag("attempt_saved-1").performClick()
        compose.onNodeWithTag("attempt_detail").assertIsDisplayed()
        compose.onNodeWithText("private trace", substring = true).assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("attempt_detail").assertIsDisplayed()
        compose.onNodeWithTag("attempt_detail").performScrollToNode(hasTestTag("copy_record"))
        compose.onNodeWithTag("copy_record").performClick()
        compose.runOnIdle { assertEquals(record, copied) }
        compose.onNodeWithTag("attempt_detail").performScrollToNode(hasTestTag("history_back"))
        compose.onNodeWithTag("history_back").performClick()
        compose.onNodeWithTag("history_screen").performScrollToNode(hasTestTag("export_all"))
        compose.onNodeWithTag("export_all").performClick()
        compose.runOnIdle { assertEquals(1, exports) }
    }

    // Catches busy operations that cannot be cancelled after changing tabs.
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

    private fun savedRecord() =
        AttemptRecord(
            id = "saved-1",
            timestamp = 1793725200000L,
            kind = AttemptKind.ACCESS,
            title = "Проверка доступа",
            summary = "Регистратор ответил",
            status = AttemptStatus.REJECTED,
            trace = "private trace\n" + "x".repeat(18_000) + "END_PRIVATE_TRACE",
            response = "private response",
        )
}
