package ai.storyteller.lightcraft.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.storyteller.lightcraft.android.ui.EditorScreen
import ai.storyteller.lightcraft.android.ui.LibraryScreen
import ai.storyteller.lightcraft.android.ui.LightCraftTheme

class MainActivity : ComponentActivity() {
    private val vm: LightCraftViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        // photos taken or deleted while away, and a permission granted in Settings
        vm.refreshGallery()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LightCraftTheme {
                val snackbar = remember { SnackbarHostState() }
                val editing by vm.editing.collectAsStateWithLifecycle()
                val opening by vm.opening.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) {
                    for (m in vm.messages) snackbar.showSnackbar(m)
                }
                LaunchedEffect(Unit) {
                    for (files in vm.shares) {
                        val uris = files.map { FileProvider.getUriForFile(this@MainActivity, "$packageName.files", it) }
                        if (uris.isEmpty()) continue
                        val send = if (uris.size == 1) {
                            Intent(Intent.ACTION_SEND).setType(ExportFormat.mimeOf(files[0].name)).putExtra(Intent.EXTRA_STREAM, uris[0])
                        } else {
                            Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                        }
                        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        startActivity(Intent.createChooser(send, if (uris.size == 1) "Share photo" else "Share ${uris.size} photos"))
                    }
                }
                val photo = editing
                val inLibrary = photo == null && opening == null
                BackHandler(enabled = !inLibrary) { vm.close() }
                Crossfade(targetState = inLibrary, animationSpec = tween(220), label = "screen") { library ->
                    if (library) LibraryScreen(vm, snackbar) else EditorScreen(vm, photo ?: -1L, snackbar)
                }
            }
        }
    }
}
