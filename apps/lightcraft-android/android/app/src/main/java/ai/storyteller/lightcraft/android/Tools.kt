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
            )
        }
    }
}

/**
 * A sub-panel of a tool ("Mix" under Color…): the engine controls whose id starts with one of
 * [prefixes] and none of [exclude], in the engine's order. Driven by the engine's control list, so
 * every slider the engine has shows up without a second list to keep in sync.
 */
data class Group(val title: String, val prefixes: List<String>, val exclude: List<String> = emptyList()) {
    fun matches(id: String) = prefixes.any { id.startsWith(it) } && exclude.none { id.startsWith(it) }
}

/** The six tabs of the editor's bottom bar (icon over label). */
enum class MainTab(val label: String) { Quick("Actions"), Presets("Presets"), Crop("Crop"), Edit("Edit"), Masking("Masking"), Remove("Remove") }

/** Brush tools paint on the photo (heal, clone, remove). */
enum class ToolKind { Sliders, Action, Curve, Brush, Profiles }

/** A labelled tool of the row above the bottom bar. */
data class Tool(val id: String, val label: String, val kind: ToolKind = ToolKind.Sliders, val groups: List<Group> = emptyList())

/** A crop aspect ratio (width / height); 0 = the photo's own. */
data class Aspect(val label: String, val ratio: Float)

/** A mask's local adjustment slider (`mask.adjust` value names). */
data class LocalSlider(val key: String, val label: String, val min: Float, val max: Float, val decimals: Int = 0)

/**
 * Why a slider cannot do anything yet. The panels are generated from the engine's control list, so
 * they happily list sliders whose effect is switched off or that need data a phone photo has not
 * got; without this they look live and do nothing, which is most of what "the features do nothing"
 * meant. [switchable] groups show a switch; the rest are hidden.
 */
data class Gate(val reason: String, val switchable: Boolean)

object GateRules {
    /**
     * The gate on a control, given the switches from `develop.get`, or `null` when it is live.
     *
     * - B&W mix: `is_bw` must hold, else the eight sliders are dropped from the pipeline.
     * - HDR: every `hdr.*` slider needs `hdr.enabled`; SDR renders use the SDR rendition.
     * - Lens profile: needs an embedded profile, which a phone-gallery JPEG does not carry.
     * - AI denoise: needs a denoised twin of the source, which only a raw with a cached denoise
     *   product has, so it can never bite on a gallery photo.
     */
    fun of(id: String, bw: Boolean, hdr: Boolean, lensProfile: Boolean): Gate? = when {
        id.startsWith("bw.") && !bw -> Gate("Turn on B&W to mix the colours", switchable = true)
        id.startsWith("hdr.") && !hdr -> Gate("Turn on HDR to use these", switchable = true)
        id.startsWith("optics.profile") && !lensProfile -> Gate("This photo has no lens profile", switchable = false)
        id == "enhance.denoise" -> Gate("AI denoise needs a raw file", switchable = false)
        else -> null
    }

    /** The sliders of [controls] worth showing at all: a hidden gate means it can never work here. */
    fun visible(controls: List<Control>, bw: Boolean, hdr: Boolean, lensProfile: Boolean): List<Control> =
        controls.filter { of(it.id, bw, hdr, lensProfile)?.switchable != false }
}

object Tools {
    val edit = listOf(
        Tool("auto", "Auto", ToolKind.Action),
        Tool("light", "Light", groups = listOf(Group("Light", listOf("light.")), Group("Tone curve", listOf("curve.")), Group("HDR", listOf("hdr.")))),
        Tool(
            "color", "Color",
            groups = listOf(
                Group("Color", listOf("wb.", "color.")),
                Group("Mix", listOf("mixer.")),
                Group("Grading", listOf("grading.")),
                Group("B&W", listOf("bw.")),
                Group("Calibration", listOf("calibration.")),
            ),
        ),
        Tool("curve", "Curve", ToolKind.Curve),
        Tool("effects", "Effects", groups = listOf(Group("Effects", listOf("effects.")), Group("Vignette", listOf("vignette.")), Group("Grain", listOf("grain.")))),
        Tool("detail", "Detail", groups = listOf(Group("Sharpening", listOf("detail.sharpen")), Group("Noise", listOf("enhance.denoise", "detail.nr")))),
        Tool("optics", "Optics", groups = listOf(Group("Lens", listOf("optics.distortion", "optics.vignetting", "optics.profile")), Group("Chromatic", listOf("optics.ca", "optics.defringe")))),
        Tool("profiles", "Profiles", ToolKind.Profiles),
    )

    /** The Crop tab's Geometry page (Upright and the manual transforms). */
    val geometry = Group("Geometry", listOf("geometry."))

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
        Tool("sky", "Sky", ToolKind.Action),
        Tool("background", "Background", ToolKind.Action),
        Tool("brush", "Brush", ToolKind.Action),
        Tool("linear", "Linear", ToolKind.Action),
        Tool("radial", "Radial", ToolKind.Action),
        Tool("colorRange", "Color Range", ToolKind.Action),
        Tool("luminanceRange", "Luminance", ToolKind.Action),
    )

    /** Remove: three brushes that work on the edit (undoable, any size), then the cutout tools. */
    val remove = listOf(
        Tool("fill", "Remove", ToolKind.Brush),
        Tool("heal", "Heal", ToolKind.Brush),
        Tool("clone", "Clone", ToolKind.Brush),
        Tool("redeye", "Red Eye"),
        Tool("eraser", "Eraser"),
        Tool("ai", "Remove BG", ToolKind.Action),
        Tool("keep", "Keep BG", ToolKind.Action),
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

    val aspects = listOf(Aspect("Original", 0f), Aspect("1:1", 1f), Aspect("4:5", 4f / 5f), Aspect("9:16", 9f / 16f), Aspect("3:2", 3f / 2f), Aspect("16:9", 16f / 9f))
}
