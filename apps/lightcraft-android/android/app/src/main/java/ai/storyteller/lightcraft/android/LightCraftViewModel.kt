package ai.storyteller.lightcraft.android

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A develop preset (`presets.list`). */
data class Preset(val id: String, val name: String, val group: String, val builtin: Boolean = true, val favorite: Boolean = false)

/** A point of a tone curve, both coordinates 0..1. */
data class CurvePoint(val x: Float, val y: Float)

/**
 * The photo's crop (`develop.get` → `crop.geometry`): [rect] is `[x0, y0, x1, y1]` normalized in
 * the straightened frame, [angle] the straighten in degrees.
 */
data class CropState(val rect: List<Float> = listOf(0f, 0f, 1f, 1f), val angle: Float = 0f) {
    val x0 get() = rect.getOrElse(0) { 0f }
    val y0 get() = rect.getOrElse(1) { 0f }
    val x1 get() = rect.getOrElse(2) { 1f }
    val y1 get() = rect.getOrElse(3) { 1f }

    companion object {
        /** `crop` out of `develop.get` (`{geometry: {rect: {x0,y0,x1,y1}, angle}}`). */
        fun fromJson(o: JSONObject?): CropState {
            val g = o?.optJSONObject("geometry") ?: return CropState()
            val angle = g.optDouble("angle", 0.0).toFloat()
            val r = g.optJSONObject("rect") ?: return CropState(angle = angle)
            val (x0, x1) = r.optDouble("x0", 0.0).toFloat() to r.optDouble("x1", 1.0).toFloat()
            val (y0, y1) = r.optDouble("y0", 0.0).toFloat() to r.optDouble("y1", 1.0).toFloat()
            val rect = listOf(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1))
            // a degenerate rect would collapse the frame the handles are drawn on: show all of it
            val sane = rect[2] - rect[0] > 1e-4f && rect[3] - rect[1] > 1e-4f
            return CropState(if (sane) rect else listOf(0f, 0f, 1f, 1f), angle)
        }
    }
}

/** One piece of a mask's selection (`develop.get` → `masks[].components`): what it is and how it joins. */
data class MaskPart(val kind: String, val op: String)

/** A mask of the photo (`develop.get` → `masks`) with its parts and local adjustments. */
data class MaskInfo(val id: Int, val name: String, val adjust: Map<String, Float>, val parts: List<MaskPart> = emptyList()) {
    /**
     * How the brush component a stroke would land in joins the mask, or `null` when the mask has
     * no paint yet. `mask.brushStroke` always paints into the *last* brush component, whatever its
     * op, so this is what decides whether a stroke adds to the selection or takes away from it.
     */
    val lastBrushOp: String? get() = parts.lastOrNull { it.kind == "brush" }?.op

    /** Whether "Sample color" has anywhere to put a sample. */
    val hasColorRange: Boolean get() = parts.any { it.kind == "colorRange" }

    companion object {
        fun fromJson(o: JSONObject): MaskInfo {
            val a = o.optJSONObject("adjust")
            val adjust = a?.keys()?.asSequence()?.associateWith { a.optDouble(it, 0.0).toFloat() } ?: emptyMap()
            val cs = o.optJSONArray("components")
            val parts = (0 until (cs?.length() ?: 0)).mapNotNull { i ->
                cs?.optJSONObject(i)?.let { c ->
                    MaskPart(c.optJSONObject("shape")?.optString("kind").orEmpty(), c.optString("op", "add"))
                }
            }
            return MaskInfo(o.optInt("id"), o.optString("name", "Mask"), adjust, parts)
        }
    }
}

/** What the screens show; every change goes through engine commands. */
/** Side of the Remove selection grid (cells over the shown render). */
const val SEL = 128

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

    private val prefs = app.getSharedPreferences("library", Context.MODE_PRIVATE)

    /** Gallery ids of the photos edited in LightCraft (the library's Edited tab), kept across launches. */
    private val _edited = MutableStateFlow(prefs.getStringSet("edited", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet())
    val edited: StateFlow<Set<Long>> = _edited.asStateFlow()

    /** Whether the open photo was changed since it was opened (then it joins [edited] on close). */
    private val _dirty = MutableStateFlow(false)
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()
    private var changed: Boolean
        get() = _dirty.value
        set(v) {
            _dirty.value = v
        }

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    /** The History step the photo was at when it was opened: Discard goes back to it. */
    @Volatile private var openStep = -1

    /** The History step the photo is at now (-1 before it has any). */
    @Volatile private var currentStep = -1

    /** The furthest History step reached since the photo was opened: Redo goes no further. */
    @Volatile private var sessionTop = -1

    /** Whether the engine's render of the open photo has arrived (the gallery's thumbnail then stays away). */
    @Volatile private var rendered = false

    /** The History step the photo was at when Crop opened: Cancel goes back to it. */
    private var cropStart: Int? = null

    /** The photo's crop, for the crop frame to draw and for `crop.drag` to start from. */
    private val _crop = MutableStateFlow(CropState())
    val crop: StateFlow<CropState> = _crop.asStateFlow()

    /** Whether the Crop tool is open: the preview then shows the whole photo, crop and all. */
    private val _cropping = MutableStateFlow(false)
    val cropping: StateFlow<Boolean> = _cropping.asStateFlow()

    /** The photo the engine was last told to make active, so [activate] does not repeat itself. */
    @Volatile private var activated: Long? = null

    /** Whether Copy Settings has filled the clipboard (Paste Settings is enabled). */
    private val _canPaste = MutableStateFlow(false)
    val canPaste: StateFlow<Boolean> = _canPaste.asStateFlow()

    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    /** The open photo's profile id and amount (`develop.get` → profile). */
    private val _profile = MutableStateFlow<Pair<String, Float>?>(null)
    val profile: StateFlow<Pair<String, Float>?> = _profile.asStateFlow()

    private val _history = MutableStateFlow(HistoryInfo())
    val history: StateFlow<HistoryInfo> = _history.asStateFlow()

    private val _info = MutableStateFlow(PhotoInfo())
    val info: StateFlow<PhotoInfo> = _info.asStateFlow()

    private val _brush = MutableStateFlow(BrushSettings())
    val brush: StateFlow<BrushSettings> = _brush.asStateFlow()

    /** Painting on the photo adds to (or erases from) the selected mask. */
    private val _maskBrush = MutableStateFlow(false)
    val maskBrush: StateFlow<Boolean> = _maskBrush.asStateFlow()

    /**
     * The Remove selection: a [SEL]×[SEL] grid over the shown render, filled by painting or by
     * tapping an object; Remove fills it from its surroundings. Null = nothing selected.
     */
    private val _selection = MutableStateFlow<BooleanArray?>(null)
    val selection: StateFlow<BooleanArray?> = _selection.asStateFlow()

    /** In Masking, the selected mask shows as a red tint over the photo. */
    private val _maskOverlay = MutableStateFlow(true)
    val maskOverlay: StateFlow<Boolean> = _maskOverlay.asStateFlow()

    fun setMaskOverlay(on: Boolean) {
        _maskOverlay.value = on
        requestRender(draft = false)
    }

    /** A tap on the photo selects the object under it (instead of painting a dab). */
    private val _pickObject = MutableStateFlow(false)
    val pickObject: StateFlow<Boolean> = _pickObject.asStateFlow()

    /** The photo's spots in screen terms, for their outlines in Remove. */
    private val _spots = MutableStateFlow<List<SpotView>>(emptyList())
    val spots: StateFlow<List<SpotView>> = _spots.asStateFlow()

    /** The optional on-device generative fill model (MI-GAN): its download and whether it is in use. */
    /** The optional AI models (Smart select, Generative fill), by pack id. */
    private val _models = MutableStateFlow(ModelPacks.ALL.associate { it.id to GenModel() })
    val models: StateFlow<Map<String, GenModel>> = _models.asStateFlow()
    private val downloads = HashMap<String, kotlinx.coroutines.Job>()
    @Volatile private var sam: SamSelector? = null
    @Volatile private var lama: LamaPainter? = null

    /** A Generative removal is being painted (LaMa takes a while): the editor says so. */
    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    /** Remove fills with the generative model (when installed) rather than content-aware fill. */
    private val _generative = MutableStateFlow(true)
    val generative: StateFlow<Boolean> = _generative.asStateFlow()

    private val _picker = MutableStateFlow(Picker.None)
    val picker: StateFlow<Picker> = _picker.asStateFlow()

    private val _export = MutableStateFlow(ExportSettings())
    val exportSettings: StateFlow<ExportSettings> = _export.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** One-off messages for the snackbar. */
    val messages = Channel<String>(Channel.BUFFERED)

    /** Files to hand to the share sheet. */
    val shares = Channel<List<File>>(Channel.BUFFERED)

    private val thumbs = object : LruCache<Long, Bitmap>(96 * 1024 * 1024) {
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
                ModelPacks.ALL.filter { installed(it) }.forEach { loadPack(it) }
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

    /** Bytes used by the edits catalog and by caches (exports, private copies of gallery files). */
    suspend fun storage(): Pair<Long, Long> = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        fun size(f: File): Long = f.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        size(File(app.filesDir, "catalog")) to (size(app.cacheDir) + size(File(app.filesDir, "originals")))
    }

    /** Empties the cache folder (exports, shared JPEGs) and the in-memory thumbnails. Edits stay. */
    fun clearCache() {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            app.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            thumbs.evictAll()
            _thumbVersion.value++
            messages.trySend("Cache cleared")
        }
    }

    fun cachedThumbnail(image: GalleryImage): Bitmap? = thumbs.get(image.id)

    /** The engine's thumbnail for photos with edits, the system's for the rest. */
    suspend fun thumbnail(image: GalleryImage, edge: Int): Bitmap? = withContext(Dispatchers.IO) {
        // a cached thumbnail serves only if it is big enough for the cell (sharp, not stretched)
        thumbs.get(image.id)?.takeIf { maxOf(it.width, it.height) >= edge * 9 / 10 } ?: run {
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
        changed = false
        openStep = -1
        currentStep = -1
        sessionTop = -1
        rendered = false
        _canUndo.value = false
        _canRedo.value = false
        // the gallery's own 1280 px thumbnail is on screen within moments; the engine's render follows
        viewModelScope.launch(Dispatchers.IO) {
            val sharp = Gallery.thumbnail(getApplication(), image, 1280) ?: return@launch
            if (_opening.value == image && !rendered) _preview.value = sharp
        }
        _picker.value = Picker.None
        _maskBrush.value = false
        _selection.value = null
        _pickObject.value = false
        _info.value = PhotoInfo()
        _history.value = HistoryInfo()
        viewModelScope.launch(commands) {
            val e = engine ?: return@launch
            try {
                val photo = e.photoForPath(sourceFile(image).absolutePath)
                opened[image.id] = photo
                if (_opening.value != image) return@launch // closed while opening
                editingImage = image
                _editing.value = photo
                e.execute("library.select", JSONObject().put("ids", JSONArray().put(photo)).put("active", photo))
                activated = photo
                loadState()
                openStep = currentStep
                sessionTop = currentStep
                _canUndo.value = false
                _canRedo.value = false
                requestRender(draft = false)
            } catch (t: Throwable) {
                Log.w(tag, "open ${image.name}", t)
                messages.trySend("Could not open ${image.name}: ${t.message}")
            } finally {
                _opening.value = null
            }
        }
    }

    /** Whether the photo changed since it was opened (leaving asks Save or Discard). */
    fun hasUnsavedEdits(): Boolean = changed && currentStep != openStep

    /** Discard: back to the History step the photo was opened at, then leave. */
    fun discardAndClose() {
        val start = openStep
        val photo = _editing.value
        val image = editingImage
        changed = false
        if (photo != null) {
            viewModelScope.launch(commands) {
                if (interacting) {
                    interacting = false
                    withEngine { e -> e.execute("develop.endInteraction") }
                }
                withEngine { e ->
                    e.execute("library.select", JSONObject().put("ids", JSONArray().put(photo)).put("active", photo))
                    activated = photo
                    if (start >= 0) e.execute("history.restore", JSONObject().put("index", start)) else e.execute("develop.reset")
                }
                image?.let { refreshThumbnail(it.id) }
            }
        }
        close()
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
        activated = null
        _cropping.value = false
        _crop.value = CropState()
        editingImage = null
        if (image != null) {
            if (changed) markEdited(image.id)
            refreshThumbnail(image.id)
        }
    }

    private fun markEdited(galleryId: Long) {
        if (galleryId in _edited.value) return
        _edited.value = _edited.value + galleryId
        prefs.edit().putStringSet("edited", _edited.value.map { it.toString() }.toSet()).apply()
    }

    fun setViewport(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || viewport == width to height) return
        viewport = width to height
        if (_editing.value != null) requestRender(draft = false)
    }

    fun selectTab(tab: MainTab) {
        if (_tab.value == tab) return
        _picker.value = Picker.None
        _maskBrush.value = false
        _selection.value = null
        _pickObject.value = false
        if (_tab.value == MainTab.Remove) endCutout()
        _tab.value = tab
        _tool.value = when (tab) {
            MainTab.Edit -> null
            MainTab.Remove -> Tools.remove.first()
            else -> null
        }
        when (tab) {
            MainTab.Presets -> loadPresets()
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

    /**
     * Make the open photo the engine's active one (edits apply to the active photo), but only when
     * it is not already. `library.select` also clears the engine's selected mask and spot, so
     * running it before every command threw away the selection a mask command was about to use.
     */
    private fun activate(e: Engine) {
        val photo = _editing.value ?: return
        if (activated == photo) return
        runCatching { e.execute("library.select", JSONObject().put("ids", JSONArray().put(photo)).put("active", photo)) }
            .onSuccess {
                activated = photo
                // it cleared the engine's mask selection: put ours back
                _activeMask.value?.let { m -> runCatching { e.execute("mask.select", JSONObject().put("id", m)) } }
            }
    }

    private fun begin(e: Engine, label: String) {
        changed = true
        if (!interacting) {
            activate(e)
            e.execute("develop.beginInteraction", JSONObject().put("label", label))
            interacting = true
        }
    }

    fun finishInteraction() {
        viewModelScope.launch(commands) {
            if (interacting) {
                interacting = false
                withEngine { e -> e.execute("develop.endInteraction") }
                loadState()
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

    fun undo() {
        when {
            _cutout.value != null -> cutoutStep { it.cutoutUndo() }
            _canUndo.value -> runEdit("edit.undo")
        }
    }

    fun redo() {
        if (_canRedo.value) runEdit("edit.redo")
    }

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

    /** Saves the open photo's settings as a preset of the "User Presets" group. */
    fun createPreset(name: String) {
        val clean = name.trim().ifEmpty { return }
        viewModelScope.launch(commands) {
            withEngine { e ->
                e.execute("preset.create", JSONObject().put("name", clean).put("group", "User Presets"))
                messages.trySend("Preset \"$clean\" created")
            }
        }
        loadPresets()
    }

    fun favoritePreset(id: String, favorite: Boolean) = presetCommand("preset.favorite", JSONObject().put("id", id).put("favorite", favorite))

    fun deletePreset(id: String) = presetCommand("preset.delete", JSONObject().put("id", id))

    private fun presetCommand(command: String, params: JSONObject) {
        viewModelScope.launch(commands) { withEngine { e -> e.execute(command, params) } }
        loadPresets()
    }

    // ---- copy and paste settings ---------------------------------------------------------------

    fun copySettings() {
        viewModelScope.launch(commands) {
            withEngine { e ->
                e.execute("develop.copy")
                _canPaste.value = true
                messages.trySend("Settings copied")
            }
        }
    }

    fun pasteSettings() = runEdit("develop.paste")

    /** The settings of the photo edited before this one. */
    fun applyPrevious() = runEdit("develop.pastePrevious")

    fun resetAll() = runEdit("develop.reset")

    fun loadPresets() {
        viewModelScope.launch(commands) {
            val list = runCatching { engine?.execute("presets.list") as? JSONArray }.getOrNull() ?: return@launch
            _presets.value = (0 until list.length()).mapNotNull { i ->
                list.optJSONObject(i)?.let {
                    Preset(it.optString("id"), it.optString("name"), it.optString("group", ""), it.optBoolean("builtin", true), it.optBoolean("favorite", false))
                }
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

    /** Crop mode opens: remember the History step so Cancel can return to it, and show the whole photo. */
    fun beginCrop() {
        cropStart = null
        if (_cropping.value) return
        _cropping.value = true
        viewModelScope.launch(commands) {
            cropStart = runCatching { (engine?.execute("history.list") as? JSONObject)?.optInt("cursor", -1) }.getOrNull()?.takeIf { it >= 0 }
            loadState()
            requestRender(draft = false)
        }
    }

    /**
     * One move of a crop handle. [handle] is `crop.drag`'s numbering (0..3 corners from the top
     * left clockwise, 4..7 top/right/bottom/left edges, 8 the whole frame); [from] and [to] are
     * normalized points in the straightened frame, [start] the crop the drag began from. The crop
     * model decides what the drag does — the anchor, the aspect lock, the image bounds — so this
     * only reports where the finger went down and where it is now.
     */
    fun cropDrag(handle: Int, from: Pair<Float, Float>, to: Pair<Float, Float>, start: CropState) {
        viewModelScope.launch(commands) {
            withEngine { e ->
                begin(e, "Crop")
                e.execute(
                    "crop.drag",
                    JSONObject()
                        .put("handle", handle)
                        .put("from", JSONArray(listOf(from.first.toDouble(), from.second.toDouble())))
                        .put("to", JSONArray(listOf(to.first.toDouble(), to.second.toDouble())))
                        .put("start", JSONArray(start.rect.map { it.toDouble() })),
                )
            }
            loadState()
            requestRender(draft = true)
        }
    }

    /** Crop's ✕: every crop, rotate and straighten change since [beginCrop] is undone. */
    fun cancelCrop() {
        _cropping.value = false
        viewModelScope.launch(commands) {
            if (interacting) {
                interacting = false
                withEngine { e -> e.execute("develop.endInteraction") }
            }
            val start = cropStart
            cropStart = null
            if (start != null) {
                withEngine { e ->
                    val now = (e.execute("history.list") as? JSONObject)?.optInt("cursor", -1) ?: -1
                    if (now != start) e.execute("history.restore", JSONObject().put("index", start))
                }
            }
            afterEdit()
        }
    }

    /** Crop's ✓: keeps the changes. */
    fun commitCrop() {
        cropStart = null
        _cropping.value = false
        finishInteraction()
    }

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

    /** `mask.add`, selecting the mask it made: the sliders and the brush act on the new mask. */
    private fun addEngineMask(e: Engine, params: JSONObject) {
        val answer = e.execute("mask.add", params) as? JSONObject
        answer?.optInt("activeMask", -1)?.takeIf { it >= 0 }?.let { _activeMask.value = it }
    }

    private fun addMask(kind: String) {
        viewModelScope.launch(commands) {
            when (kind) {
                "subject" -> subjectMask("Subject", invert = false)
                "background" -> subjectMask("Background", invert = true)
                "sky" -> withEngine { e -> addEngineMask(e, JSONObject().put("kind", "sky").put("name", "Sky")) }
                "brush" -> {
                    withEngine { e -> addEngineMask(e, JSONObject().put("kind", "brush").put("name", "Brush")) }
                    _maskBrush.value = true
                }
                "colorRange" -> {
                    withEngine { e -> addEngineMask(e, JSONObject().put("kind", "colorRange").put("name", "Color Range")) }
                    _picker.value = Picker.MaskColor
                    messages.trySend("Tap the colour to select")
                }
                "luminanceRange" -> withEngine { e ->
                    addEngineMask(e, JSONObject().put("kind", "luminanceRange").put("name", "Luminance").put("lo", 0.6).put("hi", 1.0))
                }
                else -> withEngine { e -> addEngineMask(e, JSONObject().put("kind", kind).put("name", kind.replaceFirstChar { it.uppercase() })) }
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
            e.addSegMask(name, Segmenter.SIDE, probs, invert)?.let { _activeMask.value = it }
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
        requestRender(draft = false)
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

    // ---- the generative fill model -------------------------------------------------------------

    private fun packDir(pack: ModelPack): File = File(File(getApplication<Application>().filesDir, "models"), pack.id)

    /** Whether every file of [pack] is downloaded (each was checked against its SHA-256 then). */
    private fun installed(pack: ModelPack): Boolean = pack.files.all { File(packDir(pack), it.name).length() == it.bytes }

    private fun setModel(pack: ModelPack, state: GenModel) {
        _models.value = _models.value + (pack.id to state)
    }

    fun setGenerative(on: Boolean) {
        _generative.value = on
    }

    /** Open [pack]'s model (ONNX Runtime) and put it to work. */
    private fun loadPack(pack: ModelPack) {
        viewModelScope.launch(Dispatchers.Default) {
            setModel(pack, GenModel(loading = true))
            runCatching {
                when (pack.id) {
                    ModelPacks.SELECT.id -> {
                        sam?.close()
                        sam = SamSelector(packDir(pack))
                    }
                    ModelPacks.FILL.id -> {
                        val painter = LamaPainter(File(packDir(pack), pack.files.first().name))
                        withContext(commands) { engine?.setPainter(painter) }
                        lama?.close()
                        lama = painter
                    }
                }
            }.onSuccess {
                setModel(pack, GenModel(installed = true))
                if (pack.id == ModelPacks.FILL.id && _editing.value != null) requestRender(draft = false)
            }.onFailure { t ->
                Log.w(tag, "model ${pack.id}", t)
                setModel(pack, GenModel(error = "${pack.title} could not start: ${t.message}. Delete it and download it again."))
            }
        }
    }

    /** Download [pack] (each file checked against its SHA-256 as it arrives), then start it. */
    fun downloadPack(pack: ModelPack) {
        if (downloads[pack.id]?.isActive == true) return
        setModel(pack, GenModel(downloading = true))
        downloads[pack.id] = viewModelScope.launch(Dispatchers.IO) {
            val dir = packDir(pack).apply { mkdirs() }
            var done = 0L
            try {
                for (f in pack.files) {
                    val target = File(dir, f.name)
                    if (target.length() == f.bytes) {
                        done += f.bytes
                        continue
                    }
                    val part = File(dir, f.name + ".part")
                    var url = java.net.URL(f.url)
                    var conn: java.net.HttpURLConnection
                    var hops = 0
                    // Hugging Face sends file downloads on to its CDN: follow a few redirects by hand
                    while (true) {
                        conn = url.openConnection() as java.net.HttpURLConnection
                        conn.instanceFollowRedirects = false
                        conn.connectTimeout = 20_000
                        conn.readTimeout = 60_000
                        val code = conn.responseCode
                        if (code in 300..399 && hops < 6) {
                            url = java.net.URL(url, conn.getHeaderField("Location") ?: throw java.io.IOException("redirect without a location"))
                            conn.disconnect()
                            hops++
                            continue
                        }
                        if (code != 200) throw java.io.IOException("the server answered $code for ${f.name}")
                        break
                    }
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    var got = 0L
                    conn.inputStream.use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                got += n
                                if (got > f.bytes) throw java.io.IOException("${f.name} is larger than expected")
                                out.write(buf, 0, n)
                                digest.update(buf, 0, n)
                                setModel(pack, GenModel(downloading = true, progress = ((done + got).toFloat() / pack.bytes).coerceIn(0f, 1f)))
                            }
                        }
                    }
                    val hash = digest.digest().joinToString("") { "%02x".format(it) }
                    if (got != f.bytes || hash != f.sha256) {
                        part.delete()
                        throw java.io.IOException("${f.name} did not arrive intact")
                    }
                    if (!part.renameTo(target)) throw java.io.IOException("could not keep ${f.name}")
                    done += f.bytes
                }
                loadPack(pack)
            } catch (t: kotlinx.coroutines.CancellationException) {
                dir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
                setModel(pack, GenModel())
                throw t
            } catch (t: Throwable) {
                Log.w(tag, "download ${pack.id}", t)
                dir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
                setModel(pack, GenModel(error = "Download failed: ${t.message ?: "no connection"}"))
            }
        }
    }

    fun cancelDownload(pack: ModelPack) {
        downloads[pack.id]?.cancel()
    }

    /** Delete [pack]'s files; without them Tap object uses ML Kit and Remove fills content-aware. */
    fun deletePack(pack: ModelPack) {
        viewModelScope.launch(commands) {
            when (pack.id) {
                ModelPacks.SELECT.id -> {
                    sam?.close()
                    sam = null
                }
                ModelPacks.FILL.id -> {
                    runCatching { engine?.setPainter(null) }
                    lama?.close()
                    lama = null
                    if (_editing.value != null) requestRender(draft = false)
                }
            }
            packDir(pack).deleteRecursively()
            setModel(pack, GenModel())
        }
    }

    // ---- the Remove selection ------------------------------------------------------------------

    fun setPickObject(on: Boolean) {
        _pickObject.value = on
    }

    fun clearSelection() {
        _selection.value = null
    }

    /** Paints [points] (0..1 of the shown render) into the selection with the brush size. */
    private fun selectStroke(points: List<Pair<Float, Float>>) {
        val view = _preview.value ?: return
        val grid = _selection.value?.copyOf() ?: BooleanArray(SEL * SEL)
        val long = maxOf(view.width, view.height).toFloat()
        // the brush radius in cells, along each axis of the (possibly non-square) render
        val rx = (_brush.value.size / 2f * long / view.width * SEL).coerceAtLeast(0.6f)
        val ry = (_brush.value.size / 2f * long / view.height * SEL).coerceAtLeast(0.6f)
        fun stamp(cx: Float, cy: Float) {
            val x0 = (cx - rx).toInt().coerceAtLeast(0)
            val x1 = (cx + rx).toInt().coerceAtMost(SEL - 1)
            val y0 = (cy - ry).toInt().coerceAtLeast(0)
            val y1 = (cy + ry).toInt().coerceAtMost(SEL - 1)
            for (y in y0..y1) for (x in x0..x1) {
                val dx = (x + 0.5f - cx) / rx
                val dy = (y + 0.5f - cy) / ry
                if (dx * dx + dy * dy <= 1f) grid[y * SEL + x] = true
            }
        }
        var last: Pair<Float, Float>? = null
        for ((x, y) in points) {
            val c = x * SEL to y * SEL
            val l = last
            if (l == null) {
                stamp(c.first, c.second)
            } else {
                // dabs along the segment, half a radius apart
                val d = kotlin.math.hypot(c.first - l.first, c.second - l.second)
                val n = (d / (minOf(rx, ry) / 2f)).toInt().coerceIn(1, 400)
                for (i in 1..n) stamp(l.first + (c.first - l.first) * i / n, l.second + (c.second - l.second) * i / n)
            }
            last = c
        }
        _selection.value = grid
    }

    /** Selects the object under ([x], [y]) with on-device segmentation and adds it to the selection. */
    private fun selectObjectAt(x: Float, y: Float) {
        val view = _preview.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            _busy.value = true
            try {
                // MediaPipe's tap model first (any object); ML Kit's subject segmentation if it fails
                // SAM 2.1 when it is downloaded (any object); ML Kit's subject segmentation otherwise
                val found = sam?.let { s -> runCatching { s.select(view, x, y, SEL) }.onFailure { Log.w(tag, "SAM", it) }.getOrNull() }
                    ?: runCatching { ObjectPicker.objectAt(view, x, y, SEL)?.let { dilate(it, SEL, 2) } }
                        .onFailure { Log.w(tag, "object selection", it) }
                        .getOrNull()
                if (found == null) {
                    messages.trySend("No object found there. Paint over it instead.")
                    return@launch
                }
                val grid = _selection.value?.copyOf() ?: BooleanArray(SEL * SEL)
                for (i in grid.indices) grid[i] = grid[i] || found[i]
                _selection.value = grid
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * Fills the selection from its surroundings (content-aware): the grid is swept row by row into
     * one stroke of the engine's Fill spot, so the whole selection is one undoable edit.
     */
    fun removeSelection() {
        val grid = _selection.value?.let { dilate(it, SEL, 1) } ?: return
        val photo = _editing.value ?: return
        val view = _preview.value ?: return
        val step = 2
        val path = ArrayList<Pair<Float, Float>>()
        var row = 0
        var y = 0
        while (y < SEL) {
            // the cells of this band of rows that are selected, as runs
            val runs = ArrayList<Pair<Int, Int>>()
            var x = 0
            while (x < SEL) {
                val on = (y until minOf(y + step, SEL)).any { grid[it * SEL + x] }
                if (on) {
                    val start = x
                    while (x < SEL && (y until minOf(y + step, SEL)).any { grid[it * SEL + x] }) x++
                    runs += start to x - 1
                } else {
                    x++
                }
            }
            val cy = (y + step / 2f) / SEL
            val ordered = if (row % 2 == 0) runs else runs.reversed()
            for ((a, b) in ordered) {
                val (first, second) = if (row % 2 == 0) a to b else b to a
                path += (first + 0.5f) / SEL to cy
                path += (second + 0.5f) / SEL to cy
            }
            if (runs.isNotEmpty()) row++
            y += step
        }
        if (path.isEmpty()) return
        // the dab covers a band and a half, so neighbouring bands overlap
        val size = (step.toFloat() / SEL * view.height / maxOf(view.width, view.height) * 1.7f).coerceIn(0.005f, 0.3f)
        _selection.value = null
        viewModelScope.launch(commands) {
            _busy.value = true
            try {
                withEngine { e ->
                    activate(e)
                    val map = viewMap(e, photo) ?: throw IllegalStateException("Could not map the selection onto the photo")
                    val pts = JSONArray(path.map { (px, py) -> map(px, py).let { (ix, iy) -> JSONArray(listOf(ix.toDouble(), iy.toDouble())) } })
                    val generative = _models.value[ModelPacks.FILL.id]?.installed == true && _generative.value
                    val mode = if (generative) "generative" else "fill"
                    if (generative) _generating.value = true
                    e.execute("spot.add", JSONObject().put("mode", mode).put("points", pts).put("size", size.toDouble()).put("feather", 30.0).put("opacity", 100.0))
                }
                afterEdit()
            } finally {
                _busy.value = false
            }
        }
    }

    // ---- brushes, pickers and taps on the photo -------------------------------------------------

    fun setBrush(b: BrushSettings) {
        _brush.value = b
    }

    fun setMaskBrush(on: Boolean) {
        _maskBrush.value = on
        if (on) _picker.value = Picker.None
    }

    fun setPicker(p: Picker) {
        _picker.value = if (_picker.value == p) Picker.None else p
    }

    /**
     * The affine map from the shown render (0..1 of it) to normalized image coordinates, from three
     * points the engine maps exactly (crop, straighten, rotation and flips are all affine).
     */
    private fun viewMap(e: Engine, photo: Long): ((Float, Float) -> Pair<Float, Float>)? {
        val corners = runCatching { listOf(e.viewToImage(photo, 0f, 0f), e.viewToImage(photo, 1f, 0f), e.viewToImage(photo, 0f, 1f)) }
            .onFailure { Log.w(tag, "view to image", it) }
            .getOrNull() ?: return null
        val (o, ax, ay) = corners
        return { x, y ->
            (o.first + (ax.first - o.first) * x + (ay.first - o.first) * y) to (o.second + (ax.second - o.second) * x + (ay.second - o.second) * y)
        }
    }

    /** A tap on the photo at ([x], [y]) of the shown render: pickers, the eraser, a one-dab brush. */
    fun tapPhoto(x: Float, y: Float) {
        val tool = _tool.value
        when {
            _picker.value != Picker.None -> pick(x, y)
            _tab.value == MainTab.Remove && tool?.id == "eraser" -> eraseAt(x, y)
            _tab.value == MainTab.Remove && tool?.id == "redeye" -> redEye(x, y)
            _tab.value == MainTab.Remove && tool?.id == "fill" -> if (_pickObject.value) selectObjectAt(x, y) else selectStroke(listOf(x to y))
            _tab.value == MainTab.Remove && tool?.kind == ToolKind.Brush -> strokePhoto(listOf(x to y))
            _tab.value == MainTab.Masking && _maskBrush.value -> strokePhoto(listOf(x to y))
        }
    }

    private fun pick(x: Float, y: Float) {
        val photo = _editing.value ?: return
        val which = _picker.value
        viewModelScope.launch(commands) {
            withEngine { e ->
                activate(e)
                val (ix, iy) = viewMap(e, photo)?.invoke(x, y) ?: return@withEngine
                val at = JSONObject().put("x", ix.toDouble()).put("y", iy.toDouble())
                when (which) {
                    Picker.WhiteBalance -> e.execute("develop.wbPick", at)
                    // name the mask rather than leaning on the engine's selection, as every other
                    // mask command here does
                    Picker.MaskColor -> e.execute("mask.sampleColor", at.put("add", true).put("id", _activeMask.value ?: return@withEngine))
                    Picker.None -> {}
                }
            }
            if (which == Picker.WhiteBalance) _picker.value = Picker.None
            afterEdit()
        }
    }

    /** A brush stroke over the photo ([points] are 0..1 of the shown render): heal, clone, remove, or paint a mask. */
    fun strokePhoto(points: List<Pair<Float, Float>>) {
        val photo = _editing.value ?: return
        if (points.isEmpty()) return
        val tool = _tool.value
        if (_tab.value == MainTab.Remove && tool?.id == "fill") {
            selectStroke(points)
            return
        }
        val b = _brush.value
        val tab = _tab.value
        // a long stroke is thinned to at most 96 points; the engine dabs between them
        val step = (points.size / 96).coerceAtLeast(1)
        val thinned = points.filterIndexed { i, _ -> i % step == 0 || i == points.lastIndex }
        viewModelScope.launch(commands) {
            _busy.value = true
            try {
                withEngine { e ->
                    activate(e)
                    val map = viewMap(e, photo) ?: throw IllegalStateException("Could not map the stroke onto the photo")
                    val pts = JSONArray(thinned.map { (x, y) -> map(x, y).let { (ix, iy) -> JSONArray(listOf(ix.toDouble(), iy.toDouble())) } })
                    if (tab == MainTab.Remove && tool?.kind == ToolKind.Brush) {
                        e.execute(
                            "spot.add",
                            JSONObject().put("mode", tool.id).put("points", pts).put("size", b.size.toDouble())
                                .put("feather", b.feather.toDouble()).put("opacity", b.opacity.toDouble()),
                        )
                    } else if (tab == MainTab.Masking) {
                        val id = _activeMask.value
                        // Add and Erase are *selection* operations, as the desktop's ＋/− are
                        // (ui-egui/src/menus.rs, "brush"): each paints positively into a brush
                        // component whose op does the adding or the taking away. The stroke's own
                        // `erase` flag only rubs out paint inside one component, so it cannot touch
                        // a selection that is not paint — an AI subject, the sky, a gradient.
                        val want = if (b.erase) "subtract" else "add"
                        val mask = _masks.value.firstOrNull { it.id == id }
                        if (id != null && mask != null && mask.lastBrushOp != want) {
                            e.execute("mask.addComponent", JSONObject().put("id", id).put("kind", "brush").put("op", want))
                        }
                        val stroke = JSONObject().put("points", pts).put("size", b.size.toDouble()).put("feather", b.feather.toDouble())
                        id?.let { stroke.put("id", it) }
                        e.execute("mask.brushStroke", stroke)
                    }
                }
                afterEdit()
            } finally {
                _busy.value = false
            }
        }
    }

    private fun redEye(x: Float, y: Float) {
        val photo = _editing.value ?: return
        viewModelScope.launch(commands) {
            withEngine { e ->
                activate(e)
                val (ix, iy) = viewMap(e, photo)?.invoke(x, y) ?: return@withEngine
                e.execute("redeye.add", JSONObject().put("center", JSONArray(listOf(ix.toDouble(), iy.toDouble()))).put("rx", 0.025).put("ry", 0.025))
            }
            afterEdit()
        }
    }

    /**
     * The spots of `develop.get` mapped onto the shown render: the inverse of [viewMap] (crop,
     * straighten, rotation and flips are affine, so three points give it exactly).
     */
    private fun spotViews(e: Engine, spots: JSONArray?): List<SpotView> {
        val photo = _editing.value ?: return emptyList()
        if (spots == null || spots.length() == 0) return emptyList()
        val corners = runCatching { listOf(e.viewToImage(photo, 0f, 0f), e.viewToImage(photo, 1f, 0f), e.viewToImage(photo, 0f, 1f)) }.getOrNull() ?: return emptyList()
        val (o, ax, ay) = corners
        val a = ax.first - o.first
        val b = ax.second - o.second
        val c = ay.first - o.first
        val d = ay.second - o.second
        val det = a * d - b * c
        if (kotlin.math.abs(det) < 1e-9f) return emptyList()
        fun toView(ix: Float, iy: Float): Pair<Float, Float> {
            val px = ix - o.first
            val py = iy - o.second
            return (d * px - c * py) / det to (-b * px + a * py) / det
        }
        // a radius in image long-edge units, in render long-edge units (the crop zooms in)
        val zoom = 1f / kotlin.math.sqrt(kotlin.math.abs(det))
        return (0 until spots.length()).mapNotNull { i ->
            val sp = spots.optJSONObject(i) ?: return@mapNotNull null
            val pts = sp.optJSONArray("points") ?: return@mapNotNull null
            val image = (0 until pts.length()).mapNotNull { k -> pts.optJSONObject(k)?.let { it.optDouble("x").toFloat() to it.optDouble("y").toFloat() } }
            if (image.isEmpty()) return@mapNotNull null
            val off = sp.optJSONObject("source_offset") ?: sp.optJSONObject("sourceOffset")
            SpotView(
                mode = sp.optString("mode", "remove").lowercase(),
                path = image.map { (x, y) -> toView(x, y) },
                source = off?.let { s -> image.map { (x, y) -> toView(x + s.optDouble("x").toFloat(), y + s.optDouble("y").toFloat()) } },
                radius = sp.optDouble("size", 0.05).toFloat() * zoom,
            )
        }
    }

    /** Deletes the selected heal / clone / remove spot. */
    fun deleteSpot() = runEdit("spot.delete")

    /** White balance presets: asShot, auto, daylight, cloudy, shade, tungsten, fluorescent, flash. */
    fun setWhiteBalance(mode: String) = runEdit("develop.wb", JSONObject().put("mode", mode))

    /** Upright: off, auto, level, vertical, full. */
    fun upright(mode: String) = runEdit("geometry.upright", JSONObject().put("mode", mode))

    fun autoStraighten() = runEdit("crop.autoStraighten")

    /** A colour label (red, yellow, green, blue, purple, none). */
    fun label(label: String) {
        val l = if (_info.value.label == label) "none" else label
        _info.value = _info.value.copy(label = l)
        viewModelScope.launch(commands) {
            withEngine { e ->
                activate(e)
                e.execute("photo.label", JSONObject().put("label", l))
            }
        }
    }

    /** Title, caption, copyright and creator of the photo (`photo.setMeta`). */
    fun setDetails(details: PhotoDetails) {
        _info.value = _info.value.copy(details = details)
        viewModelScope.launch(commands) {
            withEngine { e ->
                activate(e)
                e.execute(
                    "photo.setMeta",
                    JSONObject().put("title", details.title).put("caption", details.caption).put("copyright", details.copyright).put("creator", details.creator),
                )
                messages.trySend("Details saved")
            }
        }
    }

    // ---- several photos at once (the library's selection) --------------------------------------

    /** The engine ids of gallery photos, importing each in place first. Command thread only. */
    private fun engineIds(e: Engine, images: List<GalleryImage>): List<Long> = images.mapNotNull { image ->
        opened[image.id] ?: runCatching { e.photoForPath(sourceFile(image).absolutePath) }.getOrNull()?.also { opened[image.id] = it }
    }

    /** Paste the copied settings onto every photo of [images]. */
    fun pasteTo(images: List<GalleryImage>) {
        if (images.isEmpty()) return
        viewModelScope.launch(commands) {
            _busy.value = true
            try {
                withEngine { e ->
                    val ids = engineIds(e, images)
                    e.execute("develop.paste", JSONObject().put("ids", JSONArray(ids)))
                    images.forEach { markEdited(it.id); refreshThumbnail(it.id) }
                    messages.trySend("Settings pasted to ${ids.size} photo${if (ids.size == 1) "" else "s"}")
                }
            } finally {
                _busy.value = false
            }
        }
    }

    /** Export every photo of [images] with the export settings and share them together. */
    fun shareMany(images: List<GalleryImage>) {
        if (images.isEmpty()) return
        val app = getApplication<Application>()
        val settings = _export.value
        viewModelScope.launch(commands) {
            _busy.value = true
            try {
                withEngine { e ->
                    val dir = File(app.cacheDir, "exports").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val files = engineIds(e, images).map { File(e.export(it, settings.json(), dir.absolutePath)) }
                    shares.trySend(files)
                }
            } finally {
                _busy.value = false
            }
        }
    }

    // ---- profiles ------------------------------------------------------------------------------

    fun loadProfiles() {
        if (_profiles.value.isNotEmpty()) return
        viewModelScope.launch(commands) {
            _profiles.value = profiles(runCatching { engine?.execute("profiles.list") as? JSONArray }.getOrNull())
        }
    }

    fun setProfile(id: String) = runEdit("develop.profile", JSONObject().put("id", id))

    fun setProfileAmount(amount: Float) {
        val id = _profile.value?.first ?: return
        _profile.value = id to amount
        viewModelScope.launch(commands) {
            withEngine { e ->
                begin(e, "Profile Amount")
                e.execute("develop.profile", JSONObject().put("id", id).put("amount", amount.toDouble()))
            }
            requestRender(draft = true)
        }
    }

    // ---- history, versions, rating -------------------------------------------------------------

    fun loadHistory() {
        viewModelScope.launch(commands) {
            _history.value = historyInfo(runCatching { engine?.execute("history.list") as? JSONObject }.getOrNull())
        }
    }

    fun restoreHistory(index: Int) {
        runEdit("history.restore", JSONObject().put("index", index))
        loadHistory()
    }

    fun createVersion(name: String) {
        viewModelScope.launch(commands) {
            withEngine { e ->
                e.execute("version.create", JSONObject().apply { if (name.isNotBlank()) put("name", name.trim()) })
                messages.trySend("Version saved")
            }
        }
        loadHistory()
    }

    fun restoreVersion(index: Int) {
        runEdit("version.restore", JSONObject().put("index", index))
        loadHistory()
    }

    fun loadInfo() {
        val photo = _editing.value ?: return
        viewModelScope.launch(commands) {
            val e = engine ?: return@launch
            val lines = infoLines(runCatching { e.execute("photo.allMetadata") as? JSONObject }.getOrNull())
            val all = runCatching { (e.execute("catalog.query", JSONObject().put("limit", 100000)) as? JSONObject)?.optJSONArray("photos") }.getOrNull()
            val me = (0 until (all?.length() ?: 0)).mapNotNull { all?.optJSONObject(it) }.firstOrNull { it.optLong("id") == photo }
            val extra = listOfNotNull(
                me?.optString("fileName")?.takeIf { it.isNotEmpty() }?.let { InfoLine("File", it) },
                me?.takeIf { it.optInt("width") > 0 }?.let { InfoLine("Size", "${it.optInt("width")} × ${it.optInt("height")}") },
            )
            _info.value = PhotoInfo(
                rating = me?.optInt("rating") ?: 0,
                flag = me?.optString("flag")?.ifEmpty { "none" } ?: "none",
                label = me?.optString("label")?.ifEmpty { "none" }?.takeIf { it != "null" } ?: "none",
                lines = extra + lines,
                details = PhotoDetails(title = me?.optString("title")?.takeIf { it != "null" }.orEmpty()),
            )
        }
    }

    fun rate(stars: Int) {
        val r = if (_info.value.rating == stars) 0 else stars.coerceIn(0, 5)
        _info.value = _info.value.copy(rating = r)
        viewModelScope.launch(commands) {
            withEngine { e ->
                activate(e)
                e.execute("photo.rate", JSONObject().put("rating", r))
            }
        }
    }

    fun flag(flag: String) {
        val f = if (_info.value.flag == flag) "none" else flag
        _info.value = _info.value.copy(flag = f)
        viewModelScope.launch(commands) {
            withEngine { e ->
                activate(e)
                e.execute("photo.flag", JSONObject().put("flag", f))
            }
        }
    }

    // ---- export --------------------------------------------------------------------------------

    fun setExport(settings: ExportSettings) {
        _export.value = settings
    }

    /** Exports with the export settings, then shares the file or saves it into the gallery. */
    fun exportPhoto(share: Boolean) {
        val photo = _editing.value ?: return
        val app = getApplication<Application>()
        val settings = _export.value
        viewModelScope.launch(commands) {
            _busy.value = true
            try {
                withEngine { e ->
                    val dir = File(app.cacheDir, "exports").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val file = File(e.export(photo, settings.json(), dir.absolutePath))
                    if (share) {
                        shares.trySend(listOf(file))
                    } else {
                        saveIntoGallery(app, file)
                        messages.trySend("Saved to Pictures/LightCraft")
                    }
                }
            } finally {
                _busy.value = false
            }
            if (!share) refreshGallery()
        }
    }

    private fun saveIntoGallery(app: Application, file: File) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, ExportFormat.mimeOf(file.name))
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/LightCraft")
        }
        val uri = app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("The gallery refused the new photo")
        val out = app.contentResolver.openOutputStream(uri) ?: throw IllegalStateException("Could not write to the gallery")
        out.use { o -> file.inputStream().use { it.copyTo(o) } }
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
        if (tool.kind == ToolKind.Brush) {
            endCutout()
            _tool.value = tool
            return
        }
        when (tool.id) {
            "redeye" -> {
                endCutout()
                _tool.value = tool
                messages.trySend("Tap each red eye")
            }
            "eraser" -> {
                _tool.value = tool
                if (_cutout.value == null) beginCutout()
            }
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
                    shares.trySend(listOf(File(e.cutoutExport(dir.absolutePath, "$name-cutout", refine = true))))
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

    /** The share button: export with the export settings and hand the file to the share sheet. */
    fun share() = exportPhoto(share = true)

    /** Saves a copy into the phone's gallery (Pictures/LightCraft) with the export settings. */
    fun saveToDevice() = exportPhoto(share = false)

    private fun runEdit(id: String, params: JSONObject = JSONObject()) {
        viewModelScope.launch(commands) {
            if (interacting) {
                interacting = false
                withEngine { e -> e.execute("develop.endInteraction") }
            }
            withEngine { e ->
                activate(e)
                e.execute(id, params)
            }
            afterEdit()
        }
    }

    /** After an edit: re-read sliders, curves and masks, and render. Call on the command thread. */
    private fun afterEdit() {
        changed = true
        loadState()
        _presetThumbs.value = emptyMap()
        requestRender(draft = false)
    }

    /** Sliders (`develop.controls`), point curves and masks (`develop.get`). Command thread only. */
    private fun loadState() {
        val e = engine ?: return
        runCatching { e.execute("history.list") as? JSONObject }.getOrNull()?.let { h ->
            val steps = h.optJSONArray("history")?.length() ?: 0
            currentStep = if (h.isNull("cursor")) steps - 1 else h.optInt("cursor", steps - 1)
            sessionTop = if (currentStep == steps - 1) currentStep else maxOf(sessionTop, currentStep)
            // undo and redo cover this session only: what was done since the photo was opened
            _canUndo.value = openStep >= 0 && currentStep > openStep
            _canRedo.value = openStep >= 0 && currentStep < minOf(steps - 1, sessionTop)
        }
        runCatching { e.execute("develop.controls") as? JSONArray }.getOrNull()?.let { all ->
            _controls.value = (0 until all.length()).mapNotNull { i -> all.optJSONObject(i)?.let { runCatching { Control.fromJson(it) }.getOrNull() } }
        }
        val d = runCatching { e.execute("develop.get") as? JSONObject }.getOrNull() ?: return
        d.optJSONObject("profile")?.let { pr ->
            _profile.value = pr.optString("id") to pr.optDouble("amount", 100.0).toFloat()
        }
        d.optJSONObject("curve")?.let { c ->
            _curves.value = listOf("master", "red", "green", "blue").associateWith { ch ->
                val pts = c.optJSONArray(ch)
                val list = (0 until (pts?.length() ?: 0)).mapNotNull { i -> pts?.optJSONObject(i)?.let { CurvePoint(it.optDouble("x").toFloat(), it.optDouble("y").toFloat()) } }
                list.ifEmpty { listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f)) }
            }
        }
        _crop.value = CropState.fromJson(d.optJSONObject("crop"))
        _spots.value = spotViews(e, d.optJSONArray("spots"))
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
        val (vw, vh) = viewport
        val (w, h) = if (draft) (vw / 2).coerceAtLeast(320) to (vh / 2).coerceAtLeast(320) else vw to vh
        val t0 = System.nanoTime()
        val overlay = _activeMask.value.takeIf { _tab.value == MainTab.Masking && _maskOverlay.value }
        runCatching {
            when {
                // the crop tool shows the whole photo, so a handle can be dragged back outwards
                _cropping.value -> e.renderUncropped(photo, w, h, draft)
                overlay != null -> e.renderMask(photo, w, h, overlay, draft)
                else -> e.render(photo, w, h, draft = draft)
            }
        }
            .onSuccess {
                if (_editing.value == photo) {
                    rendered = true
                    _preview.value = it
                    if (!draft) _generating.value = false
                }
                Log.d(tag, "render ${it.width}x${it.height} draft=$draft ${(System.nanoTime() - t0) / 1_000_000} ms")
            }
            .onFailure { t ->
                if (_editing.value != photo) return
                Log.w(tag, "render $photo", t)
                if (t.message?.contains("no photo") == true && reopen()) return
                messages.trySend("Could not show this photo: ${t.message}")
            }
    }

    /** The engine lost the open photo (its catalog changed underneath): import the file again. */
    private fun reopen(): Boolean {
        val image = editingImage ?: return false
        val e = engine ?: return false
        return runCatching {
            val photo = e.photoForPath(sourceFile(image).absolutePath)
            opened[image.id] = photo
            _editing.value = photo
            e.execute("library.select", JSONObject().put("ids", JSONArray().put(photo)).put("active", photo))
            activated = photo
            requestRender(draft = false)
        }.isSuccess
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
            Log.w(tag, "engine: ${t.kind}: ${t.message}")
            messages.trySend(t.message ?: t.kind)
        } catch (t: Exception) {
            Log.e(tag, "engine call failed", t)
            messages.trySend(t.message ?: "Something went wrong")
        }
    }

    override fun onCleared() {
        sam?.close()
        lama?.close()
        engine?.close()
        commands.close()
    }
}
