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
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.storyteller.lightcraft.android.Gallery
import ai.storyteller.lightcraft.android.GalleryImage
import ai.storyteller.lightcraft.android.LibraryFilter
import ai.storyteller.lightcraft.android.LightCraftViewModel
import ai.storyteller.lightcraft.android.Mark
import ai.storyteller.lightcraft.android.R

private const val MIN_COLUMNS = 2
private const val MAX_COLUMNS = 6

/**
 * The phone's gallery as the library: every image, newest first, by day. Pinch for density, tap to
 * edit; press and hold to select, then favourite, paste edits, share or delete the selection.
 */
@Composable
fun LibraryScreen(vm: LightCraftViewModel, snackbar: SnackbarHostState) {
    val photos by vm.photos.collectAsStateWithLifecycle()
    val access by vm.access.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val version by vm.thumbVersion.collectAsStateWithLifecycle()
    val marks by vm.marks.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val copied by vm.copied.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var columns by rememberSaveable { mutableIntStateOf(4) }
    var confirmDelete by remember { mutableStateOf(false) }
    val shown = remember(photos, marks, filter) {
        when (filter) {
            LibraryFilter.All -> photos
            LibraryFilter.Edited -> photos.filter { vm.markOf(it, marks)?.edited == true }
            LibraryFilter.Favourites -> photos.filter { vm.markOf(it, marks)?.favourite == true }
        }
    }
    val grouped = remember(shown) { days(shown) }
    val selecting = selection.isNotEmpty()
    var asked by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true
        vm.refreshGallery()
    }
    // ask once on first launch; after that the prompt below does
    LaunchedEffect(Unit) {
        if (!access && !asked) request.launch(Gallery.permissions)
    }
    BackHandler(enabled = selecting) { vm.clearSelection() }

    Box(Modifier.fillMaxSize().background(Lc.canvas)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            if (selecting) {
                SelectionBar(
                    count = selection.size,
                    copied = copied,
                    allFavourite = photos.filter { it.id in selection }.all { vm.markOf(it, marks)?.favourite == true },
                    onClose = vm::clearSelection,
                    onSelectAll = { vm.selectAll(shown) },
                    onFavourite = vm::favouriteSelected,
                    onPaste = vm::pasteToSelected,
                    onShare = vm::shareSelected,
                    onDelete = { if (Build.VERSION.SDK_INT >= 30) vm.deleteSelected() else confirmDelete = true },
                )
            } else {
                Header(columns) { columns = if (columns >= MAX_COLUMNS) MIN_COLUMNS else columns + 1 }
            }
            if (access) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LibraryFilter.entries.forEach { f ->
                        FilterChip(f.label, f == filter) { vm.setFilter(f) }
                        Spacer(Modifier.width(6.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(count(shown.size), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!ready || busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Lc.accent, trackColor = Lc.canvas)
            } else {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Lc.divider))
            }
            when {
                !access -> AccessPrompt(asked) {
                    val activity = context as? Activity
                    val canAsk = !asked || (activity != null && Gallery.permissions.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) })
                    if (canAsk) {
                        request.launch(Gallery.permissions)
                    } else {
                        // "don't ask again": only Settings can grant it now; onResume re-reads the gallery
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                    }
                }
                photos.isEmpty() -> Empty(Icons.Outlined.PhotoLibrary, "No photos on this device yet", "Photos you take or save appear here.")
                shown.isEmpty() && filter == LibraryFilter.Edited -> Empty(Icons.Outlined.Tune, "No edited photos", "Photos you adjust in LightCraft are listed here.")
                shown.isEmpty() -> Empty(Icons.Outlined.FavoriteBorder, "No favourites yet", "Press and hold photos, then tap the heart. In the editor, the heart is under the info button.")
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(bottom = 96.dp),
                    modifier = Modifier.fillMaxSize().pinchColumns(columns) { columns = it },
                ) {
                    for ((day, images) in grouped) {
                        item(key = "day-$day", span = { GridItemSpan(maxLineSpan) }) {
                            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                                Text(day, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                if (selecting) {
                                    val all = images.all { it.id in selection }
                                    Text(
                                        if (all) "Deselect" else "Select",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Lc.accent,
                                        modifier = Modifier.clickable { images.filter { (it.id in selection) == all }.forEach(vm::toggleSelected) },
                                    )
                                }
                            }
                        }
                        items(images, key = { it.id }) { image ->
                            Thumbnail(
                                vm, image, columns, version, vm.markOf(image, marks),
                                selected = image.id in selection,
                                selecting = selecting,
                                onOpen = { vm.open(image) },
                                onToggle = { vm.toggleSelected(image) },
                            )
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Lc.bar,
            title = { Text(if (selection.size == 1) "Delete this photo?" else "Delete ${selection.size} photos?", style = MaterialTheme.typography.titleMedium) },
            text = { Text("They are removed from this phone's gallery, with their LightCraft edits.", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteSelected() }) { Text("Delete", color = Lc.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = Lc.label) } },
        )
    }
}

@Composable
private fun Header(columns: Int, onDensity: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(60.dp).padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painterResource(R.drawable.ic_brand_mark),
            contentDescription = null,
            modifier = Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)),
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("LightCraft", style = MaterialTheme.typography.titleLarge)
        }
        IconButton(onClick = onDensity) {
            Icon(Icons.Outlined.GridView, contentDescription = "Grid size: $columns per row", tint = Lc.label)
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    copied: Boolean,
    allFavourite: Boolean,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onFavourite: () -> Unit,
    onPaste: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(60.dp).padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Cancel selection", tint = Lc.text) }
        Text("$count selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        BarIcon(Icons.Outlined.SelectAll, "Select all", onClick = onSelectAll)
        BarIcon(if (allFavourite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, if (allFavourite) "Remove from Favourites" else "Add to Favourites", onClick = onFavourite)
        if (copied) BarIcon(Icons.Outlined.ContentPaste, "Paste edits", onClick = onPaste)
        BarIcon(Icons.Outlined.Share, "Share", onClick = onShare)
        BarIcon(Icons.Outlined.Delete, "Delete", tint = Lc.danger, onClick = onDelete)
    }
}

@Composable
private fun BarIcon(icon: ImageVector, label: String, tint: Color = Lc.text, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun FilterChip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 13.sp,
        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        color = if (on) Lc.onAccent else Lc.label,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (on) Lc.text else Color.Transparent)
            .border(1.dp, if (on) Color.Transparent else Lc.divider, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

private fun count(n: Int) = if (n == 1) "1 photo" else "$n photos"

@Composable
private fun AccessPrompt(asked: Boolean, onAllow: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(painterResource(R.drawable.ic_brand_mark), contentDescription = null, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)))
        Spacer(Modifier.height(20.dp))
        Text("See your photos in LightCraft", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "LightCraft shows your gallery and keeps your edits separately. Your originals are never changed and nothing leaves your phone.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onAllow,
            colors = ButtonDefaults.buttonColors(containerColor = Lc.text, contentColor = Lc.onAccent),
            shape = RoundedCornerShape(10.dp),
        ) { Text(if (asked) "Allow access" else "Continue") }
        if (asked) {
            Spacer(Modifier.height(8.dp))
            Text("If Android no longer asks, this opens Settings: Permissions > Photos and videos.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun Empty(icon: ImageVector, title: String, body: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = Lc.muted, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

/** The photos grouped by the day they were taken, newest first: "Today", "Yesterday", "Wed, Oct 14, 2026". */
private fun days(photos: List<GalleryImage>): List<Pair<String, List<GalleryImage>>> {
    val zone = java.time.ZoneId.systemDefault()
    val today = java.time.LocalDate.now(zone)
    val format = java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")
    return photos.groupBy { java.time.Instant.ofEpochMilli(it.taken).atZone(zone).toLocalDate() }
        .toList()
        .sortedByDescending { it.first }
        .map { (date, images) ->
            val label = when (date) {
                today -> "Today"
                today.minusDays(1) -> "Yesterday"
                else -> date.format(format)
            }
            label to images
        }
}

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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Thumbnail(
    vm: LightCraftViewModel,
    image: GalleryImage,
    columns: Int,
    version: Int,
    mark: Mark?,
    selected: Boolean,
    selecting: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
) {
    var bitmap by remember(image.id) { mutableStateOf<Bitmap?>(vm.cachedThumbnail(image)) }
    LaunchedEffect(image.id, version) {
        vm.thumbnail(image, if (columns <= 2) 512 else 256)?.let { bitmap = it }
    }
    val haptics = LocalHapticFeedback.current
    val inset by animateFloatAsState(if (selected) 0.88f else 1f, label = "select")
    Box(
        Modifier
            .aspectRatio(1f)
            .background(if (selected) Lc.raised else Lc.chrome)
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
            Image(
                it.asImageBitmap(),
                contentDescription = image.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = inset; scaleY = inset }.clip(RoundedCornerShape(if (selected) 4.dp else 0.dp)),
            )
        }
        if (mark != null && (mark.edited || mark.favourite) && columns <= 5) {
            Row(
                Modifier.align(Alignment.BottomStart).padding(5.dp).background(Lc.pill, RoundedCornerShape(5.dp)).padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (mark.favourite) Icon(Icons.Filled.Favorite, "Favourite", tint = Lc.text, modifier = Modifier.size(11.dp))
                if (mark.favourite && mark.edited) Spacer(Modifier.width(3.dp))
                if (mark.edited) Icon(Icons.Outlined.Tune, "Edited", tint = Lc.text, modifier = Modifier.size(11.dp))
            }
        }
        if (selecting) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (selected) Lc.accent else Lc.scrim)
                    .border(1.5.dp, if (selected) Lc.accent else Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Filled.Check, "Selected", tint = Lc.onAccent, modifier = Modifier.size(14.dp))
            }
        }
    }
}
