package ai.storyteller.lightcraft.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.storyteller.lightcraft.android.AiModelStatus
import ai.storyteller.lightcraft.android.Control
import ai.storyteller.lightcraft.android.Engine
import ai.storyteller.lightcraft.android.LightCraftViewModel
import ai.storyteller.lightcraft.android.MainTab
import ai.storyteller.lightcraft.android.Tool
import ai.storyteller.lightcraft.android.ToolKind
import ai.storyteller.lightcraft.android.Tools
import org.json.JSONObject

internal fun Control.slider(label: String = this.label, unit: String = this.unit) = SliderSpec(id, label, value, min, max, default, decimals, unit, track)

private fun controlActions(vm: LightCraftViewModel) = SliderActions(
    change = { id, v -> vm.setControl(id, v) },
    done = vm::finishInteraction,
    reset = { id -> vm.resetControl(id) },
)

/** The panel of the open tool, as the desktop's right panel shows it. */
@Composable
internal fun Panel(vm: LightCraftViewModel, tab: MainTab, tool: Tool?) {
    val controls by vm.controls.collectAsStateWithLifecycle()
    when (tab) {
        MainTab.Edit -> EditPanel(vm, controls)
        MainTab.Crop -> CropPanel(vm, controls)
        MainTab.Masking -> MaskPanel(vm, tool)
        MainTab.Remove -> RemovePanel(vm, tool)
        MainTab.Presets -> PresetPanel(vm)
    }
}

// ---- Edit ---------------------------------------------------------------------------------------

/** The histogram with the camera's settings under it (ISO, focal length, aperture, shutter). */
@Composable
internal fun HistogramBlock(vm: LightCraftViewModel) {
    val h by vm.histogram.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (!vm.showHistogram.value) vm.toggleHistogram() }
    val meta = info?.optJSONObject("meta") ?: JSONObject()
    var shown by rememberSaveable { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth().background(Lc.inset).padding(horizontal = 14.dp, vertical = 8.dp)) {
        if (shown) HistogramView(h, vm::dragZone, vm::finishInteraction, Modifier.fillMaxWidth())
        // the camera line doubles as the histogram's collapse toggle
        Row(Modifier.fillMaxWidth().clickable { shown = !shown }.padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            LcIcon(if (shown) DeskIcon.ChevronUp else DeskIcon.ChevronDown, Lc.dim, 12.dp, contentDescription = if (shown) "Hide histogram" else "Show histogram")
            val iso = meta.optInt("iso").takeIf { it > 0 }?.let { "ISO $it" } ?: "ISO –"
            val focal = meta.optDouble("focal_mm").takeIf { !it.isNaN() && it > 0 }?.let { "%.0fmm".format(java.util.Locale.ROOT, it) } ?: "–"
            val ap = meta.optDouble("aperture").takeIf { !it.isNaN() && it > 0 }?.let { "f/" + "%.1f".format(java.util.Locale.ROOT, it).removeSuffix(".0") } ?: "f/–"
            val sh = meta.optString("shutter").takeIf { it.isNotEmpty() } ?: "–"
            listOf(iso, focal, ap, sh).forEach { Text(it, style = Numbers, fontSize = 12.sp, color = Lc.label) }
        }
    }
}

@Composable
private fun EditPanel(vm: LightCraftViewModel, all: List<Control>) {
    val look by vm.look.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val open = rememberSaveable(saver = openSaver) { mutableStateMapOf("light" to true, "color" to true) }
    val by = { prefix: String -> all.filter { it.id.startsWith(prefix) } }
    val actions = controlActions(vm)
    Column(Modifier.fillMaxWidth()) {
        HistogramBlock(vm)
        // Edit: Auto, B&W, Reset
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Edit", fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Lc.text)
            Spacer(Modifier.width(14.dp))
            LcButton("Auto") { vm.quickAction("auto") }
            Spacer(Modifier.width(6.dp))
            LcButton("B&W", on = look.treatment == "bw") { vm.setTreatment(look.treatment != "bw") }
            Spacer(Modifier.weight(1f))
            LcButton("Reset") { vm.quickAction("reset") }
        }
        // Profile ⌄
        var profileMenu by remember { mutableStateOf(false) }
        Box {
            ValueRow("Profile", profiles.firstOrNull { it.id == look.profile }?.name ?: if (look.treatment == "bw") "Monochrome" else "Color", { profileMenu = true })
            DropdownMenu(expanded = profileMenu, onDismissRequest = { profileMenu = false }, containerColor = Lc.chrome) {
                profiles.groupBy { it.group.ifEmpty { "Profiles" } }.forEach { (group, list) ->
                    MenuLabel(group)
                    list.forEach { p -> MenuCheck(p.name, p.id == look.profile) { profileMenu = false; vm.setProfile(p.id) } }
                }
            }
        }
        by("profile.").forEach { LcSlider(it.slider("Profile Amount"), actions) }
        SectionDivider()

        Section("light", "Light", open) {
            SliderList(by("light.").map { it.slider() }, actions)
            Flyout(DeskIcon.Curve, "Curve", "curve", open) { CurveFlyout(vm, by("curve.")) }
            if (by("hdr.").isNotEmpty()) Flyout(DeskIcon.Sun, "HDR", "hdr", open) { SliderList(by("hdr.").map { it.slider() }, actions) }
        }
        Section("color", "Color", open) {
            WhiteBalanceRow(vm, look.wb)
            SliderList((by("wb.") + by("color.")).map { it.slider() }, actions)
            Flyout(DeskIcon.Presets, if (look.treatment == "bw") "B&W Mix" else "Color Mixer", "mixer", open) {
                if (look.treatment == "bw") SliderList(by("bw.").map { it.slider() }, actions) else ColorMixer(vm, by("mixer."))
            }
            Flyout(DeskIcon.GradeAll, "Color Grading", "grading", open) { ColorGrading(vm, by("grading.")) }
        }
        Section("effects", "Effects", open) {
            SliderList(by("effects.").map { it.slider() }, actions)
            SubLabel("Vignette")
            SliderList(by("vignette.").map { it.slider() }, actions)
            SubLabel("Grain")
            SliderList(by("grain.").map { it.slider() }, actions)
        }
        Section("detail", "Detail", open) {
            SubLabel("Sharpening")
            SliderList(by("detail.sharpen").map { it.slider() }, actions)
            SubLabel("Noise Reduction")
            SliderList((by("enhance.denoise") + by("detail.nr")).map { it.slider() }, actions)
        }
        Section("optics", "Optics", open) {
            SliderList(by("optics.").map { it.slider() }, actions)
        }
        Section("geometry", "Geometry", open) {
            SubLabel("Upright")
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("off" to "Off", "auto" to "Auto", "level" to "Level", "vertical" to "Vertical", "full" to "Full").forEach { (m, l) -> LcButton(l) { vm.upright(m) } }
            }
            SliderList(by("geometry.").map { it.slider() }, actions)
        }
        Section("calibration", "Calibration", open) {
            SliderList(by("calibration.").map { it.slider() }, actions)
        }
        Spacer(Modifier.height(24.dp))
    }
}

private val openSaver = androidx.compose.runtime.saveable.Saver<androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>, List<String>>(
    save = { m -> m.filterValues { it }.keys.toList() },
    restore = { keys -> mutableStateMapOf<String, Boolean>().apply { keys.forEach { put(it, true) } } },
)

@Composable
private fun Section(id: String, title: String, open: MutableMap<String, Boolean>, content: @Composable () -> Unit) {
    val on = open[id] == true
    SectionHeader(title, on, { open[id] = !on })
    if (on) {
        content()
        Spacer(Modifier.height(6.dp))
    }
    SectionDivider()
}

@Composable
private fun Flyout(icon: DeskIcon, title: String, id: String, open: MutableMap<String, Boolean>, content: @Composable () -> Unit) {
    val on = open[id] == true
    FlyoutRow(icon, title, on) { open[id] = !on }
    if (on) content()
}

@Composable
internal fun SubLabel(text: String) {
    Text(text, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Lc.text, modifier = Modifier.padding(start = 18.dp, top = 10.dp, bottom = 2.dp))
}

private val WB_MODES = listOf(
    "asShot" to "As Shot", "auto" to "Auto", "daylight" to "Daylight", "cloudy" to "Cloudy", "shade" to "Shade",
    "tungsten" to "Tungsten", "fluorescent" to "Fluorescent", "flash" to "Flash", "custom" to "Custom",
)

@Composable
private fun WhiteBalanceRow(vm: LightCraftViewModel, mode: String) {
    val picking by vm.wbPicking.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    Box {
        ValueRow("White Balance", WB_MODES.firstOrNull { it.first == mode }?.second ?: "Custom", { menu = true }) {
            LcIconButton(DeskIcon.Picker, "White balance selector: tap a neutral point", active = picking, size = 34.dp, iconSize = 18.dp) { vm.armWbPicker(!picking) }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Lc.chrome) {
            WB_MODES.filter { it.first != "custom" }.forEach { (m, l) -> MenuCheck(l, m == mode) { menu = false; vm.setWb(m) } }
        }
    }
}

@Composable
private fun CurveFlyout(vm: LightCraftViewModel, parametric: List<Control>) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val curves by vm.curves.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LcButton("Point", on = page == 0) { page = 0 }
            if (parametric.isNotEmpty()) LcButton("Parametric", on = page == 1) { page = 1 }
        }
        if (page == 0 || parametric.isEmpty()) {
            CurveEditor(curves, vm::setCurve, vm::finishInteraction, vm::resetCurve)
        } else {
            SliderList(parametric.map { it.slider() }, controlActions(vm))
        }
    }
}

private val BANDS = listOf("red", "orange", "yellow", "green", "aqua", "blue", "purple", "magenta")

/**
 * Color Mixer: the eight bands as colour dots (the desktop's band colours) and the chosen band's
 * Hue, Saturation and Luminance as dials, where three values for one colour read best.
 */
@Composable
private fun ColorMixer(vm: LightCraftViewModel, mixer: List<Control>) {
    var band by rememberSaveable { mutableIntStateOf(0) }
    var focus by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            BandColors.forEachIndexed { i, c ->
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(c)
                        .border(2.dp, if (i == band) Lc.pick else Color.Transparent, CircleShape)
                        .clickable { band = i; focus = null },
                )
            }
        }
        val name = BANDS[band]
        val specs = listOf("hue" to "Hue", "sat" to "Saturation", "lum" to "Luminance").mapNotNull { (k, l) ->
            mixer.firstOrNull { it.id == "mixer.$name.$k" }?.let { DialSpec(it.id, l, it.value, it.min, it.max, it.default, it.decimals) }
        }
        DialGrid(specs, focus, { focus = if (focus == it) null else it }, dialActions(vm), columns = 3, maxHeight = 220.dp)
    }
}

private val RANGES = listOf(
    Triple("all", "All", DeskIcon.GradeAll),
    Triple("shadows", "Shadows", DeskIcon.GradeShadows),
    Triple("midtones", "Midtones", DeskIcon.GradeMidtones),
    Triple("highlights", "Highlights", DeskIcon.GradeHighlights),
    Triple("global", "Global", DeskIcon.Circle),
)

/**
 * Color Grading, as the desktop's: the range buttons, then wheels. All shows Midtones over
 * Shadows and Highlights; one range shows its wheel large with its Hue, Saturation and
 * Luminance sliders. Blending and Balance close the flyout.
 */
@Composable
private fun ColorGrading(vm: LightCraftViewModel, grading: List<Control>) {
    var range by rememberSaveable { mutableIntStateOf(0) }
    val get = { id: String -> grading.firstOrNull { it.id == id } }
    @Composable
    fun wheel(key: String, label: String, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
        val hue = get("grading.$key.hue") ?: return
        val sat = get("grading.$key.sat") ?: return
        ColorWheel(
            label, hue.value, sat.value, sat.max, size,
            onChange = { h, s -> vm.setControl(hue.id, h); vm.setControl(sat.id, s) },
            onDone = vm::finishInteraction,
            onReset = { vm.resetControl(hue.id); vm.resetControl(sat.id) },
            modifier = modifier,
        )
    }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RANGES.forEachIndexed { i, (_, label, icon) -> LcIconButton(icon, label, active = i == range, size = 38.dp) { range = i } }
        }
        val key = RANGES[range].first
        if (key == "all") {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                wheel("midtones", "Midtones", 132.dp)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    wheel("shadows", "Shadows", 116.dp)
                    wheel("highlights", "Highlights", 116.dp)
                }
            }
        } else {
            Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                wheel(key, RANGES[range].second, 200.dp)
            }
            SliderList(
                listOf("hue" to "Hue", "sat" to "Saturation", "lum" to "Luminance").mapNotNull { (k, l) -> get("grading.$key.$k")?.slider(l) },
                controlActions(vm),
            )
        }
        SliderList(grading.filter { it.id == "grading.blending" || it.id == "grading.balance" }.map { it.slider() }, controlActions(vm))
    }
}

private fun dialActions(vm: LightCraftViewModel) = DialActions(change = { id, v -> vm.setControl(id, v) }, done = vm::finishInteraction, reset = { id -> vm.resetControl(id) })

// ---- Crop ---------------------------------------------------------------------------------------

@Composable
private fun CropPanel(vm: LightCraftViewModel, all: List<Control>) {
    Column(Modifier.fillMaxWidth()) {
        PanelTitle("Crop") { LcButton("Reset") { vm.cropCommand("crop.reset") } }
        SubLabel("Aspect")
        LazyRow(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(Tools.aspects) { a -> LcButton(a.label) { vm.cropAspect(a.ratio) } }
        }
        SubLabel("Rotate & Flip")
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            LcIconButton(DeskIcon.Rotate, "Rotate left", modifier = Modifier.graphicsLayer { scaleX = -1f }) { vm.cropCommand("photo.rotateLeft") }
            LcIconButton(DeskIcon.Rotate, "Rotate right") { vm.cropCommand("photo.rotateRight") }
            LcIconButton(DeskIcon.Flip, "Flip horizontal") { vm.cropCommand("photo.flipHorizontal") }
            LcIconButton(DeskIcon.Flip, "Flip vertical", modifier = Modifier.graphicsLayer { rotationZ = 90f }) { vm.cropCommand("photo.flipVertical") }
        }
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Straighten", fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Lc.text, modifier = Modifier.weight(1f))
            LcButton("Auto", onClick = vm::autoStraighten)
        }
        SliderList(all.filter { it.id == "crop.angle" }.map { it.slider("Angle", "°") }, controlActions(vm))
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
internal fun PanelTitle(title: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Lc.text, modifier = Modifier.weight(1f))
        trailing()
    }
}

// ---- Masking ------------------------------------------------------------------------------------

private fun maskIcon(id: String): DeskIcon = when (id) {
    "subject", "seg" -> DeskIcon.Subject
    "background" -> DeskIcon.Subject
    "sky" -> DeskIcon.Sky
    "object" -> DeskIcon.Target
    "describe", "prompt" -> DeskIcon.Search
    "brush" -> DeskIcon.Brush
    "dodge" -> DeskIcon.Sun
    "burn" -> DeskIcon.Moon
    "linear" -> DeskIcon.Linear
    "luminanceRange" -> DeskIcon.Sliders
    "colorRange" -> DeskIcon.Picker
    else -> DeskIcon.Radial
}

/** A labelled tool tile (Create New Mask, Remove): icon over name, the open one on the tool-active fill. */
@Composable
internal fun Tile(icon: DeskIcon, label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(6.dp)).background(if (on) Lc.toolActive else Color.Transparent).clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LcIcon(icon, if (on) Lc.text else Lc.label, 22.dp)
        Spacer(Modifier.height(5.dp))
        Text(label, fontFamily = Inter, fontSize = 11.sp, color = if (on) Lc.text else Lc.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val ADDABLE = listOf("brush" to "Brush", "linear" to "Linear Gradient", "radial" to "Radial Gradient", "sky" to "Sky", "luminanceRange" to "Luminance Range")

@Composable
private fun MaskPanel(vm: LightCraftViewModel, tool: Tool?) {
    val masks by vm.masks.collectAsStateWithLifecycle()
    val active by vm.activeMask.collectAsStateWithLifecycle()
    val ai by vm.ai.collectAsStateWithLifecycle()
    val mask = masks.firstOrNull { it.id == active }
    Column(Modifier.fillMaxWidth()) {
        PanelTitle("Masking")
        Text("Create New Mask", fontFamily = Inter, fontSize = 13.sp, color = Lc.dim, modifier = Modifier.padding(start = 18.dp, bottom = 4.dp))
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Tools.masking(Engine.aiMasksAvailable).chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { t -> Tile(maskIcon(t.id), t.label, tool?.id == t.id, Modifier.weight(1f)) { vm.selectTool(t) } }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        when (tool?.kind) {
            ToolKind.Object -> ObjectHint(vm, ai)
            ToolKind.Describe -> DescribeBox(vm, ai)
            else -> {}
        }
        Spacer(Modifier.height(6.dp))
        SectionDivider()
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Masks", fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Lc.text, modifier = Modifier.weight(1f))
            LcButton("Pop Subject") { vm.quickAction("pop") }
            Spacer(Modifier.width(6.dp))
            LcButton("Blue Sky") { vm.quickAction("sky") }
        }
        if (masks.isEmpty()) {
            Text(
                "No masks yet. Subject, Sky and Background are found on the phone; paint with Brush, Dodge or Burn; or add a gradient.",
                fontFamily = Inter, fontSize = 13.sp, color = Lc.dim, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            )
            return@Column
        }
        masks.forEach { m ->
            val on = m.id == active
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(40.dp).clip(RoundedCornerShape(4.dp)).background(if (on) Lc.toolActive else Color.Transparent).clickable { vm.selectMask(m.id) }.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LcIcon(maskIcon(m.components.firstOrNull()?.kind ?: ""), if (on) Lc.text else Lc.icon, 16.dp)
                Spacer(Modifier.width(10.dp))
                Text(m.name, fontFamily = Inter, fontSize = 14.sp, color = Lc.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (mask == null) return@Column
        Spacer(Modifier.height(6.dp))
        SectionDivider()
        // the selected mask's selections, each with Add / Subtract / Invert / delete
        mask.components.forEachIndexed { i, c ->
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                LcIcon(maskIcon(c.kind), Lc.icon, 16.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    (if (c.op == "subtract") "− " else "") + c.label + (if (c.invert) " (inverted)" else ""),
                    fontFamily = Inter, fontSize = 14.sp, color = Lc.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (i == mask.components.lastIndex) {
                    AddMenu("Add") { kind -> vm.addComponent("add", kind) }
                    Spacer(Modifier.width(6.dp))
                    AddMenu("Subtract") { kind -> vm.addComponent("subtract", kind) }
                    Spacer(Modifier.width(6.dp))
                }
                LcButton("Invert") { vm.componentAction(i, "invert") }
                Spacer(Modifier.width(2.dp))
                LcIconButton(DeskIcon.Trash, "Delete this selection", size = 32.dp, iconSize = 16.dp) { vm.componentAction(i, "delete") }
            }
        }
        if (tool?.kind == ToolKind.Brush) BrushSettings(vm)
        Spacer(Modifier.height(4.dp))
        val specs = Tools.local.map { s ->
            SliderSpec(s.key, s.label, mask.adjust[s.key] ?: 0f, s.min, s.max, 0f, s.decimals, "", s.track)
        }
        SliderList(specs, SliderActions(change = { k, v -> vm.setMaskValue(k, v) }, done = vm::finishInteraction, reset = { k -> vm.setMaskValue(k, 0f); vm.finishInteraction() }))
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
            Spacer(Modifier.weight(1f))
            LcButton("Delete Mask") { vm.maskCommand("mask.delete") }
        }
    }
}

/** Add / Subtract: a button opening the kinds of selection to combine with the mask. */
@Composable
private fun AddMenu(label: String, onKind: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        LcButton(label) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Lc.chrome) {
            ADDABLE.forEach { (k, l) ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(l, fontFamily = Inter, fontSize = 14.sp, color = Lc.text) },
                    leadingIcon = { LcIcon(maskIcon(k), Lc.icon, 18.dp) },
                    onClick = { open = false; onKind(k) },
                )
            }
        }
    }
}

@Composable
private fun BrushSettings(vm: LightCraftViewModel) {
    val b by vm.brush.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth()) {
        SectionDivider()
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Brush", fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Lc.text, modifier = Modifier.weight(1f))
            LcButton("Add", on = !b.erase) { vm.setBrush(b.copy(erase = false)) }
            Spacer(Modifier.width(6.dp))
            LcButton("Erase", on = b.erase) { vm.setBrush(b.copy(erase = true)) }
        }
        val specs = listOf(
            SliderSpec("size", "Size", b.size * 100f, 0.5f, 25f, 4f, 1),
            SliderSpec("feather", "Feather", b.feather, 0f, 100f, 50f),
            SliderSpec("flow", "Flow", b.flow, 1f, 100f, 60f),
        )
        SliderList(
            specs,
            SliderActions(
                change = { k, v ->
                    vm.setBrush(when (k) { "size" -> b.copy(size = v / 100f); "feather" -> b.copy(feather = v); else -> b.copy(flow = v) })
                },
                done = {},
                reset = { k -> vm.setBrush(when (k) { "size" -> b.copy(size = 0.04f); "feather" -> b.copy(feather = 50f); else -> b.copy(flow = 60f) }) },
            ),
        )
    }
}

/** Object selection: tap the thing to select; Subtract taps parts away. Gated on the model. */
@Composable
private fun ObjectHint(vm: LightCraftViewModel, ai: AiModelStatus) {
    if (!ai.installed) {
        AiModelCard(vm, ai)
        return
    }
    val exclude by vm.objectExclude.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        LcButton("Add", on = !exclude) { vm.setObjectExclude(false) }
        Spacer(Modifier.width(6.dp))
        LcButton("Subtract", on = exclude) { vm.setObjectExclude(true) }
        Spacer(Modifier.width(10.dp))
        Text("Tap the photo to select an object.", fontFamily = Inter, fontSize = 12.sp, color = Lc.dim, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun DescribeBox(vm: LightCraftViewModel, ai: AiModelStatus) {
    if (!ai.installed) {
        AiModelCard(vm, ai)
        return
    }
    var text by rememberSaveable { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(4.dp)).background(Lc.field).border(1.dp, Lc.fieldBorder, RoundedCornerShape(4.dp)).padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) Text("sky, trees, the red car…", fontFamily = Inter, fontSize = 14.sp, color = Lc.dim)
            BasicTextField(
                value = text,
                onValueChange = { text = it.take(120) },
                singleLine = true,
                textStyle = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = Lc.text),
                cursorBrush = SolidColor(Lc.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.describe(text) }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(8.dp))
        LcButton("Select", on = text.isNotBlank()) { vm.describe(text) }
    }
}

/** What Object and Describe need: the optional SAM 3 model, its size, licence and memory. */
@Composable
private fun AiModelCard(vm: LightCraftViewModel, ai: AiModelStatus) {
    val context = LocalContext.current
    val ramGb = remember { deviceMemoryGb(context) }
    val enough = ramGb >= MIN_AI_RAM_GB
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
        Text("AI object selection", fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Lc.text)
        Spacer(Modifier.height(4.dp))
        Text(
            if (enough) {
                "Uses Meta's SAM 3 model on this phone. It is an optional ${gb(ai.sizeBytes)} download (${ai.license}, not LightCraft's) and needs several GB of free memory; the first selection on a photo can take a minute."
            } else {
                "SAM 3 needs about $MIN_AI_RAM_GB GB of memory; this phone has ${"%.1f".format(ramGb)} GB. Subject, Background and Sky still work on any phone."
            },
            fontFamily = Inter, fontSize = 12.sp, color = Lc.label,
        )
        if (ai.downloading) {
            Spacer(Modifier.height(8.dp))
            val f = if (ai.total > 0) ai.done.toFloat() / ai.total else 0f
            LinearProgressIndicator(progress = { f }, Modifier.fillMaxWidth().height(2.dp), color = Lc.accent, trackColor = Lc.track)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${gb(ai.done)} of ${gb(ai.total.takeIf { it > 0 } ?: ai.sizeBytes)}", style = Numbers, fontSize = 12.sp, color = Lc.dim, modifier = Modifier.weight(1f))
                LcButton("Cancel", onClick = vm::cancelAiDownload)
            }
        } else if (enough) {
            Spacer(Modifier.height(8.dp))
            Row { LcButton("Download ${gb(ai.sizeBytes)}", on = true) { confirm = true } }
            ai.error?.let { Text(it, fontFamily = Inter, fontSize = 12.sp, color = Lc.reject, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = Lc.chrome,
            title = { Text("Download SAM 3?", fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Lc.text) },
            text = {
                Text(
                    "About ${gb(ai.sizeBytes)} from the model's public mirrors, under Meta's ${ai.license}. Use Wi-Fi. It stays in LightCraft's private storage; clearing the app's data removes it.",
                    fontFamily = Inter, fontSize = 14.sp, color = Lc.label,
                )
            },
            confirmButton = { TextButton(onClick = { confirm = false; vm.downloadAiModel() }) { Text("Download", color = Lc.accent, fontFamily = Inter) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Not Now", color = Lc.label, fontFamily = Inter) } },
        )
    }
}

internal const val MIN_AI_RAM_GB = 6

internal fun deviceMemoryGb(context: android.content.Context): Double {
    val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return 0.0
    val info = android.app.ActivityManager.MemoryInfo()
    am.getMemoryInfo(info)
    return info.totalMem / 1e9
}

private fun gb(bytes: Long) = if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1e9) else "%.0f MB".format(bytes / 1e6)

// ---- Remove -------------------------------------------------------------------------------------

private fun removeIcon(id: String): DeskIcon = when (id) {
    "eraser" -> DeskIcon.Eraser
    "ai" -> DeskIcon.Subject
    "keep" -> DeskIcon.Invert
    "undo" -> DeskIcon.Undo
    "reset" -> DeskIcon.Rotate
    else -> DeskIcon.Share
}

@Composable
private fun RemovePanel(vm: LightCraftViewModel, tool: Tool?) {
    Column(Modifier.fillMaxWidth()) {
        PanelTitle("Remove")
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Tools.remove.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth()) { row.forEach { t -> Tile(removeIcon(t.id), t.label, tool?.id == t.id, Modifier.weight(1f)) { vm.selectTool(t) } } }
            }
        }
        SectionDivider()
        if (tool?.id == "eraser") EraseSettings(vm)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun EraseSettings(vm: LightCraftViewModel) {
    val o by vm.erase.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth()) {
        Text("Tap the photo to erase similar colours.", fontFamily = Inter, fontSize = 12.sp, color = Lc.dim, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
        val specs = listOf(
            SliderSpec("tolerance", "Tolerance", o.tolerance, 0f, 100f, 32f),
            SliderSpec("feather", "Feather", o.feather, 0f, 20f, 0f, unit = "px"),
            SliderSpec("opacity", "Opacity", o.opacity, 0f, 100f, 100f, unit = "%"),
        )
        SliderList(
            specs,
            SliderActions(
                change = { k, v -> vm.setErase(when (k) { "tolerance" -> o.copy(tolerance = v); "feather" -> o.copy(feather = v); else -> o.copy(opacity = v) }) },
                done = {},
                reset = { k -> vm.setErase(when (k) { "tolerance" -> o.copy(tolerance = 32f); "feather" -> o.copy(feather = 0f); else -> o.copy(opacity = 100f) }) },
            ),
        )
        Toggle("Contiguous", o.contiguous) { vm.setErase(o.copy(contiguous = it)) }
        Toggle("Anti-alias", o.antiAlias) { vm.setErase(o.copy(antiAlias = it)) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Sample", fontFamily = Inter, fontSize = 13.sp, color = Lc.label, modifier = Modifier.weight(1f))
            listOf("Point", "3×3", "5×5").forEachIndexed { i, label ->
                Spacer(Modifier.width(6.dp))
                LcButton(label, on = o.sample == i) { vm.setErase(o.copy(sample = i)) }
            }
        }
    }
}

@Composable
internal fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontFamily = Inter, fontSize = 13.sp, color = Lc.label, modifier = Modifier.weight(1f))
        Switch(
            checked = on,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Lc.accent, checkedThumbColor = Color.White, checkedBorderColor = Lc.accent,
                uncheckedTrackColor = Lc.button, uncheckedThumbColor = Lc.thumb, uncheckedBorderColor = Lc.buttonBorder,
            ),
            modifier = Modifier.graphicsLayer { scaleX = 0.75f; scaleY = 0.75f },
        )
    }
}

// ---- Presets ------------------------------------------------------------------------------------

@Composable
private fun PresetPanel(vm: LightCraftViewModel) {
    val presets by vm.presets.collectAsStateWithLifecycle()
    val thumbs by vm.presetThumbs.collectAsStateWithLifecycle()
    val groups = presets.groupBy { it.group.ifEmpty { "Presets" } }
    val open = rememberSaveable(saver = openSaver) { mutableStateMapOf<String, Boolean>() }
    var applied by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(groups.keys.firstOrNull()) { groups.keys.firstOrNull()?.let { if (open.isEmpty()) open[it] = true } }
    val visible = groups.filterKeys { open[it] == true }.values.flatten().map { it.id }
    LaunchedEffect(visible) { vm.loadPresetThumbs(visible) }
    Column(Modifier.fillMaxWidth()) {
        PanelTitle("Presets")
        groups.forEach { (group, list) ->
            Section(group, group, open) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                    list.chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { p ->
                                val on = applied == p.id
                                Column(Modifier.weight(1f).clickable { applied = p.id; vm.applyPreset(p.id) }) {
                                    Box(
                                        Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(Lc.skeleton)
                                            .border(if (on) 2.dp else 0.dp, if (on) Lc.pick else Color.Transparent, RoundedCornerShape(4.dp)),
                                    ) {
                                        thumbs[p.id]?.let { Image(it.asImageBitmap(), p.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                                    }
                                    Text(p.name, fontFamily = Inter, fontSize = 12.sp, color = if (on) Lc.text else Lc.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
