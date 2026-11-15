package app.mazlens.video

import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.core.content.FileProvider
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mazlens.app.ArchiveUiState
import app.mazlens.app.MazLensUiState
import app.mazlens.ui.MazLensActions
import app.mazlens.ui.MazLensApp
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The existing offline playback journey also verifies actual seeking through visible controls. */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class SavedPlaybackControllerTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun downloadedOriginalRendersAFrameWithoutNetwork() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "offline-playback-fixture.mp4")
        val external = InstrumentationRegistry.getArguments().getString("media_path")
        if (external != null) File(external).copyTo(file, overwrite = true)
        else
            instrumentation.context.assets.open("local-recording.mp4").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        val frame = AtomicBoolean(false)
        val failed = AtomicBoolean(false)
        val controller =
            SavedPlaybackController(context) { android.util.Log.i("MazLensPlayerTest", it) }
        var player: Player? by mutableStateOf(null)
        compose.setContent {
            MazLensApp(
                MazLensUiState(
                    loadingHistory = false,
                    archive =
                        ArchiveUiState(
                            loadingSaved = false,
                            player = player,
                            playingName = "offline.mp4",
                            playingLabel = "Передняя камера",
                        ),
                ),
                MazLensActions(),
            )
        }
        try {
            compose.runOnIdle {
                player =
                    controller
                        .start(file) { failed.set(true) }
                        .also { active ->
                            active.addListener(
                                object : Player.Listener {
                                    override fun onRenderedFirstFrame() {
                                        frame.set(true)
                                    }
                                }
                            )
                        }
            }
            compose.waitUntil(10_000) { player != null }
            compose.onNodeWithTag("saved_seek").assertIsDisplayed()
            compose.waitUntil(10_000) { frame.get() || failed.get() }
            assertTrue(
                "The local original must render an actual frame",
                frame.get() && !failed.get(),
            )
            compose.onNodeWithTag("saved_play_pause").assertIsDisplayed().performClick()
            var duration = 0L
            compose.runOnIdle {
                val active = requireNotNull(player)
                assertTrue(
                    "The original must have a seekable timeline",
                    active.isCurrentMediaItemSeekable,
                )
                assertTrue(active.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
                duration = active.duration
                assertTrue("Duration must be available", duration > 10_000)
                assertTrue("Pause must work", !active.playWhenReady)
                android.util.Log.i(
                    "MazLensPlayerTest",
                    "paused durationMs=$duration positionMs=${active.currentPosition}",
                )
            }
            compose.waitUntil(5_000) {
                !compose
                    .onNodeWithTag("saved_seek")
                    .fetchSemanticsNode()
                    .config
                    .contains(SemanticsProperties.Disabled)
            }
            compose.onNodeWithTag("saved_seek").performTouchInput {
                click(Offset(width * .5f, centerY))
            }
            fun awaitPosition(target: Long) {
                var last = ""
                try {
                    compose.waitUntil(10_000) {
                        var ready = false
                        instrumentation.runOnMainSync {
                            ready =
                                player?.let {
                                    last =
                                        "target=$target position=${it.currentPosition} state=${it.playbackState}"
                                    it.playbackState == Player.STATE_READY &&
                                        kotlin.math.abs(it.currentPosition - target) < 1000
                                } == true
                        }
                        ready
                    }
                } finally {
                    android.util.Log.i("MazLensPlayerTest", last)
                }
            }
            awaitPosition(duration / 2)
            var middle = 0L
            compose.runOnIdle { middle = player!!.currentPosition }
            compose.onNodeWithTag("saved_forward_10").performClick()
            awaitPosition((middle + 10_000).coerceAtMost(duration))
            compose.onNodeWithTag("saved_back_10").performClick()
            awaitPosition(middle.coerceAtMost(duration - 10_000))
            compose.runOnIdle {
                assertTrue(!requireNotNull(player).playWhenReady)
                android.util.Log.i(
                    "MazLensPlayerTest",
                    "seek controls verified durationMs=$duration positionMs=${player!!.currentPosition}",
                )
            }
            compose.onNodeWithTag("saved_play_pause").performClick()
            compose.runOnIdle { assertTrue(requireNotNull(player).playWhenReady) }
        } finally {
            compose.runOnIdle {
                controller.stop()
                player = null
            }
            file.delete()
        }
    }

    @Test
    fun shareProviderExposesRecordingBytesAndRejectsOtherPrivateFolders() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val folder = File(context.filesDir, "recordings").apply { mkdirs() }
        val file = File(folder, "share-fixture.MP4")
        val expected =
            instrumentation.context.assets.open("local-recording.mp4").use { it.readBytes() }
        file.writeBytes(expected)
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.recordings", file)
            val actual = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            assertArrayEquals(expected, actual)
            try {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.recordings",
                    File(context.filesDir, "outside-recordings.mp4"),
                )
                fail("Other private folders must not be shared")
            } catch (_: IllegalArgumentException) {}
        } finally {
            file.delete()
        }
    }
}
