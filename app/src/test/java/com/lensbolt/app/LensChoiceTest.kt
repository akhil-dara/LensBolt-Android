package com.lensbolt.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The lenses of a phone as the app sees them (2026-11-04): main id 0 and 2 (nearest 13 cm, 7.69 D), the
// ultrawide id 3 (nearest 4.1 cm, 24.39 D). Chrome reads a phone sender's tile with id 3 held at 4-5 cm; the app's
// main lens cannot focus there and its picture shows big soft cells (Guidance.softAndBig).
class LensChoiceTest {
    private val main = Lens("0", 7.69f)
    private val wide = Lens("3", 24.39f)
    private val lenses = listOf(main, Lens("2", 7.69f), wide)

    @Test fun soft_big_cells_for_600_ms_switch_to_the_closest_focusing_lens() {
        val c = LensChoice()
        assertNull(c.observe(true, 0, main, lenses))
        assertNull(c.observe(true, 500, main, lenses))
        assertEquals("3", c.observe(true, LensChoice.SOFT_MS, main, lenses))
        assertEquals("3", c.lens)
    }

    @Test fun a_read_without_soft_big_cells_restarts_the_wait() {
        val c = LensChoice()
        assertNull(c.observe(true, 0, main, lenses))
        assertNull(c.observe(false, 400, main, lenses))
        assertNull(c.observe(true, 700, main, lenses))
        assertNull(c.observe(true, 1200, main, lenses))
        assertEquals("3", c.observe(true, 1300, main, lenses))
    }

    @Test fun no_lens_focusing_twice_as_near_means_no_switch() {
        val c = LensChoice()
        val near = listOf(main, Lens("5", 12f))
        for (t in 0L..3000L step 200) assertNull(c.observe(true, t, main, near))
        // already on the closest lens: nothing nearer
        for (t in 0L..3000L step 200) assertNull(c.observe(true, t, wide, lenses))
        // a fixed-focus lens bound (no nearest reported): no basis to compare
        for (t in 0L..3000L step 200) assertNull(c.observe(true, t, Lens("1", 0f), lenses))
    }

    @Test fun reset_returns_to_the_default_lens() {
        val c = LensChoice()
        c.observe(true, 0, main, lenses); c.observe(true, 1000, main, lenses)
        assertEquals("3", c.lens)
        c.reset()
        assertNull(c.lens)
    }
}
