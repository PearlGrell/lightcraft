package ai.storyteller.lightcraft.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.RotateLeft
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.storyteller.lightcraft.android.Control
import ai.storyteller.lightcraft.android.LightCraftViewModel
import ai.storyteller.lightcraft.android.BrushSettings
import ai.storyteller.lightcraft.android.GenModel
import ai.storyteller.lightcraft.android.ModelPack
import ai.storyteller.lightcraft.android.ModelPacks
import ai.storyteller.lightcraft.android.Preset
import ai.storyteller.lightcraft.android.Profile
import ai.storyteller.lightcraft.android.Tools
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Text tabs with a white bar under the current one (Recommended | Favorites | Yours, Aspect | Geometry). */
@Composable
internal fun UnderlineTabs(titles: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        titles.forEachIndexed { i, title ->
            val on = i == selected
            Column(
                Modifier.width(IntrinsicSize.Max).clip(RoundedCornerShape(6.dp)).clickable { onSelect(i) }.padding(horizontal = 4.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(title, fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) Lc.text else Lc.muted, maxLines = 1)
                Box(Modifier.padding(top = 7.dp).fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(if (on) Lc.text else Color.Transparent))
            }
        }
    }
}

// ---- presets -----------------------------------------------------------------------------------

private val PRESET_TABS = listOf("Recommended", "Favorites", "Yours")

/**
 * Presets as Lightroom lays them out: tabs, then one row per group (cover, name, count, chevron);
 * a group opens into a strip of live thumbnails. Long-press a preset to favourite or delete it.
 */
@Composable
internal fun PresetPanel(vm: LightCraftViewModel) {
    val presets by vm.presets.collectAsStateWithLifecycle()
    val thumbs by vm.presetThumbs.collectAsStateWithLifecycle()
    PresetBrowser(
        presets = presets,
        thumbs = thumbs,
        loadThumbs = vm::loadPresetThumbs,
        onApply = vm::applyPreset,
        onFavorite = vm::favoritePreset,
        onDelete = vm::deletePreset,
    )
}

@Composable
internal fun PresetBrowser(
    presets: List<Preset>,
    thumbs: Map<String, android.graphics.Bitmap>,
    loadThumbs: (List<String>) -> Unit,
    onApply: (String) -> Unit,
    onFavorite: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    initialGroup: String? = null,
) {
    var tab by remember { mutableIntStateOf(0) }
    var group by remember { mutableStateOf(initialGroup) }
    var applied by remember { mutableStateOf<String?>(null) }
    val inTab = when (tab) {
        0 -> presets.filter { it.builtin }
        1 -> presets.filter { it.favorite }
        else -> presets.filter { !it.builtin }
    }
    val groups = inTab.groupBy { it.group.ifEmpty { "Presets" } }.toList()
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        UnderlineTabs(PRESET_TABS, tab) {
            tab = it
            group = null
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Lc.divider))
        val open = groups.firstOrNull { it.first == group }
        if (open == null) {
            LaunchedEffect(groups.map { it.first }) { loadThumbs(groups.mapNotNull { it.second.firstOrNull()?.id }) }
            if (groups.isEmpty()) {
                Text(
                    when (tab) {
                        1 -> "No favourites yet. Long-press a preset to add it here."
                        2 -> "Your presets appear here. Save one from the ⋮ menu > Create Preset."
                        else -> "Loading presets…"
                    },
                    fontSize = 14.sp,
                    color = Lc.label,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 28.dp),
                )
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 236.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                items(groups, key = { it.first }) { (name, list) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { group = name }.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).background(Lc.raised)) {
                            list.firstOrNull()?.let { p -> thumbs[p.id]?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) } }
                        }
                        Text(name, fontSize = 15.sp, color = Lc.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 16.dp))
                        Text("${list.size}", fontSize = 14.sp, color = Lc.muted)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Open", tint = Lc.label, modifier = Modifier.padding(start = 10.dp).size(24.dp))
                    }
                }
            }
        } else {
            val (name, list) = open
            LaunchedEffect(list.map { it.id }) { loadThumbs(list.map { it.id }) }
            Row(Modifier.fillMaxWidth().clickable { group = null }.padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Back to groups", tint = Lc.text, modifier = Modifier.size(28.dp))
                Text(name, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Lc.text, modifier = Modifier.weight(1f).padding(start = 4.dp))
                Text("${list.size}", fontSize = 14.sp, color = Lc.muted)
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list, key = { it.id }) { p ->
                    PresetTile(p, thumbs[p.id]?.asImageBitmap(), applied == p.id, onApply = {
                        applied = p.id
                        onApply(p.id)
                    }, onFavorite = { onFavorite(p.id, !p.favorite) }, onDelete = if (p.builtin) null else ({ onDelete(p.id) }))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetTile(
    p: Preset,
    thumb: androidx.compose.ui.graphics.ImageBitmap?,
    applied: Boolean,
    onApply: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.width(80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Lc.raised)
                .border(2.dp, if (applied) Color.White else Color.Transparent, RoundedCornerShape(8.dp))
                .combinedClickable(onClick = onApply, onLongClick = { menu = true }),
        ) {
            thumb?.let { Image(it, p.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            if (p.favorite) Icon(Icons.Filled.Star, "Favourite", tint = Lc.star, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp))
            DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = Lc.menu) {
                MenuRow(if (p.favorite) "Remove from Favorites" else "Add to Favorites", if (p.favorite) Icons.Outlined.StarOutline else Icons.Filled.Star) {
                    menu = false
                    onFavorite()
                }
                if (onDelete != null) {
                    MenuRow("Delete preset", Icons.Outlined.Delete) {
                        menu = false
                        onDelete()
                    }
                }
            }
        }
        Text(p.name, fontSize = 12.sp, color = if (applied) Lc.text else Lc.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
}

// ---- crop --------------------------------------------------------------------------------------

/**
 * Crop: rotate and flip buttons, the straighten dial, then Aspect (ratio tiles) and Geometry
 * (Upright and the transform sliders) tabs.
 */
@Composable
internal fun CropPanel(vm: LightCraftViewModel, controls: List<Control>, aspect: Float, onAspect: (Float) -> Unit, onTilt: (Float) -> Unit) {
    CropControls(
        controls = controls,
        aspect = aspect,
        onTilt = onTilt,
        onAuto = vm::autoStraighten,
        onUpright = vm::upright,
        command = vm::cropCommand,
        setAspect = {
            onAspect(it)
            vm.cropAspect(it)
        },
        setControl = vm::setControl,
        onDone = vm::finishInteraction,
        resetControl = vm::resetControl,
    )
}

@Composable
internal fun CropControls(
    controls: List<Control>,
    aspect: Float,
    onTilt: (Float) -> Unit = {},
    onAuto: () -> Unit = {},
    onUpright: (String) -> Unit = {},
    command: (String) -> Unit,
    setAspect: (Float) -> Unit,
    setControl: (String, Float) -> Unit,
    onDone: () -> Unit,
    resetControl: (String) -> Unit,
    initialPage: Int = 0,
) {
    var page by remember { mutableIntStateOf(initialPage) }
    var more by remember { mutableStateOf(false) }
    val angle = controls.firstOrNull { it.id == "crop.angle" }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundButton(Icons.Outlined.Flip, "Flip horizontal") { command("photo.flipHorizontal") }
            RoundButton(Icons.Outlined.Flip, "Flip vertical", rotate = 90f) { command("photo.flipVertical") }
            Text(
                "Auto",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Lc.text,
                modifier = Modifier.clip(RoundedCornerShape(19.dp)).background(Lc.raised).clickable(onClick = onAuto).padding(horizontal = 14.dp, vertical = 10.dp),
            )
            Spacer(Modifier.weight(1f))
            RoundButton(Icons.AutoMirrored.Outlined.RotateRight, "Rotate right") { command("photo.rotateRight") }
            Box {
                RoundButton(Icons.Filled.MoreHoriz, "More crop options") { more = true }
                DropdownMenu(more, onDismissRequest = { more = false }, containerColor = Lc.menu) {
                    MenuRow("Rotate left", Icons.AutoMirrored.Outlined.RotateLeft) {
                        more = false
                        command("photo.rotateLeft")
                    }
                    MenuRow("Reset crop", Icons.Outlined.RestartAlt) {
                        more = false
                        setAspect(-1f)
                        command("crop.reset")
                    }
                }
            }
        }
        if (angle != null) {
            // the dial turns the photo on screen as it moves; the angle is applied once, on release
            var live by remember(angle.value) { mutableFloatStateOf(angle.value) }
            StraightenDial(
                live, angle.min, angle.max,
                onChange = {
                    live = it
                    onTilt(it - angle.value)
                },
                onDone = {
                    if (live != angle.value) setControl(angle.id, live)
                    onDone()
                    onTilt(0f)
                },
                onReset = {
                    live = angle.default
                    onTilt(0f)
                    resetControl(angle.id)
                },
            )
        }
        UnderlineTabs(listOf("Aspect", "Geometry"), page) { page = it }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Lc.divider))
        if (page == 0) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tools.aspects.forEachIndexed { i, a ->
                    item(key = a.label) {
                        AspectTile(a.label, a.ratio, aspect == a.ratio) { setAspect(a.ratio) }
                    }
                    if (i == 0) item(key = "divider") { Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(44.dp).background(Lc.divider)) }
                }
            }
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(UPRIGHT) { (label, mode) ->
                    Text(
                        label,
                        fontSize = 13.sp,
                        color = Lc.text,
                        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Lc.raised).clickable { onUpright(mode) }.padding(horizontal = 13.dp, vertical = 7.dp),
                    )
                }
            }
            val geometry = controls.filter { Tools.geometry.matches(it.id) }
            if (geometry.isEmpty()) {
                Text("No geometry controls for this photo.", fontSize = 14.sp, color = Lc.label, modifier = Modifier.padding(22.dp))
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 6.dp)) {
                items(geometry, key = { it.id }) { c ->
                    LrSlider(c.label, c.value, c.min, c.max, c.default, c.decimals, c.unit, { setControl(c.id, it) }, onDone, { resetControl(c.id) })
                }
            }
        }
    }
}

/** Upright modes (`geometry.upright`): the photo's lines are analysed and corrected. */
private val UPRIGHT = listOf("Off" to "off", "Auto" to "auto", "Level" to "level", "Vertical" to "vertical", "Full" to "full")

@Composable
private fun RoundButton(icon: ImageVector, label: String, rotate: Float = 0f, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(CircleShape).background(Lc.raised).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Lc.text, modifier = Modifier.size(21.dp).graphicsLayer { rotationZ = rotate })
    }
}

/** A ratio tile: the ratio drawn as a rounded frame (a picture icon for Original), its name under it. */
@Composable
private fun AspectTile(label: String, ratio: Float, on: Boolean, onClick: () -> Unit) {
    val tint = if (on) Color.White else Lc.icon
    Column(
        Modifier.width(62.dp).clip(RoundedCornerShape(10.dp)).background(if (on) Lc.raised else Color.Transparent).clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
            if (ratio <= 0f) {
                Icon(Icons.Outlined.Image, null, tint = tint, modifier = Modifier.size(28.dp))
            } else {
                Canvas(Modifier.size(28.dp)) {
                    val (w, h) = if (ratio >= 1f) size.width to size.width / ratio else size.height * ratio to size.height
                    drawRoundRect(
                        tint,
                        topLeft = Offset((size.width - w) / 2f, (size.height - h) / 2f),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(3.dp.toPx()),
                        style = Stroke(1.8.dp.toPx()),
                    )
                }
            }
        }
        Text(label, fontSize = 11.sp, color = if (on) Lc.text else Lc.label, modifier = Modifier.padding(top = 6.dp))
    }
}

/**
 * The straighten dial: a ruler of degree ticks that slides under a fixed centre mark, the angle
 * above it. Drag to straighten (relative to the finger), snaps to 0° with a tick, double-tap resets.
 */
@Composable
internal fun StraightenDial(value: Float, min: Float, max: Float, onChange: (Float) -> Unit, onDone: () -> Unit, onReset: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    Column(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(String.format(Locale.ROOT, "%.2f°", value), fontSize = 13.sp, color = if (value == 0f) Lc.label else Lc.accentText)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onReset() }) }
                .pointerInput(min, max) {
                    val perDegree = 9.dp.toPx()
                    var v = 0f
                    detectHorizontalDragGestures(onDragStart = { v = current }, onDragEnd = onDone, onDragCancel = onDone) { c, dx ->
                        c.consume()
                        v = (v - dx / perDegree).coerceIn(min, max)
                        val snapped = if (abs(v) < 0.25f) 0f else (v * 20f).roundToInt() / 20f
                        if (snapped == 0f && current != 0f) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        if (snapped != current) change(snapped)
                    }
                },
        ) {
            val perDegree = 9.dp.toPx()
            val mid = size.width / 2f
            val first = kotlin.math.ceil(min.toDouble()).toInt()
            val last = kotlin.math.floor(max.toDouble()).toInt()
            for (d in first..last) {
                val x = mid + (d - value) * perDegree
                if (x < -2f || x > size.width + 2f) continue
                val major = d % 5 == 0
                val fade = (1f - abs(x - mid) / mid).coerceIn(0.15f, 1f)
                val h = if (major) 12.dp.toPx() else 6.dp.toPx()
                drawLine(Lc.label.copy(alpha = fade), Offset(x, size.height / 2f - h / 2f), Offset(x, size.height / 2f + h / 2f), if (major) 1.6.dp.toPx() else 1.dp.toPx())
            }
            drawLine(Lc.accentText, Offset(mid, 4.dp.toPx()), Offset(mid, size.height - 4.dp.toPx()), 2.dp.toPx())
        }
    }
}

// ---- remove brushes ----------------------------------------------------------------------------

/** Heal, Clone and Remove: a hint, the brush's size, feather and opacity, and Delete for the selected spot. */
@Composable
internal fun BrushPanel(hint: String, brush: BrushSettings, onBrush: (BrushSettings) -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(hint, fontSize = 13.sp, color = Lc.label, modifier = Modifier.weight(1f))
            Text(
                "Delete spot",
                fontSize = 13.sp,
                color = Lc.text,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Lc.raised).clickable(onClick = onDelete).padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
        Column(Modifier.padding(horizontal = 22.dp, vertical = 4.dp)) {
            LrSlider("Size", brush.size * 100f, 1f, 25f, 5f, 0, "%", { onBrush(brush.copy(size = it / 100f)) }, {}, { onBrush(brush.copy(size = 0.05f)) })
            LrSlider("Feather", brush.feather, 0f, 100f, 50f, 0, "", { onBrush(brush.copy(feather = it)) }, {}, { onBrush(brush.copy(feather = 50f)) })
            LrSlider("Opacity", brush.opacity, 1f, 100f, 100f, 0, "%", { onBrush(brush.copy(opacity = it)) }, {}, { onBrush(brush.copy(opacity = 100f)) })
        }
    }
}

// ---- profiles ----------------------------------------------------------------------------------

/** Profiles: groups as text tabs, the profiles as tiles, the amount of the chosen one. */
@Composable
internal fun ProfilesPanel(vm: LightCraftViewModel) {
    LaunchedEffect(Unit) { vm.loadProfiles() }
    val all by vm.profiles.collectAsStateWithLifecycle()
    val current by vm.profile.collectAsStateWithLifecycle()
    ProfileBrowser(all, current, vm::setProfile, vm::setProfileAmount, vm::finishInteraction)
}

@Composable
internal fun ProfileBrowser(all: List<Profile>, current: Pair<String, Float>?, onPick: (String) -> Unit, onAmount: (Float) -> Unit, onDone: () -> Unit) {
    val groups = all.map { it.group.ifEmpty { "Profiles" } }.distinct()
    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(groups, current?.first) {
        // open on the group of the current profile
        all.firstOrNull { it.id == current?.first }?.let { p -> groups.indexOf(p.group.ifEmpty { "Profiles" }).takeIf { it >= 0 }?.let { tab = it } }
    }
    val group = groups.getOrNull(tab)
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        if (groups.isEmpty()) {
            Text("Loading profiles…", fontSize = 14.sp, color = Lc.label, modifier = Modifier.padding(22.dp))
            return@Column
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
            items(groups.size) { i ->
                val on = i == tab
                Column(Modifier.clip(RoundedCornerShape(6.dp)).clickable { tab = i }.padding(horizontal = 10.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(groups[i], fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) Lc.text else Lc.muted)
                    Box(Modifier.padding(top = 6.dp).width(24.dp).height(2.dp).background(if (on) Lc.text else Color.Transparent))
                }
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(all.filter { it.group.ifEmpty { "Profiles" } == group }, key = { it.id }) { p ->
                val on = p.id == current?.first
                Box(
                    Modifier
                        .size(width = 92.dp, height = 58.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(profileSwatch(p))
                        .border(2.dp, if (on) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
                        .clickable { onPick(p.id) }
                        .padding(8.dp),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    Text(p.name, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (p.favorite) Icon(Icons.Filled.Star, "Favourite", tint = Lc.star, modifier = Modifier.align(Alignment.TopEnd).size(14.dp))
                }
            }
        }
        current?.let { (_, amount) ->
            Box(Modifier.padding(horizontal = 22.dp)) {
                LrSlider("Amount", amount, 0f, 200f, 100f, 0, "", onAmount, onDone, { onAmount(100f); onDone() })
            }
        }
    }
}

/** A tile background that hints at a profile's look (monochrome greys, vivid or muted colour). */
private fun profileSwatch(p: Profile): androidx.compose.ui.graphics.Brush {
    val n = (p.name + " " + p.group).lowercase()
    val colors = when {
        "mono" in n || "b&w" in n || "black" in n -> listOf(Color(0xFF2B2B2B), Color(0xFF8A8A8A))
        "vivid" in n || "vibrant" in n -> listOf(Color(0xFF1F5FD1), Color(0xFFE0592A))
        "vintage" in n || "film" in n || "retro" in n -> listOf(Color(0xFF5A4632), Color(0xFFB59468))
        "portrait" in n -> listOf(Color(0xFF6B4A3E), Color(0xFFD9A38A))
        "landscape" in n -> listOf(Color(0xFF2E5D3A), Color(0xFF7DB0D8))
        "neutral" in n || "flat" in n -> listOf(Color(0xFF4A4A46), Color(0xFF8E8C84))
        else -> listOf(Color(0xFF34495E), Color(0xFF8E6E53))
    }
    return androidx.compose.ui.graphics.Brush.linearGradient(colors)
}

// ---- remove with a selection -------------------------------------------------------------------

/**
 * Remove: paint over what should go, or tap an object to select it; the selection shows in red.
 * Remove fills it from its surroundings in one step.
 */
@Composable
internal fun SelectRemovePanel(
    hasSelection: Boolean,
    pickObject: Boolean,
    brush: BrushSettings,
    onPickObject: (Boolean) -> Unit,
    onClear: () -> Unit,
    onBrush: (BrushSettings) -> Unit,
    onRemove: () -> Unit,
    models: Map<String, GenModel> = emptyMap(),
    generative: Boolean = false,
    onGenerative: (Boolean) -> Unit = {},
    onDownload: (ModelPack) -> Unit = {},
    onCancelDownload: (ModelPack) -> Unit = {},
    onDeleteModel: (ModelPack) -> Unit = {},
) {
    var licence by remember { mutableStateOf<ModelPack?>(null) }
    val fill = models[ModelPacks.FILL.id] ?: GenModel()
    val select = models[ModelPacks.SELECT.id] ?: GenModel()
    val useModel = fill.installed && generative
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeChip("Brush", !pickObject) { onPickObject(false) }
            ModeChip("Tap object", pickObject) { onPickObject(true) }
            Spacer(Modifier.weight(1f))
            if (hasSelection) {
                Text(
                    "Clear",
                    fontSize = 14.sp,
                    color = Lc.label,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClear).padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        Text(
            if (pickObject) "Tap an object to select it. Tap more to add them." else "Paint over what you want gone; the selection shows in red.",
            fontSize = 13.sp,
            color = Lc.muted,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 10.dp),
        )
        if (pickObject && !select.installed) {
            ModelCard(ModelPacks.SELECT, select, "Selects any object you tap (people, things, buildings). Without it, only people and pets.", onDownload, onCancelDownload, onDeleteModel) { licence = ModelPacks.SELECT }
        }
        if (!pickObject) {
            Box(Modifier.padding(horizontal = 22.dp)) {
                LrSlider("Brush size", brush.size * 100f, 1f, 25f, 5f, 0, "%", { onBrush(brush.copy(size = it / 100f)) }, {}, { onBrush(brush.copy(size = 0.05f)) })
            }
        }
        androidx.compose.material3.Button(
            onClick = onRemove,
            enabled = hasSelection,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Lc.accent, disabledContainerColor = Lc.raised),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp).height(44.dp),
        ) {
            Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(18.dp))
            Text(if (useModel) "Generate" else "Remove", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
        }
        if (fill.installed) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip("Generative", generative) { onGenerative(true) }
                ModeChip("Content-aware", !generative) { onGenerative(false) }
                Spacer(Modifier.weight(1f))
                Text("Delete model", fontSize = 12.sp, color = Lc.muted, modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { onDeleteModel(ModelPacks.FILL) }.padding(6.dp))
            }
        } else {
            ModelCard(ModelPacks.FILL, fill, "Paints in what was behind the object, instead of copying nearby pixels. A fill takes about 10 to 20 seconds.", onDownload, onCancelDownload, onDeleteModel) { licence = ModelPacks.FILL }
        }
    }
    licence?.let { pack ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { licence = null },
            containerColor = Lc.menu,
            shape = RoundedCornerShape(8.dp),
            title = { Text(pack.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Lc.text) },
            text = {
                Text(
                    "${pack.source}.\n\nLicence: ${pack.license}. ${pack.licenseUrl}\n\nDownloaded from Hugging Face and run on your phone; your photos never leave it.",
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = Lc.label,
                )
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { licence = null }) { Text("Close", color = Lc.accentText) } },
        )
    }
}

/** An optional model: offered with its size and source; progress and Cancel while it downloads. */
@Composable
private fun ModelCard(
    pack: ModelPack,
    state: GenModel,
    blurb: String,
    onDownload: (ModelPack) -> Unit,
    onCancel: (ModelPack) -> Unit,
    onDelete: (ModelPack) -> Unit,
    onLicence: () -> Unit,
) {
    val mb = "%.0f MB".format(pack.bytes / 1_000_000f)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, Lc.divider, RoundedCornerShape(6.dp))
            .padding(12.dp),
    ) {
        if (state.downloading || state.loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state.loading) "Starting ${pack.title}..." else "Downloading ${pack.title}: ${(state.progress * 100).toInt()} % of $mb",
                    fontSize = 13.sp,
                    color = Lc.text,
                    modifier = Modifier.weight(1f),
                )
                if (state.downloading) Text("Cancel", fontSize = 13.sp, color = Lc.accentText, modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { onCancel(pack) }.padding(6.dp))
            }
            androidx.compose.material3.LinearProgressIndicator(
                progress = { if (state.loading) 1f else state.progress },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = Lc.accent,
                trackColor = Lc.raised,
            )
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AutoAwesome, null, tint = Lc.accentText, modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(pack.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Lc.text)
                Text("On-device AI, $mb, ${pack.license}", fontSize = 12.sp, color = Lc.muted)
            }
            androidx.compose.material3.Button(
                onClick = { onDownload(pack) },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Lc.accent),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 14.dp),
                modifier = Modifier.height(32.dp),
            ) { Text("Download", fontSize = 13.sp) }
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(state.error ?: blurb, fontSize = 12.sp, color = if (state.error != null) Color(0xFFFF6B6B) else Lc.muted, modifier = Modifier.weight(1f))
            Text("About", fontSize = 12.sp, color = Lc.accentText, modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onLicence).padding(6.dp))
        }
        if (state.error != null) {
            Text("Delete files", fontSize = 12.sp, color = Lc.muted, modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { onDelete(pack) }.padding(6.dp))
        }
    }
}

@Composable
private fun ModeChip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = if (on) Lc.chipOnText else Lc.label,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (on) Lc.chipOn else Color.Transparent)
            .border(1.dp, if (on) Color.Transparent else Lc.divider, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/** The Remove selection drawn over the photo: red where selected, with soft edges. */
@Composable
internal fun SelectionOverlay(grid: BooleanArray, side: Int, fit: androidx.compose.ui.geometry.Rect) {
    val bitmap = remember(grid) {
        val px = IntArray(side * side) { if (grid.getOrElse(it) { false }) 0x8CFF3B30.toInt() else 0 }
        android.graphics.Bitmap.createBitmap(px, side, side, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Canvas(Modifier.fillMaxSize()) {
        drawImage(
            bitmap,
            dstOffset = androidx.compose.ui.unit.IntOffset(fit.left.toInt(), fit.top.toInt()),
            dstSize = androidx.compose.ui.unit.IntSize(fit.width.toInt(), fit.height.toInt()),
            filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
        )
    }
}
