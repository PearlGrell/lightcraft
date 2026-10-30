package ai.storyteller.lightcraft.android.ui

import android.graphics.Bitmap
import android.content.Intent
import androidx.activity.compose.BackHandler
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.AutoFixOff
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.ControlPointDuplicate
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Details
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Gradient
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Healing
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.RemoveRedEye
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Vignette
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.storyteller.lightcraft.android.Control
import ai.storyteller.lightcraft.android.CropState
import ai.storyteller.lightcraft.android.LightCraftViewModel
import ai.storyteller.lightcraft.android.MainTab
import ai.storyteller.lightcraft.android.MaskInfo
import ai.storyteller.lightcraft.android.Picker
import ai.storyteller.lightcraft.android.SEL
import ai.storyteller.lightcraft.android.Tool
import ai.storyteller.lightcraft.android.ToolKind
import ai.storyteller.lightcraft.android.Tools

/**
 * The editor: a black bar (back; undo and redo once something changed; share; more), the photo on
 * black, and at the bottom the six main tabs. It opens with no tab chosen and every tab labelled;
 * choosing one drops the labels, marks it with a blue square and shows its tool row and panel. Crop
 * is a mode of its own: an aspect pill on top, a frame over the photo, and ✕ / ✓ instead of the tabs.
 */
@Composable
fun EditorScreen(vm: LightCraftViewModel, photo: Long, snackbar: SnackbarHostState) {
    val preview by vm.preview.collectAsStateWithLifecycle()
    val before by vm.before.collectAsStateWithLifecycle()
    val cutout by vm.cutout.collectAsStateWithLifecycle()
    val tab by vm.tab.collectAsStateWithLifecycle()
    val tool by vm.tool.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var compare by remember(photo) { mutableStateOf(false) }
    var split by remember(photo) { mutableFloatStateOf(0.5f) }
    var holding by remember { mutableStateOf(false) }
    var zoom by remember(photo) { mutableFloatStateOf(1f) }
    var pan by remember(photo) { mutableStateOf(Offset.Zero) }
    var area by remember { mutableStateOf(IntSize.Zero) }
    val canUndo by vm.canUndo.collectAsStateWithLifecycle()
    val canRedo by vm.canRedo.collectAsStateWithLifecycle()
    var leaving by remember { mutableStateOf(false) }
    var tilt by remember { mutableFloatStateOf(0f) }
    val requestClose = { if (vm.hasUnsavedEdits()) leaving = true else vm.close() }
    var chosen by remember(photo) { mutableStateOf(false) }
    var aspect by remember(photo) { mutableFloatStateOf(-1f) }
    var cropHelp by remember { mutableStateOf(false) }
    val removing = chosen && tab == MainTab.Remove
    val cropping = chosen && tab == MainTab.Crop
    var naming by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<EditorSheet?>(null) }
    var histogram by rememberSaveable { mutableStateOf(false) }
    val picker by vm.picker.collectAsStateWithLifecycle()
    val maskBrush by vm.maskBrush.collectAsStateWithLifecycle()
    val brush by vm.brush.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val pickObject by vm.pickObject.collectAsStateWithLifecycle()
    val spots by vm.spots.collectAsStateWithLifecycle()
    val crop by vm.crop.collectAsStateWithLifecycle()
    val generating by vm.generating.collectAsStateWithLifecycle()
    val painting = chosen && ((tab == MainTab.Remove && tool?.kind == ToolKind.Brush && !(tool?.id == "fill" && pickObject)) || (tab == MainTab.Masking && maskBrush))
    val tapping = chosen && (picker != Picker.None || painting || (tab == MainTab.Remove && (tool?.id == "eraser" || tool?.id == "redeye" || tool?.id == "fill")))
    var stroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
    // tap tools (pickers, Red Eye, Tap object, Eraser) aim with a loupe: press, slide, lift
    val aiming = tapping && !painting
    var aim by remember { mutableStateOf<Offset?>(null) }
    // crop works on the whole photo at 1:1, and the frame is only drawn there: entering the tool
    // zoomed in would otherwise leave no handles to grab
    LaunchedEffect(cropping) {
        if (cropping) {
            zoom = 1f
            pan = Offset.Zero
        }
    }
    val leaveTab = { commitCrop: Boolean ->
        if (cropping) {
            if (commitCrop) vm.commitCrop() else vm.cancelCrop()
        }
        chosen = false
        vm.selectTab(MainTab.Edit)
    }
    BackHandler(enabled = chosen) { leaveTab(false) }
    BackHandler(enabled = !chosen) { requestClose() }

    Column(Modifier.fillMaxSize().background(Lc.canvas)) {
        if (cropping) {
            CropTopBar(aspectLabel(aspect)) { cropHelp = true }
        } else {
            EditorTopBar(
                vm = vm,
                busy = busy,
                canUndo = canUndo,
                canRedo = canRedo,
                onBack = requestClose,
                compare = compare,
                onCompare = {
                    compare = !compare
                    if (compare) vm.loadBefore()
                },
                onCreatePreset = { naming = true },
                onSheet = { sheet = it },
                histogram = histogram,
                onHistogram = { histogram = !histogram },
            )
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(if (cropping) PaddingValues(horizontal = 22.dp, vertical = 18.dp) else PaddingValues(0.dp))
                .onSizeChanged {
                    area = it
                    vm.setViewport(it.width, it.height)
                },
        ) {
            val shown: Bitmap? = when {
                removing -> cutout ?: preview
                holding -> before ?: preview
                else -> preview
            }
            val fit = shown?.let { fitRect(it.width, it.height, area) }
            // a touch at `o` on screen → 0..1 of the shown photo, undoing the zoom and pan
            val toPhoto = { o: Offset ->
                fit?.let { r ->
                    val c = Offset(area.width / 2f, area.height / 2f)
                    val q = (o - c - pan) / zoom + c
                    Offset(((q.x - r.left) / r.width).coerceIn(0f, 1f), ((q.y - r.top) / r.height).coerceIn(0f, 1f)).takeIf { r.contains(q) }
                }
            }
            val mapper = androidx.compose.runtime.rememberUpdatedState(toPhoto)
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(photo, removing, painting, aiming, cropping) {
                        if (aiming) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                down.consume()
                                aim = down.position
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    change.consume()
                                    aim = change.position
                                }
                                val target = aim
                                aim = null
                                target?.let { t -> mapper.value(t)?.let { q -> vm.tapPhoto(q.x, q.y) } }
                            }
                        } else if (painting) {
                            detectDragGestures(
                                onDragStart = { o -> stroke = listOf(o) },
                                onDragEnd = {
                                    val pts = stroke.mapNotNull { mapper.value(it) }.map { it.x to it.y }
                                    stroke = emptyList()
                                    vm.strokePhoto(pts)
                                },
                                onDragCancel = { stroke = emptyList() },
                            ) { c, _ ->
                                c.consume()
                                stroke = stroke + c.position
                            }
                        } else if (!removing && !cropping) {
                            // not while cropping: this consumes every drag and throws it away at
                            // zoom 1, which is exactly where the crop handles live
                            detectTransformGestures { _, p, z, _ ->
                                zoom = (zoom * z).coerceIn(1f, 8f)
                                pan = if (zoom == 1f) Offset.Zero else pan + p
                            }
                        }
                    }
                    .pointerInput(photo, removing, fit, tapping, zoom, pan, cropping) {
                        detectTapGestures(
                            onDoubleTap = {
                                // zooming while cropping would hide the frame mid-drag
                                if (!removing && !painting && !cropping) {
                                    zoom = if (zoom > 1f) 1f else 2.5f
                                    pan = Offset.Zero
                                }
                            },
                            onTap = { o ->
                                if (!tapping || aiming) return@detectTapGestures
                                val q = mapper.value(o) ?: return@detectTapGestures
                                vm.tapPhoto(q.x, q.y)
                            },
                            onPress = {
                                // a slow grab at a handle must not turn into compare-hold
                                if (removing || tapping || cropping) return@detectTapGestures
                                vm.loadBefore()
                                val released = kotlinx.coroutines.withTimeoutOrNull(320) { tryAwaitRelease() }
                                if (released == null) {
                                    holding = true
                                    tryAwaitRelease()
                                    holding = false
                                }
                            },
                        )
                    },
            ) {
                if (removing && fit != null) Checkerboard(fit)
                val layer = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = pan.x
                    translationY = pan.y
                    // straightening: the photo turns under the finger; the render follows on release
                    if (cropping) rotationZ = tilt
                }
                val b = before
                if (compare && !removing && b != null && preview != null && fit != null) {
                    val cut = fit.left + fit.width * split
                    Image(b.asImageBitmap(), "Before", contentScale = ContentScale.Fit, modifier = layer.drawWithContent { clipRect(right = cut) { this@drawWithContent.drawContent() } })
                    Image(preview?.asImageBitmap() ?: b.asImageBitmap(), "After", contentScale = ContentScale.Fit, modifier = layer.drawWithContent { clipRect(left = cut) { this@drawWithContent.drawContent() } })
                    SplitHandle(fit, split, onMove = { split = it })
                } else if (shown != null) {
                    Image(shown.asImageBitmap(), "Photo", contentScale = ContentScale.Fit, modifier = layer)
                } else {
                    CircularProgressIndicator(Modifier.align(Alignment.Center), color = Lc.accent)
                }
                if (cropping && fit != null && zoom == 1f) CropOverlay(fit, crop, vm)
                if (removing && tool?.kind == ToolKind.Brush && fit != null && zoom == 1f) SpotOutlines(spots, fit)
                if (removing && tool?.id == "fill" && fit != null && zoom == 1f) selection?.let { SelectionOverlay(it, SEL, fit) }
                if (stroke.isNotEmpty() && fit != null) BrushStroke(stroke, brush.size * maxOf(fit.width, fit.height) * zoom, (brush.erase && tab == MainTab.Masking) || (removing && tool?.id == "fill"))
                if (holding && !removing) Pill("BEFORE", Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 16.dp))
                aim?.let { a -> if (shown != null && fit != null) Loupe(shown, a, mapper.value(a)) }
                if (histogram && !cropping) shown?.let { Histogram(it, Modifier.align(Alignment.TopStart).padding(12.dp)) }
                val hint = when {
                    generating -> "Generating with LaMa, this takes a few seconds..."
                    picker == Picker.WhiteBalance -> "Tap something that should be neutral grey"
                    picker == Picker.MaskColor -> "Tap a colour to add it to the mask"
                    painting && tab == MainTab.Masking -> if (brush.erase) "Paint to erase from the mask" else "Paint to add to the mask"
                    chosen && tab == MainTab.Remove && tool?.id == "fill" && pickObject -> "Tap an object to select it"
                    chosen && tab == MainTab.Remove && tool?.id == "fill" -> if (selection == null) "Paint over what you want gone" else null
                    painting -> "Paint over the area"
                    chosen && tab == MainTab.Remove && tool?.id == "redeye" -> "Tap each red eye"
                    else -> null
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = hint != null && stroke.isEmpty(),
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                ) { Pill(hint ?: "", Modifier) }
            }
            LcSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
        }
        when {
            !chosen -> MainBar(tab = null, labels = true) { t ->
                if (t == MainTab.Crop) vm.beginCrop()
                chosen = true
                vm.selectTab(t)
            }
            cropping -> Column(Modifier.fillMaxWidth().background(Lc.chrome)) {
                CropPanel(vm, vm.controls.collectAsStateWithLifecycle().value, aspect, onAspect = { aspect = it }, onTilt = { tilt = it })
                Box(Modifier.fillMaxWidth().height(1.dp).background(Lc.divider))
                CropActionBar(onCancel = { leaveTab(false) }, onDone = { leaveTab(true) })
            }
            else -> BottomChrome(vm, tab, tool) { t ->
                if (t == MainTab.Crop) vm.beginCrop()
                vm.selectTab(t)
            }
        }
    }
    if (cropHelp) {
        AlertDialog(
            onDismissRequest = { cropHelp = false },
            containerColor = Lc.menu,
            title = { Text("Crop", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Lc.text) },
            text = {
                Text(
                    "Pick a ratio under Aspect, drag the dial to straighten, and rotate or flip with the round buttons. ✓ keeps the crop, ✕ puts everything back. Geometry corrects perspective.",
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    color = Lc.label,
                )
            },
            confirmButton = { TextButton(onClick = { cropHelp = false }) { Text("Got it", color = Lc.accentText) } },
        )
    }
    sheet?.let { EditorSheetHost(it, vm) { sheet = null } }
    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            containerColor = Lc.menu,
            shape = RoundedCornerShape(16.dp),
            title = { Text("Save your edits?", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Lc.text) },
            text = {
                Text(
                    "Saved edits stay with this photo in LightCraft and can be undone any time. The original file is never changed.",
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    color = Lc.label,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        leaving = false
                        vm.close()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Lc.accent),
                    shape = RoundedCornerShape(20.dp),
                ) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { leaving = false }) { Text("Keep editing", color = Lc.label) }
                    TextButton(onClick = {
                        leaving = false
                        vm.discardAndClose()
                    }) { Text("Discard", color = Color(0xFFFF6B6B)) }
                }
            },
        )
    }
    if (naming) {
        PresetNameDialog(onDismiss = { naming = false }) {
            naming = false
            vm.createPreset(it)
        }
    }
}

/**
 * The bar above the photo: back on the left; undo, redo, before/after, share and the more menu (save
 * a copy, create a preset, copy and paste settings, reset) on the right.
 */
@Composable
internal fun EditorTopBar(
    vm: LightCraftViewModel?,
    busy: Boolean,
    canUndo: Boolean,
    canRedo: Boolean = false,
    onBack: () -> Unit = { vm?.close() },
    compare: Boolean,
    onCompare: () -> Unit,
    onCreatePreset: () -> Unit,
    onSheet: (EditorSheet) -> Unit = {},
    histogram: Boolean = false,
    onHistogram: () -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    val canPaste = vm?.canPaste?.collectAsStateWithLifecycle()?.value ?: false
    if (resetting) {
        AlertDialog(
            onDismissRequest = { resetting = false },
            containerColor = Lc.menu,
            shape = RoundedCornerShape(8.dp),
            title = { Text("Reset all edits?", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Lc.text) },
            text = { Text("Every adjustment, crop, mask and spot on this photo goes back to the original. You can undo this.", fontSize = 15.sp, lineHeight = 21.sp, color = Lc.label) },
            confirmButton = {
                Button(
                    onClick = {
                        resetting = false
                        vm?.resetAll()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Lc.accent),
                    shape = RoundedCornerShape(16.dp),
                ) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { resetting = false }) { Text("Cancel", color = Lc.label) } },
        )
    }
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().background(Lc.canvas).statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarIcon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to library", onClick = onBack)
        Spacer(Modifier.weight(1f))
        if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(20.dp), strokeWidth = 2.dp, color = Lc.text)
        androidx.compose.animation.AnimatedVisibility(canUndo, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
            BarIcon(Icons.AutoMirrored.Outlined.Undo, "Undo") { vm?.undo() }
        }
        androidx.compose.animation.AnimatedVisibility(canRedo, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
            BarIcon(Icons.AutoMirrored.Outlined.Redo, "Redo") { vm?.redo() }
        }
        if (compare) BarIcon(LcIcons.Compare, "Hide before and after", tint = Lc.accentText, onClick = onCompare)
        BarIcon(Icons.Outlined.Info, "Info and rating") { onSheet(EditorSheet.Info) }
        BarIcon(Icons.Outlined.Share, "Export and share") { onSheet(EditorSheet.Export) }
        Box {
            BarIcon(LcIcons.More, "More") { menu = true }
            DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = Lc.menu, shape = RoundedCornerShape(10.dp)) {
                MenuRow("Save copy to device", Icons.Outlined.Download) { menu = false; vm?.saveToDevice() }
                HorizontalDivider(color = Lc.divider)
                MenuRow(if (compare) "Hide before / after" else "View before / after", Icons.Outlined.Visibility) { menu = false; onCompare() }
                HorizontalDivider(color = Lc.divider)
                MenuRow(if (histogram) "Hide histogram" else "Show histogram", Icons.Outlined.BarChart) { menu = false; onHistogram() }
                HorizontalDivider(color = Lc.divider)
                MenuRow("Versions & history", Icons.Outlined.Bookmarks) { menu = false; onSheet(EditorSheet.History) }
                HorizontalDivider(color = Lc.divider)
                MenuRow("Create Preset", Icons.Outlined.AddCircleOutline) { menu = false; onCreatePreset() }
                HorizontalDivider(color = Lc.divider)
                MenuRow("Copy Settings", Icons.Outlined.ContentCopy) { menu = false; vm?.copySettings() }
                HorizontalDivider(color = Lc.divider)
                MenuRow("Paste Settings", Icons.Outlined.ContentPaste, enabled = canPaste) { menu = false; vm?.pasteSettings() }
                HorizontalDivider(color = Lc.divider)
                MenuRow("Apply from previous", Icons.Outlined.History) { menu = false; vm?.applyPrevious() }
                HorizontalDivider(color = Lc.divider)
                MenuRow("Reset all edits", Icons.Outlined.RestartAlt) { menu = false; resetting = true }
                HorizontalDivider(color = Lc.divider)
                MenuRow("Help", Icons.AutoMirrored.Outlined.HelpOutline) {
                    menu = false
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/storytold/lightcraft/tree/main/apps/lightcraft-android")))
                }
            }
        }
    }
}

@Composable
private fun BarIcon(icon: ImageVector, label: String, tint: Color = Lc.text, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, label, tint = tint, modifier = Modifier.size(24.dp)) }
}

@Composable
private fun PresetNameDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Lc.menu,
        shape = RoundedCornerShape(16.dp),
        title = { Text("Create Preset", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Lc.text) },
        text = {
            Column {
                Text("Saves this photo's settings to Yours.", fontSize = 15.sp, color = Lc.label, modifier = Modifier.padding(bottom = 12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    singleLine = true,
                    placeholder = { Text("Preset name") },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Lc.accent, cursorColor = Lc.accent),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name) },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Lc.accent),
                shape = RoundedCornerShape(20.dp),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Lc.label) } },
    )
}

/** Where a [w]×[h] bitmap lands when fitted into [area]. */
internal fun fitRect(w: Int, h: Int, area: IntSize): Rect? {
    if (w <= 0 || h <= 0 || area.width <= 0 || area.height <= 0) return null
    val s = minOf(area.width / w.toFloat(), area.height / h.toFloat())
    val dw = w * s
    val dh = h * s
    val left = (area.width - dw) / 2f
    val top = (area.height - dh) / 2f
    return Rect(left, top, left + dw, top + dh)
}

@Composable
internal fun Checkerboard(r: Rect) {
    Canvas(Modifier.fillMaxSize()) {
        val cell = 12.dp.toPx()
        clipRect(r.left, r.top, r.right, r.bottom) {
            var y = r.top
            var row = 0
            while (y < r.bottom) {
                var x = r.left
                var col = row % 2
                while (x < r.right) {
                    drawRect(if (col % 2 == 0) Color(0xFF8C8C8C) else Color(0xFFBDBDBD), Offset(x, y), Size(cell, cell))
                    x += cell
                    col++
                }
                y += cell
                row++
            }
        }
    }
}

/** The before/after divider: a white line with a round handle, BEFORE and AFTER labels. */
@Composable
internal fun SplitHandle(fit: Rect, split: Float, onMove: (Float) -> Unit) {
    val density = LocalDensity.current
    val x = fit.left + fit.width * split
    Box(
        Modifier.fillMaxSize().pointerInput(fit) {
            detectDragGestures { c, _ ->
                c.consume()
                onMove(((c.position.x - fit.left) / fit.width).coerceIn(0.02f, 0.98f))
            }
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawLine(Color.White, Offset(x, fit.top), Offset(x, fit.bottom), 1.5.dp.toPx())
            val c = Offset(x, fit.center.y)
            drawCircle(Color.White, 13.dp.toPx(), c)
            drawCircle(Color(0x33000000), 13.dp.toPx(), c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        }
        val top = with(density) { (fit.top).toDp() }
        Pill("BEFORE", Modifier.padding(start = with(density) { fit.left.toDp() } + 12.dp, top = maxOf(top, 0.dp) + 60.dp))
        Pill(
            "AFTER",
            Modifier.align(Alignment.TopEnd).padding(end = with(density) { (fit.left).toDp() } + 12.dp, top = maxOf(top, 0.dp) + 60.dp),
        )
    }
}

@Composable
internal fun Pill(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier.background(Lc.pill, RoundedCornerShape(8.dp)).padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

@Composable
internal fun OverlayIcon(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box(Modifier.size(36.dp).background(Color(0x40000000), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = tint, modifier = Modifier.size(22.dp))
        }
    }
}

// ---- bottom ------------------------------------------------------------------------------------

@Composable
private fun BottomChrome(vm: LightCraftViewModel, tab: MainTab, tool: Tool?, onTab: (MainTab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Lc.chrome).animateContentSize(tween(180))) {
        Panel(vm, tab, tool)
        val row = when (tab) {
            MainTab.Quick -> Tools.quick
            MainTab.Edit -> Tools.edit
            MainTab.Masking -> Tools.masking
            MainTab.Remove -> Tools.remove
            else -> emptyList()
        }
        if (row.isNotEmpty()) ToolRow(row, tool, divideAfter = if (tab == MainTab.Edit) 0 else if (tab == MainTab.Remove) 3 else -1, onSelect = vm::selectTool)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Lc.divider))
        MainBar(tab, labels = false, onSelect = onTab)
    }
}

@Composable
private fun Panel(vm: LightCraftViewModel, tab: MainTab, tool: Tool?) {
    val controls by vm.controls.collectAsStateWithLifecycle()
    when (tab) {
        MainTab.Presets -> PresetPanel(vm)
        MainTab.Crop -> {} // Crop is its own mode (CropPanel in EditorScreen)
        MainTab.Masking -> MaskPanel(vm)
        MainTab.Remove -> when {
            tool?.id == "eraser" -> ErasePanel(vm)
            tool?.id == "fill" -> {
                val brush by vm.brush.collectAsStateWithLifecycle()
                val selection by vm.selection.collectAsStateWithLifecycle()
                val pickObject by vm.pickObject.collectAsStateWithLifecycle()
                val models by vm.models.collectAsStateWithLifecycle()
                val generative by vm.generative.collectAsStateWithLifecycle()
                SelectRemovePanel(
                    selection != null, pickObject, brush, vm::setPickObject, vm::clearSelection, vm::setBrush, vm::removeSelection,
                    models = models,
                    generative = generative,
                    onGenerative = vm::setGenerative,
                    onDownload = vm::downloadPack,
                    onCancelDownload = vm::cancelDownload,
                    onDeleteModel = vm::deletePack,
                )
            }
            tool?.kind == ToolKind.Brush -> {
                val brush by vm.brush.collectAsStateWithLifecycle()
                BrushPanel(
                    hint = when (tool.id) {
                        "heal" -> "Paint over a blemish; it is blended with texture from nearby"
                        "clone" -> "Paint over what to cover; a matching area is copied over it"
                        else -> "Paint over what to remove; it is filled in from its surroundings"
                    },
                    brush = brush,
                    onBrush = vm::setBrush,
                    onDelete = vm::deleteSpot,
                )
            }
        }
        MainTab.Edit -> when (tool?.kind) {
            ToolKind.Curve -> {
                val curves by vm.curves.collectAsStateWithLifecycle()
                CurveEditor(curves, vm::setCurve, vm::finishInteraction, vm::resetCurve)
            }
            ToolKind.Sliders -> SliderPanel(vm, tool, controls)
            ToolKind.Profiles -> ProfilesPanel(vm)
            else -> {}
        }
        MainTab.Quick -> {}
    }
}

@Composable
private fun SliderPanel(vm: LightCraftViewModel, tool: Tool, all: List<Control>) {
    val groups = tool.groups.filter { g -> all.any { g.matches(it.id) } }
    var selected by remember(tool.id) { mutableIntStateOf(0) }
    val group = groups.getOrNull(selected) ?: groups.firstOrNull() ?: return
    val controls = all.filter { group.matches(it.id) }
    val picker by vm.picker.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth()) {
        if (groups.size > 1) Pills(groups.map { it.title }, groups.indexOf(group), { selected = it }, onReset = { vm.resetGroup(group) })
        if (group.title == "Color") {
            val on = picker == Picker.WhiteBalance
            Row(
                Modifier
                    .padding(start = 16.dp, top = 6.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (on) Lc.accent else Lc.raised)
                    .clickable { vm.setPicker(Picker.WhiteBalance) }
                    .padding(start = 10.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Colorize, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Text(if (on) "Tap something neutral grey" else "White balance picker", fontSize = 13.sp, color = Color.White, modifier = Modifier.padding(start = 8.dp))
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(WB_MODES) { (label, mode) ->
                    Text(
                        label,
                        fontSize = 13.sp,
                        color = Lc.label,
                        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Lc.raised).clickable { vm.setWhiteBalance(mode) }.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 210.dp), contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 4.dp)) {
            items(controls, key = { it.id }) { c ->
                LrSlider(
                    c.label, c.value, c.min, c.max, c.default, c.decimals, c.unit,
                    onChange = { vm.setControl(c.id, it) },
                    onDone = vm::finishInteraction,
                    onReset = { vm.resetControl(c.id) },
                    track = trackFor(c.id),
                )
            }
        }
    }
}

/** The white balance presets of the Color panel (`develop.wb` modes). */
private val WB_MODES = listOf(
    "As Shot" to "asShot", "Auto" to "auto", "Daylight" to "daylight", "Cloudy" to "cloudy",
    "Shade" to "shade", "Tungsten" to "tungsten", "Fluorescent" to "fluorescent", "Flash" to "flash",
)

@Composable
internal fun Pills(titles: List<String>, selected: Int, onSelect: (Int) -> Unit, onReset: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 14.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(titles.size) { i ->
                val on = i == selected
                Text(
                    titles[i],
                    fontSize = 14.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) Lc.text else Lc.muted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (on) Lc.raised else Color.Transparent)
                        .clickable { onSelect(i) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        if (onReset != null) {
            Text("Reset", fontSize = 14.sp, color = Lc.label, modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onReset).padding(horizontal = 10.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun MaskPanel(vm: LightCraftViewModel) {
    val masks by vm.masks.collectAsStateWithLifecycle()
    val active by vm.activeMask.collectAsStateWithLifecycle()
    val mask = masks.firstOrNull { it.id == active }
    Column(Modifier.fillMaxWidth()) {
        if (masks.isEmpty()) {
            Text(
                "Select a Subject, the Sky or the Background with on-device AI, paint with a Brush, add a Linear or Radial gradient, or pick a Color or Luminance range.",
                fontSize = 13.sp,
                color = Lc.label,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
            )
            return@Column
        }
        Row(Modifier.fillMaxWidth().height(46.dp).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(masks, key = { it.id }) { m ->
                    val on = m.id == active
                    Text(
                        m.name,
                        fontSize = 14.sp,
                        color = if (on) Color.White else Lc.label,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (on) Lc.accent else Lc.raised)
                            .clickable { vm.selectMask(m.id) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            val overlay by vm.maskOverlay.collectAsStateWithLifecycle()
            IconButton(onClick = { vm.setMaskOverlay(!overlay) }) {
                Icon(if (overlay) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, if (overlay) "Hide the mask overlay" else "Show the mask overlay", tint = if (overlay) Lc.accentText else Lc.label)
            }
            IconButton(onClick = { vm.maskCommand("mask.invert") }) { Icon(Icons.Outlined.InvertColors, "Invert mask", tint = Lc.label) }
            IconButton(onClick = { vm.maskCommand("mask.delete") }) { Icon(Icons.Outlined.Delete, "Delete mask", tint = Lc.label) }
        }
        MaskRefineRow(vm, mask)
        if (mask != null) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 4.dp)) {
                items(Tools.local, key = { it.key }) { s ->
                    LrSlider(
                        s.label, mask.adjust[s.key] ?: 0f, s.min, s.max, 0f, s.decimals, if (s.key == "exposure") "EV" else "",
                        onChange = { vm.setMaskValue(s.key, it) },
                        onDone = vm::finishInteraction,
                        onReset = { vm.setMaskValue(s.key, 0f); vm.finishInteraction() },
                    )
                }
            }
        }
    }
}

@Composable
private fun ErasePanel(vm: LightCraftViewModel) {
    val o by vm.erase.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp)) {
        Text("Tap the photo to erase similar colours", fontSize = 13.sp, color = Lc.label, modifier = Modifier.padding(vertical = 4.dp))
        LrSlider("Tolerance", o.tolerance, 0f, 100f, 32f, 0, "", { vm.setErase(o.copy(tolerance = it)) }, {}, { vm.setErase(o.copy(tolerance = 32f)) })
        LrSlider("Feather", o.feather, 0f, 20f, 0f, 0, "px", { vm.setErase(o.copy(feather = it)) }, {}, { vm.setErase(o.copy(feather = 0f)) })
        LrSlider("Opacity", o.opacity, 0f, 100f, 100f, 0, "%", { vm.setErase(o.copy(opacity = it)) }, {}, { vm.setErase(o.copy(opacity = 100f)) })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Toggle("Contiguous", o.contiguous, Modifier.weight(1f)) { vm.setErase(o.copy(contiguous = it)) }
            Toggle("Anti-alias", o.antiAlias, Modifier.weight(1f)) { vm.setErase(o.copy(antiAlias = it)) }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Sample", fontSize = 15.sp, color = Lc.text, modifier = Modifier.weight(1f))
            listOf("Point", "3×3", "5×5").forEachIndexed { i, label ->
                val on = o.sample == i
                Text(
                    label,
                    fontSize = 13.sp,
                    color = if (on) Color.White else Lc.label,
                    modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(14.dp)).background(if (on) Lc.accent else Lc.raised).clickable { vm.setErase(o.copy(sample = i)) }.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}

@Composable
internal fun Toggle(label: String, on: Boolean, modifier: Modifier, onChange: (Boolean) -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, color = Lc.text, modifier = Modifier.weight(1f))
        Switch(
            checked = on,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Lc.accent, checkedThumbColor = Color.White),
            modifier = Modifier.graphicsLayer { scaleX = 0.8f; scaleY = 0.8f },
        )
        Spacer(Modifier.width(10.dp))
    }
}

private fun iconOf(t: Tool, tab: MainTab): ImageVector = when (tab) {
    MainTab.Quick -> when (t.id) {
        "auto" -> Icons.Outlined.AutoAwesome
        "pop" -> Icons.Outlined.Person
        "sky" -> Icons.Outlined.Cloud
        "bw" -> Icons.Outlined.Contrast
        "cutout" -> Icons.Outlined.ContentCut
        else -> Icons.Outlined.RestartAlt
    }
    MainTab.Masking -> when (t.id) {
        "subject" -> Icons.Outlined.Person
        "background" -> Icons.Outlined.Wallpaper
        "sky" -> Icons.Outlined.Cloud
        "brush" -> Icons.Outlined.Brush
        "linear" -> Icons.Outlined.Gradient
        "colorRange" -> Icons.Outlined.Colorize
        "luminanceRange" -> Icons.Outlined.Contrast
        else -> Icons.Outlined.RadioButtonUnchecked
    }
    MainTab.Remove -> when (t.id) {
        "fill" -> LcIcons.Remove
        "heal" -> Icons.Outlined.Healing
        "clone" -> Icons.Outlined.ControlPointDuplicate
        "redeye" -> Icons.Outlined.RemoveRedEye
        "eraser" -> Icons.Outlined.AutoFixHigh
        "ai" -> Icons.Outlined.AutoAwesome
        "keep" -> Icons.Outlined.Wallpaper
        "undo" -> Icons.AutoMirrored.Outlined.Undo
        "reset" -> Icons.Outlined.Restore
        else -> Icons.Outlined.SaveAlt
    }
    else -> when (t.id) {
        "auto" -> Icons.Outlined.AutoAwesome
        "light" -> Icons.Outlined.WbSunny
        "color" -> Icons.Outlined.Thermostat
        "curve" -> LcIcons.Curve
        "effects" -> Icons.Outlined.Vignette
        "detail" -> Icons.Outlined.Details
        "optics" -> Icons.Outlined.CameraAlt
        "profiles" -> Icons.Outlined.Style
        else -> Icons.Outlined.Straighten
    }
}

/** The labelled tools above the main bar; the open one sits on a lighter rounded square. */
@Composable
internal fun ToolRow(tools: List<Tool>, selected: Tool?, divideAfter: Int, onSelect: (Tool) -> Unit) {
    val tab = when (tools) {
        Tools.quick -> MainTab.Quick
        Tools.masking -> MainTab.Masking
        Tools.remove -> MainTab.Remove
        else -> MainTab.Edit
    }
    LazyRow(
        Modifier.fillMaxWidth().background(Lc.chrome).padding(top = 6.dp, bottom = 2.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        tools.forEachIndexed { i, t ->
            item(key = t.id) {
                val on = selected?.id == t.id
                Column(
                    Modifier
                        .width(66.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (on) Lc.raised else Color.Transparent)
                        .clickable { onSelect(t) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(iconOf(t, tab), contentDescription = null, tint = if (on) Color.White else Lc.icon, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(6.dp))
                    Text(t.label, color = if (on) Lc.text else Lc.label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (i == divideAfter) {
                item(key = "divider") { Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(36.dp).background(Lc.divider)) }
            }
        }
    }
}

/**
 * The six main tabs. Before one is chosen each shows its label under the icon; after, the labels go
 * and the chosen tab's icon sits on a blue rounded square.
 */
@Composable
internal fun MainBar(tab: MainTab?, labels: Boolean, onSelect: (MainTab) -> Unit) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth().background(Lc.chrome).navigationBarsPadding().height(if (labels) 68.dp else 58.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MainTab.entries.forEach { t ->
            val on = t == tab
            Column(
                Modifier.weight(1f).fillMaxHeight().clickable {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    onSelect(t)
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val fill by androidx.compose.animation.animateColorAsState(if (on) Lc.accent else Color.Transparent, tween(160), label = "tab")
                Box(
                    Modifier.size(44.dp, 38.dp).clip(RoundedCornerShape(8.dp)).background(fill),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        when (t) {
                            MainTab.Quick -> Icons.Outlined.AutoFixHigh
                            MainTab.Presets -> LcIcons.Presets
                            MainTab.Crop -> Icons.Outlined.Crop
                            MainTab.Edit -> Icons.Outlined.Tune
                            MainTab.Masking -> LcIcons.Masking
                            MainTab.Remove -> LcIcons.Remove
                        },
                        contentDescription = t.label,
                        tint = if (on) Color.White else Lc.icon,
                        modifier = Modifier.size(24.dp),
                    )
                }
                if (labels) Text(t.label, color = Lc.label, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}



/** The aspect pill's text for a ratio chosen in Crop (-1 = none chosen yet). */
internal fun aspectLabel(ratio: Float): String =
    if (ratio < 0f) "Custom" else Tools.aspects.firstOrNull { it.ratio == ratio }?.label ?: "Custom"

/** Crop's top bar: the current aspect in a pill in the middle, help on the right. */
@Composable
internal fun CropTopBar(aspect: String, onHelp: () -> Unit) {
    Box(Modifier.fillMaxWidth().background(Lc.canvas).statusBarsPadding().height(56.dp)) {
        Text(
            aspect,
            fontSize = 15.sp,
            color = Lc.text,
            modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp)).background(Lc.raised).padding(horizontal = 14.dp, vertical = 6.dp),
        )
        IconButton(onClick = onHelp, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)) {
            Icon(Icons.AutoMirrored.Outlined.HelpOutline, "Crop help", tint = Lc.text, modifier = Modifier.size(24.dp))
        }
    }
}

/**
 * The crop frame over the photo, and the drags that move it. [frame] is where the **uncropped**
 * photo is drawn, [crop] the crop inside it; a drag on a corner, an edge or the middle goes to
 * `crop.drag`, which owns the anchoring, the aspect lock and the image bounds.
 */
@Composable
internal fun CropOverlay(frame: Rect, crop: CropState, vm: LightCraftViewModel) {
    val state = androidx.compose.runtime.rememberUpdatedState(crop)
    val box = cropRect(frame, crop)
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(frame) {
                val reach = 28.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val start = state.value
                    val at = cropRect(frame, start)
                    val handle = cropHandleAt(at, down.position, reach) ?: return@awaitEachGesture
                    down.consume()
                    val from = toStraight(frame, down.position, start.angle)
                    var last = down.position
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        if (change.position != last) {
                            last = change.position
                            vm.cropDrag(handle, from, toStraight(frame, change.position, start.angle), start)
                        }
                    }
                    vm.finishInteraction()
                }
            },
    ) {
        CropFrame(box, dimTo = frame)
    }
}

/** Where [crop] sits inside [frame] on screen. */
private fun cropRect(frame: Rect, crop: CropState): Rect = Rect(
    frame.left + crop.x0 * frame.width,
    frame.top + crop.y0 * frame.height,
    frame.left + crop.x1 * frame.width,
    frame.top + crop.y1 * frame.height,
)

/**
 * A point on screen as 0..1 of the **straightened** frame — the space `crop.drag` works in. That is
 * [frame]'s own 0..1 (a drag may leave the photo, so it is not clamped) turned about the centre by
 * the straighten angle, mirroring `to_straight` in the desktop's crop tool. The frame's screen size
 * stands in for the photo's pixel size: the rotation is about the centre, so the scale cancels.
 */
internal fun toStraight(frame: Rect, o: Offset, angle: Float): Pair<Float, Float> {
    val (w, h) = frame.width.coerceAtLeast(1f) to frame.height.coerceAtLeast(1f)
    val px = o.x - frame.left
    val py = o.y - frame.top
    if (angle == 0f) return px / w to py / h
    val a = Math.toRadians(angle.toDouble())
    val (cos, sin) = kotlin.math.cos(a) to kotlin.math.sin(a)
    val dx = px - w / 2f
    val dy = py - h / 2f
    val rx = dx * cos - dy * sin + w / 2f
    val ry = dx * sin + dy * cos + h / 2f
    return (rx / w).toFloat() to (ry / h).toFloat()
}

/**
 * Which handle [o] grabs, in `crop.drag`'s numbering: 0..3 the corners from the top left clockwise,
 * 4..7 the top, right, bottom and left edges, 8 the whole frame. `null`: nothing there.
 */
internal fun cropHandleAt(box: Rect, o: Offset, reach: Float): Int? {
    val spots = listOf(
        box.topLeft, box.topRight, box.bottomRight, box.bottomLeft,
        Offset(box.center.x, box.top), Offset(box.right, box.center.y), Offset(box.center.x, box.bottom), Offset(box.left, box.center.y),
    )
    // the nearest handle within reach, so a grab between a corner and an edge takes the closer one
    val near = spots.withIndex().filter { (_, p) -> (p - o).getDistance() < reach }.minByOrNull { (_, p) -> (p - o).getDistance() }
    return near?.index ?: 8.takeIf { box.contains(o) }
}

/** The crop frame itself: the area outside it dimmed, a thin border, thirds, corner and edge handles. */
@Composable
internal fun CropFrame(r: Rect, dimTo: Rect? = null) {
    Canvas(Modifier.fillMaxSize()) {
        // the photo outside the crop, dimmed: four bands, so no transparency maths is needed
        dimTo?.let { d ->
            val shade = Color.Black.copy(alpha = 0.45f)
            drawRect(shade, Offset(d.left, d.top), Size(d.width, (r.top - d.top).coerceAtLeast(0f)))
            drawRect(shade, Offset(d.left, r.bottom), Size(d.width, (d.bottom - r.bottom).coerceAtLeast(0f)))
            drawRect(shade, Offset(d.left, r.top), Size((r.left - d.left).coerceAtLeast(0f), r.height))
            drawRect(shade, Offset(r.right, r.top), Size((d.right - r.right).coerceAtLeast(0f), r.height))
        }
        val line = 1.dp.toPx()
        drawRect(Color.White, Offset(r.left, r.top), Size(r.width, r.height), style = androidx.compose.ui.graphics.drawscope.Stroke(line))
        for (i in 1..2) {
            val x = r.left + r.width * i / 3f
            val y = r.top + r.height * i / 3f
            drawLine(Color.White.copy(alpha = 0.45f), Offset(x, r.top), Offset(x, r.bottom), 0.7.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.45f), Offset(r.left, y), Offset(r.right, y), 0.7.dp.toPx())
        }
        val w = 3.dp.toPx()
        val l = 20.dp.toPx()
        val o = w / 2f
        listOf(r.topLeft to Offset(1f, 1f), r.topRight to Offset(-1f, 1f), r.bottomLeft to Offset(1f, -1f), r.bottomRight to Offset(-1f, -1f)).forEach { (c, d) ->
            val p = Offset(c.x + d.x * o, c.y + d.y * o)
            drawLine(Color.White, p, Offset(p.x + d.x * l, p.y), w)
            drawLine(Color.White, p, Offset(p.x, p.y + d.y * l), w)
        }
        val m = 9.dp.toPx()
        drawLine(Color.White, Offset(r.center.x - m, r.top + o), Offset(r.center.x + m, r.top + o), w)
        drawLine(Color.White, Offset(r.center.x - m, r.bottom - o), Offset(r.center.x + m, r.bottom - o), w)
        drawLine(Color.White, Offset(r.left + o, r.center.y - m), Offset(r.left + o, r.center.y + m), w)
        drawLine(Color.White, Offset(r.right - o, r.center.y - m), Offset(r.right - o, r.center.y + m), w)
    }
}

/** Crop's bottom bar: ✕ (cancel), the mode's name, ✓ (done). */
@Composable
internal fun CropActionBar(onCancel: () -> Unit, onDone: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Lc.chrome).navigationBarsPadding().height(58.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, "Cancel crop", tint = Lc.text, modifier = Modifier.size(26.dp)) }
        Text("Crop", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Lc.text, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        IconButton(onClick = onDone) { Icon(Icons.Outlined.Check, "Done", tint = Lc.text, modifier = Modifier.size(26.dp)) }
    }
}


/**
 * Under the mask list: paint to add to the selected mask or erase from it, and — on a colour-range
 * mask — sample more colours. Add and Erase work on any mask, an AI subject included.
 */
@Composable
private fun MaskRefineRow(vm: LightCraftViewModel, mask: MaskInfo?) {
    val painting by vm.maskBrush.collectAsStateWithLifecycle()
    val brush by vm.brush.collectAsStateWithLifecycle()
    val picker by vm.picker.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RefineChip("Add", Icons.Outlined.Brush, painting && !brush.erase) {
                vm.setBrush(brush.copy(erase = false))
                vm.setMaskBrush(!(painting && !brush.erase))
            }
            RefineChip("Erase", Icons.Outlined.AutoFixOff, painting && brush.erase) {
                vm.setBrush(brush.copy(erase = true))
                vm.setMaskBrush(!(painting && brush.erase))
            }
            // only a colour range has somewhere to put a sample; on any other mask the engine
            // would answer "the mask has no colour range"
            if (mask?.hasColorRange == true) {
                RefineChip("Sample color", Icons.Outlined.Colorize, picker == Picker.MaskColor) { vm.setPicker(Picker.MaskColor) }
            }
        }
        if (painting) {
            Box(Modifier.padding(horizontal = 22.dp)) {
                LrSlider("Brush size", brush.size * 100f, 1f, 25f, 5f, 0, "%", { vm.setBrush(brush.copy(size = it / 100f)) }, {}, { vm.setBrush(brush.copy(size = 0.05f)) })
            }
        }
    }
}

@Composable
private fun RefineChip(label: String, icon: ImageVector, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (on) Lc.accent else Lc.raised)
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(16.dp))
        Text(label, fontSize = 13.sp, color = Color.White, modifier = Modifier.padding(start = 6.dp))
    }
}


/** The stroke being painted: its path, and the brush circle at the finger. */
@Composable
private fun BrushStroke(points: List<Offset>, diameter: Float, erase: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        val r = (diameter / 2f).coerceAtLeast(4.dp.toPx())
        val tint = if (erase) Color(0x66FF5A5A) else Color(0x664D93FF)
        val path = androidx.compose.ui.graphics.Path()
        points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
        drawPath(path, tint, style = androidx.compose.ui.graphics.drawscope.Stroke(width = r * 2f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        val last = points.last()
        drawCircle(Color.White, r, last, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
        drawCircle(Color(0x88000000), r + 1.5.dp.toPx(), last, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
    }
}


/**
 * The spots of Remove, Heal and Clone over the photo: each painted area as a soft white shape with
 * an outline; for Heal and Clone, where its pixels come from (dashed) and a line between the two.
 * The newest spot (the selected one) is drawn brighter.
 */
@Composable
internal fun SpotOutlines(spots: List<ai.storyteller.lightcraft.android.SpotView>, fit: Rect) {
    Canvas(Modifier.fillMaxSize()) {
        val long = maxOf(fit.width, fit.height)
        fun at(p: Pair<Float, Float>) = Offset(fit.left + p.first * fit.width, fit.top + p.second * fit.height)
        spots.forEachIndexed { i, sp ->
            val newest = i == spots.lastIndex
            val r = (sp.radius * long).coerceAtLeast(3.dp.toPx())
            val path = androidx.compose.ui.graphics.Path()
            sp.path.forEachIndexed { k, p -> at(p).let { if (k == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) } }
            if (sp.path.size == 1) path.lineTo(at(sp.path[0]).x + 0.01f, at(sp.path[0]).y)
            val round = androidx.compose.ui.graphics.drawscope.Stroke(r * 2f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
            drawPath(path, Color.White.copy(alpha = if (newest) 0.28f else 0.14f), style = round)
            sp.source?.let { src ->
                val from = androidx.compose.ui.graphics.Path()
                src.forEachIndexed { k, p -> at(p).let { if (k == 0) from.moveTo(it.x, it.y) else from.lineTo(it.x, it.y) } }
                if (src.size == 1) from.lineTo(at(src[0]).x + 0.01f, at(src[0]).y)
                drawPath(from, Color.White.copy(alpha = if (newest) 0.16f else 0.08f), style = round)
                drawLine(
                    Color.White.copy(alpha = if (newest) 0.9f else 0.45f),
                    at(src.first()), at(sp.path.first()),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx())),
                )
                drawCircle(Color.White.copy(alpha = if (newest) 0.9f else 0.45f), r, at(src.first()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
            }
            drawCircle(Color.White.copy(alpha = if (newest) 1f else 0.5f), r, at(sp.path.first()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
        }
    }
}


/**
 * The precision cursor of the tap tools: a magnifier above the finger (below it near the top edge)
 * showing the photo under the finger at 3×, with a crosshair on the exact point that lifting picks.
 */
@Composable
internal fun Loupe(photo: Bitmap, finger: Offset, at: Offset?) {
    val image = remember(photo) { photo.asImageBitmap() }
    Canvas(Modifier.fillMaxSize()) {
        val radius = 58.dp.toPx()
        val lift = 96.dp.toPx()
        val centre = Offset(finger.x.coerceIn(radius + 4f, size.width - radius - 4f), if (finger.y - lift - radius > 0f) finger.y - lift else finger.y + lift)
        // the finger's own spot: a small ring
        drawCircle(Color.White, 10.dp.toPx(), finger, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
        drawCircle(Color(0x99000000), 11.5.dp.toPx(), finger, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        val clip = androidx.compose.ui.graphics.Path().apply { addOval(Rect(centre, radius)) }
        drawCircle(Color.Black, radius, centre)
        if (at != null) {
            // photo pixels per screen pixel inside the loupe: 3× the photo as it is shown
            val shownScale = minOf(size.width / photo.width, size.height / photo.height)
            val src = (radius * 2f / (shownScale * 3f)).coerceAtLeast(4f)
            val sx = (at.x * photo.width - src / 2f).toInt()
            val sy = (at.y * photo.height - src / 2f).toInt()
            drawContext.canvas.save()
            drawContext.canvas.clipPath(clip)
            drawImage(
                image,
                srcOffset = androidx.compose.ui.unit.IntOffset(sx, sy),
                srcSize = androidx.compose.ui.unit.IntSize(src.toInt(), src.toInt()),
                dstOffset = androidx.compose.ui.unit.IntOffset((centre.x - radius).toInt(), (centre.y - radius).toInt()),
                dstSize = androidx.compose.ui.unit.IntSize((radius * 2).toInt(), (radius * 2).toInt()),
                filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
            )
            drawContext.canvas.restore()
        }
        val arm = 12.dp.toPx()
        val gap = 4.dp.toPx()
        val w = 1.5.dp.toPx()
        for ((dx, dy) in listOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f)) {
            drawLine(Color.Black, Offset(centre.x + dx * gap, centre.y + dy * gap), Offset(centre.x + dx * (gap + arm), centre.y + dy * (gap + arm)), w + 2f)
            drawLine(Color.White, Offset(centre.x + dx * gap, centre.y + dy * gap), Offset(centre.x + dx * (gap + arm), centre.y + dy * (gap + arm)), w)
        }
        drawCircle(Color.White, radius, centre, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        drawCircle(Color(0x66000000), radius + 2.dp.toPx(), centre, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
    }
}
