package ai.storyteller.lightcraft.android

import org.json.JSONArray
import org.json.JSONObject

/** A develop profile (`profiles.list`): Color, Vivid, Monochrome, the creative looks… */
data class Profile(val id: String, val name: String, val group: String, val favorite: Boolean)

/** The open photo's History (`history.list`): step labels, the step it is at, and its saved versions. */
data class HistoryInfo(val steps: List<String> = emptyList(), val cursor: Int = -1, val versions: List<String> = emptyList())

/** One line of the Info sheet: "Camera" → "Pixel 8 Pro". */
data class InfoLine(val label: String, val value: String)

/** The editable details of a photo (`photo.setMeta`). */
data class PhotoDetails(val title: String = "", val caption: String = "", val copyright: String = "", val creator: String = "")

/** The Info sheet: rating, flag, colour label, the photo's capture details and its editable details. */
data class PhotoInfo(
    val rating: Int = 0,
    val flag: String = "none",
    val label: String = "none",
    val lines: List<InfoLine> = emptyList(),
    val details: PhotoDetails = PhotoDetails(),
)

/** The brush of Heal, Clone, Remove and the mask brush; [size] is a fraction of the photo's long edge. */
data class BrushSettings(val size: Float = 0.05f, val feather: Float = 50f, val opacity: Float = 100f, val erase: Boolean = false)

/**
 * A heal / clone / remove spot as drawn over the photo: its path and source path in 0..1 of the
 * shown render, and its radius as a fraction of the render's long edge.
 */
data class SpotView(val mode: String, val path: List<Pair<Float, Float>>, val source: List<Pair<Float, Float>>?, val radius: Float)

/** What the next tap on the photo does, when it does something other than zoom. */
enum class Picker { None, WhiteBalance, MaskColor }

/** An export: the file type, its long edge (0 = full size), JPEG/WebP/AVIF quality, a text watermark. */
data class ExportSettings(
    val format: ExportFormat = ExportFormat.Jpeg,
    val longEdge: Int = 0,
    val quality: Int = 90,
    val watermark: String = "",
    val removeLocation: Boolean = true,
) {
    fun json(): JSONObject = JSONObject().apply {
        put("format", format.id)
        if (format.lossy) put("quality", quality)
        if (longEdge > 0) put("longEdge", longEdge)
        if (watermark.isNotBlank()) put("watermark", JSONObject().put("text", watermark.trim()).put("opacity", 0.8).put("size", 0.04))
        put("removeLocation", removeLocation)
    }
}

enum class ExportFormat(val id: String, val label: String, val mime: String, val lossy: Boolean) {
    Jpeg("jpeg", "JPG", "image/jpeg", true),
    Png("png", "PNG", "image/png", false),
    Tiff("tiff", "TIFF", "image/tiff", false),
    Webp("webp", "WebP", "image/webp", true),
    Avif("avif", "AVIF", "image/avif", true),
    Dng("renderedDng", "DNG", "image/x-adobe-dng", false),
    ;

    companion object {
        /** The MIME type of an exported file, from its extension. */
        fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "tif", "tiff" -> "image/tiff"
            "webp" -> "image/webp"
            "avif" -> "image/avif"
            "dng" -> "image/x-adobe-dng"
            else -> "image/jpeg"
        }
    }
}

/** The EXIF tags the Info sheet shows, in order, with their labels. */
private val INFO_TAGS = listOf(
    "Make" to "Make",
    "Model" to "Camera",
    "LensModel" to "Lens",
    "FocalLength" to "Focal length",
    "FNumber" to "Aperture",
    "ExposureTime" to "Shutter",
    "ISOSpeedRatings" to "ISO",
    "PhotographicSensitivity" to "ISO",
    "ExposureBiasValue" to "Exposure bias",
    "Flash" to "Flash",
    "DateTimeOriginal" to "Taken",
    "PixelXDimension" to "Width",
    "PixelYDimension" to "Height",
    "Software" to "Software",
)

/** The Info sheet's lines from `photo.allMetadata` (unknown or missing tags are left out). */
internal fun infoLines(meta: JSONObject?): List<InfoLine> {
    val exif = meta?.optJSONArray("exif") ?: return emptyList()
    val byTag = HashMap<String, String>()
    for (i in 0 until exif.length()) {
        val o = exif.optJSONObject(i) ?: continue
        val tag = o.optString("tag").ifEmpty { o.optString("name") }
        val value = o.optString("value").trim()
        if (tag.isNotEmpty() && value.isNotEmpty() && tag !in byTag) byTag[tag] = value.take(80)
    }
    val seen = HashSet<String>()
    return INFO_TAGS.mapNotNull { (tag, label) ->
        val v = byTag[tag] ?: return@mapNotNull null
        if (!seen.add(label)) null else InfoLine(label, v)
    }
}

internal fun historyInfo(o: JSONObject?): HistoryInfo {
    if (o == null) return HistoryInfo()
    val steps = o.optJSONArray("history")
    val versions = o.optJSONArray("versions")
    return HistoryInfo(
        steps = (0 until (steps?.length() ?: 0)).map { steps?.optString(it).orEmpty() },
        cursor = if (o.isNull("cursor")) -1 else o.optInt("cursor", -1),
        versions = (0 until (versions?.length() ?: 0)).map { versions?.optJSONObject(it)?.optString("name").orEmpty().ifEmpty { "Version ${it + 1}" } },
    )
}

internal fun profiles(list: JSONArray?): List<Profile> =
    (0 until (list?.length() ?: 0)).mapNotNull { i ->
        list?.optJSONObject(i)?.let { Profile(it.optString("id"), it.optString("name"), it.optString("group", "Profiles"), it.optBoolean("favorite")) }
    }.filter { it.id.isNotEmpty() }

/** The optional generative fill model: not installed, downloading (0..1), or ready. */
data class GenModel(
    val installed: Boolean = false,
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val loading: Boolean = false,
    val error: String? = null,
)

/** One file of a downloadable model, with the only size and SHA-256 it is accepted with. */
data class ModelFile(val name: String, val url: String, val sha256: String, val bytes: Long)

/** An optional on-device AI model: what it does, where it comes from, its licence and files. */
data class ModelPack(val id: String, val title: String, val source: String, val license: String, val licenseUrl: String, val files: List<ModelFile>) {
    val bytes: Long get() = files.sumOf { it.bytes }
}

object ModelPacks {
    private const val SAM = "https://huggingface.co/onnx-community/sam2.1-hiera-tiny-ONNX/resolve/main/onnx"

    /** Tap to select any object: SAM 2.1 tiny (Meta, Apache-2.0), the Transformers.js ONNX export. */
    val SELECT = ModelPack(
        id = "select",
        title = "Smart select",
        source = "SAM 2.1 tiny by Meta AI, ONNX export by onnx-community",
        license = "Apache-2.0",
        licenseUrl = "https://huggingface.co/facebook/sam2.1-hiera-tiny",
        files = listOf(
            ModelFile("vision_encoder_fp16.onnx", "$SAM/vision_encoder_fp16.onnx", "7773e79d589dada8e1e630cc7eb18b17b2c5b4faeb1eaab5ebf9df618ecbd50a", 314_925),
            ModelFile("vision_encoder_fp16.onnx_data", "$SAM/vision_encoder_fp16.onnx_data", "a4dd3759e9b6a476d991fb3493787992e78777e51462695e1bede71ae258103e", 67_005_504),
            ModelFile("prompt_encoder_mask_decoder.onnx", "$SAM/prompt_encoder_mask_decoder.onnx", "874414704c5d686db7d206a35f6e15d26563d50c8c4468fccc6739bd7e491dcf", 213_114),
            ModelFile("prompt_encoder_mask_decoder.onnx_data", "$SAM/prompt_encoder_mask_decoder.onnx_data", "e9874d900dd4134ed60eab1e97910327c2419e0b2954485d8fd6e7f1a1470f47", 20_958_208),
        ),
    )

    /** Generative fill: big-LaMa (Samsung AI Center, Apache-2.0), Carve's fp32 ONNX export. */
    val FILL = ModelPack(
        id = "fill",
        title = "Generative fill",
        source = "LaMa (big-lama) by Samsung AI Center, ONNX export by Carve",
        license = "Apache-2.0",
        licenseUrl = "https://huggingface.co/Carve/LaMa-ONNX",
        files = listOf(
            ModelFile("lama_fp32.onnx", "https://huggingface.co/Carve/LaMa-ONNX/resolve/main/lama_fp32.onnx", "1faef5301d78db7dda502fe59966957ec4b79dd64e16f03ed96913c7a4eb68d6", 208_044_816),
        ),
    )

    val ALL = listOf(SELECT, FILL)
}
