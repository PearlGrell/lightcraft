package ai.storyteller.lightcraft.android

import ai.storyteller.lightcraft.android.ui.roundTo
import ai.storyteller.lightcraft.android.ui.shortLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSlidersTest {
    @Test
    fun exposureSpansHalfTheEngineRange() {
        assertEquals(-2.5f to 2.5f, phoneRange("light.exposure", -5f, 5f, 0.3f))
        // other controls keep the engine's span
        assertEquals(-100f to 100f, phoneRange("light.contrast", -100f, 100f, 0f))
    }

    @Test
    fun exposureEditedFurtherElsewhereStaysOnTheTrack() {
        assertEquals(-3.4f to 3.4f, phoneRange("light.exposure", -5f, 5f, -3.4f))
        assertEquals(-5f to 5f, phoneRange("light.exposure", -5f, 5f, 9f))
    }

    @Test
    fun dragValuesLandOnTheShownPrecision() {
        assertEquals(0.05f, roundTo(0.0473f, 2), 1e-6f)
        assertEquals(37f, roundTo(36.6f, 0), 1e-6f)
        assertTrue(roundTo(-0.004f, 2) == 0f || roundTo(-0.004f, 2) == -0f)
    }

    @Test
    fun gradingLabelsDropTheZoneNamedByThePill() {
        assertEquals("Hue", shortLabel("Shadows Hue", "Shadows"))
        assertEquals("Luminance", shortLabel("Midtones Luminance", "Midtones"))
        assertEquals("Blending", shortLabel("Blending", "Blend"))
        assertEquals("Shadows", shortLabel("Shadows", "Shadows"))
    }

    @Test
    fun gradingHasAZoneForEachTonalRange() {
        val grading = Tools.edit.first { it.id == "grading" }
        val ids = listOf("grading.shadows.hue", "grading.midtones.sat", "grading.highlights.lum", "grading.global.hue", "grading.blending", "grading.balance")
        for (id in ids) assertEquals(id, 1, grading.groups.count { it.matches(id) })
        // grading moved out of Color: no slider shows twice
        assertTrue(Tools.edit.first { it.id == "color" }.groups.none { it.matches("grading.shadows.hue") })
    }
}
