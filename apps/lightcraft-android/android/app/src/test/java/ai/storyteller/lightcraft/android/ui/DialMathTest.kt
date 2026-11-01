package ai.storyteller.lightcraft.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialMathTest {
    @Test
    fun values_are_signed_for_plus_minus_controls_only() {
        assertEquals("+37", formatValue(37f, -100f, 0, ""))
        assertEquals("-0.50", formatValue(-0.5f, -5f, 2, "EV"))
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
    fun justified_rows_fill_the_width_and_keep_aspects() {
        val imgs = List(7) { i -> ai.storyteller.lightcraft.android.GalleryImage(i.toLong(), android.net.Uri.EMPTY, null, "p$i", "image/jpeg", width = if (i % 2 == 0) 300 else 200, height = 200) }
        val rows = justify(imgs, 400f, 120f, 4f)
        assertEquals(7, rows.sumOf { it.images.size })
        for (r in rows.dropLast(1)) {
            val w = r.images.sumOf { (it.aspect * r.height).toDouble() } + 4.0 * (r.images.size - 1)
            assertEquals(400.0, w, 0.5)
            assertTrue(r.height <= 120f)
        }
        assertTrue(justify(imgs, 0f, 120f, 4f).isEmpty())
        assertTrue(justify(emptyList(), 400f, 120f, 4f).isEmpty())
    }

    @Test
    fun library_sources_filter_and_sort() {
        fun g(id: Long, name: String, album: String, taken: Long) = ai.storyteller.lightcraft.android.GalleryImage(id, android.net.Uri.EMPTY, "/$name", name, "image/jpeg", taken = taken, album = album, added = taken)
        val now = 100L * 24 * 3600 * 1000
        val photos = listOf(g(1, "b.jpg", "Camera", now - 1000), g(2, "a.jpg", "Screenshots", now - 40L * 24 * 3600 * 1000), g(3, "c.jpg", "Camera", now - 5000))
        val marks = mapOf("/b.jpg" to ai.storyteller.lightcraft.android.Mark("/b.jpg", 1, 3, "pick", true))
        val mark = { x: ai.storyteller.lightcraft.android.GalleryImage -> marks[x.path] }
        val src = ai.storyteller.lightcraft.android.LibrarySource::class
        assertEquals(listOf(1L, 3L, 2L), ai.storyteller.lightcraft.android.libraryView(photos, emptyList(), ai.storyteller.lightcraft.android.LibrarySource.All, "", ai.storyteller.lightcraft.android.SortOrder.Newest, mark, now).map { it.id })
        assertEquals(listOf(1L), ai.storyteller.lightcraft.android.libraryView(photos, emptyList(), ai.storyteller.lightcraft.android.LibrarySource.Picks, "", ai.storyteller.lightcraft.android.SortOrder.Newest, mark, now).map { it.id })
        assertEquals(listOf(1L, 3L), ai.storyteller.lightcraft.android.libraryView(photos, emptyList(), ai.storyteller.lightcraft.android.LibrarySource.Recent, "", ai.storyteller.lightcraft.android.SortOrder.Newest, mark, now).map { it.id })
        assertEquals(listOf(2L), ai.storyteller.lightcraft.android.libraryView(photos, emptyList(), ai.storyteller.lightcraft.android.LibrarySource.Album("Screenshots"), "", ai.storyteller.lightcraft.android.SortOrder.Newest, mark, now).map { it.id })
        assertEquals(listOf(2L, 1L, 3L), ai.storyteller.lightcraft.android.libraryView(photos, emptyList(), ai.storyteller.lightcraft.android.LibrarySource.All, "", ai.storyteller.lightcraft.android.SortOrder.Name, mark, now).map { it.id })
        assertEquals(listOf(3L), ai.storyteller.lightcraft.android.libraryView(photos, emptyList(), ai.storyteller.lightcraft.android.LibrarySource.All, "C.J", ai.storyteller.lightcraft.android.SortOrder.Newest, mark, now).map { it.id })
        assertTrue(src.isSealed)
    }

    @Test
    fun a_spec_knows_when_it_changed() {
        assertFalse(DialSpec("a", "A", 0f, -100f, 100f, 0f).changed)
        assertTrue(DialSpec("a", "A", 1f, -100f, 100f, 0f).changed)
        // a degenerate range never divides by zero
        assertTrue(DialSpec("a", "A", 1f, 1f, 1f, 0f).range > 0f)
    }
}
