package ai.storyteller.lightcraft.android

import org.json.JSONObject

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
                label = o.optString("label", id),
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
enum class MainTab(val label: String) { Quick("Quick actions"), Presets("Presets"), Crop("Crop"), Edit("Edit"), Masking("Masking"), Remove("Remove") }

enum class ToolKind { Sliders, Action, Curve }

/** How a group's controls are laid out: a list of sliders, the colour mixer, or the grading wheels. */
enum class GroupStyle { Sliders, Mixer, Grading }

/** A labelled tool of the row above the bottom bar. */
data class Tool(val id: String, val label: String, val kind: ToolKind = ToolKind.Sliders, val groups: List<Group> = emptyList())

/** A crop aspect ratio (width / height); 0 = the photo's own. */
data class Aspect(val label: String, val ratio: Float)

/** A mask's local adjustment slider (`mask.adjust` value names). */
data class LocalSlider(val key: String, val label: String, val min: Float, val max: Float, val decimals: Int = 0)

object Tools {
    val edit = listOf(
        Tool("auto", "Auto", ToolKind.Action),
        Tool("light", "Light", groups = listOf(Group("Light", listOf("light.")))),
        Tool(
            "color", "Color",
            groups = listOf(
                Group("Color", listOf("wb.", "color.")),
                Group("Mixer", listOf("mixer."), style = GroupStyle.Mixer),
                Group("Grading", listOf("grading."), style = GroupStyle.Grading),
                Group("B&W", listOf("bw.")),
                Group("Calibration", listOf("calibration.")),
            ),
        ),
        Tool("curve", "Curve", ToolKind.Curve),
        Tool("effects", "Effects", groups = listOf(Group("Effects", listOf("effects.")), Group("Vignette", listOf("vignette.")), Group("Grain", listOf("grain.")))),
        Tool("detail", "Detail", groups = listOf(Group("Sharpening", listOf("detail.sharpen")), Group("Noise", listOf("enhance.denoise", "detail.nr")))),
        Tool("optics", "Optics", groups = listOf(Group("Lens", listOf("optics.distortion", "optics.vignetting", "optics.profile")), Group("Chromatic", listOf("optics.ca", "optics.defringe")))),
        Tool("geometry", "Geometry", groups = listOf(Group("Upright", listOf("geometry.")))),
    )

    val quick = listOf(
        Tool("auto", "Auto", ToolKind.Action),
        Tool("pop", "Subject pop", ToolKind.Action),
        Tool("sky", "Blue sky", ToolKind.Action),
        Tool("bw", "B&W", ToolKind.Action),
        Tool("cutout", "Remove BG", ToolKind.Action),
        Tool("reset", "Reset", ToolKind.Action),
    )

    val masking = listOf(
        Tool("subject", "Subject", ToolKind.Action),
        Tool("background", "Background", ToolKind.Action),
        Tool("sky", "Sky", ToolKind.Action),
        Tool("linear", "Linear", ToolKind.Action),
        Tool("radial", "Radial", ToolKind.Action),
    )

    val remove = listOf(
        Tool("eraser", "Magic Eraser"),
        Tool("ai", "Remove BG", ToolKind.Action),
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
