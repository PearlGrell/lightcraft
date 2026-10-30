package ai.storyteller.lightcraft.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import ai.storyteller.lightcraft.android.GalleryImage
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import ai.storyteller.lightcraft.android.Control
import ai.storyteller.lightcraft.android.CurvePoint
import ai.storyteller.lightcraft.android.MainTab
import ai.storyteller.lightcraft.android.BrushSettings
import ai.storyteller.lightcraft.android.ExportSettings
import ai.storyteller.lightcraft.android.HistoryInfo
import ai.storyteller.lightcraft.android.InfoLine
import ai.storyteller.lightcraft.android.PhotoInfo
import ai.storyteller.lightcraft.android.Preset
import ai.storyteller.lightcraft.android.Profile
import ai.storyteller.lightcraft.android.Tools
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/**
 * Renders the library and the editor's chrome on the JVM (Paparazzi) with procedural stand-in
 * photos, so the layout can be looked at without a phone. The snapshots are not committed.
 */
class EditorChromeTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, maxPercentDifference = 100.0)

    /** A procedural "photo": a sky-to-ground gradient with a sun disc (nothing bundled). */
    private fun photo(w: Int = 600, h: Int = 800, hue: Int = 0): Bitmap {
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val t = y / h.toFloat()
            val sun = (x - w * 0.62f) * (x - w * 0.62f) + (y - h * 0.3f) * (y - h * 0.3f) < (w * 0.09f) * (w * 0.09f)
            val (r, g, b) = when {
                sun -> Triple(255, 214, 140)
                t < 0.55f -> Triple((70 + 120 * t).toInt() + hue, (120 + 90 * t).toInt(), (200 - 40 * t).toInt() - hue)
                else -> Triple((90 - 40 * t).toInt() + hue, (110 - 30 * t).toInt(), (60 - 20 * t).toInt())
            }
            (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    @Composable
    private fun Editor(tab: MainTab?, panel: @Composable () -> Unit, compare: Boolean = false) {
        val bmp = photo()
        Column(Modifier.fillMaxSize().background(Lc.canvas)) {
            EditorTopBar(vm = null, busy = false, canUndo = tab != null, compare = compare, onCompare = {}, onCreatePreset = {})
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                if (compare) SplitHandle(Rect(0f, 40f, 1080f, 1400f), 0.5f) {}
            }
            Column(Modifier.fillMaxWidth().background(Lc.chrome)) {
                panel()
                when (tab) {
                    MainTab.Edit -> ToolRow(Tools.edit, Tools.edit[1], 0) {}
                    MainTab.Masking -> ToolRow(Tools.masking, null, -1) {}
                    MainTab.Remove -> ToolRow(Tools.remove, Tools.remove[1], 2) {}
                    else -> {}
                }
                if (tab != null) Box(Modifier.fillMaxWidth().height(1.dp).background(Lc.divider))
                MainBar(tab, labels = tab == null) {}
            }
        }
    }

    /** A fake library: 40 photos over five days of two months, drawn procedurally. */
    private val gallery = (0 until 40).map { i ->
        val day = 86_400_000L
        val base = 1_791_000_000_000L // October 2026
        GalleryImage(i.toLong(), android.net.Uri.EMPTY, null, "IMG_$i.jpg", "image/jpeg", base - (i / 8) * day * 9 - i * 60_000L, "Camera")
    }
    private val bitmaps = HashMap<Long, Bitmap>()
    private val fakeTile: Tile = { image, modifier ->
        val bmp = bitmaps.getOrPut(image.id) { photo(240, 240, hue = ((image.id * 37) % 120).toInt()) }
        Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = modifier)
    }

    @Composable
    private fun Library(view: LibraryView, content: @Composable (PaddingValues) -> Unit) {
        Column(Modifier.fillMaxSize().background(Lc.canvas)) {
            Column(Modifier.fillMaxWidth().background(Lc.bar).padding(top = 24.dp)) {
                LibraryHeader("Library", "October 2026", emptyList(), null, {}, FileKind.All, {}, false, {}, 4, {}, true, {}, {}, {}, {})
                ViewTabs(view) {}
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { content(PaddingValues(bottom = 16.dp)) }
            BottomTabs(LibraryTab.Gallery) {}
        }
    }

    @Test
    fun library() = paparazzi.snapshot {
        LightCraftTheme {
            Library(LibraryView.All) { pad -> AllPhotos(gallery, 4, {}, androidx.compose.foundation.lazy.grid.rememberLazyGridState(), pad, fakeTile) }
        }
    }

    @Test
    fun libraryDays() = paparazzi.snapshot {
        LightCraftTheme { Library(LibraryView.Days) { pad -> DayMosaics(gallery, pad, fakeTile) {} } }
    }

    @Test
    fun libraryMonths() = paparazzi.snapshot {
        LightCraftTheme { Library(LibraryView.Months) { pad -> MonthCards(gallery, androidx.compose.foundation.lazy.rememberLazyListState(), pad, fakeTile) {} } }
    }

    @Test
    fun drawer() = paparazzi.snapshot {
        LightCraftTheme {
            Box(Modifier.fillMaxSize().background(Lc.canvas)) {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0x99000000)))
                Row { LibraryDrawer(onTab = {}, onPage = {}) }
            }
        }
    }

    @Test
    fun noAccess() = paparazzi.snapshot {
        LightCraftTheme { Library(LibraryView.All) { pad -> Box(Modifier.padding(pad)) { NoAccess {} } } }
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

    private val presets = listOf(
        Preset("a1", "Warm", "Adaptive: Portrait"),
        Preset("a2", "Soft", "Adaptive: Portrait"),
        Preset("s1", "Deep blue", "Adaptive: Sky"),
        Preset("l1", "Vivid", "Landscape"),
        Preset("l2", "Muted", "Landscape", favorite = true),
        Preset("b1", "Classic", "Black & White"),
    )

    @Test
    fun presets() = paparazzi.snapshot {
        LightCraftTheme {
            val thumbs = presets.mapIndexed { i, p -> p.id to photo(120, 120, hue = i * 25) }.toMap()
            Editor(MainTab.Presets, { PresetBrowser(presets, thumbs, {}, {}, { _, _ -> }, {}) })
        }
    }

    @Test
    fun presetGroup() = paparazzi.snapshot {
        LightCraftTheme {
            val thumbs = presets.mapIndexed { i, p -> p.id to photo(120, 120, hue = i * 25) }.toMap()
            Editor(MainTab.Presets, { PresetBrowser(presets, thumbs, {}, {}, { _, _ -> }, {}, initialGroup = "Landscape") })
        }
    }

    @Test
    fun opened() = paparazzi.snapshot {
        LightCraftTheme { Editor(null, {}) }
    }

    @Test
    fun editTools() = paparazzi.snapshot {
        LightCraftTheme {
            Column(Modifier.fillMaxSize().background(Lc.canvas)) {
                EditorTopBar(vm = null, busy = false, canUndo = false, compare = false, onCompare = {}, onCreatePreset = {})
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Image(photo().asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
                ToolRow(Tools.edit, null, 0) {}
                Box(Modifier.fillMaxWidth().height(1.dp).background(Lc.divider))
                MainBar(MainTab.Edit, labels = false) {}
            }
        }
    }

    @Test
    fun crop() = paparazzi.snapshot {
        LightCraftTheme {
            val angle = Control("crop.angle", "Straighten", "Crop", -45f, 45f, 0f, 2, 3.5f)
            Column(Modifier.fillMaxSize().background(Lc.canvas)) {
                CropTopBar(aspectLabel(0f)) {}
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp)) {
                    val bmp = photo()
                    Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                        val w = constraints.maxWidth
                        val h = constraints.maxHeight
                        fitRect(bmp.width, bmp.height, androidx.compose.ui.unit.IntSize(w, h))?.let { CropFrame(it) }
                    }
                }
                Column(Modifier.fillMaxWidth().background(Lc.chrome)) {
                    CropControls(controls = listOf(angle), aspect = 0f, command = {}, setAspect = {}, setControl = { _, _ -> }, onDone = {}, resetControl = {})
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Lc.divider))
                    CropActionBar({}, {})
                }
            }
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

    @Composable
    private fun Sheet(content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize().background(Lc.canvas)) {
            Box(Modifier.weight(1f).fillMaxWidth()) { Image(photo().asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            Column(Modifier.fillMaxWidth().background(Lc.sheet).padding(top = 22.dp)) { content() }
        }
    }

    @Test
    fun export() = paparazzi.snapshot {
        LightCraftTheme { Sheet { ExportSheet(ExportSettings(watermark = "© Aryan"), {}, {}) } }
    }

    @Test
    fun info() = paparazzi.snapshot {
        LightCraftTheme {
            Sheet {
                InfoSheet(
                    PhotoInfo(4, "pick", "green", listOf(InfoLine("File", "IMG_2041.jpg"), InfoLine("Size", "4080 × 3072"), InfoLine("Camera", "Pixel 8 Pro"), InfoLine("Lens", "6.9 mm f/1.68"), InfoLine("Aperture", "f/1.7"), InfoLine("Shutter", "1/120"), InfoLine("ISO", "54"))),
                    {}, {},
                )
            }
        }
    }

    @Test
    fun history() = paparazzi.snapshot {
        LightCraftTheme { Sheet { HistorySheet(HistoryInfo(listOf("Import", "Exposure", "Contrast", "Heal", "Crop"), 3, listOf("Warm edit", "B&W")), {}, {}, {}) } }
    }

    @Test
    fun profiles() = paparazzi.snapshot {
        LightCraftTheme {
            val list = listOf(
                Profile("lc.color", "Color", "Basic", true), Profile("lc.vivid", "Vivid", "Basic", false), Profile("lc.mono", "Monochrome", "Basic", false),
                Profile("lc.portrait", "Portrait", "Basic", false), Profile("lc.film", "Vintage film", "Artistic", false),
            )
            Editor(MainTab.Edit, { ProfileBrowser(list, "lc.vivid" to 100f, {}, {}, {}) })
        }
    }

    @Test
    fun heal() = paparazzi.snapshot {
        LightCraftTheme {
            Editor(MainTab.Remove, { BrushPanel("Paint over a blemish; it is blended with texture from nearby", BrushSettings(), {}, {}) })
        }
    }

    @Test
    fun colorSliders() = paparazzi.snapshot {
        LightCraftTheme {
            Editor(MainTab.Edit, {
                Column(Modifier.padding(horizontal = 22.dp, vertical = 6.dp)) {
                    LrSlider("Temp", 5400f, 2000f, 50000f, 5500f, 0, "K", {}, {}, {}, track = trackFor("wb.temp"))
                    LrSlider("Tint", 8f, -150f, 150f, 0f, 0, "", {}, {}, {}, track = trackFor("wb.tint"))
                    LrSlider("Vibrance", 22f, -100f, 100f, 0f, 0, "", {}, {}, {}, track = trackFor("color.vibrance"))
                    LrSlider("Saturation", 0f, -100f, 100f, 0f, 0, "", {}, {}, {}, track = trackFor("color.saturation"))
                }
            })
        }
    }

    @Test
    fun removeGenerative() = paparazzi.snapshot {
        LightCraftTheme {
            Editor(MainTab.Remove, {
                SelectRemovePanel(
                    true, false, BrushSettings(), {}, {}, {}, {},
                    models = emptyMap(),
                )
            })
        }
    }

    @Test
    fun removeGenerativeReady() = paparazzi.snapshot {
        LightCraftTheme {
            Editor(MainTab.Remove, {
                SelectRemovePanel(true, true, BrushSettings(), {}, {}, {}, {}, models = mapOf("fill" to ai.storyteller.lightcraft.android.GenModel(installed = true)), generative = true)
            })
        }
    }

    @Test
    fun loupe() = paparazzi.snapshot {
        LightCraftTheme {
            Box(Modifier.fillMaxSize().background(Lc.canvas)) {
                val bmp = photo()
                Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                Loupe(bmp, androidx.compose.ui.geometry.Offset(700f, 900f), androidx.compose.ui.geometry.Offset(0.62f, 0.33f))
            }
        }
    }
}
