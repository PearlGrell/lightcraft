package ai.storyteller.lightcraft.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** What a drag on the crop frame moves: a corner, an edge, or the whole frame. */
internal enum class CropGrip { TL, T, TR, R, BR, B, BL, L, Move }

/**
 * Where a crop drag of [grip] by ([dx], [dy]) (normalized) leaves [r] (x0, y0, x1, y1), kept
 * inside the photo and at least [min] across. With [ratio] > 0 (width / height in pixels, on a
 * photo of [imageAspect]) corners keep the frame's shape. Pure, for the overlay and tests.
 */
internal fun dragCrop(r: List<Float>, grip: CropGrip, dx: Float, dy: Float, ratio: Float, imageAspect: Float, min: Float = 0.05f): List<Float> {
    var (x0, y0, x1, y1) = r
    when (grip) {
        CropGrip.Move -> {
            val w = x1 - x0
            val h = y1 - y0
            x0 = (x0 + dx).coerceIn(0f, 1f - w); y0 = (y0 + dy).coerceIn(0f, 1f - h)
            return listOf(x0, y0, x0 + w, y0 + h)
        }
        CropGrip.TL -> { x0 += dx; y0 += dy }
        CropGrip.T -> y0 += dy
        CropGrip.TR -> { x1 += dx; y0 += dy }
        CropGrip.R -> x1 += dx
        CropGrip.BR -> { x1 += dx; y1 += dy }
        CropGrip.B -> y1 += dy
        CropGrip.BL -> { x0 += dx; y1 += dy }
        CropGrip.L -> x0 += dx
    }
    x0 = x0.coerceIn(0f, x1 - min); x1 = x1.coerceIn(x0 + min, 1f)
    y0 = y0.coerceIn(0f, y1 - min); y1 = y1.coerceIn(y0 + min, 1f)
    if (ratio > 0f && imageAspect > 0f && grip in setOf(CropGrip.TL, CropGrip.TR, CropGrip.BR, CropGrip.BL)) {
        // keep the shape: the height follows the width, anchored at the opposite corner
        val wantH = ((x1 - x0) * imageAspect / ratio).coerceAtMost(1f)
        when (grip) {
            CropGrip.TL, CropGrip.TR -> y0 = (y1 - wantH).coerceAtLeast(0f)
            else -> y1 = (y0 + wantH).coerceAtMost(1f)
        }
        val h = y1 - y0
        val wantW = h * ratio / imageAspect
        when (grip) {
            CropGrip.TL, CropGrip.BL -> x0 = x1 - wantW
            else -> x1 = x0 + wantW
        }
    }
    return listOf(x0, y0, x1, y1)
}

/**
 * The crop tool's frame over the uncropped photo ([fit]: where it is drawn), as the desktop
 * draws it: the outside dimmed, a white frame, thirds while dragging, L-shaped corner grips and
 * edge grips. Drag a corner or edge to resize, inside to move; the crop is set when the finger
 * lifts (one undo step).
 */
@Composable
internal fun CropOverlay(fit: Rect, crop: List<Float>, ratio: Float, imageAspect: Float, onCommit: (List<Float>) -> Unit) {
    var r by remember { mutableStateOf(crop) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(crop) { if (!dragging) r = crop }
    val commit by rememberUpdatedState(onCommit)
    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(fit, ratio, imageAspect) {
                var grip = CropGrip.Move
                val reach = 28.dp.toPx()
                detectDragGestures(
                    onDragStart = { o ->
                        val x0 = fit.left + r[0] * fit.width
                        val y0 = fit.top + r[1] * fit.height
                        val x1 = fit.left + r[2] * fit.width
                        val y1 = fit.top + r[3] * fit.height
                        val nearL = abs(o.x - x0) < reach
                        val nearR = abs(o.x - x1) < reach
                        val nearT = abs(o.y - y0) < reach
                        val nearB = abs(o.y - y1) < reach
                        grip = when {
                            nearL && nearT -> CropGrip.TL
                            nearR && nearT -> CropGrip.TR
                            nearR && nearB -> CropGrip.BR
                            nearL && nearB -> CropGrip.BL
                            nearT && o.x in x0..x1 -> CropGrip.T
                            nearB && o.x in x0..x1 -> CropGrip.B
                            nearL && o.y in y0..y1 -> CropGrip.L
                            nearR && o.y in y0..y1 -> CropGrip.R
                            else -> CropGrip.Move
                        }
                        dragging = true
                    },
                    onDragEnd = { dragging = false; commit(r) },
                    onDragCancel = { dragging = false; r = crop },
                ) { c, d ->
                    c.consume()
                    r = dragCrop(r, grip, d.x / fit.width, d.y / fit.height, ratio, imageAspect)
                }
            },
    ) {
        val box = Rect(fit.left + r[0] * fit.width, fit.top + r[1] * fit.height, fit.left + r[2] * fit.width, fit.top + r[3] * fit.height)
        // dim what the crop leaves out
        clipRect(box.left, box.top, box.right, box.bottom, ClipOp.Difference) {
            drawRect(Color(0xA6000000), Offset(fit.left, fit.top), Size(fit.width, fit.height))
        }
        drawRect(Color.White, box.topLeft, box.size, style = Stroke(1.dp.toPx()))
        if (dragging) {
            for (i in 1..2) {
                val x = box.left + box.width * i / 3f
                val y = box.top + box.height * i / 3f
                drawLine(Color(0x99FFFFFF), Offset(x, box.top), Offset(x, box.bottom), 1.dp.toPx())
                drawLine(Color(0x99FFFFFF), Offset(box.left, y), Offset(box.right, y), 1.dp.toPx())
            }
        }
        val t = 3.dp.toPx()
        val l = 18.dp.toPx()
        val corners = listOf(
            Triple(box.topLeft, 1f, 1f), Triple(Offset(box.right, box.top), -1f, 1f),
            Triple(box.bottomRight, -1f, -1f), Triple(Offset(box.left, box.bottom), 1f, -1f),
        )
        for ((p, sx, sy) in corners) {
            drawLine(Color.White, p, Offset(p.x + sx * l, p.y), t)
            drawLine(Color.White, p, Offset(p.x, p.y + sy * l), t)
        }
        val e = 10.dp.toPx()
        drawLine(Color.White, Offset(box.center.x - e, box.top), Offset(box.center.x + e, box.top), t)
        drawLine(Color.White, Offset(box.center.x - e, box.bottom), Offset(box.center.x + e, box.bottom), t)
        drawLine(Color.White, Offset(box.left, box.center.y - e), Offset(box.left, box.center.y + e), t)
        drawLine(Color.White, Offset(box.right, box.center.y - e), Offset(box.right, box.center.y + e), t)
    }
}
