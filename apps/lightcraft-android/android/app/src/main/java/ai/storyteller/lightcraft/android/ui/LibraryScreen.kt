package ai.storyteller.lightcraft.android.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.storyteller.lightcraft.android.Gallery
import ai.storyteller.lightcraft.android.GalleryImage
import ai.storyteller.lightcraft.android.LightCraftViewModel
import ai.storyteller.lightcraft.android.R
import kotlinx.coroutines.launch

private const val MIN_COLUMNS = 3
private const val MAX_COLUMNS = 7

/** The destinations of the library's bottom bar. */
enum class LibraryTab(val label: String, val icon: ImageVector) {
    Gallery("Library", Icons.Outlined.PhotoLibrary),
    Albums("Albums", Icons.Outlined.Folder),
    Edited("Edited", Icons.Outlined.Tune),
}

/** How the library is laid out: year cards, month cards, day mosaics or every photo. */
enum class LibraryView(val label: String) { Years("Years"), Months("Months"), Days("Days"), All("All Photos") }

/** The filter menu: which kinds of file the grid shows. */
enum class FileKind(val label: String) {
    All("All photos"),
    Raw("Raw"),
    Jpeg("JPEG"),
    Other("PNG, HEIC and others"),
    ;

    fun matches(image: GalleryImage): Boolean {
        val ext = image.name.substringAfterLast('.', "").lowercase()
        return when (this) {
            All -> true
            Raw -> ext in RAW_EXTENSIONS || image.mime.contains("raw", ignoreCase = true)
            Jpeg -> image.mime == "image/jpeg" || ext == "jpg" || ext == "jpeg"
            Other -> !Raw.matches(image) && !Jpeg.matches(image)
        }
    }
}

private val RAW_EXTENSIONS = setOf("dng", "cr2", "cr3", "nef", "nrw", "arw", "srf", "sr2", "raf", "orf", "rw2", "pef", "srw", "x3f", "3fr", "iiq", "erf", "mrw", "kdc", "dcr", "raw")

/** The translucent dark of the floating bars and buttons (photos show through). */

/** A tile of the library: one photo drawn into [modifier] (the screen passes the real thumbnail). */
typealias Tile = @Composable (image: GalleryImage, modifier: Modifier) -> Unit

/**
 * The library, laid out like a photos app: the photos run edge to edge under a header that fades
 * into them (a large title, the date range you are looking at, Select, more, and the app's mark,
 * which opens the drawer), a floating Years / Months / Days / All Photos switcher, and a translucent
 * tab bar (Library, Albums, Edited).
 */
@Composable
fun LibraryScreen(vm: LightCraftViewModel, snackbar: SnackbarHostState) {
    val photos by vm.photos.collectAsStateWithLifecycle()
    val access by vm.access.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val edited by vm.edited.collectAsStateWithLifecycle()
    val version by vm.thumbVersion.collectAsStateWithLifecycle()
    val canPaste by vm.canPaste.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(LibraryTab.Gallery) }
    var view by rememberSaveable { mutableStateOf(LibraryView.All) }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var kind by rememberSaveable { mutableStateOf(FileKind.All) }
    var oldestFirst by rememberSaveable { mutableStateOf(false) }
    var columns by rememberSaveable { mutableIntStateOf(4) }
    var page by remember { mutableStateOf<InfoPage?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val allGrid = rememberLazyGridState()
    val monthList = rememberLazyListState()
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true
        vm.refreshGallery()
    }
    LaunchedEffect(Unit) {
        if (!access && !asked) request.launch(Gallery.permissions)
    }
    val askAccess = {
        val activity = context as? Activity
        val canAsk = !asked || (activity != null && Gallery.permissions.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) })
        if (canAsk) {
            request.launch(Gallery.permissions)
        } else {
            // "don't ask again": only Settings can grant it now; onResume re-reads the gallery
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        }
    }
    val endSelection = {
        selecting = false
        selected = emptySet()
    }
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(enabled = !drawer.isOpen && selecting) { endSelection() }
    BackHandler(enabled = !drawer.isOpen && !selecting && (tab != LibraryTab.Gallery || folder != null)) {
        if (tab != LibraryTab.Gallery) tab = LibraryTab.Gallery else folder = null
    }

    val shown = remember(photos, folder, kind, oldestFirst, tab, edited) {
        photos.filter {
            (tab != LibraryTab.Edited || it.id in edited) &&
                (tab == LibraryTab.Edited || folder == null || it.folder == folder) &&
                kind.matches(it)
        }.let { if (oldestFirst) it.reversed() else it }
    }
    val allFolders = remember(photos) { folders(photos) }
    val tile: Tile = { image, modifier ->
        Thumbnail(
            vm, image, columns, version, modifier,
            selecting = selecting,
            picked = image.id in selected,
            edited = image.id in edited,
            onLongClick = {
                selecting = true
                selected = selected + image.id
            },
        ) {
            if (selecting) selected = if (image.id in selected) selected - image.id else selected + image.id else vm.open(image)
        }
    }
    // the header's second line: what part of the library is on screen
    val visibleDate by remember(shown, view, tab) {
        derivedStateOf {
            when {
                tab == LibraryTab.Albums -> "${allFolders.size} albums"
                shown.isEmpty() -> ""
                view == LibraryView.All || tab == LibraryTab.Edited -> shown.getOrNull(allGrid.firstVisibleItemIndex)?.let { monthLabel(it.taken) } ?: ""
                else -> "%,d photos".format(shown.size)
            }
        }
    }
    val showSwitcher = tab == LibraryTab.Gallery && access
    val pad = PaddingValues(bottom = 16.dp)

    ModalNavigationDrawer(
        drawerState = drawer,
        scrimColor = Color(0x99000000),
        drawerContent = {
            LibraryDrawer(
                onTab = {
                    tab = it
                    scope.launch { drawer.close() }
                },
                onPage = {
                    page = it
                    scope.launch { drawer.close() }
                },
            )
        },
    ) {
        Box(Modifier.fillMaxSize().background(Lc.canvas)) {
          Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().background(Lc.bar).statusBarsPadding()) {
                if (selecting) {
                    SelectionHeader(
                        count = selected.size,
                        canPaste = canPaste,
                        onDone = endSelection,
                        onPaste = {
                            vm.pasteTo(photos.filter { it.id in selected })
                            endSelection()
                        },
                        onShare = {
                            vm.shareMany(photos.filter { it.id in selected })
                            endSelection()
                        },
                    )
                } else {
                    LibraryHeader(
                        title = when (tab) {
                            LibraryTab.Albums -> "Albums"
                            LibraryTab.Edited -> "Edited"
                            LibraryTab.Gallery -> folder ?: "Library"
                        },
                        subtitle = visibleDate,
                        folders = if (tab == LibraryTab.Gallery) allFolders else null,
                        folder = folder,
                        onFolder = { folder = it },
                        kind = kind,
                        onKind = { kind = it },
                        oldestFirst = oldestFirst,
                        onSort = { oldestFirst = it },
                        columns = columns,
                        onColumns = { columns = it },
                        canSelect = tab != LibraryTab.Albums && access,
                        onSelect = { selecting = true },
                        onWhatsNew = { page = InfoPage.WhatsNew },
                        onRefresh = vm::refreshGallery,
                        onAvatar = { scope.launch { drawer.open() } },
                    )
                }
                AnimatedVisibility(showSwitcher && !selecting) { ViewTabs(view) { view = it } }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Lc.hairline))
                if (!ready) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Lc.accent, trackColor = Color.Transparent)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !access -> Box(Modifier.padding(pad)) { NoAccess(askAccess) }
                tab == LibraryTab.Albums -> AlbumsPage(allFolders, photos, pad, tile) {
                    folder = it
                    tab = LibraryTab.Gallery
                    view = LibraryView.All
                }
                shown.isEmpty() -> EmptyLibrary(
                    pad,
                    if (tab == LibraryTab.Edited) "No edits yet" to "Photos you edit in LightCraft show up here."
                    else if (photos.isEmpty()) "No photos yet" to "Photos you take or save appear here."
                    else "Nothing matches" to "Try another album or the All photos filter.",
                )
                tab == LibraryTab.Edited -> AllPhotos(shown, columns, { columns = it }, allGrid, pad, tile)
                else -> AnimatedContent(
                    targetState = view,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                    label = "view",
                ) { v ->
                    when (v) {
                        LibraryView.All -> AllPhotos(shown, columns, { columns = it }, allGrid, pad, tile)
                        LibraryView.Days -> DayMosaics(shown, pad, tile) { firstOfDay ->
                            view = LibraryView.All
                            scope.launch { allGrid.scrollToItem(shown.indexOf(firstOfDay).coerceAtLeast(0)) }
                        }
                        LibraryView.Months -> MonthCards(shown, monthList, pad, tile) { firstOfMonth ->
                            view = LibraryView.All
                            scope.launch { allGrid.scrollToItem(shown.indexOf(firstOfMonth).coerceAtLeast(0)) }
                        }
                        LibraryView.Years -> YearCards(shown, pad, tile) { year ->
                            view = LibraryView.Months
                            val months = months(shown)
                            scope.launch { monthList.scrollToItem(months.indexOfFirst { it.first.startsWith("$year") }.coerceAtLeast(0)) }
                        }
                    }
                }
            }

            }
            BottomTabs(tab) {
                tab = it
                endSelection()
            }
          }
            LcSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 76.dp))
        }
    }
    page?.let { InfoDialog(it, vm, columns, { c -> columns = c }) { page = null } }
}

/** The device folders and their photo counts, the biggest first. */
private fun folders(photos: List<GalleryImage>): List<Pair<String, Int>> =
    photos.groupingBy { it.folder.ifEmpty { "Other" } }.eachCount().toList().sortedByDescending { it.second }

private fun date(ms: Long): java.time.LocalDate = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDate()

private fun monthLabel(ms: Long): String = date(ms).format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy"))

/** Photos by month, in the order given: ("2026-10", photos). */
internal fun months(photos: List<GalleryImage>): List<Pair<String, List<GalleryImage>>> {
    val out = LinkedHashMap<String, MutableList<GalleryImage>>()
    for (p in photos) out.getOrPut(date(p.taken).let { "%04d-%02d".format(it.year, it.monthValue) }) { ArrayList() } += p
    return out.toList()
}

/** Photos by day, in the order given, with labels: Today, Yesterday, Sunday, Oct 11 / Oct 11, 2025. */
internal fun days(photos: List<GalleryImage>): List<Pair<String, List<GalleryImage>>> {
    val today = java.time.LocalDate.now()
    val out = LinkedHashMap<java.time.LocalDate, MutableList<GalleryImage>>()
    for (p in photos) out.getOrPut(date(p.taken)) { ArrayList() } += p
    return out.map { (d, images) ->
        val label = when {
            d == today -> "Today"
            d == today.minusDays(1) -> "Yesterday"
            d.year == today.year -> d.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMM d"))
            else -> d.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy"))
        }
        label to images
    }
}

// ---- header ------------------------------------------------------------------------------------

@Composable
internal fun LibraryHeader(
    title: String,
    subtitle: String,
    folders: List<Pair<String, Int>>?,
    folder: String?,
    onFolder: (String?) -> Unit,
    kind: FileKind,
    onKind: (FileKind) -> Unit,
    oldestFirst: Boolean,
    onSort: (Boolean) -> Unit,
    columns: Int,
    onColumns: (Int) -> Unit,
    canSelect: Boolean,
    onSelect: () -> Unit,
    onWhatsNew: () -> Unit,
    onRefresh: () -> Unit,
    onAvatar: () -> Unit,
) {
    var folderMenu by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    // the app bar: the app's mark and name (they open the drawer), quiet actions on the right
    Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClickLabel = "Open menu", onClick = onAvatar).padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource(R.drawable.ic_brand_mark), contentDescription = "Menu", modifier = Modifier.size(28.dp).clip(RoundedCornerShape(5.dp)))
            Text("LightCraft", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Lc.text, modifier = Modifier.padding(start = 10.dp))
        }
        Spacer(Modifier.weight(1f))
        if (canSelect) QuietIcon(Icons.Outlined.Checklist, "Select photos", onClick = onSelect)
        Box {
            QuietIcon(LcIcons.More, "More options") { menu = true }
            DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = Lc.menu, shape = RoundedCornerShape(4.dp)) {
                MenuRow("Zoom in", enabled = columns > MIN_COLUMNS) { menu = false; onColumns(columns - 1) }
                MenuRow("Zoom out", enabled = columns < MAX_COLUMNS) { menu = false; onColumns(columns + 1) }
                HorizontalDivider(color = Lc.divider)
                MenuRow("What's new") { menu = false; onWhatsNew() }
                MenuRow("Refresh") { menu = false; onRefresh() }
            }
        }
    }
    // the title row: the album (a picker), how much is in it, then filter and sort
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Box {
                Row(
                    Modifier.clip(RoundedCornerShape(4.dp)).clickable(enabled = folders != null) { folderMenu = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Lc.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (folders != null) Icon(Icons.Filled.KeyboardArrowDown, "Choose album", tint = Lc.text, modifier = Modifier.padding(start = 4.dp).size(22.dp))
                }
                if (folders != null) {
                    DropdownMenu(folderMenu, onDismissRequest = { folderMenu = false }, containerColor = Lc.menu, shape = RoundedCornerShape(4.dp)) {
                        MenuRow("All photos", Icons.Outlined.PhotoLibrary, checked = folder == null) { folderMenu = false; onFolder(null) }
                        folders.forEach { (name, count) ->
                            MenuRow("$name  ·  $count", Icons.Outlined.Folder, checked = folder == name) { folderMenu = false; onFolder(name) }
                        }
                    }
                }
            }
            AnimatedContent(subtitle, transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) }, label = "subtitle") { text ->
                Text(text, fontSize = 13.sp, color = Lc.label)
            }
        }
        Box {
            var filterMenu by remember { mutableStateOf(false) }
            QuietIcon(Icons.Filled.FilterAlt, "Filter", tint = if (kind == FileKind.All) Lc.icon else Lc.accentText) { filterMenu = true }
            DropdownMenu(filterMenu, onDismissRequest = { filterMenu = false }, containerColor = Lc.menu, shape = RoundedCornerShape(4.dp)) {
                FileKind.entries.forEach { k -> MenuRow(k.label, checked = k == kind) { filterMenu = false; onKind(k) } }
            }
        }
        Box {
            var sortMenu by remember { mutableStateOf(false) }
            QuietIcon(LcIcons.SortDown, "Sort") { sortMenu = true }
            DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }, containerColor = Lc.menu, shape = RoundedCornerShape(4.dp)) {
                MenuRow("Newest first", checked = !oldestFirst) { sortMenu = false; onSort(false) }
                MenuRow("Oldest first", checked = oldestFirst) { sortMenu = false; onSort(true) }
            }
        }
    }
}

/** A quiet action button: just the icon, a square highlight when pressed. */
@Composable
internal fun QuietIcon(icon: ImageVector, label: String, tint: Color = Lc.icon, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)).clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun SelectionHeader(count: Int, canPaste: Boolean, onDone: () -> Unit, onPaste: () -> Unit, onShare: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        QuietIcon(Icons.Outlined.Close, "Cancel selection", onClick = onDone)
        Text(if (count == 0) "Select photos" else "$count selected", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Lc.text, modifier = Modifier.weight(1f).padding(start = 6.dp))
        QuietIcon(Icons.Outlined.ContentPaste, "Paste settings", tint = if (canPaste && count > 0) Lc.icon else Lc.disabled) { if (canPaste && count > 0) onPaste() }
        QuietIcon(Icons.Outlined.Share, "Share", tint = if (count > 0) Lc.icon else Lc.disabled) { if (count > 0) onShare() }
    }
    Text(
        "Tap photos to add them to the selection",
        fontSize = 13.sp,
        color = Lc.label,
        modifier = Modifier.padding(start = 16.dp, bottom = 10.dp),
    )
}

@Composable
internal fun MenuRow(text: String, icon: ImageVector? = null, checked: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, fontSize = 16.sp, color = if (enabled) Lc.text else Lc.disabled) },
        onClick = onClick,
        enabled = enabled,
        leadingIcon = icon?.let { { Icon(it, null, tint = if (enabled) Lc.icon else Lc.disabled, modifier = Modifier.size(22.dp)) } },
        trailingIcon = if (checked) {
            { Icon(Icons.Outlined.Check, "Selected", tint = Lc.accentText, modifier = Modifier.size(20.dp)) }
        } else {
            null
        },
    )
}

// ---- switcher and tabs -------------------------------------------------------------------------

/** Years · Months · Days · All Photos as tabs; a white line under the current one. */
@Composable
internal fun ViewTabs(view: LibraryView, onSelect: (LibraryView) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
        LibraryView.entries.forEach { v ->
            val on = v == view
            val line by animateColorAsState(if (on) Lc.text else Color.Transparent, tween(160), label = "tab")
            Column(
                Modifier.width(IntrinsicSize.Max).clickable { onSelect(v) }.padding(horizontal = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    v.label,
                    fontSize = 14.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) Lc.text else Lc.label,
                    modifier = Modifier.padding(top = 10.dp, bottom = 9.dp),
                )
                Box(Modifier.fillMaxWidth().height(2.dp).background(line))
            }
        }
    }
}

/** Library, Albums, Edited: icon over label on a grey bar; the current one in blue. */
@Composable
internal fun BottomTabs(tab: LibraryTab, onSelect: (LibraryTab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Lc.bar)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Lc.hairline))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
            LibraryTab.entries.forEach { t ->
                val on = t == tab
                val tint by animateColorAsState(if (on) Lc.accentText else Lc.label, tween(160), label = "nav")
                Column(
                    Modifier.weight(1f).fillMaxSize().clickable { onSelect(t) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(t.icon, null, tint = tint, modifier = Modifier.size(23.dp))
                    Text(t.label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
    }
}

// ---- the four views ----------------------------------------------------------------------------

/** Every photo, edge to edge, newest at the top; pinch to zoom the grid. */
@Composable
internal fun AllPhotos(
    photos: List<GalleryImage>,
    columns: Int,
    onColumns: (Int) -> Unit,
    state: androidx.compose.foundation.lazy.grid.LazyGridState,
    pad: PaddingValues,
    tile: Tile,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = state,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = pad,
        modifier = Modifier.fillMaxSize().pinchColumns(columns, onColumns),
    ) {
        items(photos, key = { it.id }) { image -> tile(image, Modifier.aspectRatio(1f)) }
    }
}

/** Each day as a mosaic: one large photo, two beside it, a row of three; "+N" opens the rest. */
@Composable
internal fun DayMosaics(photos: List<GalleryImage>, pad: PaddingValues, tile: Tile, onMore: (GalleryImage) -> Unit) {
    val grouped = remember(photos) { days(photos) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = pad.calculateBottomPadding()), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        items(grouped, key = { it.first + it.second.first().id }) { (day, images) ->
            Column(Modifier.padding(horizontal = 12.dp)) {
                Row(Modifier.padding(start = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
                    Text(day, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Lc.text)
                    Text("${images.size}", fontSize = 13.sp, color = Lc.muted, modifier = Modifier.padding(start = 8.dp, bottom = 1.dp))
                }
                Mosaic(images, tile) { onMore(images.first()) }
            }
        }
    }
}

@Composable
private fun Mosaic(images: List<GalleryImage>, tile: Tile, onMore: () -> Unit) {
    val gap = 3.dp
    val shape = RoundedCornerShape(4.dp)
    Column(Modifier.fillMaxWidth().clip(shape), verticalArrangement = Arrangement.spacedBy(gap)) {
        when (images.size) {
            1 -> tile(images[0], Modifier.fillMaxWidth().aspectRatio(1.5f))
            2 -> Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                tile(images[0], Modifier.weight(1f).aspectRatio(0.8f))
                tile(images[1], Modifier.weight(1f).aspectRatio(0.8f))
            }
            else -> {
                Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    tile(images[0], Modifier.weight(2f).aspectRatio(1f))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(gap)) {
                        tile(images[1], Modifier.fillMaxWidth().weight(1f))
                        tile(images[2], Modifier.fillMaxWidth().weight(1f))
                    }
                }
                if (images.size > 3) {
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        (3 until 6).forEach { i ->
                            val image = images.getOrNull(i)
                            Box(Modifier.weight(1f).aspectRatio(1f)) {
                                if (image != null) tile(image, Modifier.fillMaxSize())
                                val extra = images.size - 6
                                if (i == 5 && extra > 0) {
                                    Box(
                                        Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = onMore),
                                        contentAlignment = Alignment.Center,
                                    ) { Text("+$extra", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A big rounded photo card with its title over a dark fade at the bottom. */
@Composable
private fun PhotoCard(image: GalleryImage, title: String, caption: String, ratio: Float, titleSize: Int, tile: Tile, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(4.dp)),
    ) {
        tile(image, Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xCC000000)))
                .clickable(onClick = onClick),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
            Text(title, fontSize = titleSize.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(caption, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xCCFFFFFF))
        }
    }
}

@Composable
internal fun MonthCards(photos: List<GalleryImage>, state: androidx.compose.foundation.lazy.LazyListState, pad: PaddingValues, tile: Tile, onOpen: (GalleryImage) -> Unit) {
    val grouped = remember(photos) { months(photos) }
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = pad, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items(grouped, key = { it.first }) { (_, images) ->
            val first = images.first()
            val d = date(first.taken)
            PhotoCard(
                first,
                d.format(java.time.format.DateTimeFormatter.ofPattern("MMMM")),
                "${d.year}  ·  %,d photos".format(images.size),
                1.6f, 24, tile,
            ) { onOpen(first) }
        }
    }
}

@Composable
internal fun YearCards(photos: List<GalleryImage>, pad: PaddingValues, tile: Tile, onOpen: (Int) -> Unit) {
    val grouped = remember(photos) { photos.groupBy { date(it.taken).year }.toList() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pad, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items(grouped, key = { it.first }) { (year, images) ->
            // the year's most recent photo leads
            PhotoCard(images.first(), "$year", "%,d photos".format(images.size), 1.4f, 30, tile) { onOpen(year) }
        }
    }
}

// ---- albums ------------------------------------------------------------------------------------

@Composable
private fun AlbumsPage(folders: List<Pair<String, Int>>, photos: List<GalleryImage>, pad: PaddingValues, tile: Tile, onOpen: (String) -> Unit) {
    val covers = remember(photos) { photos.groupBy { it.folder.ifEmpty { "Other" } }.mapValues { it.value.first() } }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding()),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(folders, key = { it.first }) { (name, count) ->
            Column(Modifier.clip(RoundedCornerShape(4.dp)).clickable { onOpen(name) }) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(4.dp))) {
                    covers[name]?.let { tile(it, Modifier.fillMaxSize()) }
                    Box(Modifier.fillMaxSize().clickable { onOpen(name) })
                }
                Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Lc.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                Text("%,d".format(count), fontSize = 14.sp, color = Lc.muted)
            }
        }
    }
}

// ---- empty and no access -----------------------------------------------------------------------

@Composable
private fun EmptyLibrary(pad: PaddingValues, text: Pair<String, String>) {
    Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 40.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.PhotoLibrary, null, tint = Lc.muted, modifier = Modifier.size(56.dp))
        Text(text.first, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Lc.text, modifier = Modifier.padding(top = 16.dp))
        Text(text.second, fontSize = 15.sp, color = Lc.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
internal fun NoAccess(onAllow: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = Lc.text, modifier = Modifier.size(84.dp))
        Spacer(Modifier.height(22.dp))
        Text("No photo access", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Lc.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(
            "LightCraft needs access to the photos on your device to let you start editing. Your originals are never changed and nothing leaves your phone.",
            fontSize = 16.sp,
            lineHeight = 23.sp,
            color = Lc.label,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onAllow,
            colors = ButtonDefaults.buttonColors(containerColor = Lc.accent),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) { Text("Change permissions", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
    }
}

// ---- thumbnails --------------------------------------------------------------------------------

/** Two-finger pinch changes the column count; one finger still scrolls the grid. */
private fun Modifier.pinchColumns(columns: Int, set: (Int) -> Unit) = pointerInput(columns) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var scale = 1f
        do {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } >= 2) {
                scale *= event.calculateZoom()
                event.changes.forEach { it.consume() }
                val next = when {
                    scale > 1.35f -> columns - 1
                    scale < 0.75f -> columns + 1
                    else -> columns
                }.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
                if (next != columns) {
                    set(next)
                    return@awaitEachGesture
                }
            }
        } while (event.changes.any { it.pressed })
    }
}

/** One photo of the library, loaded at the pixel size it is drawn at and faded in. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Thumbnail(
    vm: LightCraftViewModel,
    image: GalleryImage,
    columns: Int,
    version: Int,
    modifier: Modifier = Modifier,
    selecting: Boolean = false,
    picked: Boolean = false,
    edited: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    // grid cells are a column wide; cards and mosaics are up to the screen wide
    val edge = with(density) { (config.screenWidthDp.dp.toPx() / columns.coerceAtLeast(1)).toInt() }.coerceIn(192, 1024)
    var size by remember { mutableIntStateOf(edge) }
    var bitmap by remember(image.id) { mutableStateOf<Bitmap?>(vm.cachedThumbnail(image)) }
    LaunchedEffect(image.id, version, size) {
        vm.thumbnail(image, size)?.let { bitmap = it }
    }
    val shown by animateFloatAsState(if (bitmap != null) 1f else 0f, tween(200), label = "thumb")
    val zoom by animateFloatAsState(if (picked) 0.88f else 1f, tween(150), label = "pick")
    Box(
        modifier
            .background(Color(0xFF151515))
            .onSizeChanged { size = maxOf(it.width, it.height).coerceIn(192, 1440) }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        bitmap?.let {
            Image(
                it.asImageBitmap(),
                contentDescription = image.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    alpha = shown
                    scaleX = zoom
                    scaleY = zoom
                },
            )
        }
        if (edited && !selecting) {
            Icon(Icons.Outlined.Tune, "Edited", tint = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).size(14.dp))
        }
        if (selecting) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (picked) Lc.accent else Color(0x40000000))
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) { if (picked) Icon(Icons.Outlined.Check, "Selected", tint = Color.White, modifier = Modifier.size(14.dp)) }
        }
    }
}
