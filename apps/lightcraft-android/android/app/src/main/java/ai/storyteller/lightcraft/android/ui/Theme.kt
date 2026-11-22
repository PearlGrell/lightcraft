package ai.storyteller.lightcraft.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ai.storyteller.lightcraft.android.R

/**
 * The desktop app's Midnight theme (`crates/ui-egui/src/theme.rs`, `Tokens`), token for token, so
 * the phone and the desktop read as one product: the same greys, text, track and thumb, the
 * same blue accent, the same star and flag colours.
 */
object Lc {
    val chrome = Color(0xFF1E1E1E)
    val canvas = Color(0xFF121212)
    val gridBg = Color(0xFF080808)
    val cell = Color(0xFF121212)
    val cellSelected = Color(0xFF262626)
    val skeleton = Color(0xFF1A1A1A)
    val divider = Color(0xFF0E0E0E)
    val inset = Color(0xFF181818)
    val field = Color(0xFF161616)
    val fieldBorder = Color(0xFF333333)
    val button = Color(0xFF191919)
    val buttonBorder = Color(0xFF404040)
    val hover = Color(0xFF2E2E2E)
    val pressed = Color(0xFF343434)
    val toolActive = Color(0xFF343434)
    val text = Color(0xFFE2E2E2)
    val label = Color(0xFFBCBCBC)
    val dim = Color(0xFF8E8E8E)
    val disabled = Color(0xFF5C5C5C)
    val icon = Color(0xFF9A9A9A)
    val track = Color(0xFF5A5A5A)
    val thumb = Color(0xFFA0A0A0)
    val thumbActive = Color(0xFFE0E0E0)
    val accent = Color(0xFF0165DD)
    val star = Color(0xFFD8D8D8)
    val pick = Color(0xFFF0F0F0)
    val reject = Color(0xFFE04A4A)
    val caution = Color(0xFFE3A83C)
    val ringSelected = Color(0xFF4A4A4A)
    val scrim = Color(0x99000000)
    val pill = Color(0xCC121212)
}

private val InterBundled = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

/** Tests on the JVM (Paparazzi) load the same Inter files from `assets/fonts` by path. */
internal var interOverride: FontFamily? = null

/** The desktop's UI font (Inter, OFL), bundled from `assets/fonts` at build time. */
val Inter: FontFamily get() = interOverride ?: InterBundled

/** Values beside sliders and in the info line: tabular figures, as on the desktop. */
val Numbers: TextStyle get() = TextStyle(fontFamily = Inter, fontFeatureSettings = "tnum", color = Lc.text)

private val Scheme = darkColorScheme(
    primary = Lc.accent,
    onPrimary = Color.White,
    secondary = Lc.accent,
    onSecondary = Color.White,
    background = Lc.chrome,
    surface = Lc.chrome,
    surfaceVariant = Lc.inset,
    surfaceContainer = Lc.chrome,
    surfaceContainerLow = Lc.chrome,
    surfaceContainerHigh = Lc.hover,
    surfaceContainerHighest = Lc.hover,
    onBackground = Lc.text,
    onSurface = Lc.text,
    onSurfaceVariant = Lc.label,
    outline = Lc.buttonBorder,
    outlineVariant = Lc.fieldBorder,
    error = Lc.reject,
)

private fun inter(size: Float, weight: FontWeight = FontWeight.Normal, color: Color = Lc.text) =
    TextStyle(fontFamily = Inter, fontSize = size.sp, fontWeight = weight, color = color)

private fun type() = Typography(
    headlineSmall = inter(20f, FontWeight.SemiBold),
    titleLarge = inter(17f, FontWeight.SemiBold),
    titleMedium = inter(15f, FontWeight.SemiBold),
    titleSmall = inter(14f, FontWeight.SemiBold),
    bodyLarge = inter(15f),
    bodyMedium = inter(14f, color = Lc.label),
    bodySmall = inter(12f, color = Lc.dim),
    labelLarge = inter(14f, FontWeight.Medium),
    labelMedium = inter(12f, FontWeight.Medium),
    labelSmall = inter(11f, FontWeight.Medium),
)

@Composable
fun LightCraftTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = type(), content = content)
}
