package ai.storyteller.lightcraft.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The editor palette: black around the photo, charcoal bars, grey labels, one blue accent. */
object Lc {
    val canvas = Color(0xFF000000)
    val chrome = Color(0xFF1E1E1E)
    val bar = Color(0xFF232323)
    val panel = Color(0xF21E1E1E)
    val raised = Color(0xFF3A3A3A)
    val divider = Color(0xFF3A3A3A)
    val text = Color(0xFFF2F2F2)
    val label = Color(0xFFC8C8C8)
    val muted = Color(0xFF8A8A8A)
    val track = Color(0xFF6A6A6A)
    val thumb = Color(0xFF2E2E2E)
    val accent = Color(0xFF2F7DF6)
    val pill = Color(0xCC1A1A1A)
}

private val Scheme = darkColorScheme(
    primary = Lc.accent,
    onPrimary = Color.White,
    secondary = Lc.accent,
    background = Lc.chrome,
    surface = Lc.chrome,
    surfaceVariant = Lc.raised,
    onBackground = Lc.text,
    onSurface = Lc.text,
    onSurfaceVariant = Lc.label,
    outline = Lc.divider,
)

private val Type = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Lc.text),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Lc.text),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Lc.text),
    bodyMedium = TextStyle(fontSize = 14.sp, color = Lc.label),
    bodySmall = TextStyle(fontSize = 12.sp, color = Lc.muted),
    labelSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun LightCraftTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, content = content)
}
