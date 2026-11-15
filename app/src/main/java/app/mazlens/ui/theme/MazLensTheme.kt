package app.mazlens.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val RecorderNavy = Color(0xFF102A3D)
val RecorderTeal = Color(0xFF00796B)

private val MazLensColors =
    lightColorScheme(
        primary = RecorderTeal,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD8F1EA),
        onPrimaryContainer = Color(0xFF003D33),
        secondary = Color(0xFF496577),
        secondaryContainer = Color(0xFFE0EAF0),
        onSecondaryContainer = RecorderNavy,
        background = Color(0xFFF3F6F8),
        onBackground = RecorderNavy,
        surface = Color.White,
        onSurface = RecorderNavy,
        surfaceVariant = Color(0xFFEBF0F3),
        onSurfaceVariant = Color(0xFF536472),
        outline = Color(0xFF73848E),
        outlineVariant = Color(0xFFD9E2E8),
        error = Color(0xFFAF3028),
        errorContainer = Color(0xFFFFE4DF),
        onErrorContainer = Color(0xFF601B15),
    )

/** A deliberately consistent light palette, including when the device is dark. */
@Composable
fun MazLensTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MazLensColors,
        shapes =
            Shapes(
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(18.dp),
                large = RoundedCornerShape(24.dp),
            ),
        content = content,
    )
}
