package ai.storyteller.lightcraft.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
                    for (file in vm.shares) {
                        val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("image/jpeg")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        startActivity(Intent.createChooser(send, "Share photo"))
                    }
                }
                val photo = editing
                if (photo == null && opening == null) {
                    LibraryScreen(vm, snackbar)
                } else {
                    BackHandler { vm.close() }
                    EditorScreen(vm, photo ?: -1L, snackbar)
                }
            }
        }
    }
}
