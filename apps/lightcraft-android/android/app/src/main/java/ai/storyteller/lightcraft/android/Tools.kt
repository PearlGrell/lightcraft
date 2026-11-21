package ai.storyteller.lightcraft.android

import org.json.JSONObject

/**
 * Labels the phone shows instead of the engine's where two sliders would otherwise read the same
 * ("Red Hue" in both Mixer and Calibration, "Detail" twice under Noise, "Midpoint" in Vignette and
 * Lens…), so no option appears twice under one name.
 */
object Labels {
    private val names = mapOf(
        "calibration.redHue" to "Red Primary Hue",
        "calibration.redSat" to "Red Primary Saturation",
        "calibration.greenHue" to "Green Primary Hue",
        "calibration.greenSat" to "Green Primary Saturation",
        "calibration.blueHue" to "Blue Primary Hue",
        "calibration.blueSat" to "Blue Primary Saturation",
        "enhance.denoise" to "AI Denoise",
        "detail.nrLuminance" to "Luminance",
        "detail.nrDetail" to "Luminance Detail",
        "detail.nrContrast" to "Luminance Contrast",
        "detail.nrColor" to "Color",
        "detail.nrColorDetail" to "Color Detail",
        "detail.nrColorSmoothness" to "Color Smoothness",
        "detail.sharpenAmount" to "Amount",
        "detail.sharpenDetail" to "Detail",
        "vignette.amount" to "Vignette",
        "vignette.highlights" to "Highlight Priority",
        "optics.vignettingMidpoint" to "Vignetting Midpoint",
        "lensBlur.amount" to "Blur Amount",
    )

    fun of(id: String, engine: String): String = names[id] ?: engine
}

/** One develop slider as the engine describes it (`develop.controls`), with the photo's value. */
data class Control(
    val id: String,
    val label: String,
    val section: String,
    val min: Float,
    val max: Float,
    val default: Float,
    val decimals: Int,
    val value: Float,
    val unit: String = "",
    val track: Track = Track.Plain,
) {
    companion object {
        fun fromJson(o: JSONObject): Control {
            val id = o.getString("id")
            return Control(
                id = id,
                label = Labels.of(id, o.optString("label", id)),
                section = o.optString("section", ""),
                min = o.optDouble("min", -100.0).toFloat(),
                max = o.optDouble("max", 100.0).toFloat(),
                default = o.optDouble("default", 0.0).toFloat(),
                decimals = o.optInt("decimals", 0),
                value = o.optDouble("value", o.optDouble("default", 0.0)).toFloat(),
                unit = when (id) {
                    "light.exposure" -> "EV"
                    "wb.temp" -> "K"
                    else -> ""
                },
                track = Track.fromJson(o.optJSONObject("track")),
            )
        }
    }
}

/**
 * How a slider's track is drawn, from the engine's control spec (`track` in `develop.controls`):
 * plain grey, or a colour gradient that shows what the slider does (Temp blue → yellow…).
 */
sealed interface Track {
    data object Plain : Track
    data object Centered : Track
    data object Temp : Track
    data object Tint : Track
    data object Rainbow : Track
    data class Gradient(val from: Int, val to: Int) : Track
    data class Hue(val band: Int) : Track
    data class Sat(val band: Int) : Track
    data class Lum(val band: Int) : Track

    companion object {
        fun fromJson(o: JSONObject?): Track {
            if (o == null) return Plain
            val band = o.optInt("band", 0).coerceIn(0, Bands.all.size - 1)
            return when (o.optString("kind")) {
                "centered" -> Centered
                "temp" -> Temp
                "tint" -> Tint
                "rainbow" -> Rainbow
                "hue" -> Hue(band)
                "sat" -> Sat(band)
                "lum" -> Lum(band)
                "gradient" -> {
                    val from = parseHex(o.optString("from")) ?: return Plain
                    val to = parseHex(o.optString("to")) ?: return Plain
                    Gradient(from, to)
                }
                else -> Plain
            }
        }

        /** `#rrggbb` → opaque ARGB, or null when it isn't one. */
        fun parseHex(s: String): Int? {
            val h = s.removePrefix("#")
            if (h.length != 6) return null
            return h.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
        }
    }
}

/** A colour band of the HSL mixer (`mixer.<id>.hue|sat|lum`), with the colour its swatch shows. */
data class Band(val id: String, val label: String, val argb: Int)

object Bands {
    val all = listOf(
        Band("red", "Red", 0xFFF0463C.toInt()),
        Band("orange", "Orange", 0xFFF39A2B.toInt()),
        Band("yellow", "Yellow", 0xFFE8D33A.toInt()),
        Band("green", "Green", 0xFF45C35A.toInt()),
        Band("aqua", "Aqua", 0xFF3CC6C6.toInt()),
        Band("blue", "Blue", 0xFF4677F0.toInt()),
        Band("purple", "Purple", 0xFF9757EC.toInt()),
        Band("magenta", "Magenta", 0xFFDE4CC4.toInt()),
    )
}

/** A colour grading range (`grading.<id>.hue|sat|lum`). */
data class Range(val id: String, val label: String)

object Ranges {
    val all = listOf(Range("shadows", "Shadows"), Range("midtones", "Midtones"), Range("highlights", "Highlights"), Range("global", "Global"))
}

/**
 * A sub-panel of a tool ("Mixer" under Color…): the engine controls whose id starts with one of
 * [prefixes] and none of [exclude], in the engine's order. Driven by the engine's control list, so
 * every slider the engine has shows up without a second list to keep in sync.
 */
data class Group(
    val title: String,
    val prefixes: List<String>,
    val exclude: List<String> = emptyList(),
    val style: GroupStyle = GroupStyle.Sliders,
) {
    fun matches(id: String) = prefixes.any { id.startsWith(it) } && exclude.none { id.startsWith(it) }
}

/** The six tabs of the editor's bottom bar. */
enum class MainTab(val label: String) { Quick("Quick edit"), Presets("Presets"), Crop("Crop"), Edit("Edit"), Masking("Masking"), Remove("Remove") }

enum class ToolKind { Sliders, Action, Curve, LensBlur, Profile, Heal }

/** What a tap on the photo does in the Edit tab (one picker at a time). */
enum class TapMode { None, WhiteBalance, PointColor, Heal, RedEye, AiRemove }

/** An optional on-device AI model (`ai.models`) and its download. */
data class AiModel(
    val id: String,
    val name: String,
    val purpose: String,
    val size: Long,
    val licence: String,
    val source: String,
    val ready: Boolean,
    val downloading: Boolean = false,
    val done: Long = 0,
    val total: Long = 0,
    val error: String = "",
)

/** A person or object the detector found: its label, confidence and box (0..1 on the photo as shot). */
data class Suggestion(val label: String, val score: Float, val x0: Float, val y0: Float, val x1: Float, val y1: Float)

/** The develop state the switches and pickers show (`develop.get`). */
data class DevelopState(
    val wbMode: String = "asShot",
    val hdr: Boolean = false,
    val profile: String = "",
    val upright: String = "off",
    val bw: Boolean = false,
    val spots: Int = 0,
    val pointColors: Int = 0,
    val redEyes: Int = 0,
)

/** A camera / look profile (`profiles.list`). */
data class ProfileItem(val id: String, val name: String, val group: String)

/** The lens-profile database (`lens.databaseStatus`): lenses it holds and the download's state. */
data class LensDb(val lenses: Int = 0, val state: String = "idle", val error: String = "")

/** Healing (`spot.add`): mode `heal`, `clone`, `fill` (content-aware remove); size as a fraction of the long edge. */
data class HealSettings(val mode: String = "heal", val size: Float = 0.025f, val best: Boolean = false)

/** How a group's controls are laid out: a list of sliders, the colour mixer, or the grading wheels. */
enum class GroupStyle { Sliders, Mixer, Grading, PointColor }

/** A labelled tool of the row above the bottom bar. */
data class Tool(val id: String, val label: String, val kind: ToolKind = ToolKind.Sliders, val groups: List<Group> = emptyList())

/**
 * Temp and Tint on the relative scale of rendered files (JPEG, HEIC, PNG…), as the desktop shows
 * them: −100…+100, a shift in mireds around 6500 K for Temp. The engine stores Kelvin either way.
 */
object RelativeWb {
    private const val NEUTRAL_MIRED = 1e6 / 6500.0

    fun kelvinToRel(k: Float): Float =
        if (k <= 0f || !k.isFinite()) 0f else ((NEUTRAL_MIRED - 1e6 / k) / 0.8).toFloat().coerceIn(-100f, 100f)

    fun relToKelvin(r: Float): Float = (1e6 / (NEUTRAL_MIRED - r.coerceIn(-100f, 100f) * 0.8)).toFloat()

    /** The engine's Temp / Tint controls as the relative sliders show them; As Shot reads as 0. */
    fun show(c: Control, asShot: Boolean): Control = when (c.id) {
        "wb.temp" -> c.copy(min = -100f, max = 100f, default = 0f, decimals = 0, unit = "", value = if (asShot) 0f else kelvinToRel(c.value))
        "wb.tint" -> c.copy(min = -100f, max = 100f, default = 0f, value = if (asShot) 0f else c.value.coerceIn(-100f, 100f))
        else -> c
    }

    /** A relative slider value as the engine's control value. */
    fun toEngine(id: String, v: Float): Float = if (id == "wb.temp") relToKelvin(v) else v
}

/** The develop switches that aren't sliders: Optics' two checkboxes and Lens Blur's Apply and shape. */
data class OpticsState(
    val lensProfile: Boolean = false,
    val removeCa: Boolean = false,
    val blur: Boolean = false,
    val blurShape: String = "auto",
    /** Lens Blur uses a depth map from the depth model. */
    val blurDepth: Boolean = false,
)

/** The photo's History (`history.list`): step labels oldest first, the step it is at, and its saved versions. */
data class HistoryState(val steps: List<String> = emptyList(), val cursor: Int = -1, val versions: List<String> = emptyList())

/**
 * Export options (the engine's `ExportOptions` JSON): format, quality, long edge in pixels (0 =
 * full size), colour space, metadata policy and whether to drop the location.
 */
data class ExportSettings(
    val format: String = "jpeg",
    val quality: Int = 90,
    val longEdge: Int = 0,
    val colorSpace: String = "srgb",
    val metadata: String = "all",
    val removeLocation: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("format", format)
        .put("quality", quality)
        .put("colorSpace", colorSpace)
        .put("metadata", metadata)
        .put("removeLocation", removeLocation)
        .also { if (longEdge > 0) it.put("resize", JSONObject().put("mode", "longEdge").put("value", longEdge).put("dontEnlarge", true)) }

    companion object {
        val formats = listOf("jpeg" to "JPEG", "png" to "PNG", "webp" to "WebP", "avif" to "AVIF", "jxl" to "JPEG XL", "tiff" to "TIFF", "dng" to "DNG")
        val sizes = listOf(0 to "Full size", 4096 to "4096 px", 2048 to "2048 px", 1080 to "1080 px")
        val spaces = listOf("srgb" to "sRGB", "displayp3" to "Display P3", "adobergb" to "Adobe RGB")
        val metadataModes = listOf("all" to "All", "allexceptcamera" to "No camera info", "copyright" to "Copyright only", "none" to "None")
    }
}

/** A crop aspect ratio (width / height); 0 = the photo's own. */
data class Aspect(val label: String, val ratio: Float)

/** A mask's local adjustment slider (`mask.adjust` value names). */
data class LocalSlider(val key: String, val label: String, val min: Float, val max: Float, val decimals: Int = 0)

object Tools {
    /**
     * The Edit tab's tools. One-tap actions (Auto, B&W, Reset…) live only in Quick edit, so nothing
     * appears in two tabs.
     */
    val edit = listOf(
        Tool("profile", "Profile", ToolKind.Profile, groups = listOf(Group("Profile", listOf("profile.")))),
        Tool("light", "Light", groups = listOf(Group("Light", listOf("light.")))),
        Tool(
            "color", "Color",
            groups = listOf(
                Group("Color", listOf("wb.", "color.")),
                Group("Mixer", listOf("mixer."), style = GroupStyle.Mixer),
                Group("Grading", listOf("grading."), style = GroupStyle.Grading),
                Group("Point Color", listOf("pointColor."), style = GroupStyle.PointColor),
                Group("B&W", listOf("bw.")),
                Group("Calibration", listOf("calibration.")),
            ),
        ),
        Tool("curve", "Curve", ToolKind.Curve),
        Tool("effects", "Effects", groups = listOf(Group("Effects", listOf("effects.")), Group("Vignette", listOf("vignette.")), Group("Grain", listOf("grain.")))),
        Tool("detail", "Detail", groups = listOf(Group("Sharpening", listOf("detail.sharpen")), Group("Noise", listOf("enhance.denoise", "detail.nr")))),
        Tool("optics", "Optics", groups = listOf(Group("Lens", listOf("optics.distortion", "optics.vignetting", "optics.profile")), Group("Chromatic", listOf("optics.ca", "optics.defringe")))),
        Tool("blur", "Lens Blur", ToolKind.LensBlur, groups = listOf(Group("Lens Blur", listOf("lensBlur.")))),
        Tool("heal", "Heal", ToolKind.Heal),
        Tool("geometry", "Geometry", groups = listOf(Group("Upright", listOf("geometry.")))),
    )

    /** Quick edit's one-tap actions (background removal is in the Remove tab). */
    val quick = listOf(
        Tool("auto", "Auto", ToolKind.Action),
        Tool("pop", "Subject pop", ToolKind.Action),
        Tool("sky", "Blue sky", ToolKind.Action),
        Tool("bw", "B&W", ToolKind.Action),
        Tool("reset", "Reset", ToolKind.Action),
    )

    /** Quick edit's sliders: the ones most edits use, in the order they are usually reached for. */
    val quickControls = listOf(
        "light.exposure", "light.contrast", "light.highlights", "light.shadows", "light.whites", "light.blacks",
        "wb.temp", "wb.tint", "color.vibrance", "color.saturation",
        "effects.texture", "effects.clarity", "effects.dehaze", "vignette.amount",
    )

    /** White balance presets (`develop.wb` modes). */
    val wbModes = listOf("asShot" to "As Shot", "auto" to "Auto", "daylight" to "Daylight", "cloudy" to "Cloudy", "shade" to "Shade", "tungsten" to "Tungsten", "fluorescent" to "Fluorescent", "flash" to "Flash")

    /** Upright (`geometry.upright` modes the phone offers; Guided needs lines drawn on the photo). */
    val uprightModes = listOf("off" to "Off", "auto" to "Auto", "level" to "Level", "vertical" to "Vertical", "full" to "Full")

    /** Healing modes (`spot.add`). */
    val healModes = listOf("heal" to "Heal", "clone" to "Clone", "fill" to "Remove", "ai" to "AI Remove")

    /** Lens Blur's in-focus area when no mask sets it (`lens_blur.shape`). */
    val blurShapes = listOf("auto" to "Subject", "round" to "Round", "wide" to "Wide", "tall" to "Tall")

    val masking = listOf(
        Tool("object", "Object", ToolKind.Action),
        Tool("subject", "Subject", ToolKind.Action),
        Tool("background", "Background", ToolKind.Action),
        Tool("sky", "Sky", ToolKind.Action),
        Tool("brush", "Brush", ToolKind.Action),
        Tool("linear", "Linear", ToolKind.Action),
        Tool("radial", "Radial", ToolKind.Action),
        Tool("color", "Color", ToolKind.Action),
        Tool("luminance", "Luminance", ToolKind.Action),
    )

    val remove = listOf(
        Tool("eraser", "Magic Eraser"),
        Tool("ai", "Remove BG", ToolKind.Action),
        Tool("people", "People (AI)", ToolKind.Action),
        Tool("keep", "Keep BG", ToolKind.Action),
        Tool("undo", "Undo", ToolKind.Action),
        Tool("reset", "Restore", ToolKind.Action),
        Tool("export", "Save PNG", ToolKind.Action),
    )

    val local = listOf(
        LocalSlider("exposure", "Exposure", -4f, 4f, 2),
        LocalSlider("contrast", "Contrast", -100f, 100f),
        LocalSlider("highlights", "Highlights", -100f, 100f),
        LocalSlider("shadows", "Shadows", -100f, 100f),
        LocalSlider("whites", "Whites", -100f, 100f),
        LocalSlider("blacks", "Blacks", -100f, 100f),
        LocalSlider("temp", "Temp", -100f, 100f),
        LocalSlider("tint", "Tint", -100f, 100f),
        LocalSlider("saturation", "Saturation", -100f, 100f),
        LocalSlider("texture", "Texture", -100f, 100f),
        LocalSlider("clarity", "Clarity", -100f, 100f),
        LocalSlider("dehaze", "Dehaze", -100f, 100f),
        LocalSlider("sharpness", "Sharpness", -100f, 100f),
        LocalSlider("noise", "Noise", -100f, 100f),
    )

    val aspects = listOf(Aspect("Original", 0f), Aspect("1:1", 1f), Aspect("4:5", 4f / 5f), Aspect("3:2", 3f / 2f), Aspect("16:9", 16f / 9f))
}
