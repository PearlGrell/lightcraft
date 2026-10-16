package ai.storyteller.lightcraft.android.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.storyteller.lightcraft.android.Gallery
import ai.storyteller.lightcraft.android.GalleryImage
import ai.storyteller.lightcraft.android.LightCraftViewModel
import ai.storyteller.lightcraft.android.R

private const val MIN_COLUMNS = 2
private const val MAX_COLUMNS = 6

/** The phone's gallery as the library: every image, newest first; pinch for density, tap to edit. */
@Composable
fun LibraryScreen(vm: LightCraftViewModel, snackbar: SnackbarHostState) {
    val photos by vm.photos.collectAsStateWithLifecycle()
    val access by vm.access.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val version by vm.thumbVersion.collectAsStateWithLifecycle()
    var columns by rememberSaveable { mutableIntStateOf(4) }
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

    Box(Modifier.fillMaxSize().background(Lc.chrome)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(painterResource(R.drawable.ic_brand_mark), contentDescription = "LightCraft", modifier = Modifier.size(28.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("All Photos", style = MaterialTheme.typography.titleMedium)
                    Text(if (access) "${photos.size} photos" else "Gallery access needed", style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { columns = if (columns >= MAX_COLUMNS) MIN_COLUMNS else columns + 1 }) {
                    Icon(Icons.Outlined.GridView, contentDescription = "Grid size", tint = Lc.label)
                }
            }
            if (!ready) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Lc.accent, trackColor = Lc.chrome)
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
                photos.isEmpty() -> Centered("No photos on this device yet", "Photos you take or save appear here.")
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    horizontalArrangement = Arrangement.spacedBy(1.5.dp),
                    verticalArrangement = Arrangement.spacedBy(1.5.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.fillMaxSize().navigationBarsPadding().pinchColumns(columns) { columns = it },
                ) {
                    items(photos, key = { it.id }) { image ->
                        Thumbnail(vm, image, columns, version) { vm.open(image) }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp))
    }
}

@Composable
private fun AccessPrompt(asked: Boolean, onAllow: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, tint = Lc.label, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
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
            colors = ButtonDefaults.buttonColors(containerColor = Lc.accent),
            shape = RoundedCornerShape(20.dp),
        ) { Text(if (asked) "Allow access" else "Continue") }
        if (asked) {
            Spacer(Modifier.height(8.dp))
            Text("If Android no longer asks, this opens Settings: Permissions > Photos and videos.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun Centered(title: String, body: String) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium)
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
private fun Thumbnail(vm: LightCraftViewModel, image: GalleryImage, columns: Int, version: Int, onClick: () -> Unit) {
    var bitmap by remember(image.id) { mutableStateOf<Bitmap?>(vm.cachedThumbnail(image)) }
    LaunchedEffect(image.id, version) {
        vm.thumbnail(image, if (columns <= 2) 512 else 256)?.let { bitmap = it }
    }
    Box(
        Modifier
            .aspectRatio(1f)
            .background(Lc.raised)
            .clickable(onClick = onClick),
    ) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = image.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
