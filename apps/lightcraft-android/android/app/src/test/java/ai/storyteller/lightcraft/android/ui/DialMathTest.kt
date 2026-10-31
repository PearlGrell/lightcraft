package ai.storyteller.lightcraft.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialMathTest {
    @Test
    fun values_are_signed_for_plus_minus_controls_only() {
        assertEquals("+37", formatValue(37f, -100f, 0, ""))
        assertEquals("-0.50 EV", formatValue(-0.5f, -5f, 2, "EV"))
        assertEquals("0", formatValue(0f, -100f, 0, ""))
        assertEquals("5600 K", formatValue(5600f, 2000f, 0, "K"))
        assertEquals("+3.0°", formatValue(3f, -45f, 1, "°"))
        assertEquals("80%", formatValue(80f, 0f, 0, "%"))
    }

    @Test
    fun histogram_zones_cover_the_width_in_order() {
        assertEquals(0, zoneAt(0f))
        assertEquals(0, zoneAt(0.05f))
        assertEquals(1, zoneAt(0.2f))
        assertEquals(2, zoneAt(0.5f))
        assertEquals(3, zoneAt(0.8f))
        assertEquals(4, zoneAt(0.95f))
        assertEquals(4, zoneAt(1.5f))
        assertEquals(5, ZONES.size)
    }

    @Test
    fun a_spec_knows_when_it_changed() {
        assertFalse(DialSpec("a", "A", 0f, -100f, 100f, 0f).changed)
        assertTrue(DialSpec("a", "A", 1f, -100f, 100f, 0f).changed)
        // a degenerate range never divides by zero
        assertTrue(DialSpec("a", "A", 1f, 1f, 1f, 0f).range > 0f)
    }
}
