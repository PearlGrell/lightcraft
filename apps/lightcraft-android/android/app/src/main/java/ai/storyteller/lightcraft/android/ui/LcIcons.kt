package ai.storyteller.lightcraft.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * The editor's own icons, drawn here as vectors (original work; see assets/ATTRIBUTION.md). Every
 * other icon comes from the Material Symbols set (Apache-2.0) the Compose icon library ships.
 */
object LcIcons {
    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.stroke(width: Float = 1.7f, fillAlpha: Float = 0f, d: PathBuilder.() -> Unit) =
        path(
            fill = if (fillAlpha > 0f) SolidColor(Color.White.copy(alpha = fillAlpha)) else null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = d,
        )

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
        arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
        close()
    }

    /** Two overlapping discs: looks layered on the photo. */
    val Presets: ImageVector = icon("Presets") {
        stroke { circle(9.5f, 12f, 5.5f) }
        stroke(fillAlpha = 0.35f) { circle(14.5f, 12f, 5.5f) }
    }

    /** A ring of dots: a selection over part of the photo. */
    val Masking: ImageVector = icon("Masking") {
        path(fill = SolidColor(Color.White)) {
            for (ring in 0..2) {
                val r = 3f + ring * 3f
                val n = 6 + ring * 6
                for (i in 0 until n) {
                    val a = (i * 2 * Math.PI / n).toFloat()
                    circle(12f + r * cos(a), 12f + r * sin(a), 0.85f)
                }
            }
            circle(12f, 12f, 0.9f)
        }
    }

    /** An eraser block, tipped. */
    val Remove: ImageVector = icon("Remove") {
        stroke {
            moveTo(9f, 20f)
            lineTo(20f, 20f)
        }
        stroke(fillAlpha = 0f) {
            moveTo(4.5f, 15.5f)
            lineTo(13.5f, 6.5f)
            lineTo(19f, 12f)
            lineTo(11f, 20f)
            lineTo(9f, 20f)
            close()
        }
        path(fill = SolidColor(Color.White)) {
            moveTo(4.5f, 15.5f)
            lineTo(8.5f, 11.5f)
            lineTo(14f, 17f)
            lineTo(11f, 20f)
            lineTo(9f, 20f)
            close()
        }
    }

    /** A frame split down the middle, one half filled: before and after. */
    val Compare: ImageVector = icon("Compare") {
        stroke {
            moveTo(5f, 5f)
            lineTo(19f, 5f)
            lineTo(19f, 19f)
            lineTo(5f, 19f)
            close()
            moveTo(12f, 3f)
            lineTo(12f, 21f)
        }
        path(fill = SolidColor(Color.White.copy(alpha = 0.45f))) {
            moveTo(5f, 5f)
            lineTo(12f, 5f)
            lineTo(12f, 19f)
            lineTo(5f, 19f)
            close()
        }
    }

    /** A diagonal through a grid with one bend: the tone curve. */
    val Curve: ImageVector = icon("Curve") {
        stroke(width = 1.2f) {
            moveTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 20f); lineTo(4f, 20f); close()
            moveTo(9.3f, 4f); lineTo(9.3f, 20f)
            moveTo(14.7f, 4f); lineTo(14.7f, 20f)
            moveTo(4f, 9.3f); lineTo(20f, 9.3f)
            moveTo(4f, 14.7f); lineTo(20f, 14.7f)
        }
        stroke(width = 2f) {
            moveTo(4f, 20f)
            curveTo(9f, 19f, 11f, 9f, 20f, 4f)
        }
    }

    /** Three lines getting shorter beside a down arrow: sort order. */
    val SortDown: ImageVector = icon("SortDown") {
        stroke(width = 2f) {
            moveTo(3f, 6f); lineTo(15f, 6f)
            moveTo(3f, 12f); lineTo(12f, 12f)
            moveTo(3f, 18f); lineTo(9f, 18f)
            moveTo(19f, 4f); lineTo(19f, 19.5f)
            moveTo(16f, 16.5f); lineTo(19f, 19.5f); lineTo(22f, 16.5f)
        }
    }

    /** Three thin, round-ended lines: the drawer menu. */
    val Menu: ImageVector = icon("Menu") {
        stroke(width = 1.6f) {
            moveTo(3.5f, 6.5f); lineTo(20.5f, 6.5f)
            moveTo(3.5f, 12f); lineTo(20.5f, 12f)
            moveTo(3.5f, 17.5f); lineTo(20.5f, 17.5f)
        }
    }

    /** Three small dots, stacked: more options. */
    val More: ImageVector = icon("More") {
        path(fill = SolidColor(Color.White)) {
            circle(12f, 5.5f, 1.6f)
            circle(12f, 12f, 1.6f)
            circle(12f, 18.5f, 1.6f)
        }
    }
}
