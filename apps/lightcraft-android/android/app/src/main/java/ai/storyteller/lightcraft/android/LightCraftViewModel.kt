package ai.storyteller.lightcraft.android

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
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

/** What the screens show; every change goes through engine commands. */
class LightCraftViewModel(app: Application) : AndroidViewModel(app) {
    private val tag = "LightCraft"

    /** Commands run one at a time, in order, off the main thread; renders run beside them. */
    private val commands = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var engine: Engine? = null

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _photos = MutableStateFlow<List<Long>>(emptyList())
    val photos: StateFlow<List<Long>> = _photos.asStateFlow()

    private val _editing = MutableStateFlow<Long?>(null)
    val editing: StateFlow<Long?> = _editing.asStateFlow()

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
                e.openLibrary(File(app.filesDir, "library").absolutePath, seedDemo = true)
                engine = e
                _photos.value = e.photos().toList()
                _ready.value = true
            } catch (t: Throwable) {
                Log.e(tag, "engine start failed", t)
                messages.trySend("LightCraft could not open its library: ${t.message}")
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            for (draft in renders) renderNow(draft)
        }
    }

    // ---- library -------------------------------------------------------------------------------

    fun cachedThumbnail(photo: Long): Bitmap? = thumbs.get(photo)

    suspend fun thumbnail(photo: Long, edge: Int): Bitmap? = withContext(Dispatchers.Default) {
        thumbs.get(photo) ?: runCatching { engine?.thumbnail(photo, edge) }
            .onFailure { Log.w(tag, "thumbnail $photo", it) }
            .getOrNull()
            ?.also { thumbs.put(photo, it) }
    }

    /** Copy the picked photos into the app's library folder and import them. */
    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(commands) {
            val e = engine ?: return@launch
            _busy.value = true
            try {
                val dir = File(getApplication<Application>().filesDir, "library/originals").apply { mkdirs() }
                val paths = JSONArray()
                for (uri in uris) {
                    runCatching { copyIn(uri, dir) }
                        .onSuccess { paths.put(it.absolutePath) }
                        .onFailure { messages.trySend("Could not read a photo: ${it.message}") }
                }
                if (paths.length() > 0) {
                    e.execute("library.import", JSONObject().put("paths", paths))
                    _photos.value = e.photos().toList()
                    messages.trySend("Imported ${paths.length()} photo${if (paths.length() == 1) "" else "s"}")
                }
            } catch (t: Throwable) {
                messages.trySend("Import failed: ${t.message}")
            } finally {
                _busy.value = false
            }
        }
    }

    private fun copyIn(uri: Uri, dir: File): File {
        val resolver = getApplication<Application>().contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "photo.jpg"
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "photo.jpg" }
        var out = File(dir, safe)
        var n = 1
        while (out.exists()) out = File(dir, "${safe.substringBeforeLast('.')}-${n++}.${safe.substringAfterLast('.', "jpg")}")
        resolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
            ?: throw IllegalStateException("$name is not readable")
        return out
    }

    // ---- editor --------------------------------------------------------------------------------

    fun open(photo: Long) {
        _editing.value = photo
        _preview.value = thumbs.get(photo)
        _before.value = null
        _tool.value = null
        viewModelScope.launch(commands) {
            run { e -> e.execute("library.select", JSONObject().put("ids", JSONArray().put(photo)).put("active", photo)) }
            requestRender(draft = false)
        }
    }

    fun close() {
        val photo = _editing.value
        finishInteraction()
        _editing.value = null
        _tool.value = null
        _preview.value = null
        _before.value = null
        if (photo != null) refreshThumbnail(photo)
    }

    fun setViewport(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || viewport == width to height) return
        viewport = width to height
        if (_editing.value != null) requestRender(draft = false)
    }

    fun selectTool(tool: Tool) {
        if (tool.id == Tools.AUTO) {
            runEdit("develop.auto")
            return
        }
        if (tool.controls.isEmpty()) {
            messages.trySend("${tool.label} is coming in a later milestone")
            return
        }
        _tool.value = if (_tool.value?.id == tool.id) null else tool
        loadControls()
    }

    /** A slider moved: one undo step per drag (`develop.beginInteraction` … `endInteraction`). */
    fun setControl(id: String, value: Float) {
        _controls.value = _controls.value.map { if (it.id == id) it.copy(value = value) else it }
        viewModelScope.launch(commands) {
            run { e ->
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
                run { e -> e.execute("develop.endInteraction") }
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
                run { e ->
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
                run { e -> e.execute("develop.endInteraction") }
            }
            run { e -> e.execute(id, params) }
            loadControlsNow()
            requestRender(draft = false)
        }
    }

    private fun loadControls() {
        viewModelScope.launch(commands) { loadControlsNow() }
    }

    private fun loadControlsNow() {
        val tool = _tool.value ?: return
        val e = engine ?: return
        val all = runCatching { e.execute("develop.controls") as? JSONArray }.getOrNull() ?: return
        val byId = HashMap<String, Control>()
        for (i in 0 until all.length()) {
            val o = all.optJSONObject(i) ?: continue
            runCatching { Control.fromJson(o) }.getOrNull()?.let { byId[it.id] = it }
        }
        _controls.value = tool.controls.mapNotNull { byId[it] }
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

    private fun refreshThumbnail(photo: Long) {
        thumbs.remove(photo)
        _thumbVersion.value++
    }

    /** Run [f] with the engine, reporting a failure in the snackbar instead of crashing. */
    private inline fun run(f: (Engine) -> Unit) {
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
