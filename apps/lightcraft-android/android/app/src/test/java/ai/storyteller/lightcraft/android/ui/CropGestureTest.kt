package ai.storyteller.lightcraft.android.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The crop frame's two pieces of arithmetic: which handle a touch grabs, and where that touch lies
 * in the straightened frame `crop.drag` works in. Both are plain functions, so they are tested
 * here rather than through a gesture.
 */
class CropGestureTest {
    private val frame = Rect(100f, 50f, 500f, 450f) // 400 x 400 on screen
    private val box = Rect(140f, 90f, 460f, 410f) // the crop inside it

    @Test
    fun `each corner and edge has its own handle number`() {
        val reach = 30f
        // 0..3 the corners from the top left clockwise
        assertEquals(0, cropHandleAt(box, box.topLeft, reach))
        assertEquals(1, cropHandleAt(box, box.topRight, reach))
        assertEquals(2, cropHandleAt(box, box.bottomRight, reach))
        assertEquals(3, cropHandleAt(box, box.bottomLeft, reach))
        // 4..7 the top, right, bottom and left edges
        assertEquals(4, cropHandleAt(box, Offset(box.center.x, box.top), reach))
        assertEquals(5, cropHandleAt(box, Offset(box.right, box.center.y), reach))
        assertEquals(6, cropHandleAt(box, Offset(box.center.x, box.bottom), reach))
        assertEquals(7, cropHandleAt(box, Offset(box.left, box.center.y), reach))
    }

    @Test
    fun `the middle moves the whole frame and the outside grabs nothing`() {
        assertEquals(8, cropHandleAt(box, box.center, 30f))
        assertNull(cropHandleAt(box, Offset(box.left - 80f, box.top - 80f), 30f))
    }

    @Test
    fun `a touch near a handle still grabs it, and the nearest one wins`() {
        // a finger is not a pixel: within reach of the corner counts as the corner
        assertEquals(0, cropHandleAt(box, box.topLeft + Offset(12f, 12f), 30f))
        // within reach of both the top-left corner and the left edge, but nearer the corner
        val nearCorner = Offset(box.left, box.top + 40f)
        assertEquals(0, cropHandleAt(box, nearCorner, 200f))
        // and nearer the edge the other way about
        val nearEdge = Offset(box.left, box.center.y - 20f)
        assertEquals(7, cropHandleAt(box, nearEdge, 200f))
    }

    @Test
    fun `an unstraightened frame maps a touch to its own fraction`() {
        val (x, y) = toStraight(frame, Offset(frame.left + 100f, frame.top + 300f), 0f)
        assertEquals(0.25f, x, 1e-4f)
        assertEquals(0.75f, y, 1e-4f)
    }

    @Test
    fun `the centre is the centre at any angle`() {
        for (angle in listOf(-45f, -12f, 0f, 7.5f, 45f)) {
            val (x, y) = toStraight(frame, frame.center, angle)
            assertEquals("angle $angle", 0.5f, x, 1e-4f)
            assertEquals("angle $angle", 0.5f, y, 1e-4f)
        }
    }

    @Test
    fun `straightening turns the touch about the centre`() {
        // 90 degrees about the centre of a square frame: the top middle goes to the right middle
        val (x, y) = toStraight(frame, Offset(frame.center.x, frame.top), 90f)
        assertEquals(1f, x, 1e-4f)
        assertEquals(0.5f, y, 1e-4f)
        // and the sign matches the engine's: a positive angle turns clockwise on screen
        val (bx, by) = toStraight(frame, Offset(frame.center.x, frame.top), -90f)
        assertEquals(0f, bx, 1e-4f)
        assertEquals(0.5f, by, 1e-4f)
    }

    @Test
    fun `a drag outside the photo is not clamped away`() {
        // crop.drag stops the frame at the image edge itself, so the finger is reported as it is
        val (x, y) = toStraight(frame, Offset(frame.left - 200f, frame.bottom + 200f), 0f)
        assertEquals(-0.5f, x, 1e-4f)
        assertEquals(1.5f, y, 1e-4f)
    }
}
