package com.lensbolt.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The sender-rate banner names only rates the sender page offers (15, 30, 60; as the browser receiver,
// web/receive-logic.js) and only one below the sender's, and the window's keep-screen-on flag changes only on
// transitions.
class SenderFpsTest {
    @Test fun suggestionsAreRatesTheSenderOffers() {
        assertEquals(listOf(15, 30, 60), SenderFps.OPTIONS.toList())
        assertEquals(30, SenderFps.suggestion(29.8))
        assertEquals(15, SenderFps.suggestion(25.0))
        // a slow camera: the sender's slowest rate, 15
        assertEquals(15, SenderFps.suggestion(15.2))
        assertEquals(15, SenderFps.suggestion(9.0))
        assertEquals(60, SenderFps.suggestion(59.0))
    }

    // advice, not an order: the faster rate, or keep going and how much longer it takes (as web/receive-logic.js)
    @Test fun bannerWords() {
        assertEquals("The sender shows 60 fps; this camera reads 30. Set the sender to 30 fps or keep going: it will take about twice as long.", SenderFps.banner(60.0, 29.8))
        assertEquals("The sender shows 30 fps; this camera reads 15. Set the sender to 15 fps or keep going: it will take about twice as long.", SenderFps.banner(30.0, 14.6))
        assertEquals("The sender shows 30 fps; this camera reads 20. Set the sender to 15 fps or keep going: it will take about 1.5 times as long.", SenderFps.banner(30.0, 20.4))
        assertEquals("The sender shows 30 fps; this camera reads 24. Set the sender to 15 fps or keep going: it will take about 1.3 times as long.", SenderFps.banner(30.0, 24.0))
        // nothing slower than the sender to suggest: keep going alone (a sender measured at 15.4 is at 15)
        assertEquals("The sender shows 15 fps; this camera reads 10. Keep going: it will take about 1.5 times as long.", SenderFps.banner(15.0, 10.0))
        assertEquals("The sender shows 15 fps; this camera reads 9. Keep going: it will take about 1.7 times as long.", SenderFps.banner(15.4, 9.0))
        assertEquals("The sender shows 48 fps; this camera reads 30. Set the sender to 30 fps or keep going: it will take about 1.6 times as long.", SenderFps.banner(48.0, 30.0))
        assertEquals("The sender shows 120 fps; this camera reads 30. Set the sender to 30 fps or keep going: it will take about 4 times as long.", SenderFps.banner(120.0, 30.0))
    }

    @Test fun aCameraNearAStandardRateCountsAsThatRate() {
        assertEquals(30.0, SenderFps.effective(29.7), 1e-9)
        assertEquals(30.0, SenderFps.effective(27.2), 1e-9)
        assertEquals(24.0, SenderFps.effective(24.0), 1e-9)
        assertEquals(15.0, SenderFps.effective(14.6), 1e-9)
        assertEquals(20.4, SenderFps.effective(20.4), 1e-9)
    }

    /** Polls every 200 ms from `from` to `to`: the suggestions the watch gave. */
    private fun SenderFpsWatch.run(sender: Double, cam: (Long) -> Double, from: Long, to: Long): List<Int?> {
        val out = ArrayList<Int?>(); var t = from
        while (t <= to) { out += update(sender, cam(t), t); t += 200 }
        return out
    }

    @Test fun matchingRatesNeverWarn() {
        // the user's screenshot: Details said camera 29.7 fps, sender 29.9 fps, and the banner said "takes 24"
        assertTrue(SenderFpsWatch().run(29.9, { 29.7 }, 0, 20_000).all { it == null })
    }

    @Test fun aFasterSenderWarnsAfterThreeSeconds() {
        val w = SenderFpsWatch()
        val r = w.run(60.0, { 30.0 }, 0, 4000)
        assertTrue(r.take(15).all { it == null })          // 0..2.8 s
        assertEquals(30, r[15])                            // 3.0 s
        assertEquals(30, r.last())
        assertEquals("The sender shows 60 fps; this camera reads 30. Set the sender to 30 fps or keep going: it will take about twice as long.", SenderFps.banner(60.0, w.camRate))
        val s = SenderFpsWatch().run(30.0, { 24.0 }, 0, 4000)
        assertEquals(15, s.last())
    }

    @Test fun underTwentyPercentNeverWarns() {
        assertTrue(SenderFpsWatch().run(35.9, { 30.0 }, 0, 20_000).all { it == null })
        assertTrue(SenderFpsWatch().run(28.0, { 24.0 }, 0, 20_000).all { it == null })
    }

    @Test fun aRunBrokenBeforeThreeSecondsStartsAgain() {
        val w = SenderFpsWatch()
        val r = w.run(60.0, { 30.0 }, 0, 2400) + listOf(w.update(30.0, 30.0, 2600)) + w.run(60.0, { 30.0 }, 2800, 6000)
        // the run restarts at 2.8 s: the banner from 5.8 s on
        assertTrue(r.take(r.size - 2).all { it == null })
        assertEquals(30, r.last())
    }

    @Test fun aOneSecondCameraDipNeverWarns() {
        // a heavy colour decode slows the camera to 24 fps for a second
        val r = SenderFpsWatch().run(29.9, { t -> if (t in 5000..5999) 24.0 else 29.8 }, 0, 20_000)
        assertTrue(r.all { it == null })
    }

    @Test fun changesReportsTransitionsOnly() {
        val c = Changes<Boolean>()
        assertTrue(c.changed(true))
        assertFalse(c.changed(true))
        assertFalse(c.changed(true))
        assertTrue(c.changed(false))
        assertFalse(c.changed(false))
        assertTrue(c.changed(true))
    }
}
