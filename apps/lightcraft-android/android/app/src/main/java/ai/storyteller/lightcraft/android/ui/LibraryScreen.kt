package ai.storyteller.lightcraft.android.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.storyteller.lightcraft.android.LightCraftViewModel

private const val MIN_COLUMNS = 2
private const val MAX_COLUMNS = 6

/** The photo grid: pinch to change density, tap to edit, add photos from the system picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: LightCraftViewModel, snackbar: SnackbarHostState) {
    val photos by vm.photos.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val version by vm.thumbVersion.collectAsStateWithLifecycle()
    var columns by rememberSaveable { mutableIntStateOf(3) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { vm.import(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LightCraft") },
                actions = {
                    IconButton(onClick = { columns = if (columns >= MAX_COLUMNS) MIN_COLUMNS else columns + 1 }) {
                        Icon(Icons.Filled.GridView, contentDescription = "Grid size")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                icon = { Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null) },
                text = { Text("Add photos") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!ready || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (ready && photos.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No photos yet. Tap Add photos.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .pinchColumns(columns) { columns = it },
            ) {
                items(photos, key = { it }) { photo ->
                    Thumbnail(vm, photo, columns, version) { vm.open(photo) }
                }
            }
        }
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

@Composable
private fun Thumbnail(vm: LightCraftViewModel, photo: Long, columns: Int, version: Int, onClick: () -> Unit) {
    var bitmap by remember(photo) { mutableStateOf<Bitmap?>(vm.cachedThumbnail(photo)) }
    LaunchedEffect(photo, version) {
        vm.thumbnail(photo, if (columns <= 2) 512 else 320)?.let { bitmap = it }
    }
    Box(
        Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = "Photo $photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
