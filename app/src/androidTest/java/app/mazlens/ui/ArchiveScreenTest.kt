package app.mazlens.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mazlens.app.ArchiveUiState
import app.mazlens.app.ConnectionUiState
import app.mazlens.app.MazLensUiState
import app.mazlens.core.archive.RecorderFile
import app.mazlens.data.recordings.SavedRecording
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchiveScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun rearCameraChoiceIsAvailableBeforeStartingLive() {
        var selected: Boolean? = null
        compose.setContent {
            MazLensApp(
                MazLensUiState(
                    connection = ConnectionUiState(connected = true),
                    loadingHistory = false,
                ),
                MazLensActions(onVideoCamera = { selected = it }),
            )
        }
        compose.onNodeWithTag("tab_video").performClick()
        compose.onNodeWithTag("camera_rear").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(true, selected) }
    }

    @Test
    fun archiveLibraryIsReachableWithoutRecorderAndRefreshIsDisabled() {
        compose.setContent {
            MazLensApp(
                MazLensUiState(
                    loadingHistory = false,
                    archive = ArchiveUiState(loadingSaved = false),
                ),
                MazLensActions(),
            )
        }
        compose.onNodeWithTag("tab_archive").performClick()
        compose.onNodeWithTag("archive_refresh").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("На телефоне").assertIsDisplayed().performClick()
        compose.onNodeWithText("Сохранённых записей пока нет").assertIsDisplayed()
    }

    @Test
    fun archiveDownloadUsesSelectedEntryAndNeverStartsOnOpeningTab() {
        val entry = recording(2)
        var requested: String? = null
        compose.setContent {
            MazLensApp(
                MazLensUiState(
                    connection = ConnectionUiState(connected = true),
                    loadingHistory = false,
                    archive = ArchiveUiState(files = listOf(entry), loadingSaved = false),
                ),
                MazLensActions(onDownload = { requested = it }),
            )
        }
        compose.onNodeWithTag("tab_archive").performClick()
        compose.runOnIdle { assertEquals(null, requested) }
        compose
            .onNodeWithTag("archive_screen")
            .performScrollToNode(hasTestTag("download_${entry.id}"))
        compose.onNodeWithTag("download_${entry.id}").performClick()
        compose.runOnIdle { assertEquals(entry.id, requested) }
    }

    @Test
    fun savedOriginalCanBePlayedAndSharedWithoutRecorder() {
        val entry = recording(1)
        val operations = mutableListOf<String>()
        compose.setContent {
            MazLensApp(
                MazLensUiState(
                    loadingHistory = false,
                    archive =
                        ArchiveUiState(
                            loadingSaved = false,
                            saved = listOf(SavedRecording(entry, File("/unused-fixture.MP4"), 1)),
                        ),
                ),
                MazLensActions(
                    onPlaySaved = { operations.add("play:$it") },
                    onShareSaved = { operations.add("share:$it") },
                ),
            )
        }
        compose.onNodeWithTag("tab_archive").performClick()
        compose.onNodeWithText("На телефоне").performClick()
        compose.onNodeWithTag("archive_screen").performScrollToNode(hasTestTag("play_${entry.id}"))
        compose.onNodeWithTag("play_${entry.id}").assertIsEnabled().performClick()
        compose.onNodeWithTag("share_${entry.id}").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf("play:${entry.id}", "share:${entry.id}"), operations)
        }
    }

    @Test
    fun activeDownloadPreventsSecondDownloadAndCanBeCancelled() {
        val entry = recording(1)
        var cancelled = 0
        compose.setContent {
            MazLensApp(
                MazLensUiState(
                    busy = true,
                    loadingHistory = false,
                    archive =
                        ArchiveUiState(
                            loadingSaved = false,
                            files = listOf(entry),
                            downloadingId = entry.id,
                            receivedBytes = 25,
                            totalBytes = 100,
                        ),
                ),
                MazLensActions(onCancel = { cancelled++ }),
            )
        }
        compose.onNodeWithTag("tab_archive").performClick()
        compose.onNodeWithTag("archive_screen").performScrollToNode(hasTestTag("download_cancel"))
        compose.onNodeWithTag("download_cancel").performClick()
        compose.runOnIdle { assertEquals(1, cancelled) }
        compose
            .onNodeWithTag("archive_screen")
            .performScrollToNode(hasTestTag("download_${entry.id}"))
        compose.onNodeWithTag("download_${entry.id}").assertIsNotEnabled()
    }

    private fun recording(camera: Int) =
        RecorderFile(
            "261106103000",
            camera,
            1,
            100,
            "2026-11-06 10:30:00",
            "/tmp/SD0/${if(camera==2)"REAR/" else ""}NORMAL/261106103000.MP4",
        )
}
