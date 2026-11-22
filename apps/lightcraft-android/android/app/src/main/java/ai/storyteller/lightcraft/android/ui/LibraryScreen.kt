package ai.storyteller.lightcraft.android.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.storyteller.lightcraft.android.Gallery
import ai.storyteller.lightcraft.android.GalleryImage
import ai.storyteller.lightcraft.android.LibrarySource
import ai.storyteller.lightcraft.android.LightCraftViewModel
import ai.storyteller.lightcraft.android.Mark
import ai.storyteller.lightcraft.android.Page
import ai.storyteller.lightcraft.android.R
import ai.storyteller.lightcraft.android.SortOrder
import ai.storyteller.lightcraft.android.ViewMode
import ai.storyteller.lightcraft.android.libraryView
import kotlinx.coroutines.launch

private const val GAP = 4

/**
 * The library, laid out as the desktop's: My Photos / Albums / By Date in a sidebar (a drawer
 * here), the search field and filter on the top bar, the source's name and count over a justified
 * grid with stars, flags and edited marks on the photos, and the bottom bar's view modes, sort,
 * rating, flags and Copy Edit Settings. Press and hold a photo to select it.
 */
@Composable
fun LibraryScreen(vm: LightCraftViewModel, snackbar: SnackbarHostState) {
    val photos by vm.photos.collectAsStateWithLifecycle()
    val trash by vm.trash.collectAsStateWithLifecycle()
    val access by vm.access.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val version by vm.thumbVersion.collectAsStateWithLifecycle()
    val marks by vm.marks.collectAsStateWithLifecycle()
    val source by vm.source.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val mode by vm.viewMode.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val copied by vm.copied.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var minRating by rememberSaveable { mutableIntStateOf(0) }
    var flagFilter by rememberSaveable { mutableStateOf("any") }
    var rowHeight by rememberSaveable { mutableFloatStateOf(130f) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val markOf = { g: GalleryImage -> vm.markOf(g, marks) }
    val shown = remember(photos, trash, marks, source, query, sort, minRating, flagFilter) {
        libraryView(photos, trash, source, query, sort, markOf).filter { g ->
            val m = markOf(g)
            (m?.rating ?: 0) >= minRating && when (flagFilter) {
                "pick" -> m?.flag == "pick"
                "reject" -> m?.flag == "reject"
                "none" -> (m?.flag ?: "none") == "none"
                else -> true
            }
        }
    }
    val selecting = selection.isNotEmpty()
    val deleted = source == LibrarySource.Deleted
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var asked by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true
        vm.refreshGallery()
    }
    LaunchedEffect(Unit) {
        if (!access && !asked) request.launch(Gallery.permissions)
    }
    BackHandler(enabled = selecting) { vm.clearSelection() }
    BackHandler(enabled = !selecting && drawer.isOpen) { scope.launch { drawer.close() } }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Lc.chrome, drawerShape = RoundedCornerShape(0.dp), modifier = Modifier.width(300.dp)) {
                Sidebar(photos, trash, marks, source, vm) { s ->
                    vm.setSource(s)
                    scope.launch { drawer.close() }
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize().background(Lc.gridBg)) {
            Column(Modifier.fillMaxSize()) {
                // the top bar
                Column(Modifier.fillMaxWidth().background(Lc.chrome).statusBarsPadding()) {
                    if (selecting) {
                        SelectionBar(selection.size, deleted, onClose = vm::clearSelection, onAll = { vm.selectAll(shown) }, onShare = vm::shareSelected) {
                            if (deleted) confirm = "forever" else if (Build.VERSION.SDK_INT >= 30) vm.deleteSelected() else confirm = "delete"
                        }
                    } else {
                        TopBar(query, vm::setQuery, minRating, flagFilter, onSidebar = { scope.launch { drawer.open() } }, onRating = { minRating = it }, onFlag = { flagFilter = it })
                    }
                    if (!ready || busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().height(1.dp), color = Lc.accent, trackColor = Lc.chrome)
                    } else {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Lc.divider))
                    }
                }
                // the source's name and count
                Row(Modifier.fillMaxWidth().background(Lc.canvas).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(source.title, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Lc.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (shown.size == 1) "1 photo" else "${shown.size} photos", fontFamily = Inter, fontSize = 13.sp, color = Lc.dim)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        !access -> AccessPrompt(asked) {
                            val activity = context as? Activity
                            val canAsk = !asked || (activity != null && Gallery.permissions.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) })
                            if (canAsk) {
                                request.launch(Gallery.permissions)
                            } else {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                            }
                        }
                        shown.isEmpty() -> Empty(source, query.isNotBlank() || minRating > 0 || flagFilter != "any")
                        else -> Box(Modifier.fillMaxSize().pinchSize(rowHeight) { rowHeight = it }) {
                            PhotoGrid(vm, shown, mode, rowHeight.dp, version, marks, selection, selecting)
                        }
                    }
                    LcSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
                }
                BottomBar(
                    vm, mode, sort, rowHeight, selecting, deleted, copied,
                    rating = shown.filter { it.id in selection }.map { markOf(it)?.rating ?: 0 }.distinct().singleOrNull() ?: 0,
                    flag = shown.filter { it.id in selection }.map { markOf(it)?.flag ?: "none" }.distinct().singleOrNull() ?: "",
                    single = selection.size == 1,
                ) { rowHeight = it }
            }
        }
    }

    when (confirm) {
        "delete", "forever" -> AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = Lc.chrome,
            title = { Text(if (selection.size == 1) "Delete this photo?" else "Delete ${selection.size} photos?", fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Lc.text) },
            text = { Text("It is removed from this phone for good, with its LightCraft edits. This can't be undone.", fontFamily = Inter, fontSize = 14.sp, color = Lc.label) },
            confirmButton = { TextButton(onClick = { val c = confirm; confirm = null; if (c == "forever") vm.deleteSelectedForever() else vm.deleteSelected() }) { Text("Delete", color = Lc.reject, fontFamily = Inter) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel", color = Lc.label, fontFamily = Inter) } },
        )
    }
}

// ---- top bars -----------------------------------------------------------------------------------

@Composable
private fun TopBar(
    query: String,
    onQuery: (String) -> Unit,
    minRating: Int,
    flag: String,
    onSidebar: () -> Unit,
    onRating: (Int) -> Unit,
    onFlag: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        LcIconButton(DeskIcon.Sidebar, "Sidebar", onClick = onSidebar)
        Spacer(Modifier.width(6.dp))
        Row(
            Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(6.dp)).background(Lc.field).border(1.dp, Lc.fieldBorder, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LcIcon(DeskIcon.Search, Lc.dim, 16.dp)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Search Photos", fontFamily = Inter, fontSize = 14.sp, color = Lc.dim)
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = Lc.text),
                    cursorBrush = SolidColor(Lc.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) LcIconButton(DeskIcon.Close, "Clear search", size = 28.dp, iconSize = 14.dp) { onQuery("") }
        }
        Spacer(Modifier.width(6.dp))
        Box {
            LcIconButton(DeskIcon.Filter, "Filter", active = minRating > 0 || flag != "any") { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Lc.chrome) {
                MenuLabel("Rating")
                listOf(0 to "Any rating", 1 to "★ and up", 2 to "★★ and up", 3 to "★★★ and up", 4 to "★★★★ and up", 5 to "★★★★★").forEach { (r, label) ->
                    MenuCheck(label, minRating == r) { onRating(r); menu = false }
                }
                MenuLabel("Flag")
                listOf("any" to "Any flag", "pick" to "Picked", "reject" to "Rejected", "none" to "Unflagged").forEach { (f, label) ->
                    MenuCheck(label, flag == f) { onFlag(f); menu = false }
                }
            }
        }
    }
}

@Composable
internal fun MenuLabel(text: String) {
    Text(text.uppercase(), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Lc.dim, modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp))
}

@Composable
internal fun MenuCheck(label: String, on: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, fontFamily = Inter, fontSize = 14.sp, color = Lc.text) },
        leadingIcon = { Box(Modifier.size(18.dp)) { if (on) LcIcon(DeskIcon.Check, Lc.text, 16.dp) } },
        onClick = onClick,
    )
}

@Composable
private fun SelectionBar(count: Int, deleted: Boolean, onClose: () -> Unit, onAll: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        LcIconButton(DeskIcon.Close, "Cancel selection", onClick = onClose)
        Text("$count selected", fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Lc.text, modifier = Modifier.weight(1f).padding(start = 8.dp))
        LcButton("Select All", Modifier.padding(end = 6.dp), onClick = onAll)
        if (!deleted) LcIconButton(DeskIcon.Share, "Share", onClick = onShare)
        LcIconButton(DeskIcon.Trash, if (deleted) "Delete for good" else "Delete", tint = Lc.reject, onClick = onDelete)
    }
}

// ---- sidebar ------------------------------------------------------------------------------------

@Composable
private fun Sidebar(
    photos: List<GalleryImage>,
    trash: List<GalleryImage>,
    marks: Map<String, Mark>,
    source: LibrarySource,
    vm: LightCraftViewModel,
    onSource: (LibrarySource) -> Unit,
) {
    val markOf = { g: GalleryImage -> vm.markOf(g, marks) }
    val albums = remember(photos) { photos.groupingBy { it.album }.eachCount().toList().sortedByDescending { it.second } }
    val years = remember(photos) {
        val zone = java.time.ZoneId.systemDefault()
        photos.groupingBy { java.time.Instant.ofEpochMilli(it.taken).atZone(zone).year }.eachCount().toList().sortedByDescending { it.first }
    }
    val now = System.currentTimeMillis()
    Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().padding(vertical = 8.dp)) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_brand_mark), contentDescription = null, modifier = Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)))
            Spacer(Modifier.width(10.dp))
            Text("LightCraft", fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Lc.text)
        }
        SideHeader("My Photos")
        SideRow(DeskIcon.Photos, "All Photos", photos.size, source == LibrarySource.All) { onSource(LibrarySource.All) }
        SideRow(DeskIcon.Clock, "Recently Added", photos.count { now - it.added <= 30L * 24 * 3600 * 1000 }, source == LibrarySource.Recent) { onSource(LibrarySource.Recent) }
        SideRow(DeskIcon.FlagPick, "Picks", photos.count { markOf(it)?.flag == "pick" }, source == LibrarySource.Picks) { onSource(LibrarySource.Picks) }
        SideRow(DeskIcon.Sliders, "Edited", photos.count { markOf(it)?.edited == true }, source == LibrarySource.Edited) { onSource(LibrarySource.Edited) }
        if (albums.isNotEmpty()) {
            SideHeader("Albums")
            albums.forEach { (name, n) ->
                SideRow(DeskIcon.Album, name.ifEmpty { "Other" }, n, source == LibrarySource.Album(name)) { onSource(LibrarySource.Album(name)) }
            }
        }
        if (years.isNotEmpty()) {
            SideHeader("By Date")
            years.forEach { (y, n) -> SideRow(DeskIcon.Clock, y.toString(), n, source == LibrarySource.Year(y)) { onSource(LibrarySource.Year(y)) } }
        }
        if (Gallery.hasTrash) {
            Spacer(Modifier.height(10.dp))
            SideRow(DeskIcon.Trash, "Recently Deleted", trash.size, source == LibrarySource.Deleted) { onSource(LibrarySource.Deleted) }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(1.dp).background(Lc.divider))
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(40.dp).clip(RoundedCornerShape(6.dp)).clickable { vm.openPage(Page.Downloads) }.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LcIcon(DeskIcon.Download, Lc.icon, 18.dp)
            Spacer(Modifier.width(12.dp))
            Text("Downloads & Storage", fontFamily = Inter, fontSize = 14.sp, color = Lc.text, modifier = Modifier.weight(1f))
            LcIcon(DeskIcon.ChevronRight, Lc.dim, 14.dp)
        }
    }
}

@Composable
internal fun SideHeader(text: String) {
    Text(text, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Lc.text, modifier = Modifier.padding(start = 18.dp, top = 16.dp, bottom = 6.dp))
}

@Composable
internal fun SideRow(icon: DeskIcon, label: String, count: Int, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .height(40.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (on) Lc.toolActive else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LcIcon(icon, if (on) Lc.text else Lc.icon, 18.dp)
        Spacer(Modifier.width(12.dp))
        Text(label, fontFamily = Inter, fontSize = 14.sp, color = Lc.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(count.toString(), style = Numbers, fontSize = 13.sp, color = Lc.dim)
    }
}

// ---- the grid -----------------------------------------------------------------------------------

/** One row of the justified grid: its photos and the height they share. */
internal data class JustifiedRow(val images: List<GalleryImage>, val height: Float)

/**
 * The desktop's justified layout: photos at their own aspect ratio, filling rows of [width] at
 * about [target] height; the last row keeps the target height instead of stretching.
 */
internal fun justify(images: List<GalleryImage>, width: Float, target: Float, gap: Float): List<JustifiedRow> {
    if (width <= 0f || target <= 0f) return emptyList()
    val rows = ArrayList<JustifiedRow>()
    var row = ArrayList<GalleryImage>()
    var aspects = 0f
    for (g in images) {
        row += g
        aspects += g.aspect
        val h = (width - gap * (row.size - 1)) / aspects
        if (h <= target) {
            rows += JustifiedRow(row, h.coerceAtLeast(target * 0.5f))
            row = ArrayList()
            aspects = 0f
        }
    }
    if (row.isNotEmpty()) rows += JustifiedRow(row, minOf(target, (width - gap * (row.size - 1)) / aspects))
    return rows
}

@Composable
private fun PhotoGrid(
    vm: LightCraftViewModel,
    shown: List<GalleryImage>,
    mode: ViewMode,
    rowHeight: Dp,
    version: Int,
    marks: Map<String, Mark>,
    selection: Set<Long>,
    selecting: Boolean,
) {
    val cell: @Composable (GalleryImage, Modifier, Int) -> Unit = { g, m, edge ->
        Thumbnail(vm, g, edge, version, vm.markOf(g, marks), g.id in selection, selecting, m, onOpen = { vm.open(g, shown) }, onToggle = { vm.toggleSelected(g) })
    }
    when (mode) {
        ViewMode.Square -> {
            val cols = (360f / rowHeight.value * 2.6f).toInt().coerceIn(2, 8)
            LazyVerticalGrid(
                columns = GridCells.Fixed(cols),
                horizontalArrangement = Arrangement.spacedBy(GAP.dp),
                verticalArrangement = Arrangement.spacedBy(GAP.dp),
                contentPadding = PaddingValues(GAP.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(shown, key = { it.id }) { g -> cell(g, Modifier.aspectRatio(1f), 384) }
            }
        }
        ViewMode.Single -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(GAP.dp),
            verticalArrangement = Arrangement.spacedBy(GAP.dp),
        ) {
            items(shown, key = { it.id }) { g -> cell(g, Modifier.fillMaxWidth().aspectRatio(g.aspect.coerceAtLeast(0.6f)), 1080) }
        }
        ViewMode.Justified -> BoxWithConstraints(Modifier.fillMaxSize()) {
            val width = maxWidth.value - GAP * 2
            val rows = remember(shown, width, rowHeight) { justify(shown, width, rowHeight.value, GAP.toFloat()) }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(GAP.dp),
                verticalArrangement = Arrangement.spacedBy(GAP.dp),
            ) {
                items(rows, key = { r -> r.images.first().id }) { r ->
                    Row(Modifier.fillMaxWidth().height(r.height.dp), horizontalArrangement = Arrangement.spacedBy(GAP.dp)) {
                        r.images.forEach { g -> cell(g, Modifier.width((r.height * g.aspect).dp).fillMaxHeight(), if (r.height > 180) 768 else 512) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Thumbnail(
    vm: LightCraftViewModel,
    image: GalleryImage,
    edge: Int,
    version: Int,
    mark: Mark?,
    selected: Boolean,
    selecting: Boolean,
    modifier: Modifier,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
) {
    var bitmap by remember(image.id) { mutableStateOf<Bitmap?>(vm.cachedThumbnail(image)) }
    LaunchedEffect(image.id, version, edge) {
        vm.thumbnail(image, edge)?.let { bitmap = it }
    }
    val haptics = LocalHapticFeedback.current
    Box(
        modifier
            .background(if (selected) Lc.cellSelected else Lc.skeleton)
            .combinedClickable(
                onClick = { if (selecting) onToggle() else onOpen() },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggle()
                },
                onLongClickLabel = "Select",
            ),
    ) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = image.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (mark != null && (mark.rating > 0 || mark.flag != "none")) {
            Row(Modifier.align(Alignment.BottomStart).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (mark.rating > 0) Stars(mark.rating, 10.dp, Lc.star, Color.Transparent)
                if (mark.flag == "pick") LcIcon(DeskIcon.FlagPickFilled, Lc.pick, 12.dp, Modifier.padding(start = 3.dp))
                if (mark.flag == "reject") LcIcon(DeskIcon.FlagReject, Lc.reject, 12.dp, Modifier.padding(start = 3.dp))
            }
        }
        if (mark?.edited == true) {
            LcIcon(DeskIcon.Sliders, Lc.pick, 13.dp, Modifier.align(Alignment.BottomEnd).padding(6.dp), contentDescription = "Edited")
        }
        if (selected) {
            Box(Modifier.fillMaxSize().border(2.dp, Lc.pick))
        }
    }
}

// ---- the bottom bar -----------------------------------------------------------------------------

@Composable
private fun BottomBar(
    vm: LightCraftViewModel,
    mode: ViewMode,
    sort: SortOrder,
    rowHeight: Float,
    selecting: Boolean,
    deleted: Boolean,
    copied: Boolean,
    rating: Int,
    flag: String,
    single: Boolean,
    onRowHeight: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Lc.chrome).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Lc.divider))
        if (selecting && deleted) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LcButton("Restore", Modifier.weight(1f), onClick = vm::restoreSelected)
                LcButton("Delete for Good", Modifier.weight(1f), onClick = vm::deleteSelectedForever)
            }
        } else if (selecting) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.clip(RoundedCornerShape(18.dp)).background(Lc.inset).border(1.dp, Lc.fieldBorder, RoundedCornerShape(18.dp)).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Stars(rating, 16.dp, Lc.star, Lc.icon, onRate = vm::rateSelected)
                    Spacer(Modifier.width(2.dp))
                    LcIconButton(DeskIcon.FlagPick, "Pick", active = flag == "pick", size = 34.dp, iconSize = 17.dp) { vm.flagSelected("pick") }
                    LcIconButton(DeskIcon.FlagReject, "Reject", active = flag == "reject", tint = if (flag == "reject") Lc.reject else Lc.icon, size = 34.dp, iconSize = 17.dp) { vm.flagSelected("reject") }
                }
                Spacer(Modifier.weight(1f))
                when {
                    copied -> LcButton("Paste Edits", onClick = vm::pasteToSelected)
                    else -> LcButton("Copy Edit Settings", enabled = single, onClick = vm::copyFromSelected)
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            LcIconButton(DeskIcon.GridPhoto, "Justified grid", active = mode == ViewMode.Justified, size = 36.dp) { vm.setViewMode(ViewMode.Justified) }
            LcIconButton(DeskIcon.GridSquare, "Square grid", active = mode == ViewMode.Square, size = 36.dp) { vm.setViewMode(ViewMode.Square) }
            LcIconButton(DeskIcon.Single, "One per row", active = mode == ViewMode.Single, size = 36.dp) { vm.setViewMode(ViewMode.Single) }
            Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(22.dp).background(Lc.buttonBorder))
            var menu by remember { mutableStateOf(false) }
            Box {
                LcIconButton(DeskIcon.Sort, "Sort", size = 36.dp) { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Lc.chrome) {
                    MenuLabel("Sort")
                    SortOrder.entries.forEach { o -> MenuCheck(o.label, sort == o) { vm.setSort(o); menu = false } }
                }
            }
            Spacer(Modifier.weight(1f))
            if (mode != ViewMode.Single) SizeSlider(rowHeight, onRowHeight)
        }
    }
}

/** Two-finger pinch resizes the thumbnails (as the size slider does); one finger still scrolls. */
private fun Modifier.pinchSize(value: Float, set: (Float) -> Unit) = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var v = value
        do {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } >= 2) {
                v = (v * event.calculateZoom()).coerceIn(80f, 260f)
                set(v)
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

/** The desktop's thumbnail-size slider: a short track with a solid thumb. */
@Composable
private fun SizeSlider(value: Float, onChange: (Float) -> Unit) {
    val min = 80f
    val max = 260f
    val density = LocalDensity.current
    Canvas(
        Modifier
            .width(110.dp)
            .height(32.dp)
            .pointerInput(Unit) {
                var v = value
                detectHorizontalDragGestures(onDragStart = { o -> v = min + (o.x / size.width).coerceIn(0f, 1f) * (max - min); onChange(v) }) { c, dx ->
                    c.consume()
                    v = (v + dx / size.width * (max - min)).coerceIn(min, max)
                    onChange(v)
                }
            },
    ) {
        val y = size.height / 2
        drawLine(Lc.track, Offset(0f, y), Offset(size.width, y), with(density) { 2.dp.toPx() })
        val x = (value - min) / (max - min) * size.width
        drawLine(Lc.text, Offset(x, y - with(density) { 7.dp.toPx() }), Offset(x, y + with(density) { 7.dp.toPx() }), with(density) { 2.dp.toPx() })
    }
}

// ---- empty states -------------------------------------------------------------------------------

@Composable
private fun AccessPrompt(asked: Boolean, onAllow: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Lc.canvas).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.ic_brand_mark), contentDescription = null, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)))
        Spacer(Modifier.height(20.dp))
        Text("See your photos in LightCraft", fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Lc.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "LightCraft shows your gallery and keeps your edits separately. Your originals are never changed and nothing leaves your phone.",
            fontFamily = Inter, fontSize = 14.sp, color = Lc.label, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        LcButton(if (asked) "Allow Access" else "Continue", on = true, onClick = onAllow)
        if (asked) {
            Spacer(Modifier.height(10.dp))
            Text("If Android no longer asks, this opens Settings: Permissions > Photos and videos.", fontFamily = Inter, fontSize = 12.sp, color = Lc.dim, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun Empty(source: LibrarySource, filtered: Boolean) {
    val (icon, title, body) = when {
        filtered -> Triple(DeskIcon.Filter, "No photos match", "Change the search or the filter to see more.")
        source == LibrarySource.Picks -> Triple(DeskIcon.FlagPick, "No picks yet", "Select photos and flag them as picks in the bottom bar.")
        source == LibrarySource.Edited -> Triple(DeskIcon.Sliders, "No edited photos", "Photos you adjust in LightCraft are listed here.")
        source == LibrarySource.Deleted -> Triple(DeskIcon.Trash, "Recently Deleted is empty", "Deleted photos stay here for 30 days before Android removes them.")
        else -> Triple(DeskIcon.Photos, "No photos here yet", "Photos you take or save appear here.")
    }
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        LcIcon(icon, Lc.dim, 36.dp)
        Spacer(Modifier.height(14.dp))
        Text(title, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Lc.text)
        Spacer(Modifier.height(4.dp))
        Text(body, fontFamily = Inter, fontSize = 13.sp, color = Lc.dim, textAlign = TextAlign.Center)
    }
}

