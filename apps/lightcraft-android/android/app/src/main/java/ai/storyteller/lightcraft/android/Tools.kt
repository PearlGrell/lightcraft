package ai.storyteller.lightcraft.android

import org.json.JSONObject

/** One develop slider as the engine describes it (`develop.controls`), with the photo's value. */
data class Control(
    val id: String,
    val label: String,
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
            min = o.optDouble("min", -100.0).toFloat(),
            max = o.optDouble("max", 100.0).toFloat(),
            default = o.optDouble("default", 0.0).toFloat(),
            decimals = o.optInt("decimals", 0),
            value = o.optDouble("value", o.optDouble("default", 0.0)).toFloat(),
        )
    }
}

/** A tool of the editor's bottom rail, Lightroom-mobile style; [controls] are engine control ids. */
data class Tool(val id: String, val label: String, val controls: List<String> = emptyList())

object Tools {
    /** Runs `develop.auto` instead of opening a panel. */
    const val AUTO = "auto"

    val rail = listOf(
        Tool(AUTO, "Auto"),
        Tool("light", "Light", listOf("light.exposure", "light.contrast", "light.highlights", "light.shadows", "light.whites", "light.blacks")),
        Tool("color", "Color", listOf("wb.temp", "wb.tint", "color.vibrance", "color.saturation")),
        Tool("effects", "Effects", listOf("effects.texture", "effects.clarity", "effects.dehaze", "vignette.amount", "vignette.midpoint")),
        Tool("detail", "Detail", listOf("detail.sharpenAmount", "detail.sharpenRadius", "detail.sharpenDetail", "detail.nrLuminance", "detail.nrColor")),
        Tool("optics", "Optics"),
        Tool("geometry", "Geometry"),
        Tool("crop", "Crop"),
        Tool("masking", "Masking"),
        Tool("remove", "Remove"),
        Tool("presets", "Presets"),
    )
}
