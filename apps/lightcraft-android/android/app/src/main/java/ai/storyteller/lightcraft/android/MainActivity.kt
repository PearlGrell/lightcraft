package ai.storyteller.lightcraft.android

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.storyteller.lightcraft.android.ui.EditorScreen
import ai.storyteller.lightcraft.android.ui.LibraryScreen
import ai.storyteller.lightcraft.android.ui.LightCraftTheme

class MainActivity : ComponentActivity() {
    private val vm: LightCraftViewModel by viewModels()

    /** The photos of the delete in progress (Android's confirmation is showing). */
    private var deleting: List<GalleryImage> = emptyList()

    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        // Android 10 asks file by file: after a yes, carry on with the rest
        if (Build.VERSION.SDK_INT < 30 && result.resultCode == RESULT_OK) {
            startDelete(deleting)
        } else {
            vm.onDeleteFinished(deleting)
            deleting = emptyList()
        }
    }

    private fun startDelete(images: List<GalleryImage>) {
        deleting = images
        val left = images.filterNot { Gallery.isGone(this, it) }
        val sender = runCatching { Gallery.deleteRequest(this, left) }
            .onFailure { vm.messages.trySend("Could not delete: ${it.message}") }
            .getOrNull()
        if (sender == null) {
            vm.onDeleteFinished(images)
            deleting = emptyList()
        } else {
            deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
        }
    }

    override fun onResume() {
        super.onResume()
        // photos taken or deleted while away, and a permission granted in Settings
        vm.refreshGallery()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // the lynx on espresso until the engine has opened the library (at most ~1.5 s)
        val started = System.nanoTime()
        installSplashScreen().setKeepOnScreenCondition {
            !vm.ready.value && System.nanoTime() - started < 1_500_000_000L
        }
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
                    for (images in vm.deletions) startDelete(images)
                }
                LaunchedEffect(Unit) {
                    for (file in vm.shares) {
                        val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
                        val type = when (file.extension.lowercase()) {
                            "png" -> "image/png"
                            "webp" -> "image/webp"
                            "avif" -> "image/avif"
                            else -> "image/jpeg"
                        }
                        val send = Intent(Intent.ACTION_SEND)
                            .setType(type)
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        startActivity(Intent.createChooser(send, "Share photo"))
                    }
                }
                LaunchedEffect(Unit) {
                    for (uris in vm.shareOriginals) shareOriginals(uris)
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

    /** The selected gallery photos, as they are, to the share sheet. */
    private fun shareOriginals(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        send.setType("image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, if (uris.size == 1) "Share photo" else "Share ${uris.size} photos"))
    }
}
