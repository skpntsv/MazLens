package app.mazlens.ui

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
    fun archiveDownloadUsesSelectedEntryAndNeverStartsOnOpeningTab() {
        val entry = recording(2).copy(recorderId = "recorder-b")
        var requested: String? = null
        compose.setContent {
            MazLensApp(
                MazLensUiState(
                    connection = ConnectionUiState(connected = true, recorderId = "recorder-b"),
                    loadingHistory = false,
                    archive =
                        ArchiveUiState(
                            recorderId = "recorder-b",
                            files = listOf(entry),
                            saved =
                                listOf(
                                    SavedRecording(
                                        entry.copy(recorderId = "recorder-a"),
                                        File("unused.mp4"),
                                        1,
                                    )
                                ),
                            loadingSaved = false,
                        ),
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
