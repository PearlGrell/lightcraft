package ai.storyteller.lightcraft.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * The desktop app's icons (`crates/ui-egui/src/icons.rs`, original work drawn in code), ported
 * stroke for stroke: each is drawn on a 20 × 20 grid with a 1.5-unit pen, so the phone and the
 * desktop share one icon set.
 */
enum class DeskIcon {
    Sidebar, Back, Forward, Search, Filter, Share, Help, Sliders, Crop, Eraser, Mask, Eye, Presets,
    Versions, Activity, More, Tag, Info, GridPhoto, GridSquare, Single, Compare, Sort, Star,
    StarFilled, Circle, FlagPick, FlagPickFilled, FlagReject, Gear, BeforeAfter, ChevronDown,
    ChevronRight, ChevronLeft, ChevronUp, Plus, Minus, Album, Stack, Folder, Pencil, Photos, Clock,
    Calendar, Trash, Curve, ProfileGrid, Close, Check, Brush, Linear, Radial, Sky, Subject, Picker,
    Rotate, Flip, Invert, Heart, Edited, Target, GradeAll, GradeShadows, GradeMidtones,
    GradeHighlights, Sun, Moon,
    /** Undo and Redo: a hooked arrow (drawn for the phone in the same pen; the desktop uses text arrows). */
    Undo, Redo,
    Filmstrip,
    /** Download: an arrow down into a tray (Share turned over). */
    Download,
}

/** [icon] in [tint], [size] square (20 grid units). */
@Composable
fun LcIcon(icon: DeskIcon, tint: Color = Lc.icon, size: Dp = 20.dp, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val m = if (contentDescription != null) modifier.semantics { this.contentDescription = contentDescription } else modifier
    Canvas(m.size(size)) { drawDeskIcon(icon, tint) }
}

private class Pen(val d: DrawScope, val color: Color) {
    val k = minOf(d.size.width, d.size.height) / 20f
    val ox = (d.size.width - 20f * k) / 2f
    val oy = (d.size.height - 20f * k) / 2f
    val stroke = Stroke(width = maxOf(1.5f * k, 1f), cap = StrokeCap.Round, join = StrokeJoin.Round)

    fun pt(x: Float, y: Float) = Offset(ox + x * k, oy + y * k)

    fun path(pts: List<Pair<Float, Float>>, close: Boolean) = Path().apply {
        pts.forEachIndexed { i, (x, y) -> val p = pt(x, y); if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
        if (close) close()
    }

    fun line(vararg pts: Pair<Float, Float>) = d.drawPath(path(pts.toList(), false), color, style = stroke)
    fun closed(vararg pts: Pair<Float, Float>) = d.drawPath(path(pts.toList(), true), color, style = stroke)
    fun fill(pts: List<Pair<Float, Float>>, c: Color = color) = d.drawPath(path(pts, true), c)
    fun circle(x: Float, y: Float, r: Float, c: Color = color) = d.drawCircle(c, r * k, pt(x, y), style = stroke)
    fun dot(x: Float, y: Float, r: Float) = d.drawCircle(color, r * k, pt(x, y))
    fun rect(x0: Float, y0: Float, x1: Float, y1: Float, round: Float) =
        d.drawRoundRect(color, pt(x0, y0), Size((x1 - x0) * k, (y1 - y0) * k), CornerRadius(round * k), style = stroke)
    fun rectFill(x0: Float, y0: Float, x1: Float, y1: Float, round: Float) =
        d.drawRoundRect(color, pt(x0, y0), Size((x1 - x0) * k, (y1 - y0) * k), CornerRadius(round * k))
    fun arc(cx: Float, cy: Float, r: Float, a0: Float, a1: Float) {
        val n = 24
        line(*Array(n + 1) { i ->
            val a = Math.toRadians((a0 + (a1 - a0) * i / n).toDouble())
            (cx + r * cos(a).toFloat()) to (cy + r * sin(a).toFloat())
        })
    }
    fun seg(x0: Float, y0: Float, x1: Float, y1: Float, c: Color) = d.drawLine(c, pt(x0, y0), pt(x1, y1), stroke.width, cap = StrokeCap.Round)
}

private fun starPts(cx: Float, cy: Float, r: Float): List<Pair<Float, Float>> = (0 until 10).map { i ->
    val a = Math.toRadians(-90.0 + i * 36.0)
    val rr = if (i % 2 == 0) r else r * 0.45f
    (cx + rr * cos(a).toFloat()) to (cy + rr * sin(a).toFloat())
}

private fun ring(cx: Float, cy: Float, r: Float, a0: Float, a1: Float, n: Int): List<Pair<Float, Float>> = (0..n).map { i ->
    val a = Math.toRadians((a0 + (a1 - a0) * i / n).toDouble())
    (cx + r * cos(a).toFloat()) to (cy + r * sin(a).toFloat())
}

fun DrawScope.drawDeskIcon(icon: DeskIcon, color: Color) {
    val p = Pen(this, color)
    when (icon) {
        DeskIcon.Sidebar -> { p.rect(3f, 4f, 17f, 16f, 1.5f); p.line(8f to 4f, 8f to 16f) }
        DeskIcon.Sun -> {
            p.circle(10f, 10f, 3.2f)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0); val c = cos(a).toFloat(); val s = sin(a).toFloat()
                p.line((10f + 5.4f * c) to (10f + 5.4f * s), (10f + 7.4f * c) to (10f + 7.4f * s))
            }
        }
        DeskIcon.Moon -> { p.arc(10f, 10f, 6.5f, 2.9f, 263.8f); p.arc(13.4f, 6.4f, 5f, 214.9f, 51.8f) }
        DeskIcon.Search -> { p.circle(8.5f, 8.5f, 5f); p.line(12.2f to 12.2f, 16.5f to 16.5f) }
        DeskIcon.Filter -> p.closed(3f to 4f, 17f to 4f, 11.5f to 10.5f, 11.5f to 16f, 8.5f to 14.5f, 8.5f to 10.5f)
        DeskIcon.Share -> {
            p.line(6f to 8f, 4f to 8f, 4f to 17f, 16f to 17f, 16f to 8f, 14f to 8f)
            p.line(10f to 12f, 10f to 2.5f)
            p.line(6.5f to 6f, 10f to 2.5f, 13.5f to 6f)
        }
        DeskIcon.Help -> { p.circle(10f, 10f, 7.5f); p.arc(10f, 8f, 2.5f, 180f, 400f); p.line(10f to 10.5f, 10f to 11.8f); p.dot(10f, 14.2f, 0.9f) }
        DeskIcon.Sliders -> for ((y, x) in listOf(5f to 12f, 10f to 7f, 15f to 13f)) {
            p.line(3f to y, (x - 2f) to y); p.line((x + 2f) to y, 17f to y); p.circle(x, y, 2f)
        }
        DeskIcon.Crop -> {
            p.line(6f to 2.5f, 6f to 14f, 17.5f to 14f)
            p.line(2.5f to 6f, 14f to 6f, 14f to 17.5f)
            p.line(15.5f to 2f, 17.5f to 4f, 15.5f to 6f)
        }
        DeskIcon.Eraser -> {
            p.closed(3f to 12f, 10f to 5f, 16f to 11f, 11f to 16f, 7f to 16f)
            p.line(6.5f to 8.5f, 12.5f to 14.5f)
            p.line(7f to 17.5f, 17f to 17.5f)
        }
        DeskIcon.Mask -> {
            p.circle(10f, 10f, 7f)
            for (i in 0 until 5) for (j in 0 until 5) {
                val x = 5f + i * 2.5f; val y = 5f + j * 2.5f
                if (kotlin.math.hypot(x - 10f, y - 10f) < 5.8f) p.dot(x, y, 0.6f)
            }
        }
        DeskIcon.Eye -> { p.arc(10f, 16f, 9f, 212f, 328f); p.arc(10f, 4f, 9f, 32f, 148f); p.circle(10f, 10f, 2.5f) }
        DeskIcon.GradeAll -> { p.circle(10f, 6f, 3.2f); p.circle(5.5f, 13.5f, 3.2f); p.circle(14.5f, 13.5f, 3.2f) }
        DeskIcon.GradeShadows -> { p.circle(10f, 10f, 6f); p.dot(10f, 10f, 6f) }
        DeskIcon.GradeMidtones -> { p.circle(10f, 10f, 6f); p.fill(ring(10f, 10f, 6f, 90f, 270f, 12)) }
        DeskIcon.GradeHighlights -> {
            p.circle(10f, 10f, 4f)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0); val c = cos(a).toFloat(); val s = sin(a).toFloat()
                p.line((10f + 6f * c) to (10f + 6f * s), (10f + 8f * c) to (10f + 8f * s))
            }
        }
        DeskIcon.Presets -> { p.circle(8f, 8f, 5f); p.circle(12f, 12f, 5f) }
        DeskIcon.Versions -> { p.rect(5f, 3f, 17f, 13f, 1.5f); p.line(3f to 6f, 3f to 16f, 14f to 16f) }
        DeskIcon.Activity -> { p.circle(10f, 10f, 7f); p.line(10f to 6f, 10f to 10f, 13f to 12f) }
        DeskIcon.More -> for (x in listOf(5f, 10f, 15f)) p.dot(x, 10f, 1.3f)
        DeskIcon.Tag -> { p.closed(3f to 3.5f, 10f to 3.5f, 17f to 10.5f, 10.5f to 17f, 3f to 10f); p.dot(7f, 7.5f, 1.2f) }
        DeskIcon.Info -> { p.circle(10f, 10f, 7.5f); p.line(10f to 9f, 10f to 14f); p.dot(10f, 6.3f, 0.9f) }
        DeskIcon.GridPhoto -> {
            p.rectFill(3f, 4f, 10f, 9.5f, 0.5f); p.rectFill(11f, 4f, 17f, 9.5f, 0.5f)
            p.rectFill(3f, 10.5f, 8f, 16f, 0.5f); p.rectFill(9f, 10.5f, 17f, 16f, 0.5f)
        }
        DeskIcon.GridSquare -> {
            p.rectFill(3.5f, 4f, 9.5f, 9.5f, 0.5f); p.rectFill(10.5f, 4f, 16.5f, 9.5f, 0.5f)
            p.rectFill(3.5f, 10.5f, 9.5f, 16f, 0.5f); p.rectFill(10.5f, 10.5f, 16.5f, 16f, 0.5f)
        }
        DeskIcon.Single -> p.rectFill(3f, 5f, 17f, 15f, 0.5f)
        DeskIcon.Compare -> { p.rectFill(2f, 5f, 8.8f, 15f, 0.5f); p.rectFill(11.2f, 5f, 18f, 15f, 0.5f) }
        DeskIcon.Sort -> { p.line(3f to 5f, 17f to 5f); p.line(3f to 10f, 13f to 10f); p.line(3f to 15f, 9f to 15f) }
        DeskIcon.Star -> p.closed(*starPts(10f, 10.5f, 7.5f).toTypedArray())
        DeskIcon.StarFilled -> { val s = starPts(10f, 10.5f, 7.5f); p.fill(s); p.closed(*s.toTypedArray()) }
        DeskIcon.Circle -> p.circle(10f, 10f, 6f)
        DeskIcon.FlagPick -> { p.line(5f to 17f, 5f to 3.5f); p.closed(5f to 3.5f, 15f to 3.5f, 12.5f to 7f, 15f to 10.5f, 5f to 10.5f) }
        DeskIcon.FlagPickFilled -> {
            p.line(5f to 17f, 5f to 3.5f)
            p.fill(listOf(5f to 3.5f, 15f to 3.5f, 12.5f to 7f, 15f to 10.5f, 5f to 10.5f))
            p.closed(5f to 3.5f, 15f to 3.5f, 12.5f to 7f, 15f to 10.5f, 5f to 10.5f)
        }
        DeskIcon.FlagReject -> {
            p.line(5f to 17f, 5f to 3.5f)
            p.closed(5f to 3.5f, 15f to 3.5f, 12.5f to 7f, 15f to 10.5f, 5f to 10.5f)
            p.line(8f to 5f, 12f to 9f); p.line(12f to 5f, 8f to 9f)
        }
        DeskIcon.Gear -> {
            p.circle(10f, 10f, 2.5f)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0); val c = cos(a).toFloat(); val s = sin(a).toFloat()
                p.line((10f + 5.2f * c) to (10f + 5.2f * s), (10f + 7.5f * c) to (10f + 7.5f * s))
            }
            p.circle(10f, 10f, 5.2f)
        }
        DeskIcon.BeforeAfter -> { p.rect(3f, 4f, 17f, 16f, 1f); p.line(10f to 2.5f, 10f to 17.5f); p.rectFill(10f, 4f, 17f, 16f, 0.5f) }
        DeskIcon.ChevronDown -> p.line(5f to 7.5f, 10f to 12.5f, 15f to 7.5f)
        DeskIcon.Forward, DeskIcon.ChevronRight -> p.line(7.5f to 5f, 12.5f to 10f, 7.5f to 15f)
        DeskIcon.Back, DeskIcon.ChevronLeft -> p.line(12.5f to 5f, 7.5f to 10f, 12.5f to 15f)
        DeskIcon.ChevronUp -> p.line(5f to 12.5f, 10f to 7.5f, 15f to 12.5f)
        DeskIcon.Plus -> { p.line(10f to 4f, 10f to 16f); p.line(4f to 10f, 16f to 10f) }
        DeskIcon.Minus -> p.line(4f to 10f, 16f to 10f)
        DeskIcon.Album -> { p.rect(3f, 5f, 15f, 17f, 1f); p.line(6f to 2.5f, 17.5f to 2.5f, 17.5f to 14f) }
        DeskIcon.Stack -> { p.rect(2.5f, 7f, 13.5f, 17.5f, 1f); p.line(5f to 4.5f, 16f to 4.5f, 16f to 14.5f); p.line(7.5f to 2f, 18.5f to 2f, 18.5f to 11.5f) }
        DeskIcon.Folder -> p.closed(2.5f to 5f, 8f to 5f, 9.5f to 7f, 17.5f to 7f, 17.5f to 15.5f, 2.5f to 15.5f)
        DeskIcon.Pencil -> { p.closed(4f to 16f, 4.5f to 12.5f, 13.5f to 3.5f, 16.5f to 6.5f, 7.5f to 15.5f); p.line(11.5f to 5.5f, 14.5f to 8.5f) }
        DeskIcon.Photos -> {
            p.rect(2.5f, 4f, 17.5f, 16f, 1.5f)
            p.line(2.5f to 13.5f, 7f to 9.5f, 11f to 13f, 13.5f to 11f, 17.5f to 14.5f)
            p.dot(13.5f, 7.5f, 1.3f)
        }
        DeskIcon.Clock -> { p.circle(10f, 10f, 7f); p.line(10f to 5.5f, 10f to 10f, 13.5f to 11.5f) }
        DeskIcon.Calendar -> {
            p.rect(3f, 4.5f, 17f, 17f, 1.5f); p.line(3f to 8.5f, 17f to 8.5f)
            p.line(7f to 2.5f, 7f to 6f); p.line(13f to 2.5f, 13f to 6f); p.rect(11f, 11.5f, 14f, 14.5f, 0.5f)
        }
        DeskIcon.Trash -> {
            p.line(3f to 5.5f, 17f to 5.5f)
            p.line(8f to 5.5f, 8f to 3f, 12f to 3f, 12f to 5.5f)
            p.closed(5f to 5.5f, 15f to 5.5f, 14f to 17f, 6f to 17f)
        }
        DeskIcon.Curve -> {
            p.rect(3f, 3f, 17f, 17f, 1.5f)
            p.line(*Array(13) { i -> val t = i / 12f; val y = t * t * (3f - 2f * t); (4.5f + 11f * t) to (15.5f - 11f * y) })
        }
        DeskIcon.ProfileGrid -> {
            p.rectFill(3.5f, 3.5f, 9f, 9f, 0.5f); p.rectFill(11f, 3.5f, 16.5f, 9f, 0.5f); p.rectFill(3.5f, 11f, 9f, 16.5f, 0.5f)
            p.circle(14f, 14f, 2.5f); p.line(15.8f to 15.8f, 17.5f to 17.5f)
        }
        DeskIcon.Close -> { p.line(5f to 5f, 15f to 15f); p.line(15f to 5f, 5f to 15f) }
        DeskIcon.Check -> p.line(4f to 10.5f, 8.5f to 15f, 16f to 5.5f)
        DeskIcon.Brush -> { p.line(16.5f to 3.5f, 9f to 11f); p.closed(9f to 11f, 6f to 11.5f, 4f to 14.5f, 3.5f to 17f, 6f to 16.5f, 8.5f to 14f) }
        DeskIcon.Linear -> listOf(4f, 7.5f, 11f, 14.5f).forEachIndexed { i, x -> p.seg(x, 3f, x, 17f, color.copy(alpha = color.alpha * (1f - i * 0.22f))) }
        DeskIcon.Radial -> { p.circle(10f, 10f, 7f); p.circle(10f, 10f, 3.5f, color.copy(alpha = color.alpha * 0.5f)) }
        DeskIcon.Sky -> {
            p.arc(8f, 11f, 3.2f, 150f, 300f); p.arc(12f, 9.5f, 3.8f, 210f, 360f)
            p.line(5f to 13.5f, 15.5f to 13.5f); p.line(2.5f to 17f, 17.5f to 17f)
        }
        DeskIcon.Subject -> { p.circle(10f, 7f, 3f); p.arc(10f, 18f, 6.5f, 200f, 340f); p.rect(2.5f, 2.5f, 17.5f, 17.5f, 2f) }
        DeskIcon.Picker -> { p.line(4f to 16f, 11.5f to 8.5f); p.closed(11f to 5f, 15f to 9f, 17f to 7f, 13f to 3f) }
        DeskIcon.Rotate -> { p.arc(10f, 10f, 6.5f, 200f, 470f); p.line(3f to 5.5f, 4f to 9f, 7.5f to 8f) }
        DeskIcon.Flip -> {
            p.line(10f to 2.5f, 10f to 17.5f)
            p.closed(8f to 5f, 8f to 15f, 3f to 15f)
            p.fill(listOf(12f to 5f, 12f to 15f, 17f to 15f))
        }
        DeskIcon.Invert -> { p.circle(10f, 10f, 7f); p.fill(ring(10f, 10f, 7f, 90f, 270f, 16)) }
        DeskIcon.Heart -> p.closed(10f to 16.5f, 3.5f to 9.5f, 4f to 5.5f, 7.5f to 4f, 10f to 6.5f, 12.5f to 4f, 16f to 5.5f, 16.5f to 9.5f)
        DeskIcon.Edited -> { p.circle(10f, 10f, 7f); p.line(7f to 13f, 13f to 7f) }
        DeskIcon.Download -> {
            p.line(4f to 12f, 4f to 17f, 16f to 17f, 16f to 12f)
            p.line(10f to 2.5f, 10f to 13f)
            p.line(6.5f to 9.5f, 10f to 13f, 13.5f to 9.5f)
        }
        DeskIcon.Filmstrip -> {
            p.rect(2.5f, 5f, 17.5f, 15f, 1f)
            p.rectFill(5f, 11f, 8.5f, 13.5f, 0.3f)
            p.rectFill(9.5f, 11f, 13f, 13.5f, 0.3f)
            p.line(2.5f to 9.5f, 17.5f to 9.5f)
        }
        DeskIcon.Undo -> {
            p.line(4f to 8f, 12.5f to 8f); p.arc(12.5f, 11.5f, 3.5f, -90f, 90f); p.line(12.5f to 15f, 7.5f to 15f)
            p.line(7f to 5f, 4f to 8f, 7f to 11f)
        }
        DeskIcon.Redo -> {
            p.line(16f to 8f, 7.5f to 8f); p.arc(7.5f, 11.5f, 3.5f, 270f, 90f); p.line(7.5f to 15f, 12.5f to 15f)
            p.line(13f to 5f, 16f to 8f, 13f to 11f)
        }
        DeskIcon.Target -> {
            p.circle(8f, 10f, 5f); p.dot(8f, 10f, 1.3f)
            p.line(15.5f to 3.5f, 15.5f to 16.5f)
            p.line(13.5f to 5.5f, 15.5f to 3.5f, 17.5f to 5.5f)
            p.line(13.5f to 14.5f, 15.5f to 16.5f, 17.5f to 14.5f)
        }
    }
}
