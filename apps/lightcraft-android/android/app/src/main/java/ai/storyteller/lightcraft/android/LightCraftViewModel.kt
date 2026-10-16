package ai.storyteller.lightcraft.android

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A develop preset (`presets.list`). */
data class Preset(val id: String, val name: String, val group: String)

/** What the screens show; every change goes through engine commands. */
class LightCraftViewModel(app: Application) : AndroidViewModel(app) {
    private val tag = "LightCraft"

    /** Commands run one at a time, in order, off the main thread; renders run beside them. */
    private val commands = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var engine: Engine? = null

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _photos = MutableStateFlow<List<GalleryImage>>(emptyList())
    val photos: StateFlow<List<GalleryImage>> = _photos.asStateFlow()

    private val _access = MutableStateFlow(Gallery.hasAccess(app))
    /** Whether the app may read the gallery; the library asks for it when not. */
    val access: StateFlow<Boolean> = _access.asStateFlow()

    /** Gallery image → its library photo, for the images opened in the editor. */
    private val opened = HashMap<Long, Long>()

    private val _editing = MutableStateFlow<Long?>(null)
    val editing: StateFlow<Long?> = _editing.asStateFlow()

    private val _opening = MutableStateFlow<GalleryImage?>(null)
    /** The gallery image being opened (the editor shows its thumbnail meanwhile). */
    val opening: StateFlow<GalleryImage?> = _opening.asStateFlow()
    private var editingImage: GalleryImage? = null

    private val _preview = MutableStateFlow<Bitmap?>(null)
    val preview: StateFlow<Bitmap?> = _preview.asStateFlow()

    private val _before = MutableStateFlow<Bitmap?>(null)
    val before: StateFlow<Bitmap?> = _before.asStateFlow()

    private val _tool = MutableStateFlow<Tool?>(null)
    val tool: StateFlow<Tool?> = _tool.asStateFlow()

    private val _controls = MutableStateFlow<List<Control>>(emptyList())
    val controls: StateFlow<List<Control>> = _controls.asStateFlow()

    /** Bumped when an edited photo's thumbnail is stale, so grid cells reload. */
    private val _thumbVersion = MutableStateFlow(0)
    val thumbVersion: StateFlow<Int> = _thumbVersion.asStateFlow()

    private val _presets = MutableStateFlow<List<Preset>>(emptyList())
    val presets: StateFlow<List<Preset>> = _presets.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** One-off messages for the snackbar. */
    val messages = Channel<String>(Channel.BUFFERED)

    /** Files to hand to the share sheet. */
    val shares = Channel<File>(Channel.BUFFERED)

    private val thumbs = object : LruCache<Long, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }

    private var viewport = 1080 to 1440
    private val renders = Channel<Boolean>(Channel.CONFLATED) // true = draft
    private var interacting = false

    init {
        viewModelScope.launch(commands) {
            try {
                val e = Engine.create()
                // the edits live here; the photos stay where they are in the gallery
                e.openLibrary(File(app.filesDir, "catalog").absolutePath, seedDemo = false)
                engine = e
                _ready.value = true
            } catch (t: Throwable) {
                Log.e(tag, "engine start failed", t)
                messages.trySend("LightCraft could not open its library: ${t.message}")
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            for (draft in renders) renderNow(draft)
        }
        refreshGallery()
    }

    // ---- library -------------------------------------------------------------------------------

    /** Re-read the gallery (after the permission answer, and whenever the app comes back). */
    fun refreshGallery() {
        val app = getApplication<Application>()
        _access.value = Gallery.hasAccess(app)
        if (!_access.value) {
            _photos.value = emptyList()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { Gallery.images(app) }
                .onSuccess { _photos.value = it }
                .onFailure { messages.trySend("Could not read the gallery: ${it.message}") }
        }
    }

    fun cachedThumbnail(image: GalleryImage): Bitmap? = thumbs.get(image.id)

    /** The engine's thumbnail for photos with edits, the system's for the rest. */
    suspend fun thumbnail(image: GalleryImage, edge: Int): Bitmap? = withContext(Dispatchers.IO) {
        thumbs.get(image.id) ?: run {
            val photo = opened[image.id]
            val bitmap = if (photo != null) {
                runCatching { engine?.thumbnail(photo, edge) }.getOrNull()
            } else {
                Gallery.thumbnail(getApplication(), image, edge)
            }
            bitmap?.also { thumbs.put(image.id, it) }
        }
    }

    /** The file the engine reads: the gallery file itself, or a private copy when it has no readable path. */
    private fun sourceFile(image: GalleryImage): File {
        image.path?.let { File(it) }?.takeIf { it.canRead() }?.let { return it }
        val app = getApplication<Application>()
        val ext = image.name.substringAfterLast('.', "jpg").filter { it.isLetterOrDigit() }.ifEmpty { "jpg" }
        val copy = File(File(app.filesDir, "originals").apply { mkdirs() }, "${image.id}.$ext")
        if (!copy.exists()) {
            app.contentResolver.openInputStream(image.uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } }
                ?: throw IllegalStateException("${image.name} is not readable")
        }
        return copy
    }

    // ---- editor --------------------------------------------------------------------------------

    fun open(image: GalleryImage) {
        _preview.value = thumbs.get(image.id)
        _before.value = null
        _tool.value = null
        _opening.value = image
        viewModelScope.launch(commands) {
            val e = engine ?: return@launch
            try {
                val photo = e.photoForPath(sourceFile(image).absolutePath)
                opened[image.id] = photo
                if (_opening.value != image) return@launch // closed while opening
                editingImage = image
                _editing.value = photo
                e.execute("library.select", JSONObject().put("ids", JSONArray().put(photo)).put("active", photo))
                loadControlsNow()
                requestRender(draft = false)
            } catch (t: Throwable) {
                Log.w(tag, "open ${image.name}", t)
                messages.trySend("Could not open ${image.name}: ${t.message}")
            } finally {
                _opening.value = null
            }
        }
    }

    fun close() {
        val image = editingImage
        _opening.value = null
        finishInteraction()
        _editing.value = null
        _tool.value = null
        _preview.value = null
        _before.value = null
        editingImage = null
        if (image != null) refreshThumbnail(image.id)
    }

    fun setViewport(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || viewport == width to height) return
        viewport = width to height
        if (_editing.value != null) requestRender(draft = false)
    }

    fun selectTool(tool: Tool) {
        when (tool.kind) {
            ToolKind.Auto -> {
                _tool.value = null
                runEdit("develop.auto")
            }
            else -> {
                _tool.value = if (_tool.value?.id == tool.id) null else tool
                if (tool.kind == ToolKind.Presets) loadPresets()
                loadControls()
            }
        }
    }

    /** Back to the defaults of every slider in [group], as one undo step. */
    fun resetGroup(group: Group) {
        val values = JSONObject()
        _controls.value.filter { group.matches(it.id) && it.value != it.default }.forEach { values.put(it.id, it.default.toDouble()) }
        if (values.length() > 0) runEdit("develop.set", JSONObject().put("values", values))
    }

    fun applyPreset(id: String) = runEdit("preset.apply", JSONObject().put("id", id))

    /** Rotate, flip and crop-reset buttons of the Crop tool: engine commands without parameters. */
    fun cropCommand(id: String) = runEdit(id)

    /** A centred crop of [aspect] (width / height) of the uncropped photo, or the whole photo for 0. */
    fun cropAspect(aspect: Float) {
        val photo = _editing.value ?: return
        viewModelScope.launch(commands) {
            val rect = if (aspect <= 0f) {
                listOf(0.0, 0.0, 1.0, 1.0)
            } else {
                // the unedited render has the photo's own proportions (no crop)
                val original = runCatching { engine?.render(photo, 256, 256, before = true) }.getOrNull() ?: return@launch
                val image = original.width.toFloat() / original.height.coerceAtLeast(1)
                val (w, h) = if (aspect < image) aspect / image to 1f else 1f to image / aspect
                val x0 = (1f - w) / 2f
                val y0 = (1f - h) / 2f
                listOf(x0, y0, x0 + w, y0 + h).map { it.toDouble() }
            }
            runEdit("crop.set", JSONObject().put("rect", JSONArray(rect)))
        }
    }

    /** A slider moved: one undo step per drag (`develop.beginInteraction` … `endInteraction`). */
    fun setControl(id: String, value: Float) {
        _controls.value = _controls.value.map { if (it.id == id) it.copy(value = value) else it }
        viewModelScope.launch(commands) {
            withEngine { e ->
                if (!interacting) {
                    val label = _controls.value.firstOrNull { it.id == id }?.label ?: "Edit"
                    e.execute("develop.beginInteraction", JSONObject().put("label", label))
                    interacting = true
                }
                e.execute("develop.set", JSONObject().put("control", id).put("value", value.toDouble()))
            }
            requestRender(draft = true)
        }
    }

    fun finishInteraction() {
        viewModelScope.launch(commands) {
            if (interacting) {
                interacting = false
                withEngine { e -> e.execute("develop.endInteraction") }
            }
            requestRender(draft = false)
        }
    }

    /** Double-tap on a slider: back to its default, as its own undo step. */
    fun resetControl(id: String) {
        val default = _controls.value.firstOrNull { it.id == id }?.default ?: return
        _controls.value = _controls.value.map { if (it.id == id) it.copy(value = default) else it }
        runEdit("develop.set", JSONObject().put("control", id).put("value", default.toDouble()))
    }

    fun undo() = runEdit("edit.undo")
    fun redo() = runEdit("edit.redo")

    /** The unedited look, for press-and-hold. */
    fun loadBefore() {
        val photo = _editing.value ?: return
        if (_before.value != null) return
        viewModelScope.launch(Dispatchers.Default) {
            val (w, h) = viewport
            _before.value = runCatching { engine?.render(photo, w, h, before = true) }.getOrNull()
        }
    }

    fun share() {
        val photo = _editing.value ?: return
        viewModelScope.launch(commands) {
            _busy.value = true
            try {
                withEngine { e ->
                    val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val path = e.export(photo, JSONObject().put("format", "jpeg").put("quality", 90), dir.absolutePath)
                    shares.trySend(File(path))
                }
            } finally {
                _busy.value = false
            }
        }
    }

    private fun runEdit(id: String, params: JSONObject = JSONObject()) {
        viewModelScope.launch(commands) {
            if (interacting) {
                interacting = false
                withEngine { e -> e.execute("develop.endInteraction") }
            }
            withEngine { e -> e.execute(id, params) }
            loadControlsNow()
            requestRender(draft = false)
        }
    }

    private fun loadControls() {
        viewModelScope.launch(commands) { loadControlsNow() }
    }

    private fun loadControlsNow() {
        val e = engine ?: return
        val all = runCatching { e.execute("develop.controls") as? JSONArray }.getOrNull() ?: return
        _controls.value = (0 until all.length()).mapNotNull { i -> all.optJSONObject(i)?.let { runCatching { Control.fromJson(it) }.getOrNull() } }
    }

    private fun loadPresets() {
        viewModelScope.launch(commands) {
            val list = runCatching { engine?.execute("presets.list") as? JSONArray }.getOrNull() ?: return@launch
            _presets.value = (0 until list.length()).mapNotNull { i ->
                list.optJSONObject(i)?.let { Preset(it.optString("id"), it.optString("name"), it.optString("group", "")) }
            }.filter { it.id.isNotEmpty() }
        }
    }

    private fun requestRender(draft: Boolean) {
        renders.trySend(draft)
    }

    private fun renderNow(draft: Boolean) {
        val photo = _editing.value ?: return
        val e = engine ?: return
        val (w, h) = viewport
        val t0 = System.nanoTime()
        runCatching { e.render(photo, w, h, draft = draft) }
            .onSuccess {
                if (_editing.value == photo) _preview.value = it
                Log.d(tag, "render ${it.width}x${it.height} draft=$draft ${(System.nanoTime() - t0) / 1_000_000} ms")
            }
            .onFailure { messages.trySend("Render failed: ${it.message}") }
    }

    private fun refreshThumbnail(galleryId: Long) {
        thumbs.remove(galleryId)
        _thumbVersion.value++
    }

    /** Run [f] with the engine, reporting a failure in the snackbar instead of crashing. */
    private inline fun withEngine(f: (Engine) -> Unit) {
        val e = engine ?: return
        try {
            f(e)
        } catch (t: EngineException) {
            messages.trySend(t.message ?: t.kind)
        } catch (t: Exception) {
            Log.e(tag, "engine call failed", t)
            messages.trySend(t.message ?: "Something went wrong")
        }
    }

    override fun onCleared() {
        engine?.close()
        commands.close()
    }
}
