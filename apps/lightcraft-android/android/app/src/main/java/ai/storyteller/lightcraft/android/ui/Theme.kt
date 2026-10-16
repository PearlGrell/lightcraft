package ai.storyteller.lightcraft.android.ui

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Dark = darkColorScheme(
    primary = Color(0xFF5DA9E9),
    onPrimary = Color(0xFF0E1116),
    secondary = Color(0xFFF2B33D),
    background = Color(0xFF121212),
    surface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFF242424),
    onSurface = Color(0xFFE6E6E6),
    onSurfaceVariant = Color(0xFFB0B0B0),
)

/** Dark by default, like a photo editor should be; Material You accents when [dynamic]. */
@Composable
fun LightCraftTheme(dynamic: Boolean = false, content: @Composable () -> Unit) {
    val scheme = if (dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicDarkColorScheme(LocalContext.current) else Dark
    MaterialTheme(colorScheme = scheme, content = content)
}
