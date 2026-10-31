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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.RotateLeft
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.storyteller.lightcraft.android.AiModelStatus
import ai.storyteller.lightcraft.android.Control
import ai.storyteller.lightcraft.android.LightCraftViewModel
import ai.storyteller.lightcraft.android.MainTab
import ai.storyteller.lightcraft.android.Tool
import ai.storyteller.lightcraft.android.ToolKind
import ai.storyteller.lightcraft.android.Tools

internal fun Control.spec(unitOverride: String? = null) = DialSpec(id, label, value, min, max, default, decimals, unitOverride ?: unit)

/** The panel above the tool row for the open tab and tool. */
@Composable
internal fun Panel(vm: LightCraftViewModel, tab: MainTab, tool: Tool?) {
    val controls by vm.controls.collectAsStateWithLifecycle()
    when (tab) {
        MainTab.Presets -> PresetPanel(vm)
        MainTab.Crop -> CropPanel(vm, controls.filter { it.id == "crop.angle" })
        MainTab.Masking -> MaskPanel(vm, tool)
        MainTab.Remove -> if (tool?.id == "eraser") ErasePanel(vm)
        MainTab.Edit -> when (tool?.kind) {
            ToolKind.Curve -> CurvePanel(vm, tool, controls)
            ToolKind.Sliders -> SliderPanel(vm, tool, controls)
            else -> {}
        }
        MainTab.Quick -> {}
    }
}

private fun controlActions(vm: LightCraftViewModel) = DialActions(
    change = { id, v -> vm.setControl(id, v) },
    done = vm::finishInteraction,
    reset = { id -> vm.resetControl(id) },
)

@Composable
private fun SliderPanel(vm: LightCraftViewModel, tool: Tool, all: List<Control>) {
    val groups = tool.groups.filter { g -> all.any { g.matches(it.id) } }
    var selected by remember(tool.id) { mutableIntStateOf(0) }
    val group = groups.getOrNull(selected) ?: groups.firstOrNull() ?: return
    val controls = all.filter { group.matches(it.id) }
    var focus by remember(tool.id, group.title) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth()) {
        Pills(groups.map { it.title }, groups.indexOf(group), { selected = it }, onReset = { vm.resetGroup(group) })
        DialGrid(controls.map { it.spec() }, focus, { focus = if (focus == it) null else it }, controlActions(vm), columns = group.columns)
    }
}

@Composable
private fun CurvePanel(vm: LightCraftViewModel, tool: Tool, all: List<Control>) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val parametric = tool.groups.firstOrNull()
    val controls = if (parametric == null) emptyList() else all.filter { parametric.matches(it.id) }
    var focus by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth()) {
        Pills(
            listOfNotNull("Point", if (controls.isNotEmpty()) "Parametric" else null),
            page,
            { page = it },
            onReset = if (page == 1 && parametric != null) ({ vm.resetGroup(parametric) }) else null,
        )
        if (page == 0 || controls.isEmpty()) {
            val curves by vm.curves.collectAsStateWithLifecycle()
            CurveEditor(curves, vm::setCurve, vm::finishInteraction, vm::resetCurve)
        } else {
            DialGrid(controls.map { it.spec() }, focus, { focus = if (focus == it) null else it }, controlActions(vm))
        }
    }
}

/** Sub-panel tabs: the open one in paper white; Reset at the end when given. */
@Composable
internal fun Pills(titles: List<String>, selected: Int, onSelect: (Int) -> Unit, onReset: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().height(42.dp).padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            items(titles.size) { i ->
                val on = i == selected
                Column(
                    Modifier.clip(RoundedCornerShape(6.dp)).clickable { onSelect(i) }.padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        titles[i],
                        fontSize = 13.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (on) Lc.text else Lc.muted,
                    )
                    Spacer(Modifier.height(3.dp))
                    Box(Modifier.width(14.dp).height(2.dp).background(if (on) Lc.accent else Color.Transparent, RoundedCornerShape(1.dp)))
                }
            }
        }
        if (onReset != null) {
            Text("Reset", fontSize = 13.sp, color = Lc.label, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onReset).padding(horizontal = 10.dp, vertical = 6.dp))
        }
    }
}

/** A small rounded choice, paper white when chosen. */
@Composable
internal fun Chip(label: String, on: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 13.sp,
        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        color = if (on) Lc.onAccent else Lc.text,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (on) Lc.text else Lc.raised)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun PresetPanel(vm: LightCraftViewModel) {
    val presets by vm.presets.collectAsStateWithLifecycle()
    val thumbs by vm.presetThumbs.collectAsStateWithLifecycle()
    val groups = presets.map { it.group.ifEmpty { "Presets" } }.distinct()
    var selected by remember { mutableIntStateOf(0) }
    val group = groups.getOrNull(selected) ?: groups.firstOrNull()
    val shown = presets.filter { it.group.ifEmpty { "Presets" } == group }
    var applied by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(shown.map { it.id }) { vm.loadPresetThumbs(shown.map { it.id }) }
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Pills(groups.ifEmpty { listOf("Presets") }, groups.indexOf(group).coerceAtLeast(0), { selected = it }, onReset = null)
        LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(shown, key = { it.id }) { p ->
                val on = applied == p.id
                Column(Modifier.width(80.dp).clickable { applied = p.id; vm.applyPreset(p.id) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(80.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Lc.raised)
                            .border(if (on) 2.dp else 0.dp, if (on) Lc.accent else Color.Transparent, RoundedCornerShape(6.dp)),
                    ) {
                        thumbs[p.id]?.let { Image(it.asImageBitmap(), p.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    }
                    Text(
                        p.name, fontSize = 11.sp, color = if (on) Lc.text else Lc.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CropPanel(vm: LightCraftViewModel, straighten: List<Control>) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        SectionLabel("Aspect")
        LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(Tools.aspects) { a -> Chip(a.label) { vm.cropAspect(a.ratio) } }
            item { Chip("Reset") { vm.cropCommand("crop.reset") } }
        }
        Spacer(Modifier.height(8.dp))
        SectionLabel("Level")
        LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Chip("Auto straighten", onClick = vm::autoStraighten) }
            item { Chip("Upright: Auto") { vm.upright("auto") } }
            item { Chip("Level") { vm.upright("level") } }
            item { Chip("Vertical") { vm.upright("vertical") } }
            item { Chip("Off") { vm.upright("off") } }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.cropCommand("photo.rotateLeft") }) { Icon(Icons.AutoMirrored.Outlined.RotateLeft, "Rotate left", tint = Lc.text) }
            IconButton(onClick = { vm.cropCommand("photo.rotateRight") }) { Icon(Icons.AutoMirrored.Outlined.RotateRight, "Rotate right", tint = Lc.text) }
            IconButton(onClick = { vm.cropCommand("photo.flipHorizontal") }) { Icon(Icons.Outlined.Flip, "Flip horizontal", tint = Lc.text) }
            IconButton(onClick = { vm.cropCommand("photo.flipVertical") }) {
                Icon(Icons.Outlined.Flip, "Flip vertical", tint = Lc.text, modifier = Modifier.graphicsLayer { rotationZ = 90f })
            }
            Spacer(Modifier.weight(1f))
        }
        val specs = straighten.map { it.spec("°").copy(label = "Straighten") }
        if (specs.isNotEmpty()) {
            // the angle wants precision: its dial with the fine ruler always open
            DialGrid(specs, specs.first().key, {}, controlActions(vm), columns = 4, maxHeight = 96.dp)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Lc.muted,
        modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun MaskPanel(vm: LightCraftViewModel, tool: Tool?) {
    val masks by vm.masks.collectAsStateWithLifecycle()
    val active by vm.activeMask.collectAsStateWithLifecycle()
    val ai by vm.ai.collectAsStateWithLifecycle()
    val mask = masks.firstOrNull { it.id == active }
    var focus by remember(active) { mutableStateOf<String?>(null) }
    var page by remember(active, tool?.id) { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth()) {
        when (tool?.kind) {
            ToolKind.Object -> ObjectHint(vm, ai)
            ToolKind.Describe -> DescribeBox(vm, ai)
            else -> {}
        }
        if (masks.isEmpty()) {
            if (tool == null) {
                Text(
                    "Choose what to adjust: Subject, Background and Sky are found on the phone; paint with Brush, Dodge or Burn; or add a gradient.",
                    fontSize = 13.sp,
                    color = Lc.label,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
                )
            }
            return@Column
        }
        Row(Modifier.fillMaxWidth().height(46.dp).padding(start = 12.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(masks, key = { it.id }) { m -> Chip(m.name, m.id == active) { vm.selectMask(m.id) } }
            }
            IconButton(onClick = { vm.maskCommand("mask.invert") }) { Icon(Icons.Outlined.InvertColors, "Invert mask", tint = Lc.label) }
            IconButton(onClick = { vm.maskCommand("mask.delete") }) { Icon(Icons.Outlined.Delete, "Delete mask", tint = Lc.label) }
        }
        if (mask == null) return@Column
        val brushing = tool?.kind == ToolKind.Brush
        if (brushing) {
            Pills(listOf("Adjust", "Brush"), page, { page = it }, onReset = null)
        }
        if (brushing && page == 1) {
            BrushPanel(vm)
        } else {
            val specs = Tools.local.map { s ->
                DialSpec(s.key, s.label, mask.adjust[s.key] ?: 0f, s.min, s.max, 0f, s.decimals, if (s.key == "exposure") "EV" else "")
            }
            DialGrid(
                specs, focus, { focus = if (focus == it) null else it },
                DialActions(
                    change = { k, v -> vm.setMaskValue(k, v) },
                    done = vm::finishInteraction,
                    reset = { k -> vm.setMaskValue(k, 0f); vm.finishInteraction() },
                ),
                maxHeight = 176.dp,
            )
        }
    }
}

@Composable
private fun BrushPanel(vm: LightCraftViewModel) {
    val b by vm.brush.collectAsStateWithLifecycle()
    var focus by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("Paint", !b.erase) { vm.setBrush(b.copy(erase = false)) }
            Chip("Erase", b.erase) { vm.setBrush(b.copy(erase = true)) }
            Spacer(Modifier.weight(1f))
            Text("Paint on the photo", fontSize = 12.sp, color = Lc.muted)
        }
        val specs = Tools.brush.map { s ->
            val v = when (s.key) { "size" -> b.size; "feather" -> b.feather; else -> b.flow }
            val d = when (s.key) { "size" -> 0.04f; "feather" -> 50f; else -> 60f }
            DialSpec(s.key, s.label, v, s.min, s.max, d, s.decimals, if (s.key == "size") "" else "%")
        }
        DialGrid(
            specs, focus, { focus = if (focus == it) null else it },
            DialActions(
                change = { k, v ->
                    vm.setBrush(
                        when (k) {
                            "size" -> b.copy(size = v)
                            "feather" -> b.copy(feather = v)
                            else -> b.copy(flow = v)
                        },
                    )
                },
                done = {},
                reset = { k -> vm.setBrush(when (k) { "size" -> b.copy(size = 0.04f); "feather" -> b.copy(feather = 50f); else -> b.copy(flow = 60f) }) },
            ),
            columns = 4,
            maxHeight = 110.dp,
        )
    }
}

/** Object selection: tap the thing to select; Remove mode taps parts away. Gated on the model. */
@Composable
private fun ObjectHint(vm: LightCraftViewModel, ai: AiModelStatus) {
    if (!ai.installed) {
        AiModelCard(vm, ai)
        return
    }
    val exclude by vm.objectExclude.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("Add", !exclude) { vm.setObjectExclude(false) }
        Chip("Remove", exclude) { vm.setObjectExclude(true) }
        Spacer(Modifier.width(4.dp))
        Text("Tap the photo to select an object. The first selection on a photo takes a while.", fontSize = 12.sp, color = Lc.muted, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun DescribeBox(vm: LightCraftViewModel, ai: AiModelStatus) {
    if (!ai.installed) {
        AiModelCard(vm, ai)
        return
    }
    var text by rememberSaveable { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(120) },
            placeholder = { Text("sky, trees, the red car…", color = Lc.muted, fontSize = 14.sp) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.describe(text) }),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Lc.accent, unfocusedBorderColor = Lc.divider, cursorColor = Lc.accent),
            textStyle = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Chip("Select", on = text.isNotBlank()) { vm.describe(text) }
    }
}

/** What Object and Describe need: the optional SAM 3 model, its size, licence and memory. */
@Composable
private fun AiModelCard(vm: LightCraftViewModel, ai: AiModelStatus) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val ramGb = remember { deviceMemoryGb(context) }
    val enough = ramGb >= MIN_AI_RAM_GB
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text("AI object selection", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            if (enough) {
                "Uses Meta's SAM 3 model on this phone. It is an optional ${gb(ai.sizeBytes)} download (${ai.license}, not LightCraft's) and needs several GB of free memory; the first selection on a photo can take a minute."
            } else {
                "SAM 3 needs about $MIN_AI_RAM_GB GB of memory; this phone has ${"%.1f".format(ramGb)} GB. Subject, Background and Sky still work on any phone."
            },
            fontSize = 12.sp,
            color = Lc.label,
        )
        if (ai.downloading) {
            Spacer(Modifier.height(8.dp))
            val f = if (ai.total > 0) ai.done.toFloat() / ai.total else 0f
            LinearProgressIndicator(progress = { f }, Modifier.fillMaxWidth().height(3.dp), color = Lc.accent, trackColor = Lc.raised)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${gb(ai.done)} of ${gb(ai.total.takeIf { it > 0 } ?: ai.sizeBytes)}", fontSize = 12.sp, style = Numbers, color = Lc.muted, modifier = Modifier.weight(1f))
                Chip("Cancel", onClick = vm::cancelAiDownload)
            }
        } else if (enough) {
            Spacer(Modifier.height(8.dp))
            Row { Chip("Download ${gb(ai.sizeBytes)}", on = true) { confirm = true } }
            ai.error?.let { Text(it, fontSize = 12.sp, color = Lc.danger, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
    if (confirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = Lc.bar,
            title = { Text("Download SAM 3?", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "About ${gb(ai.sizeBytes)} from the model's public mirrors, under Meta's ${ai.license}. Use Wi-Fi. It stays in LightCraft's private storage; clearing the app's data removes it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { confirm = false; vm.downloadAiModel() }) { Text("Download", color = Lc.accent) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirm = false }) { Text("Not now", color = Lc.label) } },
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

@Composable
private fun ErasePanel(vm: LightCraftViewModel) {
    val o by vm.erase.collectAsStateWithLifecycle()
    var focus by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("Tap the photo to erase similar colours", fontSize = 12.sp, color = Lc.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        val specs = listOf(
            DialSpec("tolerance", "Tolerance", o.tolerance, 0f, 100f, 32f),
            DialSpec("feather", "Feather", o.feather, 0f, 20f, 0f, unit = "px"),
            DialSpec("opacity", "Opacity", o.opacity, 0f, 100f, 100f, unit = "%"),
        )
        DialGrid(
            specs, focus, { focus = if (focus == it) null else it },
            DialActions(
                change = { k, v ->
                    vm.setErase(
                        when (k) {
                            "tolerance" -> o.copy(tolerance = v)
                            "feather" -> o.copy(feather = v)
                            else -> o.copy(opacity = v)
                        },
                    )
                },
                done = {},
                reset = { k -> vm.setErase(when (k) { "tolerance" -> o.copy(tolerance = 32f); "feather" -> o.copy(feather = 0f); else -> o.copy(opacity = 100f) }) },
            ),
            maxHeight = 110.dp,
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Toggle("Contiguous", o.contiguous, Modifier.weight(1f)) { vm.setErase(o.copy(contiguous = it)) }
            Toggle("Anti-alias", o.antiAlias, Modifier.weight(1f)) { vm.setErase(o.copy(antiAlias = it)) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Sample", fontSize = 14.sp, color = Lc.text, modifier = Modifier.weight(1f))
            listOf("Point", "3×3", "5×5").forEachIndexed { i, label ->
                Spacer(Modifier.width(6.dp))
                Chip(label, o.sample == i) { vm.setErase(o.copy(sample = i)) }
            }
        }
    }
}

@Composable
internal fun Toggle(label: String, on: Boolean, modifier: Modifier, onChange: (Boolean) -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = Lc.text, modifier = Modifier.weight(1f))
        Switch(
            checked = on,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Lc.accent, checkedThumbColor = Lc.onAccent,
                uncheckedTrackColor = Lc.raised, uncheckedThumbColor = Lc.label, uncheckedBorderColor = Lc.divider,
            ),
            modifier = Modifier.graphicsLayer { scaleX = 0.8f; scaleY = 0.8f },
        )
        Spacer(Modifier.width(10.dp))
    }
}
