package ai.storyteller.lightcraft.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Share
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import ai.storyteller.lightcraft.android.CurvePoint
import ai.storyteller.lightcraft.android.MainTab
import ai.storyteller.lightcraft.android.Tools
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/**
 * Renders the editor's chrome on the JVM (Paparazzi) with a procedural stand-in photo, so the
 * layout can be looked at without a phone. The snapshots are not committed.
 */
class EditorChromeTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, maxPercentDifference = 100.0)

    /** A procedural "photo": a sky-to-ground gradient with a sun disc (nothing bundled). */
    private fun photo(w: Int = 600, h: Int = 800): Bitmap {
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val t = y / h.toFloat()
            val sun = (x - w * 0.62f) * (x - w * 0.62f) + (y - h * 0.3f) * (y - h * 0.3f) < (w * 0.09f) * (w * 0.09f)
            val (r, g, b) = when {
                sun -> Triple(255, 214, 140)
                t < 0.55f -> Triple((70 + 120 * t).toInt(), (120 + 90 * t).toInt(), (200 - 40 * t).toInt())
                else -> Triple((90 - 40 * t).toInt(), (110 - 30 * t).toInt(), (60 - 20 * t).toInt())
            }
            (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    @Composable
    private fun Editor(tab: MainTab, panel: @Composable () -> Unit, compare: Boolean = false) {
        val bmp = photo()
        Column(Modifier.fillMaxSize().background(Lc.canvas)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                if (compare) SplitHandle(Rect(0f, 120f, 1080f, 1560f), 0.5f) {}
                Row(Modifier.fillMaxWidth().padding(top = 24.dp, start = 4.dp, end = 4.dp)) {
                    OverlayIcon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", Color.White) {}
                    Spacer(Modifier.weight(1f))
                    OverlayIcon(Icons.AutoMirrored.Outlined.Undo, "Undo", Color.White) {}
                    OverlayIcon(Icons.AutoMirrored.Outlined.Redo, "Redo", Color.White) {}
                    OverlayIcon(LcIcons.Compare, "Compare", if (compare) Lc.accent else Color.White) {}
                    OverlayIcon(Icons.Outlined.Share, "Share", Color.White) {}
                }
            }
            Column(Modifier.fillMaxWidth().background(Lc.chrome)) {
                panel()
                when (tab) {
                    MainTab.Edit -> ToolRow(Tools.edit, Tools.edit[1], true) {}
                    MainTab.Masking -> ToolRow(Tools.masking, null, false) {}
                    MainTab.Remove -> ToolRow(Tools.remove, Tools.remove[0], false) {}
                    else -> {}
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF2E2E2E)))
                MainBar(tab) {}
            }
        }
    }

    @Test
    fun light() = paparazzi.snapshot {
        LightCraftTheme {
            Editor(MainTab.Edit, {
                Column(Modifier.padding(horizontal = 22.dp, vertical = 6.dp)) {
                    LrSlider("Exposure", 0.2f, -5f, 5f, 0f, 2, "EV", {}, {}, {})
                    LrSlider("Contrast", 37f, -100f, 100f, 0f, 0, "", {}, {}, {})
                    LrSlider("Highlights", -84f, -100f, 100f, 0f, 0, "", {}, {}, {})
                }
            }, compare = true)
        }
    }

    @Test
    fun curve() = paparazzi.snapshot {
        LightCraftTheme {
            Editor(MainTab.Edit, {
                CurveEditor(mapOf("master" to listOf(CurvePoint(0f, 0.05f), CurvePoint(0.3f, 0.22f), CurvePoint(0.7f, 0.8f), CurvePoint(1f, 1f))), { _, _ -> }, {}, {})
            })
        }
    }

    @Test
    fun masking() = paparazzi.snapshot {
        LightCraftTheme { Editor(MainTab.Masking, { Pills(listOf("Subject", "Sky"), 0, {}, null) }) }
    }
}
