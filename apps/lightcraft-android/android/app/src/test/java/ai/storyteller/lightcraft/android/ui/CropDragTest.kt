package ai.storyteller.lightcraft.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CropDragTest {
    private val full = listOf(0f, 0f, 1f, 1f)

    @Test
    fun moving_keeps_the_size_and_stays_inside() {
        val r = dragCrop(listOf(0.2f, 0.2f, 0.6f, 0.6f), CropGrip.Move, 0.9f, -0.5f, 0f, 1.5f)
        assertEquals(0.6f, r[0], 1e-4f)
        assertEquals(0f, r[1], 1e-4f)
        assertEquals(1f, r[2], 1e-4f)
        assertEquals(0.4f, r[3], 1e-4f)
    }

    @Test
    fun edges_and_corners_resize_but_never_invert_or_vanish() {
        val r = dragCrop(full, CropGrip.L, 2f, 0f, 0f, 1.5f)
        assertTrue(r[2] - r[0] >= 0.05f - 1e-5f)
        assertEquals(1f, r[2], 1e-4f)
        val c = dragCrop(full, CropGrip.TL, 0.1f, 0.2f, 0f, 1.5f)
        assertEquals(listOf(0.1f, 0.2f, 1f, 1f), c)
        val out = dragCrop(full, CropGrip.BR, 0.5f, 0.5f, 0f, 1.5f)
        assertEquals(listOf(0f, 0f, 1f, 1f), out)
    }

    @Test
    fun a_locked_ratio_keeps_the_frame_shape() {
        // a 1:1 crop on a 3:2 photo: normalized width × 1.5 = normalized height
        val r = dragCrop(listOf(0.2f, 0.1f, 0.6f, 0.7f), CropGrip.BR, -0.1f, 0.3f, 1f, 1.5f)
        val pixelAspect = (r[2] - r[0]) * 1.5f / (r[3] - r[1])
        assertEquals(1f, pixelAspect, 1e-3f)
        assertTrue(r.all { it in 0f..1f })
    }
}
