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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** The open photo's treatment (`color` / `bw`), profile and white-balance mode. */
data class Look(
    val treatment: String = "color",
    val profile: String = "lc.color",
    val profileAmount: Float = 100f,
    val wb: String = "asShot",
    /** Optics: Enable Profile Corrections. */
    val lensProfile: Boolean = false,
    /** Optics: Remove Chromatic Aberration. */
    val removeCa: Boolean = false,
    /** Lens Blur: applied, what stays in focus without a mask, and whether it uses a depth map. */
    val blur: Boolean = false,
    val blurShape: String = "auto",
    val blurDepth: Boolean = false,
    /** The crop, normalized over the uncropped photo: x0, y0, x1, y1. */
    val crop: List<Float> = listOf(0f, 0f, 1f, 1f),
)

/** A develop preset (`presets.list`). */
data class Preset(val id: String, val name: String, val group: String)

/** A point of a tone curve, both coordinates 0..1. */
data class CurvePoint(val x: Float, val y: Float)

/** A mask of the photo (`develop.get` → `masks`) with its local adjustments. */
data class MaskInfo(val id: Int, val name: String, val adjust: Map<String, Float>, val components: List<MaskComponent> = emptyList()) {
    companion object {
        fun fromJson(o: JSONObject): MaskInfo {
            val a = o.optJSONObject("adjust")
            val adjust = a?.keys()?.asSequence()?.associateWith { a.optDouble(it, 0.0).toFloat() } ?: emptyMap()
            val c = o.optJSONArray("components")
            val components = (0 until (c?.length() ?: 0)).mapNotNull { i ->
                c?.optJSONObject(i)?.let { MaskComponent(it.optJSONObject("shape")?.optString("kind", "") ?: "", it.optString("op", "add"), it.optBoolean("invert"), it.optJSONObject("shape")) }
            }
            return MaskInfo(o.optInt("id"), o.optString("name", "Mask"), adjust, components)
        }
    }
}

/** One selection of a mask (`kind`: radial, linear, brush, sky, object…) and how it combines. */
data class MaskComponent(val kind: String, val op: String, val invert: Boolean, val shape: JSONObject? = null) {
    val label: String get() = when (kind) {
        "radial" -> "Radial Gradient"
        "linear" -> "Linear Gradient"
        "brush" -> "Brush"
        "luminanceRange" -> "Luminance Range"
        "colorRange" -> "Color Range"
        "sky" -> "Sky"
        "subject", "seg" -> "Subject"
        "object" -> "Object"
        "prompt" -> "Describe"
        else -> kind.replaceFirstChar { it.uppercase() }.ifEmpty { "Selection" }
    }
}

/** Which photos the library grid shows. */
sealed class LibrarySource(val title: String) {
    data object All : LibrarySource("All Photos")
    data object Recent : LibrarySource("Recently Added")
    data object Picks : LibrarySource("Picks")
    data object Edited : LibrarySource("Edited")
    data object Deleted : LibrarySource("Recently Deleted")
    data class Album(val name: String) : LibrarySource(name.ifEmpty { "Other" })
    data class Year(val year: Int) : LibrarySource(year.toString())
}

/** The grid's layouts, as the desktop's bottom bar offers them. */
enum class ViewMode { Justified, Square, Single }

enum class SortOrder(val label: String) { Newest("Capture time, newest first"), Oldest("Capture time, oldest first"), Name("File name") }

/** What the grid shows for [source], [query] and [sort]. Pure, for the screen and tests. */
fun libraryView(
    photos: List<GalleryImage>,
    trash: List<GalleryImage>,
    source: LibrarySource,
    query: String,
    sort: SortOrder,
    markOf: (GalleryImage) -> Mark?,
    now: Long = System.currentTimeMillis(),
): List<GalleryImage> {
    val zone = java.time.ZoneId.systemDefault()
    val base = when (source) {
        LibrarySource.All -> photos
        LibrarySource.Recent -> photos.filter { now - it.added <= 30L * 24 * 3600 * 1000 }
        LibrarySource.Picks -> photos.filter { markOf(it)?.flag == "pick" }
        LibrarySource.Edited -> photos.filter { markOf(it)?.edited == true }
        LibrarySource.Deleted -> trash
        is LibrarySource.Album -> photos.filter { it.album == source.name }
        is LibrarySource.Year -> photos.filter { java.time.Instant.ofEpochMilli(it.taken).atZone(zone).year == source.year }
    }
    val q = query.trim().lowercase()
    val found = if (q.isEmpty()) base else base.filter { it.name.lowercase().contains(q) || it.album.lowercase().contains(q) }
    return when (sort) {
        SortOrder.Newest -> found.sortedByDescending { it.taken }
        SortOrder.Oldest -> found.sortedBy { it.taken }
        SortOrder.Name -> found.sortedBy { it.name.lowercase() }
    }
}

/** Export choices of the export sheet. */
data class ExportChoice(val format: String = "jpeg", val quality: Int = 90, val longEdge: Int = 0) {
    val mime: String get() = when (format) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "avif" -> "image/avif"
        else -> "image/jpeg"
    }
}

/** The SAM 3 model as `segment.model.status` reports it. */
data class AiModelStatus(
    val available: Boolean = false,
    val installed: Boolean = false,
    val downloading: Boolean = false,
    val done: Long = 0,
    val total: Long = 0,
    val sizeBytes: Long = 3_439_938_512,
    val license: String = "SAM License (Meta)",
    val error: String? = null,
    /** How many download locations are configured (0: none, so the download needs a source). */
    val mirrors: Int = 0,
)

/**
 * The small tap-to-select model (EfficientSAM-Ti, `ai.models` id `efficientsam-ti`): Object uses it
 * when SAM 3 isn't installed. It downloads from its pinned public source, no account needed.
 */
data class QuickSelectStatus(
    val ready: Boolean = false,
    val downloading: Boolean = false,
    val done: Long = 0,
    val total: Long = 0,
    val size: Long = 0,
    val licence: String = "",
    val error: String? = null,
)

/** One of the optional on-device AI models (`ai.models`): fill, best fill, objects, depth, people, select. */
data class LcAiModel(
    val id: String,
    val name: String,
    val purpose: String,
    val size: Long,
    val licence: String,
    val ready: Boolean,
    val downloading: Boolean,
    val done: Long,
    val total: Long,
    val error: String?,
)

/** What Find Objects found: a label, its confidence and its box on the photo as shot (0..1). */
data class Suggestion(val label: String, val score: Float, val x0: Float, val y0: Float, val x1: Float, val y1: Float)

/** The photo's History (`history.list`): step labels oldest first, the step it is at, and its saved versions. */
data class HistoryState(val steps: List<String> = emptyList(), val cursor: Int = -1, val versions: List<String> = emptyList())

/** AI Remove: brush size (share of the photo's long edge) and LaMa (best) or MI-GAN (fast). */
data class AiEraseSettings(val on: Boolean = false, val size: Float = 0.04f, val best: Boolean = false)

/** Where SAM 3 downloads from: Meta's gated Hugging Face page with the person's own token, or a mirror. */
data class SamSource(val token: String = "", val mirror: String = "") {
    val configured: Boolean get() = token.isNotBlank() || mirror.isNotBlank()
}

/** The ML Kit subject-segmentation model, which Google Play services downloads and keeps. */
data class SegModelStatus(val checked: Boolean = false, val installed: Boolean = false, val downloading: Boolean = false, val progress: Float = 0f, val error: String? = null)

/** What LightCraft keeps on the phone, in bytes, for the Downloads & Storage page. */
data class StorageUse(val catalog: Long = 0, val copies: Long = 0, val exports: Long = 0, val models: Long = 0)

/** Full-screen pages besides the library and the editor. */
enum class Page { None, Downloads }

/** Brush settings for brush, dodge and burn masks. */
data class BrushSettings(val size: Float = 0.04f, val feather: Float = 50f, val flow: Float = 60f, val erase: Boolean = false)

/** Meta's SAM 3 on Hugging Face (gated: each person accepts the licence there and uses their own token). */
const val HF_SAM3 = "https://huggingface.co/facebook/sam3/resolve/main"

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

    private val _marks = MutableStateFlow<Map<String, Mark>>(emptyMap())
    /** Edited / favourite / rating of the gallery files the library knows, by file path. */
    val marks: StateFlow<Map<String, Mark>> = _marks.asStateFlow()

    private val _source = MutableStateFlow<LibrarySource>(LibrarySource.All)
    val source: StateFlow<LibrarySource> = _source.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _viewMode = MutableStateFlow(ViewMode.Justified)
    val viewMode: StateFlow<ViewMode> = _viewMode.asStateFlow()

    private val _sort = MutableStateFlow(SortOrder.Newest)
    val sort: StateFlow<SortOrder> = _sort.asStateFlow()

    private val _trash = MutableStateFlow<List<GalleryImage>>(emptyList())
    /** Photos in the phone's trash (Android 11+): the desktop's Recently Deleted. */
    val trash: StateFlow<List<GalleryImage>> = _trash.asStateFlow()

    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    /** Gallery ids selected in the library (long-press starts a selection). */
    val selection: StateFlow<Set<Long>> = _selection.asStateFlow()

    private val _copied = MutableStateFlow(false)
    /** Whether edits were copied (`develop.copy`) and can be pasted. */
    val copied: StateFlow<Boolean> = _copied.asStateFlow()

    private val _showHistogram = MutableStateFlow(false)
    val showHistogram: StateFlow<Boolean> = _showHistogram.asStateFlow()

    private val _histogram = MutableStateFlow<Histogram?>(null)
    val histogram: StateFlow<Histogram?> = _histogram.asStateFlow()

    private val _info = MutableStateFlow<JSONObject?>(null)
    /** `photo.inspect` of the photo being edited, for the info sheet. */
    val info: StateFlow<JSONObject?> = _info.asStateFlow()

    private val _brush = MutableStateFlow(BrushSettings())
    val brush: StateFlow<BrushSettings> = _brush.asStateFlow()

    private val _ai = MutableStateFlow(AiModelStatus(available = Engine.aiMasksAvailable))
    val ai: StateFlow<AiModelStatus> = _ai.asStateFlow()

    /** Gallery photos to share as they are (the library's selection). */
    val shareOriginals = Channel<List<android.net.Uri>>(Channel.BUFFERED)

    /** Gallery images to delete: MainActivity asks Android (which confirms) and reports back. */
    val deletions = Channel<Pair<List<GalleryImage>, DeleteMode>>(Channel.BUFFERED)

    private val _look = MutableStateFlow(Look())
    /** Treatment, profile and white-balance mode of the open photo (`develop.get`). */
    val look: StateFlow<Look> = _look.asStateFlow()

    private val _profiles = MutableStateFlow<List<Preset>>(emptyList())
    /** The engine's profiles (`profiles.list`), as presets: id, name, group. */
    val profiles: StateFlow<List<Preset>> = _profiles.asStateFlow()

    private val _wbPicking = MutableStateFlow(false)
    /** The white-balance selector is armed: the next tap on the photo picks a neutral point. */
    val wbPicking: StateFlow<Boolean> = _wbPicking.asStateFlow()

    private val _page = MutableStateFlow(Page.None)
    val page: StateFlow<Page> = _page.asStateFlow()

    private val _seg = MutableStateFlow(SegModelStatus())
    val seg: StateFlow<SegModelStatus> = _seg.asStateFlow()

    private val _storage = MutableStateFlow(StorageUse())
    val storage: StateFlow<StorageUse> = _storage.asStateFlow()

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
                runCatching { e.setModelsDir(File(app.filesDir, "models").absolutePath) }
                loadSamSource()
                engine = e
                _marks.value = e.galleryMarks()
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
        refreshMarks()
        viewModelScope.launch(Dispatchers.IO) {
            _trash.value = runCatching { Gallery.trashed(app) }.getOrDefault(emptyList())
            runCatching { Gallery.images(app) }
                .onSuccess {
                    _photos.value = it
                    val known = (it + _trash.value).mapTo(HashSet()) { g -> g.id }
                    _selection.value = _selection.value.filterTo(HashSet()) { id -> id in known }
                }
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
        val copy = copyOf(image).apply { parentFile?.mkdirs() }
        if (!copy.exists()) {
            app.contentResolver.openInputStream(image.uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } }
                ?: throw IllegalStateException("${image.name} is not readable")
        }
        return copy
    }

    // ---- editor --------------------------------------------------------------------------------

    private val _strip = MutableStateFlow<List<GalleryImage>>(emptyList())
    /** The photos of the library view the editor was opened from: its filmstrip. */
    val strip: StateFlow<List<GalleryImage>> = _strip.asStateFlow()

    /** The gallery image being edited (its id), for the filmstrip. */
    val editingImageId: Long? get() = editingImage?.id

    fun open(image: GalleryImage, strip: List<GalleryImage>) {
        if (strip.isNotEmpty()) _strip.value = strip.take(2000)
        open(image)
    }

    /** Another photo of the filmstrip: leave this one (its thumbnail refreshes) and open that. */
    fun switchTo(image: GalleryImage) {
        if (image.id == editingImage?.id) return
        val tab = _tab.value
        close()
        open(image)
        if (tab != MainTab.Edit) selectTab(tab)
    }

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
                loadInfoNow()
                if (_profiles.value.isEmpty()) loadProfiles(e)
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
        _info.value = null
        _histogram.value = null
        if (image != null) refreshThumbnail(image.id)
        refreshMarks()
    }

    fun setViewport(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || viewport == width to height) return
        viewport = width to height
        if (_editing.value != null) requestRender(draft = false)
    }

    fun selectTab(tab: MainTab) {
        if (_tab.value == tab) return
        if (_tab.value == MainTab.Remove) endCutout()
        // leaving Crop: the photo is shown cropped again
        if (_tab.value == MainTab.Crop) requestRender(draft = false)
        // the mask overlay comes and goes with the Masking tab
        val masking = _tab.value == MainTab.Masking || tab == MainTab.Masking
        _tab.value = tab
        if (masking) requestRender(draft = false)
        _tool.value = when (tab) {
            MainTab.Remove -> Tools.remove[0]
            else -> null
        }
        objectMask = null
        _objectStarted.value = false
        when (tab) {
            MainTab.Presets -> loadPresets()
            MainTab.Crop -> requestRender(draft = false)
            MainTab.Remove -> beginCutout()
            MainTab.Masking -> viewModelScope.launch(commands) { loadState() }
            else -> {}
        }
    }

    /** A tool of the row above the bottom bar. */
    fun selectTool(tool: Tool) {
        when (_tab.value) {
            MainTab.Masking -> maskTool(tool)
            MainTab.Remove -> removeAction(tool)
            else -> if (tool.kind == ToolKind.Action) {
                if (tool.id == "auto") runEdit("develop.auto")
            } else {
                _tool.value = if (_tool.value?.id == tool.id) null else tool
            }
        }
    }

    /** The one-tap looks of the Masking panel ("pop", "sky") and the Edit header's Auto / B&W / Reset. */
    fun quickAction(id: String) {
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
                    afterEdit(selectNewest = true)
                }
            }
            "sky" -> viewModelScope.launch(commands) {
                withEngine { e ->
                    e.execute("mask.add", JSONObject().put("kind", "sky").put("name", "Blue sky"))
                    e.execute("mask.adjust", JSONObject().put("values", JSONObject().put("temp", -18).put("saturation", 25).put("highlights", -20)))
                }
                afterEdit(selectNewest = true)
            }
        }
    }

    // ---- sliders -------------------------------------------------------------------------------

    /**
     * Temp and Tint show as −100…+100 (0 = as shot) for photos without a camera Kelvin scale
     * (JPEG, HEIC, PNG…), as the desktop shows them for rendered files; the engine still takes Kelvin.
     */
    @Volatile private var relativeWb = false

    private fun toEngine(id: String, v: Float): Float = if (relativeWb) RelativeWb.toEngine(id, v) else v

    /** A slider moved: one undo step per drag (`develop.beginInteraction` … `endInteraction`). */
    fun setControl(id: String, value: Float) {
        _controls.value = _controls.value.map { if (it.id == id) it.copy(value = value) else it }
        viewModelScope.launch(commands) {
            withEngine { e ->
                begin(e, _controls.value.firstOrNull { it.id == id }?.label ?: "Edit")
                e.execute("develop.set", JSONObject().put("control", id).put("value", toEngine(id, value).toDouble()))
            }
            requestRender(draft = true)
        }
    }

    private fun begin(e: Engine, label: String) {
        if (_lastPreset.value != null) {
            _lastPreset.value = null
            _presetThumbs.value = emptyMap()
        }
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
        runEdit("develop.set", JSONObject().put("control", id).put("value", toEngine(id, default).toDouble()))
    }

    /** Back to the defaults of every slider in [group], as one undo step. */
    fun resetGroup(group: Group) {
        val values = JSONObject()
        _controls.value.filter { group.matches(it.id) && it.value != it.default }.forEach { values.put(it.id, toEngine(it.id, it.default).toDouble()) }
        if (values.length() > 0) runEdit("develop.set", JSONObject().put("values", values))
    }

    fun undo() = if (_tab.value == MainTab.Remove) cutoutStep { it.cutoutUndo() } else runEdit("edit.undo")
    fun redo() = runEdit("edit.redo")

    /** ⋮ → Reset: all edits, or with Remove open, the cutout back to the original. */
    fun resetAll() = if (_tab.value == MainTab.Remove) cutoutStep { it.cutoutReset() } else quickAction("reset")

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

    private val _lastPreset = MutableStateFlow<String?>(null)
    /** The preset being tried on: applied last, with no other edit since (another one replaces it). */
    val lastPreset: StateFlow<String?> = _lastPreset.asStateFlow()

    private val _presetAmount = MutableStateFlow(100f)
    val presetAmount: StateFlow<Float> = _presetAmount.asStateFlow()

    @Volatile private var applyingPreset = false

    /**
     * Try a preset on: it replaces the preset tried just before (undone first), so presets don't
     * pile up; the same preset again takes it off. Presets change only the settings they contain,
     * on top of the photo's other edits, as on the desktop.
     */
    fun applyPreset(id: String) {
        val replacing = _lastPreset.value
        if (replacing == id) {
            removePreset()
            return
        }
        _presetAmount.value = 100f
        presetStep(undoFirst = replacing != null, id = id, amount = 100f)
    }

    /** The tried-on preset's strength, 0–200 %: re-applied at the new amount, one step. */
    fun setPresetAmount(amount: Float) {
        _presetAmount.value = amount.coerceIn(0f, 200f)
    }

    fun commitPresetAmount() {
        val id = _lastPreset.value ?: return
        presetStep(undoFirst = true, id = id, amount = _presetAmount.value)
    }

    /** Take the tried-on preset off again. */
    fun removePreset() {
        if (_lastPreset.value == null) return
        viewModelScope.launch(commands) {
            applyingPreset = true
            withEngine { e -> e.execute("edit.undo") }
            _lastPreset.value = null
            afterEdit()
        }
    }

    private fun presetStep(undoFirst: Boolean, id: String, amount: Float) {
        viewModelScope.launch(commands) {
            if (interacting) {
                interacting = false
                withEngine { e -> e.execute("develop.endInteraction") }
            }
            applyingPreset = true
            withEngine { e ->
                if (undoFirst) e.execute("edit.undo")
                e.execute("preset.apply", JSONObject().put("id", id).put("amount", amount.toDouble()))
                _lastPreset.value = id
            }
            afterEdit()
        }
    }

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
    private val _cropView = MutableStateFlow<Bitmap?>(null)
    /** The photo without its crop, while the Crop tool is open. */
    val cropView: StateFlow<Bitmap?> = _cropView.asStateFlow()

    private val _cropRatio = MutableStateFlow(0f)
    /** The aspect the crop frame keeps while dragged (width / height; 0 = free). */
    val cropRatio: StateFlow<Float> = _cropRatio.asStateFlow()

    /** The crop frame was dragged: set it (normalized over the uncropped photo), one undo step. */
    fun setCropRect(x0: Float, y0: Float, x1: Float, y1: Float) {
        val r = listOf(x0, y0, x1, y1).map { it.coerceIn(0f, 1f).toDouble() }
        if (r[2] - r[0] < 0.02 || r[3] - r[1] < 0.02) return
        runEdit("crop.set", JSONObject().put("rect", JSONArray(r)))
    }

    fun cropAspect(aspect: Float) {
        _cropRatio.value = aspect
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

    /** A tool of the Masking row: brushes and Object / Describe stay open; the rest add a mask. */
    private fun maskTool(tool: Tool) {
        objectMask = null
        _objectStarted.value = false
        when (tool.kind) {
            ToolKind.Brush -> {
                _tool.value = tool
                startBrush(tool.id)
            }
            ToolKind.Object, ToolKind.Describe -> {
                _tool.value = if (_tool.value?.id == tool.id) null else tool
                refreshAiStatus()
                refreshQuickSelect()
            }
            else -> {
                _tool.value = null
                addMask(tool.id)
            }
        }
    }

    /** Leave a painting / tapping mask tool (the mask stays). */
    fun closeTool() {
        _tool.value = null
        objectMask = null
        _objectStarted.value = false
    }

    private fun addMask(kind: String) {
        viewModelScope.launch(commands) {
            when (kind) {
                "subject" -> subjectMask("Subject", invert = false)
                "background" -> subjectMask("Background", invert = true)
                "sky" -> withEngine { e -> e.execute("mask.add", JSONObject().put("kind", "sky").put("name", "Sky")) }
                else -> withEngine { e ->
                    val name = mapOf("linear" to "Linear Gradient", "radial" to "Radial Gradient", "luminanceRange" to "Luminance Range")[kind] ?: kind.replaceFirstChar { it.uppercase() }
                    e.execute("mask.add", JSONObject().put("kind", kind).put("name", name))
                }
            }
            afterEdit(selectNewest = true)
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

    /** Add (or subtract) a selection of [kind] to the selected mask, as the desktop's Add / Subtract. */
    fun addComponent(op: String, kind: String) {
        val id = _activeMask.value ?: return
        if (kind == "subject" || kind == "background") {
            messages.trySend("Subject and Background make their own masks; add them from Create New Mask")
            return
        }
        runEdit("mask.addComponent", JSONObject().put("id", id).put("op", op).put("kind", kind))
    }

    /** invert / delete one selection of the selected mask. */
    fun componentAction(index: Int, action: String) {
        val id = _activeMask.value ?: return
        runEdit("mask.component", JSONObject().put("id", id).put("component", index).put("action", action))
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
            "people" -> viewModelScope.launch(commands) {
                _busy.value = true
                try {
                    val e = engine ?: return@launch
                    try {
                        e.cutoutPeople()
                        _cutout.value = e.cutoutFrame()
                    } catch (t: Exception) {
                        val m = t.message ?: "People cutout failed"
                        messages.trySend(if (m.contains("isn't downloaded")) "$m. Get it in Downloads & Storage." else m)
                    }
                } finally {
                    _busy.value = false
                }
            }
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

    /** The top bar's Export while Remove is open: the cutout as a PNG. */
    fun exportCutout() {
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

    // ---- library: marks, filter, selection, delete, favourites, paste ------------------------------

    /** The private copy of a gallery image the engine can't read in place (see [sourceFile]). */
    private fun copyOf(image: GalleryImage): File = File(File(getApplication<Application>().filesDir, "originals"), "${image.id}.${extOf(image)}")

    private fun extOf(image: GalleryImage) = image.name.substringAfterLast('.', "jpg").filter { it.isLetterOrDigit() }.ifEmpty { "jpg" }

    /** The files the engine may know [image] by: its gallery path and its private copy. */
    private fun keysOf(image: GalleryImage): List<String> = listOfNotNull(image.path, copyOf(image).absolutePath)

    fun markOf(image: GalleryImage, marks: Map<String, Mark>): Mark? =
        image.path?.let { marks[it] } ?: if (marks.isEmpty()) null else marks[copyOf(image).absolutePath]

    fun refreshMarks() {
        viewModelScope.launch(commands) {
            val e = engine ?: return@launch
            runCatching { e.galleryMarks() }.onSuccess { _marks.value = it }
        }
    }

    fun setSource(s: LibrarySource) {
        _source.value = s
        _selection.value = emptySet()
    }

    fun setQuery(q: String) {
        _query.value = q.take(100)
    }

    fun setViewMode(m: ViewMode) {
        _viewMode.value = m
    }

    fun setSort(o: SortOrder) {
        _sort.value = o
    }

    fun toggleSelected(image: GalleryImage) {
        val s = _selection.value
        _selection.value = if (image.id in s) s - image.id else s + image.id
    }

    fun selectAll(images: List<GalleryImage>) {
        _selection.value = images.mapTo(HashSet()) { it.id }
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    private fun selectedImages(): List<GalleryImage> {
        val s = _selection.value
        return (_photos.value + _trash.value).filter { it.id in s }.distinctBy { it.id }
    }

    fun shareSelected() {
        val images = selectedImages()
        if (images.isNotEmpty()) shareOriginals.trySend(images.map { it.uri })
    }

    /**
     * Delete the selected photos: into the phone's trash (Recently Deleted, 30 days) on Android 11+,
     * for good before. Android asks to confirm.
     */
    fun deleteSelected() {
        val images = selectedImages()
        if (images.isNotEmpty()) deletions.trySend(images to if (Gallery.hasTrash) DeleteMode.Trash else DeleteMode.Delete)
    }

    /** Recently Deleted: put the selected photos back. */
    fun restoreSelected() {
        val images = selectedImages()
        if (images.isNotEmpty()) deletions.trySend(images to DeleteMode.Restore)
    }

    /** Recently Deleted: delete the selected photos for good (and their edits). */
    fun deleteSelectedForever() {
        val images = selectedImages()
        if (images.isNotEmpty()) deletions.trySend(images to DeleteMode.Delete)
    }

    /** Delete the photo being edited (to the trash where there is one). */
    fun deleteCurrent() {
        editingImage?.let { deletions.trySend(listOf(it) to if (Gallery.hasTrash) DeleteMode.Trash else DeleteMode.Delete) }
    }

    /**
     * After Android's answer: photos deleted for good leave the library with their edits; trashed
     * ones keep their edits (they may come back). The editor closes if its photo went.
     */
    fun onDeleteFinished(images: List<GalleryImage>, mode: DeleteMode) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val trashedBefore = _trash.value.mapTo(HashSet()) { it.id }
            val gone = images.filter { Gallery.isGone(app, it) }
            if (gone.isNotEmpty()) {
                gone.forEach { thumbs.remove(it.id); opened.remove(it.id) }
                withContext(commands) {
                    withEngine { e -> e.forgetPaths(gone.flatMap { keysOf(it) }) }
                    gone.forEach { g -> copyOf(g).delete() }
                }
            }
            val nowTrash = runCatching { Gallery.trashed(app) }.getOrDefault(emptyList()).mapTo(HashSet()) { it.id }
            val moved = when (mode) {
                DeleteMode.Trash -> images.count { it.id in nowTrash }
                DeleteMode.Restore -> images.count { it.id in trashedBefore && it.id !in nowTrash }
                DeleteMode.Delete -> gone.size
            }
            withContext(Dispatchers.Main) {
                val left = if (mode == DeleteMode.Restore) moved > 0 else (gone.isNotEmpty() || moved > 0)
                if (left && mode != DeleteMode.Restore && editingImage != null && images.any { it.id == editingImage?.id }) close()
                if (moved > 0 || gone.isNotEmpty()) _selection.value = emptySet()
            }
            if (moved > 0) {
                val n = if (moved == 1) "1 photo" else "$moved photos"
                messages.trySend(
                    when (mode) {
                        DeleteMode.Trash -> "$n moved to Recently Deleted"
                        DeleteMode.Restore -> "$n restored"
                        DeleteMode.Delete -> "$n deleted"
                    },
                )
            }
            refreshGallery()
        }
    }

    /** Library photos for gallery [images], importing them in place as needed. Command thread only. */
    private fun photosFor(e: Engine, images: List<GalleryImage>): List<Long> = images.mapNotNull { image ->
        runCatching { e.photoForPath(sourceFile(image).absolutePath).also { opened[image.id] = it } }
            .onFailure { Log.w(tag, "import ${image.name}", it) }
            .getOrNull()
    }

    /** Rate the selected photos 0–5 stars (`photo.rate`). */
    fun rateSelected(rating: Int) = markSelected("photo.rate", JSONObject().put("rating", rating.coerceIn(0, 5)))

    /** Flag the selected photos `pick`, `reject` or `none` (`photo.flag`); the same flag again clears it. */
    fun flagSelected(flag: String) {
        val images = selectedImages().ifEmpty { return }
        val marks = _marks.value
        val f = if (images.all { markOf(it, marks)?.flag == flag }) "none" else flag
        markSelected("photo.flag", JSONObject().put("flag", f))
    }

    private fun markSelected(command: String, params: JSONObject) {
        val images = selectedImages().ifEmpty { return }
        viewModelScope.launch(commands) {
            withEngine { e ->
                val ids = photosFor(e, images)
                if (ids.isNotEmpty()) e.execute(command, params.put("ids", JSONArray(ids)))
                _marks.value = e.galleryMarks()
            }
        }
    }

    /** Copy Edit Settings from the one selected photo (as the desktop's bottom-bar button). */
    fun copyFromSelected() {
        val image = selectedImages().singleOrNull() ?: return
        viewModelScope.launch(commands) {
            withEngine { e ->
                val id = photosFor(e, listOf(image)).firstOrNull() ?: return@withEngine
                e.execute("library.select", JSONObject().put("ids", JSONArray().put(id)).put("active", id))
                e.execute("develop.copy")
                _copied.value = true
                messages.trySend("Edit settings copied")
            }
        }
    }

    /** Paste the copied edits onto the selected photos. */
    fun pasteToSelected() {
        val images = selectedImages().ifEmpty { return }
        viewModelScope.launch(commands) {
            _busy.value = true
            try {
                withEngine { e ->
                    val ids = photosFor(e, images)
                    if (ids.isNotEmpty()) e.execute("develop.paste", JSONObject().put("ids", JSONArray(ids)))
                    _marks.value = e.galleryMarks()
                }
                images.forEach { thumbs.remove(it.id) }
                _thumbVersion.value++
                messages.trySend(if (images.size == 1) "Edits pasted" else "Edits pasted to ${images.size} photos")
            } finally {
                _busy.value = false
            }
        }
    }

    // ---- editor extras -------------------------------------------------------------------------

    fun copyEdits() {
        viewModelScope.launch(commands) {
            withEngine { e ->
                e.execute("develop.copy")
                _copied.value = true
                messages.trySend("Edits copied")
            }
        }
    }

    fun pasteEdits() = runEdit("develop.paste")

    /** The open photo's flag: `pick`, `reject` or `none`. */
    fun setFlag(flag: String) {
        val photo = _editing.value ?: return
        viewModelScope.launch(commands) {
            withEngine { e ->
                e.execute("photo.flag", JSONObject().put("ids", JSONArray().put(photo)).put("flag", flag))
                _marks.value = e.galleryMarks()
            }
            loadInfoNow()
        }
    }

    fun setRating(rating: Int) {
        val photo = _editing.value ?: return
        viewModelScope.launch(commands) {
            withEngine { e ->
                e.execute("photo.rate", JSONObject().put("ids", JSONArray().put(photo)).put("rating", rating.coerceIn(0, 5)))
                _marks.value = e.galleryMarks()
            }
            loadInfoNow()
        }
    }

    fun loadInfo() {
        viewModelScope.launch(commands) { loadInfoNow() }
    }

    private fun loadInfoNow() {
        val photo = _editing.value ?: return
        _info.value = runCatching { engine?.execute("photo.inspect", JSONObject().put("id", photo)) as? JSONObject }.getOrNull()
    }

    fun toggleHistogram() {
        _showHistogram.value = !_showHistogram.value
        if (_showHistogram.value) {
            val photo = _editing.value ?: return
            viewModelScope.launch(Dispatchers.Default) { engine?.let { updateHistogram(it, photo) } }
        }
    }

    private fun updateHistogram(e: Engine, photo: Long) {
        runCatching { e.histogram(photo, 256) }.onSuccess { if (_editing.value == photo) _histogram.value = it }
    }

    /**
     * A drag on the histogram: its five zones adjust Blacks, Shadows, Exposure, Highlights and
     * Whites (a drag of [fraction] of the histogram's width moves the control that share of its range).
     */
    fun dragZone(zone: Int, fraction: Float) {
        val id = listOf("light.blacks", "light.shadows", "light.exposure", "light.highlights", "light.whites").getOrNull(zone) ?: return
        val c = _controls.value.firstOrNull { it.id == id } ?: return
        val v = (c.value + fraction * (c.max - c.min)).coerceIn(c.min, c.max)
        if (v != c.value) setControl(id, v)
    }

    fun autoStraighten() = runEdit("crop.autoStraighten")

    private fun loadProfiles(e: Engine) {
        val list = runCatching { e.execute("profiles.list") as? JSONArray }.getOrNull() ?: return
        _profiles.value = (0 until list.length()).mapNotNull { i ->
            list.optJSONObject(i)?.let { Preset(it.optString("id"), it.optString("name"), it.optString("group", "")) }
        }.filter { it.id.isNotEmpty() }
    }

    fun setProfile(id: String) = runEdit("develop.profile", JSONObject().put("id", id))

    /** Color or B&W, as the desktop's treatment buttons. */
    fun setTreatment(bw: Boolean) {
        if ((_look.value.treatment == "bw") != bw) runEdit("develop.treatment")
    }

    /** A white-balance preset: asShot, auto, daylight, cloudy, shade, tungsten, fluorescent, flash. */
    fun setWb(mode: String) = runEdit("develop.wb", JSONObject().put("mode", mode))

    fun armWbPicker(on: Boolean) {
        _wbPicking.value = on
    }

    /** The white-balance selector's tap: [u], [v] over the shown photo. */
    fun pickWb(u: Float, v: Float) {
        val photo = _editing.value ?: return
        _wbPicking.value = false
        viewModelScope.launch(commands) {
            withEngine { e ->
                val (x, y) = e.viewToImage(photo, u, v) ?: return@withEngine
                e.execute("develop.wbPick", JSONObject().put("x", x.toDouble()).put("y", y.toDouble()))
            }
            afterEdit()
        }
    }

    fun upright(mode: String) = runEdit("geometry.upright", JSONObject().put("mode", mode))

    /** Optics' checkboxes: Enable Profile Corrections, Remove Chromatic Aberration. */
    fun setLensProfile(on: Boolean) = mergeDevelop(JSONObject().put("optics", JSONObject().put("lens_profile", on)), "Lens Corrections")

    fun setRemoveCa(on: Boolean) = mergeDevelop(JSONObject().put("optics", JSONObject().put("remove_ca", on)), "Remove Chromatic Aberration")

    private fun mergeDevelop(patch: JSONObject, label: String) = runEdit("develop.merge", JSONObject().put("settings", patch).put("label", label))

    // ---- brush, Object and Describe masks -------------------------------------------------------

    fun setBrush(b: BrushSettings) {
        _brush.value = b
    }

    /** A new brush mask; Dodge and Burn start with ±⅓ EV. Then strokes on the photo paint it. */
    fun startBrush(kind: String) {
        viewModelScope.launch(commands) {
            withEngine { e ->
                val name = kind.replaceFirstChar { it.uppercase() }
                e.execute("mask.add", JSONObject().put("kind", "brush").put("name", name))
                when (kind) {
                    "dodge" -> e.execute("mask.adjust", JSONObject().put("values", JSONObject().put("exposure", 0.35)))
                    "burn" -> e.execute("mask.adjust", JSONObject().put("values", JSONObject().put("exposure", -0.35)))
                }
                _brush.value = _brush.value.copy(erase = false)
            }
            afterEdit(selectNewest = true)
        }
    }

    /** One stroke on the photo; [points] are 0..1 over the shown (cropped) photo. */
    fun brushStroke(points: List<Pair<Float, Float>>) {
        val photo = _editing.value ?: return
        if (points.isEmpty()) return
        viewModelScope.launch(commands) {
            withEngine { e ->
                val mapped = points.mapNotNull { (u, v) -> e.viewToImage(photo, u, v) }
                if (mapped.isEmpty()) return@withEngine
                val b = _brush.value
                val o = JSONObject()
                _activeMask.value?.let { o.put("id", it) }
                e.execute(
                    "mask.brushStroke",
                    o
                        .put("points", JSONArray(mapped.map { JSONArray(listOf(it.first.toDouble(), it.second.toDouble())) }))
                        .put("size", b.size.toDouble()).put("feather", b.feather.toDouble()).put("flow", b.flow.toDouble())
                        .put("erase", b.erase),
                )
            }
            afterEdit()
        }
    }

    /** Object (EfficientSAM-Ti): the first tap makes the mask, later taps add (or with [exclude], remove) parts. */
    private val _objectExclude = MutableStateFlow(false)
    /** Object taps remove parts instead of adding them. */
    val objectExclude: StateFlow<Boolean> = _objectExclude.asStateFlow()

    fun setObjectExclude(on: Boolean) {
        _objectExclude.value = on
    }

    /** The Object mask the taps refine (null: the next tap makes a new one). */
    private var objectMask: Int? = null


    private val _quickSelect = MutableStateFlow(QuickSelectStatus())
    /** The small Object model's state (used while SAM 3 isn't installed). */
    val quickSelect: StateFlow<QuickSelectStatus> = _quickSelect.asStateFlow()

    private var quickPolling = false

    private val _aiModels = MutableStateFlow<List<LcAiModel>>(emptyList())
    /** The optional on-device models (Downloads & Storage lists them). */
    val aiModels: StateFlow<List<LcAiModel>> = _aiModels.asStateFlow()

    /** Read the models' state; while one downloads, read it again every second. Command thread. */
    private fun readQuickSelect() {
        val e = engine ?: return
        val r = runCatching { e.execute("ai.models") as? JSONObject }.getOrNull() ?: return
        val list = r.optJSONArray("models") ?: return
        _aiModels.value = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.map { m ->
            val dl = m.optJSONObject("download")
            // the engine's download states: running, done, failed, cancelled
            val state = dl?.optString("state", "") ?: ""
            LcAiModel(
                id = m.optString("id"),
                name = m.optString("name"),
                purpose = m.optString("purpose"),
                size = m.optLong("size"),
                licence = m.optString("licence"),
                ready = m.optBoolean("ready"),
                downloading = state == "running",
                done = dl?.optLong("done") ?: 0,
                total = dl?.optLong("total") ?: 0,
                error = dl?.optString("error")?.takeIf { state == "failed" && it.isNotBlank() },
            )
        }
        reportDownloads()
        _aiModels.value.firstOrNull { it.id == QUICK_SELECT }?.let { m ->
            _quickSelect.value = QuickSelectStatus(m.ready, m.downloading, m.done, m.total, m.size, m.licence, m.error)
        }
        if (_aiModels.value.any { it.downloading } && !quickPolling) {
            quickPolling = true
            viewModelScope.launch {
                try {
                    while (true) {
                        delay(1000)
                        withContext(commands) { readQuickSelect() }
                        if (_aiModels.value.none { it.downloading }) break
                    }
                } finally {
                    quickPolling = false
                }
            }
        }
    }

    fun refreshQuickSelect() {
        viewModelScope.launch(commands) { readQuickSelect() }
    }

    /** Download the small Object model (after the person accepted its licence in the card). */
    fun downloadQuickSelect() = downloadLcModel(QUICK_SELECT)

    private val notifier by lazy { DownloadNotifier(getApplication()) }

    /** The model whose download the app last saw running (to tell when it ends). */
    private var watching: String? = null

    /** Asks MainActivity for the notification permission (Android 13+) at the first download. */
    val askNotifications = Channel<Unit>(Channel.CONFLATED)

    /** Progress in the notification shade; a message when a download ends. */
    private fun reportDownloads() {
        val models = _aiModels.value
        val running = models.firstOrNull { it.downloading }
        if (running != null) {
            watching = running.id
            notifier.progress(running.name, running.done, running.total.takeIf { it > 0 } ?: running.size)
            return
        }
        val id = watching ?: return
        watching = null
        val m = models.firstOrNull { it.id == id } ?: return notifier.clear()
        when {
            m.ready -> {
                notifier.finished(m.name, ok = true)
                messages.trySend("${m.name} downloaded. Ready to use.")
            }
            m.error != null -> {
                notifier.finished(m.name, ok = false, detail = m.error)
                messages.trySend("${m.name} didn't download: ${m.error}")
            }
            else -> notifier.clear() // cancelled
        }
    }

    /** Download model [id] (the person accepted its licence first). */
    fun downloadLcModel(id: String) {
        askNotifications.trySend(Unit)
        viewModelScope.launch(commands) {
            val name = _aiModels.value.firstOrNull { it.id == id }?.name ?: "The model"
            var started = false
            withEngine { e ->
                e.execute("ai.download", JSONObject().put("id", id).put("acknowledged", true))
                started = true
            }
            if (started) messages.trySend("Downloading $name… Progress shows in Downloads & Storage and in your notifications.")
            readQuickSelect()
        }
    }

    fun cancelLcModel(id: String) {
        viewModelScope.launch(commands) {
            withEngine { e -> e.execute("ai.cancelDownload", JSONObject().put("id", id)) }
            readQuickSelect()
        }
    }

    fun removeLcModel(id: String) {
        viewModelScope.launch(commands) {
            withEngine { e -> e.execute("ai.removeModel", JSONObject().put("id", id)) }
            readQuickSelect()
        }
    }

    /** Run an AI command with the busy spinner; a missing model says where it downloads. */
    private fun aiCommand(id: String, params: JSONObject, then: (Any?) -> Unit = {}) {
        viewModelScope.launch(commands) {
            _busy.value = true
            try {
                val e = engine ?: return@launch
                val r = try {
                    e.execute(id, params)
                } catch (t: EngineException) {
                    val m = t.message ?: t.kind
                    messages.trySend(if (m.contains("isn't downloaded")) "$m. Get it in Downloads & Storage." else m)
                    return@launch
                }
                then(r)
                afterEdit()
            } finally {
                _busy.value = false
            }
        }
    }

    // ---- AI Remove, Find Objects --------------------------------------------------------------

    private val _aiErase = MutableStateFlow(AiEraseSettings())
    /** AI Remove: while on, a drag on the photo is replaced with generated content. */
    val aiErase: StateFlow<AiEraseSettings> = _aiErase.asStateFlow()

    fun setAiErase(s: AiEraseSettings) {
        _aiErase.value = s
    }

    /** One AI Remove stroke ([uv]: points across the shown photo, 0..1). */
    fun generativeStroke(uv: List<Pair<Float, Float>>) {
        val photo = _editing.value ?: return
        val e = engine ?: return
        if (uv.isEmpty()) return
        val flat = FloatArray(uv.size * 2) { i -> if (i % 2 == 0) uv[i / 2].first else uv[i / 2].second }
        val pts = runCatching { e.viewToCorrected(photo, flat) }.getOrNull() ?: return
        if (pts.size < 2) return
        val points = JSONArray()
        for (i in 0 until pts.size / 2) points.put(JSONArray().put(pts[2 * i].toDouble()).put(pts[2 * i + 1].toDouble()))
        val st = _aiErase.value
        aiCommand("spot.generate", JSONObject().put("points", points).put("size", st.size.toDouble()).put("quality", if (st.best) "best" else "fast"))
    }

    private val _suggestions = MutableStateFlow<List<Suggestion>>(emptyList())
    /** What Find Objects found, drawn as boxes on the photo; tap one to remove it. */
    val suggestions: StateFlow<List<Suggestion>> = _suggestions.asStateFlow()

    fun findObjects() {
        aiCommand("ai.detect", JSONObject().put("min", 0.45)) { r ->
            val objs = (r as? JSONObject)?.optJSONArray("objects")
            _suggestions.value = (0 until (objs?.length() ?: 0)).mapNotNull { i ->
                objs?.optJSONObject(i)?.let { o ->
                    val b = o.optJSONArray("box") ?: return@let null
                    Suggestion(o.optString("label"), o.optDouble("score").toFloat(), b.optDouble(0).toFloat(), b.optDouble(1).toFloat(), b.optDouble(2).toFloat(), b.optDouble(3).toFloat())
                }
            }
            if (_suggestions.value.isEmpty()) messages.trySend("Nothing found to remove")
        }
    }

    fun clearSuggestions() {
        _suggestions.value = emptyList()
    }

    /** A suggestion's box on the shown photo (0..1: x0, y0, x1, y1), or null where it has no place. */
    fun suggestionBox(s: Suggestion): FloatArray? {
        val photo = _editing.value ?: return null
        val e = engine ?: return null
        val v = runCatching { e.maskToView(photo, -1L, floatArrayOf(s.x0, s.y0, s.x1, s.y1)) }.getOrNull() ?: return null
        if (v.size < 4 || v.any { !it.isFinite() }) return null
        return floatArrayOf(minOf(v[0], v[2]), minOf(v[1], v[3]), maxOf(v[0], v[2]), maxOf(v[1], v[3]))
    }

    /** Remove suggestion [s]: an AI Remove stroke along its box's middle, as wide as the box. */
    fun removeSuggestion(s: Suggestion, viewW: Float, viewH: Float) {
        _suggestions.value = _suggestions.value - s
        val b = suggestionBox(s) ?: return
        val (x0, y0, x1, y1) = b.toList()
        val bw = (x1 - x0) * viewW
        val bh = (y1 - y0) * viewH
        val long = maxOf(viewW, viewH).coerceAtLeast(1f)
        val radius = minOf(bw, bh) / 2f * 1.15f
        val size = (radius / long).coerceIn(0.005f, 0.25f)
        val steps = (maxOf(bw, bh) / radius.coerceAtLeast(1f)).toInt().coerceIn(1, 24)
        val uv = (0..steps).map { i ->
            val t = i / steps.toFloat()
            if (bw >= bh) (x0 + (x1 - x0) * (0.5f / steps + t * (1 - 1f / steps))) to ((y0 + y1) / 2)
            else ((x0 + x1) / 2) to (y0 + (y1 - y0) * (0.5f / steps + t * (1 - 1f / steps)))
        }
        val keep = _aiErase.value
        _aiErase.value = keep.copy(size = size)
        generativeStroke(uv)
        _aiErase.value = keep
    }

    // ---- Lens Blur -------------------------------------------------------------------------------

    /** Lens Blur's Apply; turning it on with no blur yet starts at 50, as the desktop app does. */
    fun setLensBlur(on: Boolean) {
        val patch = JSONObject().put("enabled", on)
        val amount = _controls.value.firstOrNull { it.id == "lensBlur.amount" }?.value ?: 0f
        if (on && amount <= 0f) patch.put("amount", 50.0)
        mergeDevelop(JSONObject().put("lens_blur", patch), "Lens Blur")
    }

    /** Lens Blur from a depth map (the depth model estimates it once and it is stored), or without. */
    fun setBlurDepth(on: Boolean) = aiCommand("lensBlur.depth", if (on) JSONObject() else JSONObject().put("clear", true))

    /** What Lens Blur keeps in focus without a mask: `auto` (the subject), `round`, `wide` or `tall`. */
    fun setBlurShape(shape: String) =
        mergeDevelop(JSONObject().put("lens_blur", JSONObject().put("shape", shape).put("area", JSONObject.NULL)), "Lens Blur Shape")

    // ---- History and Versions -------------------------------------------------------------------

    private val _history = MutableStateFlow(HistoryState())
    val history: StateFlow<HistoryState> = _history.asStateFlow()

    /** Read the open photo's History and versions. */
    fun loadHistory() {
        viewModelScope.launch(commands) {
            val r = runCatching { engine?.execute("history.list") as? JSONObject }.getOrNull() ?: return@launch
            val h = r.optJSONArray("history")
            val v = r.optJSONArray("versions")
            _history.value = HistoryState(
                steps = (0 until (h?.length() ?: 0)).map { h?.optString(it, "") ?: "" },
                cursor = r.optInt("cursor", -1),
                versions = (0 until (v?.length() ?: 0)).map { v?.optJSONObject(it)?.optString("name", "Version") ?: "Version" },
            )
        }
    }

    private fun thenHistory(id: String, params: JSONObject) {
        viewModelScope.launch(commands) {
            withEngine { e -> e.execute(id, params) }
            afterEdit()
        }
        loadHistory()
    }

    fun restoreHistory(index: Int) = thenHistory("history.restore", JSONObject().put("index", index))
    fun createVersion(name: String) = thenHistory("version.create", JSONObject().put("name", name.trim().ifEmpty { "Version" }))
    fun restoreVersion(index: Int) = thenHistory("version.restore", JSONObject().put("index", index))
    fun deleteVersion(index: Int) = thenHistory("version.delete", JSONObject().put("index", index))

    // ---- mask overlay ----------------------------------------------------------------------------

    private val _maskOverlay = MutableStateFlow(true)
    /** Masking tints the selected mask's area red on the photo. */
    val maskOverlay: StateFlow<Boolean> = _maskOverlay.asStateFlow()

    /** Mask [mask]'s coordinates → the shown photo (0..1 pairs; NaN where a point has no place). */
    fun maskToView(mask: Int, pts: FloatArray): FloatArray {
        val photo = _editing.value ?: return FloatArray(0)
        val e = engine ?: return FloatArray(0)
        return runCatching { e.maskToView(photo, mask.toLong(), pts) }.getOrDefault(FloatArray(0))
    }

    /** Points on the shown photo (0..1 pairs) → mask [mask]'s coordinates. */
    fun viewToMask(mask: Int, uv: FloatArray): FloatArray {
        val photo = _editing.value ?: return FloatArray(0)
        val e = engine ?: return FloatArray(0)
        return runCatching { e.viewToMask(photo, mask.toLong(), uv) }.getOrDefault(FloatArray(0))
    }

    /** Width ÷ height of the space radial radii are measured in (long-edge units). */
    fun maskAspect(): Double {
        val photo = _editing.value ?: return 1.0
        val e = engine ?: return 1.0
        return runCatching { e.maskAspect(photo) }.getOrDefault(1.0)
    }

    /** A gradient's handle dragged: part [component] of the selected mask takes [shape] (one undo step per drag). */
    fun moveComponent(component: Int, shape: JSONObject, done: Boolean) {
        val id = _activeMask.value ?: return
        _masks.value = _masks.value.map { m ->
            if (m.id != id) m else m.copy(components = m.components.mapIndexed { i, c -> if (i == component) c.copy(shape = shape) else c })
        }
        val copy = JSONObject(shape.toString())
        viewModelScope.launch(commands) {
            withEngine { e ->
                begin(e, "Move Mask")
                e.execute("mask.update", JSONObject().put("id", id).put("component", component).put("shape", copy))
            }
            requestRender(draft = !done)
        }
        if (done) finishInteraction()
    }

    fun setMaskOverlay(on: Boolean) {
        _maskOverlay.value = on
        requestRender(draft = false)
    }

    fun cancelQuickSelect() {
        viewModelScope.launch(commands) {
            withEngine { e -> e.execute("ai.cancelDownload", JSONObject().put("id", QUICK_SELECT)) }
            readQuickSelect()
        }
    }

    private val _objectStarted = MutableStateFlow(false)
    /** An Object selection has been made with the open tool (the "tap an object" hint can go). */
    val objectStarted: StateFlow<Boolean> = _objectStarted.asStateFlow()

    /**
     * A tap on the photo with Object open: the first makes a new Object mask; later ones add the
     * tapped object to it, or with Subtract on, cut it out (each as a part of the mask of its own).
     */
    fun objectTap(u: Float, v: Float) {
        val photo = _editing.value ?: return
        val first = objectMask == null || objectMask != _activeMask.value
        // read now, not when the tap handler was made: Add / Subtract may have changed since
        val subtract = _objectExclude.value && !first
        viewModelScope.launch(commands) {
            _busy.value = true
            try {
                withEngine { e ->
                    val (x, y) = e.viewToImage(photo, u, v) ?: return@withEngine
                    val tap = JSONArray(listOf(JSONArray(listOf(x.toDouble(), y.toDouble(), true))))
                    val p = JSONObject().put("taps", tap)
                    if (!first) objectMask?.let { p.put("id", it).put("op", if (subtract) "subtract" else "add") }
                    e.execute("ai.select", p)
                    _objectStarted.value = true
                }
                afterEdit(selectNewest = first)
                if (first) objectMask = _activeMask.value
            } finally {
                _busy.value = false
            }
        }
    }

    /** Describe (SAM 3): select everything matching [text] ("sky", "the red car", "car, road"). */
    fun describe(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch(commands) {
            _busy.value = true
            try {
                withEngine { e -> e.execute("mask.add", JSONObject().put("kind", "prompt").put("name", text.trim().take(40)).put("text", text.trim())) }
                afterEdit(selectNewest = true)
            } finally {
                _busy.value = false
            }
        }
    }

    fun refreshAiStatus() {
        viewModelScope.launch(commands) { readAiStatus() }
    }

    private fun readAiStatus() {
        val o = runCatching { engine?.execute("segment.model.status") as? JSONObject }.getOrNull() ?: return
        val d = o.optJSONObject("download")
        _ai.value = AiModelStatus(
            available = o.optBoolean("available", Engine.aiMasksAvailable) || Engine.aiMasksAvailable,
            installed = o.optBoolean("installed"),
            downloading = d?.optBoolean("running") == true,
            done = d?.optLong("done") ?: 0,
            total = d?.optLong("total") ?: 0,
            sizeBytes = o.optLong("sizeBytes", 3_439_938_512),
            license = o.optString("license", "SAM License (Meta)"),
            error = d?.optString("error")?.takeIf { it.isNotEmpty() && it != "null" },
            mirrors = o.optInt("mirrors", 0),
        )
    }

    private val prefs by lazy { getApplication<Application>().getSharedPreferences("lightcraft", android.content.Context.MODE_PRIVATE) }

    private val _samSource = MutableStateFlow(SamSource())
    /** Where SAM 3 downloads from (kept in the app's private settings). */
    val samSource: StateFlow<SamSource> = _samSource.asStateFlow()

    /** Read the saved download source and hand it to the engine (its documented environment settings). */
    private fun loadSamSource() {
        _samSource.value = SamSource(prefs.getString("sam3.token", "") ?: "", prefs.getString("sam3.mirror", "") ?: "")
        exportSamSource(_samSource.value)
    }

    private fun exportSamSource(src: SamSource) {
        runCatching {
            val mirror = src.mirror.trim().ifEmpty { if (src.token.isNotBlank()) HF_SAM3 else "" }
            if (mirror.isEmpty()) android.system.Os.unsetenv("LIGHTCRAFT_SAM3_MIRRORS") else android.system.Os.setenv("LIGHTCRAFT_SAM3_MIRRORS", mirror, true)
            if (src.token.isBlank()) android.system.Os.unsetenv("LIGHTCRAFT_SAM3_TOKEN") else android.system.Os.setenv("LIGHTCRAFT_SAM3_TOKEN", src.token.trim(), true)
        }.onFailure { Log.w(tag, "SAM 3 source", it) }
    }

    /**
     * Save where SAM 3 downloads from: a Hugging Face access token (Meta's page, once the person
     * accepted the licence there) and / or a mirror URL. Blank clears.
     */
    fun setSamSource(token: String, mirror: String) {
        val m = mirror.trim()
        if (m.isNotEmpty() && !m.startsWith("https://")) {
            messages.trySend("A mirror must be an https:// address")
            return
        }
        val src = SamSource(token.trim(), m)
        prefs.edit().putString("sam3.token", src.token).putString("sam3.mirror", src.mirror).apply()
        _samSource.value = src
        exportSamSource(src)
        refreshAiStatus()
    }

    /**
     * Install SAM 3 from files the person has: `model.safetensors`, `vocab.json` and `merges.txt`
     * (by those names) are copied into the model folder.
     */
    fun importSamFiles(uris: List<android.net.Uri>) {
        val app = getApplication<Application>()
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            try {
                val dir = File(File(app.filesDir, "models"), "sam3").apply { mkdirs() }
                val wanted = setOf("model.safetensors", "vocab.json", "merges.txt")
                var copied = 0
                for (uri in uris) {
                    val name = app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: continue
                    if (name !in wanted) {
                        messages.trySend("$name isn't one of the SAM 3 files (model.safetensors, vocab.json, merges.txt)")
                        continue
                    }
                    val part = File(dir, "$name.import")
                    app.contentResolver.openInputStream(uri)?.use { input -> part.outputStream().use { input.copyTo(it, 1 shl 20) } } ?: continue
                    if (!part.renameTo(File(dir, name))) part.delete() else copied++
                }
                val model = File(dir, "model.safetensors")
                if (model.exists() && model.length() != _ai.value.sizeBytes) {
                    messages.trySend("model.safetensors is ${model.length()} bytes, not the ${_ai.value.sizeBytes} of Meta's Hugging Face release; it may not load")
                }
                withContext(commands) { readAiStatus() }
                _storage.value = measureStorage()
                messages.trySend(if (_ai.value.installed) "SAM 3 is installed" else "Imported $copied file(s); SAM 3 needs all three")
            } catch (t: Throwable) {
                Log.w(tag, "import SAM 3", t)
                messages.trySend("Could not import: ${t.message ?: "the files are not readable"}")
            } finally {
                _busy.value = false
            }
        }
    }

    /** Download the SAM 3 model (the person agreed in the dialog); progress is polled until done. */
    fun downloadAiModel() {
        viewModelScope.launch(commands) {
            withEngine { e -> e.execute("segment.model.download", JSONObject().put("acknowledged", true)) }
            readAiStatus()
        }
        viewModelScope.launch {
            do {
                kotlinx.coroutines.delay(1000)
                withContext(commands) { readAiStatus() }
                if (_page.value == Page.Downloads) _storage.value = withContext(Dispatchers.IO) { measureStorage() }
            } while (_ai.value.downloading)
            if (_ai.value.installed) messages.trySend("AI object selection is ready")
            _ai.value.error?.let { messages.trySend("Download stopped: $it") }
        }
    }

    fun cancelAiDownload() {
        viewModelScope.launch(commands) {
            withEngine { e -> e.execute("segment.model.cancel") }
            readAiStatus()
        }
    }

    // ---- downloads & storage ---------------------------------------------------------------------

    fun openPage(p: Page) {
        _page.value = p
        if (p == Page.Downloads) refreshDownloads()
    }

    /** Re-read both models' state and the storage use. */
    fun refreshDownloads() {
        val app = getApplication<Application>()
        refreshAiStatus()
        refreshQuickSelect()
        viewModelScope.launch(Dispatchers.IO) {
            if (!_seg.value.downloading) _seg.value = _seg.value.copy(checked = true, installed = Segmenter.installed(app))
            _storage.value = measureStorage()
        }
    }

    private fun dirSize(f: File): Long = runCatching { f.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)

    private fun measureStorage(): StorageUse {
        val app = getApplication<Application>()
        return StorageUse(
            catalog = dirSize(File(app.filesDir, "catalog")),
            copies = dirSize(File(app.filesDir, "originals")),
            exports = dirSize(File(app.cacheDir, "exports")),
            models = dirSize(File(app.filesDir, "models")),
        )
    }

    /** Have Play services download the subject-segmentation model now. */
    fun downloadSegModel() {
        val app = getApplication<Application>()
        _seg.value = _seg.value.copy(downloading = true, progress = 0f, error = null)
        Segmenter.install(app, progress = { f -> _seg.value = _seg.value.copy(progress = f) }) { err ->
            _seg.value = SegModelStatus(checked = true, installed = err == null, error = err)
        }
    }

    /** Let Play services remove the subject-segmentation model (it comes back with the next use). */
    fun releaseSegModel() {
        Segmenter.release(getApplication())
        _seg.value = _seg.value.copy(installed = false)
        messages.trySend("Google Play services may now remove the model; it downloads again when needed")
    }

    /** Delete the SAM 3 model's files (a running download is stopped first). */
    fun deleteAiModel() {
        val app = getApplication<Application>()
        viewModelScope.launch(commands) {
            withEngine { e -> runCatching { e.execute("segment.model.cancel") } }
            val dir = File(File(app.filesDir, "models"), "sam3")
            val ok = runCatching { dir.deleteRecursively() }.getOrDefault(false)
            readAiStatus()
            _storage.value = measureStorage()
            messages.trySend(if (ok) "SAM 3 deleted" else "Some SAM 3 files could not be deleted")
        }
    }

    /** Clear the private copies of photos LightCraft could not read in place (made again when needed). */
    fun clearCopies() {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            File(app.filesDir, "originals").listFiles()?.forEach { it.delete() }
            _storage.value = measureStorage()
            messages.trySend("Photo copies cleared")
        }
    }

    /** Clear exported files waiting in the share cache. */
    fun clearExports() {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            File(app.cacheDir, "exports").listFiles()?.forEach { it.deleteRecursively() }
            _storage.value = measureStorage()
            messages.trySend("Export cache cleared")
        }
    }

    // ---- export --------------------------------------------------------------------------------

    /** Export with [choice]: into the phone's gallery (Pictures/LightCraft) or to the share sheet. */
    fun export(choice: ExportChoice, toGallery: Boolean) {
        val photo = _editing.value ?: return
        viewModelScope.launch(commands) {
            _busy.value = true
            try {
                withEngine { e ->
                    val app = getApplication<Application>()
                    val dir = File(app.cacheDir, "exports").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val o = JSONObject().put("format", choice.format)
                    if (choice.format in setOf("jpeg", "webp", "avif")) o.put("quality", choice.quality)
                    if (choice.longEdge > 0) o.put("resize", JSONObject().put("mode", "longEdge").put("value", choice.longEdge).put("dontEnlarge", true))
                    val file = File(e.export(photo, o, dir.absolutePath))
                    if (toGallery) {
                        val name = Gallery.saveToGallery(app, file, choice.mime)
                        messages.trySend("Saved $name to Pictures/LightCraft")
                        refreshGallery()
                    } else {
                        shares.trySend(file)
                    }
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
    private fun afterEdit(selectNewest: Boolean = false) {
        loadState()
        if (selectNewest) _masks.value.maxByOrNull { it.id }?.let { _activeMask.value = it.id }
        // a preset being tried on leaves the previews as they are (they show the photo without it);
        // any other edit makes them stale
        if (applyingPreset) {
            applyingPreset = false
        } else {
            _lastPreset.value = null
            _presetThumbs.value = emptyMap()
        }
        requestRender(draft = false)
    }

    /** Sliders (`develop.controls`), point curves and masks (`develop.get`). Command thread only. */
    private fun loadState() {
        val e = engine ?: return
        val d0 = runCatching { e.execute("develop.get") as? JSONObject }.getOrNull()
        relativeWb = _editing.value?.let { runCatching { !e.kelvinWb(it) }.getOrNull() } ?: false
        val asShot = d0?.optJSONObject("wb")?.optString("mode", "")?.equals("asShot", ignoreCase = true) == true
        runCatching { e.execute("develop.controls") as? JSONArray }.getOrNull()?.let { all ->
            val list = (0 until all.length()).mapNotNull { i -> all.optJSONObject(i)?.let { runCatching { Control.fromJson(it) }.getOrNull() } }
            _controls.value = if (relativeWb) list.map { RelativeWb.show(it, asShot) } else list
        }
        val d = d0 ?: return
        _look.value = Look(
            treatment = d.optString("treatment", "color"),
            profile = d.optJSONObject("profile")?.optString("id", "lc.color") ?: "lc.color",
            profileAmount = d.optJSONObject("profile")?.optDouble("amount", 100.0)?.toFloat() ?: 100f,
            wb = d.optJSONObject("wb")?.optString("mode", "asShot") ?: "asShot",
            lensProfile = d.optJSONObject("optics")?.optBoolean("lens_profile") ?: false,
            removeCa = d.optJSONObject("optics")?.optBoolean("remove_ca") ?: false,
            blur = d.optJSONObject("lens_blur")?.optBoolean("enabled") ?: false,
            blurShape = d.optJSONObject("lens_blur")?.optString("shape", "auto")?.ifBlank { "auto" } ?: "auto",
            blurDepth = d.optJSONObject("lens_blur")?.let { it.has("depth") && !it.isNull("depth") } ?: false,
            crop = d.optJSONObject("crop")?.optJSONObject("geometry")?.optJSONObject("rect")?.let { r ->
                listOf("x0", "y0", "x1", "y1").map { r.optDouble(it, if (it.endsWith("1")) 1.0 else 0.0).toFloat().coerceIn(0f, 1f) }
            } ?: listOf(0f, 0f, 1f, 1f),
        )
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
        if (_tab.value == MainTab.Crop) {
            // the crop tool draws its frame over the whole photo
            runCatching { e.renderUncropped(photo, w, h, draft) }
                .onSuccess { if (_editing.value == photo) _cropView.value = it }
                .onFailure { messages.trySend("Render failed: ${it.message}") }
            return
        }
        val overlay = _activeMask.value?.takeIf { _tab.value == MainTab.Masking && _maskOverlay.value }
        runCatching { if (overlay != null) e.renderMasked(photo, w, h, overlay.toLong(), draft) else e.render(photo, w, h, draft = draft) }
            .onSuccess {
                if (_editing.value == photo) _preview.value = it
                if (!draft && _showHistogram.value) updateHistogram(e, photo)
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

/** The small tap-to-select model's id in `ai.models`. */
private const val QUICK_SELECT = "efficientsam-ti"
