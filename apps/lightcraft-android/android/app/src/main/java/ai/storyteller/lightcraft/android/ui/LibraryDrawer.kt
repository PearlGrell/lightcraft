package ai.storyteller.lightcraft.android.ui

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.storyteller.lightcraft.android.LightCraftViewModel
import ai.storyteller.lightcraft.android.R

private const val REPO = "https://github.com/storytold/lightcraft"

/** The pages the drawer and the Welcome pill open, as dialogs over the library. */
enum class InfoPage { Welcome, Preferences, WhatsNew, About, Storage }

/** The side drawer: the app's mark and name, then a list of places and pages, icon and label each. */
@Composable
internal fun LibraryDrawer(onTab: (LibraryTab) -> Unit, onPage: (InfoPage) -> Unit) {
    val context = LocalContext.current
    val open = { url: String -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    ModalDrawerSheet(
        drawerContainerColor = Lc.drawer,
        drawerShape = RectangleShape,
        modifier = Modifier.width(272.dp).fillMaxHeight(),
    ) {
        Row(Modifier.statusBarsPadding().padding(start = 12.dp, end = 12.dp, top = 23.dp, bottom = 23.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(
                painterResource(R.drawable.ic_brand_mark),
                contentDescription = "LightCraft",
                modifier = Modifier.size(58.dp).clip(RoundedCornerShape(10.dp)),
            )
            Button(
                onClick = { onPage(InfoPage.Welcome) },
                colors = ButtonDefaults.buttonColors(containerColor = Lc.accent),
                shape = RoundedCornerShape(19.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.padding(start = 16.dp).fillMaxWidth().height(38.dp),
            ) { Text("Get started", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White) }
        }
        Box(Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(1.dp).background(Lc.divider))
        Spacer(Modifier.height(1.dp))
        DrawerItem(Icons.Filled.Folder, "Albums on device") { onTab(LibraryTab.Albums) }
        DrawerItem(Icons.Filled.Tune, "Edited photos") { onTab(LibraryTab.Edited) }
        DrawerItem(Icons.Filled.Settings, "Preferences") { onPage(InfoPage.Preferences) }
        DrawerItem(Icons.Filled.Campaign, "What's New") { onPage(InfoPage.WhatsNew) }
        DrawerItem(null, "About LightCraft") { onPage(InfoPage.About) }
        DrawerItem(Icons.Filled.PhoneAndroid, "Local storage") { onPage(InfoPage.Storage) }
        DrawerItem(Icons.AutoMirrored.Filled.Help, "Help & Support") { open("$REPO/issues") }
        DrawerItem(Icons.Filled.Star, "Star LightCraft", tint = Lc.star) { open(REPO) }
    }
}

/** A drawer row: a 24dp icon (the app's own mark when [icon] is null) and its label, 46dp tall. */
@Composable
private fun DrawerItem(icon: ImageVector?, label: String, tint: Color = Lc.text, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(46.dp).clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        } else {
            Image(painterResource(R.drawable.ic_brand_mark), null, modifier = Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)))
        }
        Text(label, fontSize = 16.sp, color = Lc.text, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
internal fun InfoDialog(page: InfoPage, vm: LightCraftViewModel, columns: Int, onColumns: (Int) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var storage by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    if (page == InfoPage.Storage) LaunchedEffect(Unit) { storage = vm.storage() }
    val (title, lines) = when (page) {
        InfoPage.Welcome -> "Welcome to LightCraft" to listOf(
            "Tap any photo to start editing. Your originals stay untouched; edits are kept separately and can always be undone.",
            "Edit has Light, Color, Curve, Effects, Detail and Optics. Presets, Crop, Masking and Remove sit next to it.",
            "Press and hold the photo to see the original. Everything runs on your phone.",
        )
        InfoPage.Preferences -> "Preferences" to emptyList()
        InfoPage.WhatsNew -> "What's New" to listOf(
            "A new library: photos by day, device folders as albums, filters and an Edited tab.",
            "Presets by category, with your own presets and favourites.",
            "Crop with aspect ratios, a straighten dial and Geometry.",
            "Save a copy to your gallery, copy and paste settings between photos.",
        )
        InfoPage.About -> "About LightCraft" to listOf(
            "LightCraft is an open-source photo editor. The same Rust engine runs the desktop app, the command line and this phone app.",
            "Licensed MIT or Apache-2.0. $REPO",
        )
        InfoPage.Storage -> "Local storage" to listOf(
            storage?.let { "Edits and catalog: ${Formatter.formatShortFileSize(context, it.first)}" } ?: "Measuring…",
            storage?.let { "Cache and copies: ${Formatter.formatShortFileSize(context, it.second)}" } ?: "",
            "Clearing the cache keeps every edit.",
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Lc.menu,
        shape = RoundedCornerShape(16.dp),
        title = { Text(title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Lc.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                lines.filter { it.isNotEmpty() }.forEach { Text(it, fontSize = 15.sp, lineHeight = 21.sp, color = Lc.label) }
                if (page == InfoPage.Preferences) {
                    Text("Thumbnail size", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Lc.text)
                    listOf("Large" to 3, "Medium" to 4, "Small" to 5).forEach { (name, n) ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onColumns(n) }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = columns == n, onClick = { onColumns(n) }, colors = RadioButtonDefaults.colors(selectedColor = Lc.accent))
                            Text(name, fontSize = 16.sp, color = Lc.text)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = Lc.accent), shape = RoundedCornerShape(20.dp)) {
                Text(if (page == InfoPage.Welcome) "Get started" else "Done")
            }
        },
        dismissButton = if (page == InfoPage.Storage) {
            {
                TextButton(onClick = {
                    vm.clearCache()
                    onDismiss()
                }) { Text("Clear cache", color = Lc.accentText) }
            }
        } else {
            null
        },
    )
}
