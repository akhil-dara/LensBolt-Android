package com.lensbolt.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StageMathTest {
    private val aspect = 4f / 3f

    // every landscape window from a narrow split screen to a wide phone, at three densities, with and without
    // side insets: no exception, and when columns are used neither they nor the Details band reach over the guide
    @Test fun columns_never_throw_and_keep_clear_of_the_guide() {
        for (density in floatArrayOf(1f, 2.75f, 3.5f)) for (wDp in intArrayOf(320, 480, 640, 660, 900)) {
            for (ratio in floatArrayOf(0.4f, 0.45f, 0.5625f, 0.75f, 0.95f)) for (insetDp in intArrayOf(0, 24, 48)) {
                val w = (wDp * density).toInt(); val h = (wDp * ratio * density).toInt()
                val ins = (insetDp * density).toInt()
                val c = StageMath.columns(w, h, ins, ins, aspect, density) ?: continue
                val g = c.guide
                val tag = "w=${wDp}dp ratio=$ratio inset=${insetDp}dp density=$density: $c"
                assertTrue(tag, c.colLeft >= g.r)
                assertTrue(tag, c.colRight <= w - ins && c.colRight - c.colLeft >= 200 * density - 1)
                assertTrue(tag, g.l >= ins)                           // the guide stays clear of the start inset
                assertEquals(tag, 0.95f * c.image.height, g.height, 0.5f)
                if (!c.detailsInColumn) {
                    assertTrue(tag, c.detRight <= g.l)
                    assertTrue(tag, c.detLeft >= 0 && c.detRight - c.detLeft >= 180 * density - 1)
                }
            }
        }
    }

    @Test fun narrow_windows_fall_back_to_the_stack_and_wide_ones_use_columns() {
        val d = 2.75f
        fun cols(wDp: Int, hDp: Int) = StageMath.columns((wDp * d).toInt(), (hDp * d).toInt(), 0, 0, aspect, d)
        assertNull(cols(320, 180))
        assertNull(cols(480, 270))
        assertNotNull(cols(640, 360))                               // a 360x640 phone turned: the guide moves left
        assertNotNull(cols(660, 371))
        assertNotNull(cols(900, 405))
        // the OnePlus 12 at its default size, landscape with the cutout inset: columns, Details in the left band
        val c = StageMath.columns((914 * d).toInt(), (411 * d).toInt(), (32 * d).toInt(), 0, aspect, d)
        assertNotNull(c); assertTrue(!c!!.detailsInColumn)
    }

    // portrait phones from 360x640 dp to the OnePlus 12 (411x914 dp), status and navigation insets of every
    // kind, the top bar at its height and taller (font scale 1.3): the guide is under the top bar (on the
    // smallest phones with the tallest bars it may move up under the bar), the band for every panel is under the guide, inside the window and at least 96 dp tall (panels scroll inside it, so a
    // larger font never pushes one over the guide)
    @Test fun portrait_panels_stay_under_the_guide() {
        for (density in floatArrayOf(2f, 2.75f, 3.5f)) for ((wDp, hDp) in listOf(360 to 640, 360 to 780, 393 to 851, 411 to 914)) {
            for (topDp in intArrayOf(24, 40, 48)) for (navDp in intArrayOf(16, 24, 48)) for (barDp in intArrayOf(64, 80)) {
                val w = (wDp * density).toInt(); val h = (hDp * density).toInt()
                val st = StageMath.stack(w, h, aspect, (topDp * density).toInt(), (navDp * density).toInt(), (barDp * density).toInt(), density)
                val g = st.guide
                val tag = "${wDp}x${hDp}dp status=$topDp nav=$navDp bar=$barDp density=$density: $st"
                assertTrue(tag, g.t >= topDp * density)
                // under the top bar whenever the window has the room for it and a 96 dp band
                val room = (hDp - topDp - barDp - navDp - 4 - 12 - 12 - 96) * density >= g.height
                if (room) assertTrue(tag, g.t >= (topDp + barDp) * density)
                assertTrue(tag, st.bandTop >= g.b)
                assertTrue(tag, st.bandBottom <= h - navDp * density)
                assertTrue(tag, st.bandBottom - st.bandTop >= 96 * density - 1)
                assertEquals(tag, 0.95f * st.image.width, g.width, 0.5f)
                assertEquals(tag, g.cx, st.image.cx, 0.5f)
            }
        }
    }

    @Test fun guide_sits_on_the_fitted_image() {
        val img = StageMath.image(1080, 2400, aspect)            // portrait: 3:4 image across the width
        assertTrue(img.width == 1080f && img.height == 1440f)
        val g = StageMath.square(1080, 2400, aspect)
        assertTrue(g.width == g.height && g.l >= img.l && g.r <= img.r && g.cy == 1200f)
    }

    // the guide marks almost the whole short side: the tile held to fill the corners gets ~1.8 camera px per
    // 2 px cell at 1440x1080 (at 84% users held it at ~66% of the frame, 1.45 px: below the floor)
    @Test fun guide_is_95_percent_of_the_short_side() {
        for ((w, h) in listOf(1080 to 2400, 2400 to 1080, 1440 to 1080)) {
            val img = StageMath.image(w, h, aspect)
            val g = StageMath.square(w, h, aspect)
            assertEquals("$w x $h", 0.95f * minOf(img.width, img.height), g.width, 0.5f)
        }
    }
}
