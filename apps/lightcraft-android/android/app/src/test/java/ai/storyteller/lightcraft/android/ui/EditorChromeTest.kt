package ai.storyteller.lightcraft.android.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.storyteller.lightcraft.android.GalleryImage
import ai.storyteller.lightcraft.android.Histogram
import ai.storyteller.lightcraft.android.MainTab
import ai.storyteller.lightcraft.android.Track
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/**
 * Renders the phone UI's pieces on the JVM (Paparazzi) with procedural stand-in photos, to compare
 * with the desktop app without a phone: the library, the Edit panel, Masking and Color Grading.
 * The snapshots are not committed.
 */
class EditorChromeTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, maxPercentDifference = 100.0)

    init {
        // layoutlib here can't load font files (no native font builder): the snapshots use the
        // system sans-serif in place of Inter, which the app bundles
        interOverride = androidx.compose.ui.text.font.FontFamily.SansSerif
    }

    /** A procedural "photo": sky to ground with a sun disc, in a few palettes (nothing bundled). */
    private fun photo(w: Int, h: Int, palette: Int = 0): Bitmap {
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val t = y / h.toFloat()
            val sun = (x - w * 0.62f) * (x - w * 0.62f) + (y - h * 0.32f) * (y - h * 0.32f) < (w * 0.07f) * (w * 0.07f)
            val (r, g, b) = when {
                sun -> Triple(255, 226, 170)
                t < 0.55f -> when (palette % 3) {
                    0 -> Triple((150 + 90 * t).toInt(), (110 + 80 * t).toInt(), (190 - 60 * t).toInt())
                    1 -> Triple((40 + 60 * t).toInt(), (70 + 120 * t).toInt(), (120 + 60 * t).toInt())
                    else -> Triple((230 - 40 * t).toInt(), (150 - 30 * t).toInt(), (90 - 20 * t).toInt())
                }
                else -> Triple((60 - 30 * t).toInt(), (70 - 30 * t).toInt(), (60 - 20 * t).toInt())
            }
            (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private val histogram: Histogram = run {
        val luma = IntArray(256) { i -> (2200 * kotlin.math.exp(-((i - 110) * (i - 110)) / 2400.0)).toInt() + (900 * kotlin.math.exp(-((i - 200) * (i - 200)) / 300.0)).toInt() + 30 }
        val r = IntArray(256) { luma[(it + 12).coerceAtMost(255)] }
        val b = IntArray(256) { luma[(it - 14).coerceAtLeast(0)] }
        Histogram(r, luma, b, luma, 0.001f, 0.012f)
    }

    private val none = SliderActions({ _, _ -> }, {}, {})

    @Composable
    private fun TopBar(title: String) {
        Row(Modifier.fillMaxWidth().background(Lc.chrome).height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            LcIconButton(DeskIcon.Back, "Back") {}
            Text(title, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Lc.text, modifier = Modifier.weight(1f))
            LcIconButton(DeskIcon.Undo, "Undo", size = 38.dp) {}
            LcIconButton(DeskIcon.Redo, "Redo", size = 38.dp) {}
            LcIconButton(DeskIcon.BeforeAfter, "Before / After", size = 38.dp) {}
            LcIconButton(DeskIcon.Share, "Export", size = 38.dp) {}
            LcIconButton(DeskIcon.More, "More", size = 38.dp) {}
        }
    }

    @Composable
    private fun Editor(tab: MainTab, panel: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize().background(Lc.canvas)) {
            TopBar("LC01207.JPG")
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Image(photo(900, 600).asImageBitmap(), "Photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            Row(Modifier.fillMaxWidth().height(64.dp).background(Lc.inset).padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(6) { i ->
                    Box(Modifier.width(78.dp).fillMaxHeight().then(if (i == 2) Modifier.border(2.dp, Lc.pick) else Modifier)) {
                        Image(photo(240, 160, i).asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().padding(if (i == 2) 2.dp else 0.dp))
                    }
                }
            }
            Column(Modifier.fillMaxWidth().height(380.dp).background(Lc.chrome).verticalScroll(rememberScrollState())) { panel() }
            Rail(tab) {}
        }
    }

    @Test
    fun edit() = paparazzi.snapshot {
        LightCraftTheme {
            Editor(MainTab.Edit) {
                Column(Modifier.fillMaxWidth().background(Lc.inset).padding(horizontal = 14.dp, vertical = 8.dp)) {
                    HistogramView(histogram, { _, _ -> }, {}, Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf("ISO 100", "18mm", "f/2.8", "1/250").forEach { Text(it, style = Numbers, fontSize = 12.sp, color = Lc.label) }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Edit", fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Lc.text)
                    Spacer(Modifier.width(14.dp))
                    LcButton("Auto") {}
                    Spacer(Modifier.width(6.dp))
                    LcButton("B&W") {}
                    Spacer(Modifier.weight(1f))
                    LcButton("Reset") {}
                }
                ValueRow("Profile", "Color", {})
                SectionDivider()
                SectionHeader("Light", true, {})
                SliderList(
                    listOf(
                        SliderSpec("e", "Exposure", 0.15f, -5f, 5f, 0f, 2, "EV", Track("centered")),
                        SliderSpec("c", "Contrast", 28f, -100f, 100f, 0f),
                        SliderSpec("h", "Highlights", -45f, -100f, 100f, 0f),
                        SliderSpec("s", "Shadows", 38f, -100f, 100f, 0f),
                    ),
                    none,
                )
                FlyoutRow(DeskIcon.Curve, "Curve", false) {}
                SectionDivider()
                SectionHeader("Color", true, {})
                ValueRow("White Balance", "As Shot", {}) { LcIconButton(DeskIcon.Picker, "Picker", size = 34.dp, iconSize = 18.dp) {} }
                SliderList(
                    listOf(
                        SliderSpec("t", "Temp", 5600f, 2000f, 50000f, 6500f, 0, "K", Track("temp")),
                        SliderSpec("n", "Tint", 0f, -150f, 150f, 0f, track = Track("tint")),
                        SliderSpec("v", "Vibrance", 12f, -100f, 100f, 0f, track = Track("gradient", "#7a7a7a", "#d8406a")),
                    ),
                    none,
                )
            }
        }
    }

    @Test
    fun masking() = paparazzi.snapshot {
        LightCraftTheme {
            Editor(MainTab.Masking) {
                PanelTitle("Masking")
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    listOf(
                        listOf(DeskIcon.Subject to "Subject", DeskIcon.Sky to "Sky", DeskIcon.Subject to "Background", DeskIcon.Brush to "Brush"),
                        listOf(DeskIcon.Linear to "Linear", DeskIcon.Radial to "Radial", DeskIcon.Sliders to "Luminance", DeskIcon.Target to "Object"),
                    ).forEach { row -> Row(Modifier.fillMaxWidth()) { row.forEachIndexed { i, (icon, l) -> Tile(icon, l, i == 1 && l == "Radial", Modifier.weight(1f)) {} } } }
                }
                SectionDivider()
                SubLabel("Masks")
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(40.dp).clip(RoundedCornerShape(4.dp)).background(Lc.toolActive).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    LcIcon(DeskIcon.Radial, Lc.text, 16.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Sun glow", fontFamily = Inter, fontSize = 14.sp, color = Lc.text)
                }
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    LcButton("Add") {}
                    Spacer(Modifier.width(6.dp))
                    LcButton("Subtract") {}
                    Spacer(Modifier.width(6.dp))
                    LcButton("Invert") {}
                    LcIconButton(DeskIcon.Trash, "Delete", size = 32.dp, iconSize = 16.dp) {}
                }
                SliderList(
                    listOf(
                        SliderSpec("temp", "Temp", 40f, -100f, 100f, 0f, track = Track("temp")),
                        SliderSpec("tint", "Tint", 0f, -100f, 100f, 0f, track = Track("tint")),
                        SliderSpec("hue", "Hue", 0f, -100f, 100f, 0f, track = Track("rainbow")),
                        SliderSpec("saturation", "Saturation", 15f, -100f, 100f, 0f, track = Track("gradient", "#7a7a7a", "#e04a3a")),
                    ),
                    none,
                )
            }
        }
    }

    @Test
    fun grading() = paparazzi.snapshot {
        LightCraftTheme {
            Editor(MainTab.Edit) {
                FlyoutRow(DeskIcon.GradeAll, "Color Grading", true) {}
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(DeskIcon.GradeAll, DeskIcon.GradeShadows, DeskIcon.GradeMidtones, DeskIcon.GradeHighlights, DeskIcon.Circle).forEachIndexed { i, ic -> LcIconButton(ic, "r$i", active = i == 0, size = 38.dp) {} }
                }
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    ColorWheel("Midtones", 30f, 20f, 100f, 132.dp, { _, _ -> }, {}, {})
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        ColorWheel("Shadows", 210f, 35f, 100f, 116.dp, { _, _ -> }, {}, {})
                        ColorWheel("Highlights", 50f, 25f, 100f, 116.dp, { _, _ -> }, {}, {})
                    }
                }
            }
        }
    }

    @Test
    fun library() = paparazzi.snapshot {
        LightCraftTheme {
            val images = List(14) { i ->
                val (w, h) = listOf(3 to 2, 2 to 3, 4 to 3, 16 to 9, 1 to 1, 3 to 4)[i % 6]
                GalleryImage(i.toLong(), Uri.EMPTY, null, "LC0$i.JPG", "image/jpeg", width = w * 100, height = h * 100)
            }
            val rows = justify(images, 403f, 130f, 4f)
            Column(Modifier.fillMaxSize().background(Lc.gridBg)) {
                Row(Modifier.fillMaxWidth().background(Lc.chrome).height(52.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    LcIconButton(DeskIcon.Sidebar, "Sidebar") {}
                    Row(Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(6.dp)).background(Lc.field).border(1.dp, Lc.fieldBorder, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        LcIcon(DeskIcon.Search, Lc.dim, 16.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Search Photos", fontFamily = Inter, fontSize = 14.sp, color = Lc.dim)
                    }
                    LcIconButton(DeskIcon.Filter, "Filter") {}
                }
                Row(Modifier.fillMaxWidth().background(Lc.canvas).padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("All Photos", fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Lc.text, modifier = Modifier.weight(1f))
                    Text("24 photos", fontFamily = Inter, fontSize = 13.sp, color = Lc.dim)
                }
                Column(Modifier.weight(1f).padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    var n = 0
                    rows.forEach { r ->
                        Row(Modifier.fillMaxWidth().height(r.height.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            r.images.forEach { g ->
                                val k = n++
                                Box(Modifier.width((r.height * g.aspect).dp).fillMaxHeight().then(if (k == 0) Modifier.border(2.dp, Lc.pick) else Modifier)) {
                                    Image(photo(300, 200, k).asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                    Row(Modifier.align(Alignment.BottomStart).padding(6.dp)) {
                                        if (k % 3 == 1) Stars(k % 5 + 1, 10.dp, Lc.star, androidx.compose.ui.graphics.Color.Transparent)
                                        if (k % 4 == 2) LcIcon(DeskIcon.FlagPickFilled, Lc.pick, 12.dp)
                                    }
                                    if (k % 5 == 3) LcIcon(DeskIcon.Sliders, Lc.pick, 13.dp, Modifier.align(Alignment.BottomEnd).padding(6.dp))
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().background(Lc.chrome).height(48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    LcIconButton(DeskIcon.GridPhoto, "Justified", active = true, size = 36.dp) {}
                    LcIconButton(DeskIcon.GridSquare, "Square", size = 36.dp) {}
                    LcIconButton(DeskIcon.Single, "Single", size = 36.dp) {}
                    Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(22.dp).background(Lc.buttonBorder))
                    LcIconButton(DeskIcon.Sort, "Sort", size = 36.dp) {}
                }
            }
        }
    }

    @Test
    fun sidebar() = paparazzi.snapshot {
        LightCraftTheme {
            Column(Modifier.fillMaxSize().background(Lc.chrome).padding(vertical = 8.dp)) {
                SideHeader("My Photos")
                SideRow(DeskIcon.Photos, "All Photos", 24, true) {}
                SideRow(DeskIcon.Clock, "Recently Added", 7, false) {}
                SideRow(DeskIcon.FlagPick, "Picks", 4, false) {}
                SideRow(DeskIcon.Sliders, "Edited", 3, false) {}
                SideHeader("Albums")
                SideRow(DeskIcon.Album, "Camera", 18, false) {}
                SideRow(DeskIcon.Album, "Screenshots", 6, false) {}
                SideHeader("By Date")
                SideRow(DeskIcon.Clock, "2026", 24, false) {}
                Spacer(Modifier.height(10.dp))
                SideRow(DeskIcon.Trash, "Recently Deleted", 0, false) {}
            }
        }
    }

    @Test
    fun downloads() = paparazzi.snapshot {
        LightCraftTheme {
            Column(Modifier.fillMaxSize().background(Lc.canvas)) {
                Row(Modifier.fillMaxWidth().background(Lc.chrome).height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    LcIconButton(DeskIcon.Back, "Back") {}
                    Text("Downloads & Storage", fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Lc.text)
                }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Heading("Models", "Downloaded on demand, smallest first. Photos never leave the phone.")
                    ItemCard(DeskIcon.Subject, "Subject Segmentation", "Subject, Background, Pop Subject and Remove BG. Google ML Kit through Google Play services.", "Small", "Installed", Lc.text) { LcButton("Release") {} }
                    ItemCard(DeskIcon.Target, "SAM 3", "Object and Describe masks. Meta's Segment Anything 3, run on the phone; SAM License (Meta).", "3.4 GB", "Downloading… 1.2 GB of 3.4 GB", progress = 0.35f) { LcButton("Cancel") {} }
                    Heading("Storage", "What LightCraft keeps on this phone.")
                    ItemCard(DeskIcon.Sliders, "Library and Edits", "Your edits, ratings, flags, masks and history.", "2 MB", "In use", Lc.text)
                    ItemCard(DeskIcon.Share, "Export Cache", "Files made for the share sheet.", "14 MB", "Can be cleared") { LcButton("Clear") {} }
                }
            }
        }
    }
}
