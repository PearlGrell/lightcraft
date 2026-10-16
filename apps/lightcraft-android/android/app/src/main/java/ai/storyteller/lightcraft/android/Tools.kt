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
) {
    companion object {
        fun fromJson(o: JSONObject) = Control(
            id = o.getString("id"),
            label = o.optString("label", o.getString("id")),
            section = o.optString("section", ""),
            min = o.optDouble("min", -100.0).toFloat(),
            max = o.optDouble("max", 100.0).toFloat(),
            default = o.optDouble("default", 0.0).toFloat(),
            decimals = o.optInt("decimals", 0),
            value = o.optDouble("value", o.optDouble("default", 0.0)).toFloat(),
        )
    }
}

/**
 * A sub-panel of a tool ("Curve" under Light, "Mix" under Color…): the engine controls whose id
 * starts with one of [prefixes] and none of [exclude], in the engine's order. Driven by the engine's
 * control list, so every slider the engine has shows up without a second list to keep in sync.
 */
data class Group(val title: String, val prefixes: List<String>, val exclude: List<String> = emptyList()) {
    fun matches(id: String) = prefixes.any { id.startsWith(it) } && exclude.none { id.startsWith(it) }
}

enum class ToolKind { Sliders, Auto, Presets, Crop }

/** A tool of the editor's bottom rail, Lightroom-mobile style. */
data class Tool(val id: String, val label: String, val kind: ToolKind = ToolKind.Sliders, val groups: List<Group> = emptyList())

/** A crop aspect ratio (width / height); 0 = the photo's own. */
data class Aspect(val label: String, val ratio: Float)

object Tools {
    val rail = listOf(
        Tool("presets", "Presets", ToolKind.Presets),
        Tool("auto", "Auto", ToolKind.Auto),
        Tool(
            "light", "Light",
            groups = listOf(Group("Light", listOf("light.")), Group("Curve", listOf("curve."))),
        ),
        Tool(
            "color", "Color",
            groups = listOf(
                Group("Color", listOf("wb.", "color.")),
                Group("Mix", listOf("mixer.")),
                Group("Grading", listOf("grading.")),
                Group("B&W Mix", listOf("bw.")),
                Group("Calibration", listOf("calibration.")),
            ),
        ),
        Tool(
            "effects", "Effects",
            groups = listOf(Group("Effects", listOf("effects.")), Group("Vignette", listOf("vignette.")), Group("Grain", listOf("grain."))),
        ),
        Tool(
            "detail", "Detail",
            groups = listOf(Group("Sharpening", listOf("detail.sharpen")), Group("Noise", listOf("enhance.denoise", "detail.nr"))),
        ),
        Tool(
            "optics", "Optics",
            groups = listOf(
                Group("Lens", listOf("optics.distortion", "optics.vignetting", "optics.profile")),
                Group("Chromatic", listOf("optics.ca", "optics.defringe")),
            ),
        ),
        Tool("geometry", "Geometry", groups = listOf(Group("Upright", listOf("geometry.")))),
        Tool("crop", "Crop", ToolKind.Crop, groups = listOf(Group("Crop", listOf("crop.angle")))),
    )

    val aspects = listOf(Aspect("Original", 0f), Aspect("1:1", 1f), Aspect("4:5", 4f / 5f), Aspect("3:2", 3f / 2f), Aspect("16:9", 16f / 9f))
}
