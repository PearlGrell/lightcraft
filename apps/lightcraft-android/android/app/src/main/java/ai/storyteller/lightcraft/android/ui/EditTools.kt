package ai.storyteller.lightcraft.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.storyteller.lightcraft.android.Control
import ai.storyteller.lightcraft.android.LightCraftViewModel
import ai.storyteller.lightcraft.android.TapMode
import ai.storyteller.lightcraft.android.Tools

/** A row of choice chips; the one equal to [selected] is lit. */
@Composable
internal fun Chips(options: List<Pair<String, String>>, selected: String?, enabled: Boolean = true, onPick: (String) -> Unit) {
    LazyRow(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(options, key = { it.first }) { (id, label) ->
            val on = id == selected
            Text(
                label,
                fontSize = 13.sp,
                color = if (on) Color.White else if (enabled) Lc.label else Lc.muted,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (on) Lc.accent else Lc.raised)
                    .clickable(enabled = enabled) { onPick(id) }
                    .padding(horizontal = 11.dp, vertical = 6.dp),
            )
        }
    }
}

/** A small text button. */
@Composable
internal fun SmallButton(label: String, modifier: Modifier = Modifier, accent: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 13.sp,
        color = if (accent) Color.White else Lc.text,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (accent) Lc.accent else Lc.raised)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Color → Color: the white balance presets and the eyedropper, above Temp and Tint. */
@Composable
internal fun WhiteBalanceRow(vm: LightCraftViewModel) {
    val d by vm.develop.collectAsStateWithLifecycle()
    val tap by vm.tapMode.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { Chips(Tools.wbModes, d.wbMode) { vm.setWbMode(it) } }
        val on = tap == TapMode.WhiteBalance
        Box(
            Modifier
                .padding(start = 6.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (on) Lc.accent else Lc.raised)
                .clickable { vm.setTapMode(TapMode.WhiteBalance) }
                .padding(6.dp),
        ) { Icon(Icons.Outlined.Colorize, "Pick a neutral point", tint = Color.White) }
    }
    if (tap == TapMode.WhiteBalance) Note("Tap something that should be neutral grey or white.")
}

/** Color → Point Color: pick colours on the photo, then shift each one's hue, saturation and luminance. */
@Composable
internal fun PointColorRow(vm: LightCraftViewModel) {
    val d by vm.develop.collectAsStateWithLifecycle()
    val tap by vm.tapMode.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        SmallButton(if (tap == TapMode.PointColor) "Picking…" else "Pick color", accent = tap == TapMode.PointColor) { vm.setTapMode(TapMode.PointColor) }
        Text("  ${d.pointColors} of 8 samples", fontSize = 13.sp, color = Lc.muted, modifier = Modifier.weight(1f))
        if (d.pointColors > 0) SmallButton("Delete last") { vm.deletePointColor(d.pointColors - 1) }
    }
    if (tap == TapMode.PointColor) Note("Tap the colour to change; its sliders appear below.")
}

/** Color → B&W: the automatic mix from the photo's colours. */
@Composable
internal fun BwRow(vm: LightCraftViewModel) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { SmallButton("Auto mix") { vm.autoBwMix() } }
}

/** Light: HDR editing (highlights above SDR white for HDR displays and exports). */
@Composable
internal fun HdrRow(vm: LightCraftViewModel) {
    val d by vm.develop.collectAsStateWithLifecycle()
    Toggle("HDR editing", d.hdr, Modifier.fillMaxWidth()) { vm.setHdr(it) }
}

/** Geometry: Upright, above the manual transform sliders. */
@Composable
internal fun UprightRow(vm: LightCraftViewModel) {
    val d by vm.develop.collectAsStateWithLifecycle()
    Text("Upright", fontSize = 13.sp, color = Lc.label, modifier = Modifier.padding(top = 4.dp))
    Chips(Tools.uprightModes, d.upright) { vm.setUpright(it) }
}

/** Optics: the lens-profile database, which Enable Profile Corrections needs for most lenses. */
@Composable
internal fun LensDbRow(vm: LightCraftViewModel) {
    val db by vm.lensDb.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val text = when {
            db.state == "running" -> "Downloading lens profiles…"
            db.state == "failed" -> "Lens profiles failed to download${if (db.error.isNotEmpty()) ": ${db.error}" else ""}"
            db.lenses > 0 -> "Lens profiles: ${db.lenses} lenses"
            else -> "No lens profiles yet (lensfun database, CC BY-SA 3.0)"
        }
        Text(text, fontSize = 13.sp, color = Lc.muted, modifier = Modifier.weight(1f))
        if (db.state != "running" && db.lenses == 0) SmallButton("Download", accent = true) { vm.downloadLensDb() }
    }
}

/** Profile: the look the photo starts from, by group, and how strongly it applies. */
@Composable
internal fun ProfilePanel(vm: LightCraftViewModel, all: List<Control>) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val d by vm.develop.collectAsStateWithLifecycle()
    val groups = profiles.map { it.group.ifEmpty { "Profiles" } }.distinct()
    var group by remember { mutableStateOf<String?>(null) }
    val open = group ?: profiles.firstOrNull { it.id == d.profile }?.group?.ifEmpty { "Profiles" } ?: groups.firstOrNull()
    Column(Modifier.fillMaxWidth().heightIn(max = 230.dp).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 6.dp)) {
        if (groups.size > 1) Chips(groups.map { it to it }, open) { group = it }
        Chips(profiles.filter { (it.group.ifEmpty { "Profiles" }) == open }.map { it.id to it.name }, d.profile) { vm.applyProfile(it) }
        all.filter { it.id.startsWith("profile.") }.forEach { c ->
            LrSlider(c.label, c.value, c.min, c.max, c.default, c.decimals, c.unit, { vm.setControl(c.id, it) }, vm::finishInteraction, { vm.resetControl(c.id) }, track = c.track)
        }
    }
}

/** Heal: tap a blemish to heal, clone or remove it; red eye; find sensor dust. */
@Composable
internal fun HealPanel(vm: LightCraftViewModel) {
    val h by vm.heal.collectAsStateWithLifecycle()
    val tap by vm.tapMode.collectAsStateWithLifecycle()
    val d by vm.develop.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().heightIn(max = 230.dp).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 6.dp)) {
        Chips(Tools.healModes + ("redeye" to "Red Eye"), if (tap == TapMode.RedEye) "redeye" else h.mode) {
            if (it == "redeye") {
                vm.setTapMode(TapMode.RedEye)
            } else {
                vm.setHeal(h.copy(mode = it))
                if (tap != TapMode.Heal) vm.setTapMode(TapMode.Heal)
            }
        }
        Note(
            if (tap == TapMode.RedEye) {
                "Tap the centre of each red eye."
            } else {
                "Tap what to ${if (h.mode == "fill") "remove" else h.mode}; LightCraft picks the source."
            },
        )
        if (tap != TapMode.RedEye) {
            LrSlider("Size", h.size * 100f, 0.5f, 15f, 2.5f, 1, "%", { vm.setHeal(h.copy(size = it / 100f)) }, {}, { vm.setHeal(h.copy(size = 0.025f)) })
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallButton("Find dust") { vm.findDust() }
            if (d.spots > 0) SmallButton("Delete spot") { vm.deleteSpot() }
            if (d.redEyes > 0) SmallButton("Undo red eye") { vm.deleteRedEye(d.redEyes - 1) }
            Text("${d.spots} spots", fontSize = 13.sp, color = Lc.muted)
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, fontSize = 13.sp, color = Lc.muted, modifier = Modifier.padding(vertical = 2.dp))
}

/** While a picker is on, a tap on the photo goes to it (drags and pinches still zoom and pan). */
@Composable
internal fun PhotoTapLayer(vm: LightCraftViewModel, map: ViewMap) {
    val mode by vm.tapMode.collectAsStateWithLifecycle()
    if (mode == TapMode.None) return
    val m by rememberUpdatedState(map)
    Box(
        Modifier.fillMaxSize().pointerInput(mode) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var moved = false
                while (true) {
                    val ev = awaitPointerEvent()
                    if (ev.changes.size > 1) moved = true
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if ((c.position - down.position).getDistance() > 24f || c.positionChange() != Offset.Zero && moved) moved = true
                    if (!c.pressed) break
                }
                if (!moved) {
                    val p = m.toUv(down.position)
                    if (p.x in 0f..1f && p.y in 0f..1f) vm.tapPhoto(p.x, p.y)
                }
            }
        },
    )
}
