package ai.storyteller.lightcraft.android

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneSlidersTest {
    @Test
    fun exposureSpansHalfTheEngineRange() {
        assertEquals(-2.5f to 2.5f, phoneRange("light.exposure", -5f, 5f, 0.3f))
        // other controls keep the engine's span
        assertEquals(-100f to 100f, phoneRange("light.contrast", -100f, 100f, 0f))
    }

    @Test
    fun exposureEditedFurtherElsewhereStaysReachable() {
        assertEquals(-3.4f to 3.4f, phoneRange("light.exposure", -5f, 5f, -3.4f))
        assertEquals(-5f to 5f, phoneRange("light.exposure", -5f, 5f, 9f))
    }

    @Test
    fun smallExposureStepsAreNotSwallowedBySnapping() {
        // 2 dp of travel on a ~320 dp track over ±2.5 EV is ~0.03 EV of snap zone
        val snap = 2f / 320f * 5f
        assertEquals(0.05f, dragValue(0.0473f, 0f, snap, 2, -2.5f, 2.5f), 1e-6f)
        assertEquals(0f, dragValue(0.02f, 0f, snap, 2, -2.5f, 2.5f), 1e-6f)
        assertEquals(37f, dragValue(36.6f, 0f, 1f, 0, -100f, 100f), 1e-6f)
        assertEquals(2.5f, dragValue(2.5049f, 0f, snap, 2, -2.5f, 2.5f), 1e-6f)
    }
}
