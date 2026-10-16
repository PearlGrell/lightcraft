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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A develop preset (`presets.list`). */
data class Preset(val id: String, val name: String, val group: String)

/** A point of a tone curve, both coordinates 0..1. */
data class CurvePoint(val x: Float, val y: Float)

/** A mask of the photo (`develop.get` → `masks`) with its local adjustments. */
data class MaskInfo(val id: Int, val name: String, val adjust: Map<String, Float>) {
    companion object {
        fun fromJson(o: JSONObject): MaskInfo {
            val a = o.optJSONObject("adjust")
            val adjust = a?.keys()?.asSequence()?.associateWith { a.optDouble(it, 0.0).toFloat() } ?: emptyMap()
            return MaskInfo(o.optInt("id"), o.optString("name", "Mask"), adjust)
        }
    }
}

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

    private val _tab = MutableStateFlow(MainTab.Edit)
    val tab: StateFlow<MainTab> = _tab.asStateFlow()

    /** The open tool of the row above the bottom bar (null: none open). */
    private val _tool = MutableStateFlow<Tool?>(null)
    val tool: StateFlow<Tool?> = _tool.asStateFlow()

    private val _masks = MutableStateFlow<List<MaskInfo>>(emptyList())
    val masks: StateFlow<List<MaskInfo>> = _masks.asStateFlow()

    private val _activeMask = MutableStateFlow<Int?>(null)
    val activeMask: StateFlow<Int?> = _activeMask.asStateFlow()

    /** Point curves by channel (`master`, `red`, `green`, `blue`), normalized points. */
    private val _curves = MutableStateFlow<Map<String, List<CurvePoint>>>(emptyMap())
    val curves: StateFlow<Map<String, List<CurvePoint>>> = _curves.asStateFlow()

    /** The background-removal preview (photo with its matte as alpha), while the Remove tab is open. */
    private val _cutout = MutableStateFlow<Bitmap?>(null)
    val cutout: StateFlow<Bitmap?> = _cutout.asStateFlow()

    private val _erase = MutableStateFlow(EraseSettings())
    val erase: StateFlow<EraseSettings> = _erase.asStateFlow()

    private val _presetThumbs = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val presetThumbs: StateFlow<Map<String, Bitmap>> = _presetThumbs.asStateFlow()

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
        _tab.value = MainTab.Edit
        _cutout.value = null
        _presetThumbs.value = emptyMap()
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
                loadState()
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
        endCutout()
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

    fun selectTab(tab: MainTab) {
        if (_tab.value == tab) return
        if (_tab.value == MainTab.Remove) endCutout()
        _tab.value = tab
        _tool.value = when (tab) {
            MainTab.Edit -> Tools.edit[1]
            MainTab.Remove -> Tools.remove[0]
            else -> null
        }
        when (tab) {
            MainTab.Presets -> loadPresets()
            MainTab.Remove -> beginCutout()
            MainTab.Masking -> viewModelScope.launch(commands) { loadState() }
            else -> {}
        }
    }

    /** A tool of the row above the bottom bar. */
    fun selectTool(tool: Tool) {
        when (_tab.value) {
            MainTab.Quick -> quickAction(tool.id)
            MainTab.Masking -> addMask(tool.id)
            MainTab.Remove -> removeAction(tool)
            else -> if (tool.kind == ToolKind.Action) {
                if (tool.id == "auto") runEdit("develop.auto")
            } else {
                _tool.value = if (_tool.value?.id == tool.id) null else tool
            }
        }
    }

    private fun quickAction(id: String) {
        when (id) {
            "auto" -> runEdit("develop.auto")
            "bw" -> runEdit("develop.treatment")
            "reset" -> runEdit("develop.reset")
            "cutout" -> {
                selectTab(MainTab.Remove)
                removeBackground(keepBackground = false)
            }
            "pop" -> viewModelScope.launch(commands) {
                if (subjectMask("Subject pop", invert = false)) {
                    withEngine { e -> e.execute("mask.adjust", JSONObject().put("values", JSONObject().put("exposure", 0.25).put("clarity", 20).put("saturation", 12))) }
                    afterEdit()
                }
            }
            "sky" -> viewModelScope.launch(commands) {
                withEngine { e ->
                    e.execute("mask.add", JSONObject().put("kind", "sky").put("name", "Blue sky"))
                    e.execute("mask.adjust", JSONObject().put("values", JSONObject().put("temp", -18).put("saturation", 25).put("highlights", -20)))
                }
                afterEdit()
            }
        }
    }

    // ---- sliders -------------------------------------------------------------------------------

    /** A slider moved: one undo step per drag (`develop.beginInteraction` … `endInteraction`). */
    fun setControl(id: String, value: Float) {
        _controls.value = _controls.value.map { if (it.id == id) it.copy(value = value) else it }
        viewModelScope.launch(commands) {
            withEngine { e ->
                begin(e, _controls.value.firstOrNull { it.id == id }?.label ?: "Edit")
                e.execute("develop.set", JSONObject().put("control", id).put("value", value.toDouble()))
            }
            requestRender(draft = true)
        }
    }

    private fun begin(e: Engine, label: String) {
        if (!interacting) {
            e.execute("develop.beginInteraction", JSONObject().put("label", label))
            interacting = true
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

    /** Back to the defaults of every slider in [group], as one undo step. */
    fun resetGroup(group: Group) {
        val values = JSONObject()
        _controls.value.filter { group.matches(it.id) && it.value != it.default }.forEach { values.put(it.id, it.default.toDouble()) }
        if (values.length() > 0) runEdit("develop.set", JSONObject().put("values", values))
    }

    fun undo() = if (_tab.value == MainTab.Remove) cutoutStep { it.cutoutUndo() } else runEdit("edit.undo")
    fun redo() = runEdit("edit.redo")

    // ---- curve ---------------------------------------------------------------------------------

    /** The point curve of [channel] moved (live while dragging; one undo step per drag). */
    fun setCurve(channel: String, points: List<CurvePoint>) {
        _curves.value = _curves.value + (channel to points)
        viewModelScope.launch(commands) {
            withEngine { e ->
                begin(e, "Point Curve")
                e.execute("develop.curve", curveParams(channel, points))
            }
            requestRender(draft = true)
        }
    }

    fun resetCurve(channel: String) {
        val line = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))
        _curves.value = _curves.value + (channel to line)
        runEdit("develop.curve", curveParams(channel, line))
    }

    private fun curveParams(channel: String, points: List<CurvePoint>) =
        JSONObject().put("channel", channel).put("points", JSONArray(points.sortedBy { it.x }.map { JSONArray(listOf(it.x.toDouble(), it.y.toDouble())) }))

    // ---- presets -------------------------------------------------------------------------------

    fun applyPreset(id: String) = runEdit("preset.apply", JSONObject().put("id", id))

    private fun loadPresets() {
        viewModelScope.launch(commands) {
            val list = runCatching { engine?.execute("presets.list") as? JSONArray }.getOrNull() ?: return@launch
            _presets.value = (0 until list.length()).mapNotNull { i ->
                list.optJSONObject(i)?.let { Preset(it.optString("id"), it.optString("name"), it.optString("group", "")) }
            }.filter { it.id.isNotEmpty() }
        }
    }

    /** Thumbnails for the presets strip, rendered on demand and kept for this photo. */
    fun loadPresetThumbs(ids: List<String>) {
        val photo = _editing.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            for (id in ids) {
                if (_presetThumbs.value.containsKey(id) || _editing.value != photo) continue
                val bmp = runCatching { engine?.presetThumbnail(photo, id, 160) }.getOrNull() ?: continue
                _presetThumbs.value = _presetThumbs.value + (id to bmp)
            }
        }
    }

    // ---- crop ----------------------------------------------------------------------------------

    /** Rotate, flip and crop-reset buttons of the Crop tool: engine commands without parameters. */
    fun cropCommand(id: String) = runEdit(id)

    /** A centred crop of [aspect] (width / height) of the uncropped photo, or the whole photo for 0. */
    fun cropAspect(aspect: Float) {
        val photo = _editing.value ?: return
        viewModelScope.launch(commands) {
            val rect = if (aspect <= 0f) {
                listOf(0.0, 0.0, 1.0, 1.0)
            } else {
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

    // ---- masking -------------------------------------------------------------------------------

    private fun addMask(kind: String) {
        viewModelScope.launch(commands) {
            when (kind) {
                "subject" -> subjectMask("Subject", invert = false)
                "background" -> subjectMask("Background", invert = true)
                "sky" -> withEngine { e -> e.execute("mask.add", JSONObject().put("kind", "sky").put("name", "Sky")) }
                else -> withEngine { e -> e.execute("mask.add", JSONObject().put("kind", kind).put("name", kind.replaceFirstChar { it.uppercase() })) }
            }
            afterEdit()
        }
    }

    /** Subject segmentation on the phone (ML Kit) over the uncropped photo → an engine mask. */
    private fun subjectMask(name: String, invert: Boolean): Boolean {
        val photo = _editing.value ?: return false
        val e = engine ?: return false
        _busy.value = true
        return try {
            val source = e.render(photo, 1024, 1024, before = true)
            val probs = Segmenter.subject(source)
            e.addSegMask(name, Segmenter.SIDE, probs, invert)
            true
        } catch (t: Throwable) {
            Log.w(tag, "segmentation", t)
            messages.trySend("$name selection is unavailable: ${t.message ?: "the on-device model is still downloading"}")
            false
        } finally {
            _busy.value = false
        }
    }

    fun selectMask(id: Int) {
        _activeMask.value = id
        viewModelScope.launch(commands) { withEngine { e -> e.execute("mask.select", JSONObject().put("id", id)) } }
    }

    fun setMaskValue(key: String, value: Float) {
        val id = _activeMask.value ?: return
        _masks.value = _masks.value.map { if (it.id == id) it.copy(adjust = it.adjust + (key to value)) else it }
        viewModelScope.launch(commands) {
            withEngine { e ->
                begin(e, "Mask Adjustment")
                e.execute("mask.adjust", JSONObject().put("id", id).put("values", JSONObject().put(key, value.toDouble())))
            }
            requestRender(draft = true)
        }
    }

    fun maskCommand(command: String) {
        val id = _activeMask.value ?: return
        runEdit(command, JSONObject().put("id", id))
    }

    // ---- remove (background) -------------------------------------------------------------------

    private fun beginCutout() {
        val photo = _editing.value ?: return
        viewModelScope.launch(commands) {
            withEngine { e ->
                val (w, h) = viewport
                e.cutoutBegin(photo, maxOf(w, h).coerceIn(512, 2048))
                _cutout.value = e.cutoutFrame()
            }
        }
    }

    private fun endCutout() {
        if (_cutout.value == null) return
        _cutout.value = null
        viewModelScope.launch(commands) { withEngine { e -> e.cutoutEnd() } }
    }

    private fun removeAction(tool: Tool) {
        when (tool.id) {
            "eraser" -> _tool.value = tool
            "ai" -> removeBackground(keepBackground = false)
            "keep" -> removeBackground(keepBackground = true)
            "undo" -> cutoutStep { it.cutoutUndo() }
            "reset" -> cutoutStep { it.cutoutReset() }
            "export" -> exportCutout()
        }
    }

    fun setErase(settings: EraseSettings) {
        _erase.value = settings
    }

    /** Magic eraser at the normalized point of the photo. */
    fun eraseAt(x: Float, y: Float) = cutoutStep { e ->
        if (e.cutoutErase(x, y, _erase.value) == 0) messages.trySend("Nothing similar to erase there")
    }

    private fun removeBackground(keepBackground: Boolean) {
        viewModelScope.launch(commands) {
            val e = engine ?: return@launch
            _busy.value = true
            try {
                if (_cutout.value == null) {
                    val photo = _editing.value ?: return@launch
                    val (w, h) = viewport
                    e.cutoutBegin(photo, maxOf(w, h).coerceIn(512, 2048))
                }
                // segment the very frame the matte applies to (it is cropped and edited)
                val frame = e.cutoutFrame()
                val probs = Segmenter.subject(frame)
                e.cutoutMatte(Segmenter.SIDE, probs, keepBackground)
                _cutout.value = e.cutoutFrame()
            } catch (t: Throwable) {
                Log.w(tag, "remove background", t)
                messages.trySend("Background removal is unavailable: ${t.message ?: "the on-device model is still downloading"}")
            } finally {
                _busy.value = false
            }
        }
    }

    private fun cutoutStep(step: (Engine) -> Unit) {
        viewModelScope.launch(commands) {
            withEngine { e ->
                step(e)
                _cutout.value = e.cutoutFrame()
            }
        }
    }

    private fun exportCutout() {
        viewModelScope.launch(commands) {
            _busy.value = true
            try {
                withEngine { e ->
                    val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val name = editingImage?.name?.substringBeforeLast('.') ?: "cutout"
                    shares.trySend(File(e.cutoutExport(dir.absolutePath, "$name-cutout", refine = true)))
                }
            } finally {
                _busy.value = false
            }
        }
    }

    // ---- shared --------------------------------------------------------------------------------

    /** The unedited look, for the compare view. */
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
            afterEdit()
        }
    }

    /** After an edit: re-read sliders, curves and masks, and render. Call on the command thread. */
    private fun afterEdit() {
        loadState()
        _presetThumbs.value = emptyMap()
        requestRender(draft = false)
    }

    /** Sliders (`develop.controls`), point curves and masks (`develop.get`). Command thread only. */
    private fun loadState() {
        val e = engine ?: return
        runCatching { e.execute("develop.controls") as? JSONArray }.getOrNull()?.let { all ->
            _controls.value = (0 until all.length()).mapNotNull { i -> all.optJSONObject(i)?.let { runCatching { Control.fromJson(it) }.getOrNull() } }
        }
        val d = runCatching { e.execute("develop.get") as? JSONObject }.getOrNull() ?: return
        d.optJSONObject("curve")?.let { c ->
            _curves.value = listOf("master", "red", "green", "blue").associateWith { ch ->
                val pts = c.optJSONArray(ch)
                val list = (0 until (pts?.length() ?: 0)).mapNotNull { i -> pts?.optJSONObject(i)?.let { CurvePoint(it.optDouble("x").toFloat(), it.optDouble("y").toFloat()) } }
                list.ifEmpty { listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f)) }
            }
        }
        val masks = d.optJSONArray("masks")
        _masks.value = (0 until (masks?.length() ?: 0)).mapNotNull { i -> masks?.optJSONObject(i)?.let { MaskInfo.fromJson(it) } }
        if (_masks.value.none { it.id == _activeMask.value }) _activeMask.value = _masks.value.lastOrNull()?.id
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
