package ai.storyteller.lightcraft.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The editor palette: near-black chrome around the photo, grey labels, one blue accent. */
object Lc {
    val canvas = Color(0xFF000000)
    val chrome = Color(0xFF121212)
    val panel = Color(0xFF1B1B1B)
    val raised = Color(0xFF2A2A2A)
    val divider = Color(0xFF2E2E2E)
    val text = Color(0xFFF0F0F0)
    val label = Color(0xFFB4B4B4)
    val muted = Color(0xFF7A7A7A)
    val track = Color(0xFF4A4A4A)
    val accent = Color(0xFF3B8EEA)
}

private val Scheme = darkColorScheme(
    primary = Lc.accent,
    onPrimary = Color.White,
    secondary = Lc.accent,
    background = Lc.chrome,
    surface = Lc.panel,
    surfaceVariant = Lc.raised,
    onBackground = Lc.text,
    onSurface = Lc.text,
    onSurfaceVariant = Lc.label,
    outline = Lc.divider,
)

private val Type = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Lc.text),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Lc.text),
    bodyMedium = TextStyle(fontSize = 14.sp, color = Lc.label),
    bodySmall = TextStyle(fontSize = 12.sp, color = Lc.muted),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun LightCraftTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, content = content)
}
