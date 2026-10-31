package ai.storyteller.lightcraft.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The LightCraft palette: a warm near-black around the photo, charcoal chrome, paper-white text
 * (the app icon's paper, #efe9dc family) and one accent, the icon's amber, kept for values and the
 * current choice. No gradients or glows: the photo is the only colourful thing on screen.
 */
object Lc {
    val canvas = Color(0xFF0F0E0D)
    val chrome = Color(0xFF161514)
    val bar = Color(0xFF1A1918)
    val panel = Color(0xF2161514)
    val raised = Color(0xFF282624)
    val divider = Color(0xFF2B2927)
    val text = Color(0xFFF3EFE8)
    val label = Color(0xFFBCB6AD)
    val muted = Color(0xFF85807A)
    val track = Color(0xFF3A3734)
    val thumb = Color(0xFF2E2C2A)
    val accent = Color(0xFFF2A516)
    val onAccent = Color(0xFF0B0B0C)
    val pill = Color(0xCC121110)
    val danger = Color(0xFFE5484D)
    val scrim = Color(0x99000000)
}

/** Numbers that don't jiggle while they change (tabular figures). */
val Numbers = TextStyle(fontFeatureSettings = "tnum", color = Lc.text)

private val Scheme = darkColorScheme(
    primary = Lc.accent,
    onPrimary = Lc.onAccent,
    secondary = Lc.accent,
    onSecondary = Lc.onAccent,
    background = Lc.chrome,
    surface = Lc.chrome,
    surfaceVariant = Lc.raised,
    surfaceContainer = Lc.bar,
    surfaceContainerHigh = Lc.raised,
    surfaceContainerHighest = Lc.raised,
    onBackground = Lc.text,
    onSurface = Lc.text,
    onSurfaceVariant = Lc.label,
    outline = Lc.divider,
    outlineVariant = Lc.divider,
    error = Lc.danger,
)

private val Type = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Lc.text, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Lc.text),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Lc.text),
    titleSmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Lc.text),
    bodyLarge = TextStyle(fontSize = 15.sp, color = Lc.text),
    bodyMedium = TextStyle(fontSize = 14.sp, color = Lc.label),
    bodySmall = TextStyle(fontSize = 12.sp, color = Lc.muted),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
)

@Composable
fun LightCraftTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, content = content)
}
