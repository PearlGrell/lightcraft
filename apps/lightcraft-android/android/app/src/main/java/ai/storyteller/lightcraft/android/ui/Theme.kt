package ai.storyteller.lightcraft.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import ai.storyteller.lightcraft.android.R
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The app palette: pure black behind photos, charcoal bars and sheets, white and grey text, one
 * blue accent for the current tool, primary buttons and the selected tab.
 */
object Lc {
    val canvas = Color(0xFF000000)
    val chrome = Color(0xFF161616)
    val bar = Color(0xFF1E1E1E)
    /** The 1 dp line between bars and content. */
    val hairline = Color(0xFF2C2C2C)
    val drawer = Color(0xFF1E1E1E)
    val panel = Color(0xF2161616)
    val menu = Color(0xFF2C2C2C)
    val sheet = Color(0xFF1C1C1E)
    /** A selected chip / navigation indicator: a muted blue tone, with light blue on it. */
    val chipOn = Color(0xFF243650)
    val chipOnText = Color(0xFFCFE0FF)
    val navBar = Color(0xFF0F0F0F)
    val raised = Color(0xFF333333)
    val divider = Color(0xFF353535)
    val text = Color(0xFFF2F2F2)
    val label = Color(0xFFB3B3B3)
    val icon = Color(0xFFD0D0D0)
    val muted = Color(0xFF8E8E8E)
    val disabled = Color(0xFF5E5E5E)
    val track = Color(0xFF5C5C5C)
    val thumb = Color(0xFF2E2E2E)
    val accent = Color(0xFF2680EB)
    val accentText = Color(0xFF4B9CF5)
    val star = Color(0xFFF5C518)
    val pill = Color(0xCC1A1A1A)

    /** The blue-to-violet of the Welcome pill. */
    val welcome = Brush.horizontalGradient(listOf(Color(0xFF2F6BFF), Color(0xFF7A4DFF), Color(0xFFC63DEB)))
}

private val Scheme = darkColorScheme(
    primary = Lc.accent,
    onPrimary = Color.White,
    secondary = Lc.accent,
    background = Lc.canvas,
    surface = Lc.chrome,
    surfaceVariant = Lc.raised,
    surfaceContainer = Lc.menu,
    onBackground = Lc.text,
    onSurface = Lc.text,
    onSurfaceVariant = Lc.label,
    outline = Lc.divider,
)

/** Inter (OFL), the UI font: copied from assets/fonts by the build (see app/build.gradle.kts). */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_semibold, FontWeight.Bold),
)

private fun style(size: Int, weight: FontWeight = FontWeight.Normal, color: Color = Lc.text) =
    TextStyle(fontFamily = Inter, fontSize = size.sp, fontWeight = weight, color = color, letterSpacing = (-0.1).sp)

private val Type = Typography(
    headlineSmall = style(20, FontWeight.SemiBold),
    titleLarge = style(16),
    titleMedium = style(17, FontWeight.Medium),
    bodyLarge = style(16),
    bodyMedium = style(15, color = Lc.label),
    bodySmall = style(13, color = Lc.muted),
    labelLarge = style(15, FontWeight.SemiBold),
    labelMedium = style(13, FontWeight.Medium),
    labelSmall = style(12, FontWeight.Medium),
)

@Composable
fun LightCraftTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, content = content)
}

/** Snackbars as the app shows them: a dark rounded card over the photo or grid. */
@Composable
fun LcSnackbarHost(state: androidx.compose.material3.SnackbarHostState, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    androidx.compose.material3.SnackbarHost(state, modifier) { data ->
        androidx.compose.material3.Snackbar(
            snackbarData = data,
            containerColor = Lc.menu,
            contentColor = Lc.text,
            actionColor = Lc.accentText,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            modifier = androidx.compose.ui.Modifier.padding(horizontal = 12.dp),
        )
    }
}
