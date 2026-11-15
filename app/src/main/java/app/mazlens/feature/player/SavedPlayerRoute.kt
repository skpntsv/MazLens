package app.mazlens.feature.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player

/** Window presentation belongs to the route; playback lifetime belongs to the ViewModel. */
@Composable
fun SavedPlayerRoute(
    player: Player,
    title: String,
    subtitle: String,
    onClose: () -> Unit,
    onShare: () -> Unit,
) {
    val activity = LocalContext.current.activity()
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val originalOrientation = rememberSaveable {
        activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    DisposableEffect(activity, fullscreen) {
        val bars = activity?.let { WindowInsetsControllerCompat(it.window, it.window.decorView) }
        bars?.apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (fullscreen) hide(WindowInsetsCompat.Type.systemBars())
            else show(WindowInsetsCompat.Type.systemBars())
        }
        activity?.requestedOrientation =
            if (fullscreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else originalOrientation
        onDispose {
            // Configuration recreation retains both the ViewModel player and the saved
            // presentation.
            if (activity?.isChangingConfigurations != true) {
                activity?.requestedOrientation = originalOrientation
                bars?.apply {
                    show(WindowInsetsCompat.Type.systemBars())
                    isAppearanceLightStatusBars = true
                    isAppearanceLightNavigationBars = true
                }
            }
        }
    }
    BackHandler { if (fullscreen) fullscreen = false else onClose() }
    SavedPlayerScreen(
        player,
        title,
        subtitle,
        fullscreen,
        onClose = { if (fullscreen) fullscreen = false else onClose() },
        onShare = onShare,
        onToggleFullscreen = { fullscreen = !fullscreen },
    )
}

private tailrec fun Context.activity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }
