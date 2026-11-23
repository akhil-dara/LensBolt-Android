package com.lensbolt.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseTest {
    @Test fun pauseIsOfferedWhileAFileArrives() {
        assertTrue(Pause.offered(finishing = false))
        assertFalse(Pause.offered(finishing = true))
    }

    @Test fun theClockAddsUpEveryPause() {
        val c = PauseClock()
        assertFalse(c.paused)
        assertEquals(0, c.pausedMs(1_000))
        c.pause(1_000)
        assertTrue(c.paused)
        assertEquals(500, c.pausedMs(1_500))
        c.resume(3_000)
        assertFalse(c.paused)
        assertEquals(2_000, c.pausedMs(9_000))
        // a second Pause while paused changes nothing
        c.pause(10_000); c.pause(11_000)
        c.resume(12_000)
        assertEquals(4_000, c.pausedMs(20_000))
        c.resume(30_000)
        assertEquals(4_000, c.pausedMs(40_000))
        c.reset()
        assertFalse(c.paused)
        assertEquals(0, c.pausedMs(50_000))
    }

    @Test fun timeAndSpeedLeaveThePausesOut() {
        assertEquals(40.0, Pause.active(100.0, 60_000), 1e-9)
        // 2 Mbit/s over 100 s is 5 Mbit/s over the 40 s spent reading
        assertEquals(5.0, Pause.rate(2.0, 100.0, 60_000), 1e-9)
        assertEquals(2.0, Pause.rate(2.0, 100.0, 0), 1e-9)
        assertEquals(0.0, Pause.active(10.0, 60_000), 1e-9)
        assertEquals(2.0, Pause.rate(2.0, 10.0, 60_000), 1e-9)
    }

    @Test fun theScreenSaysProgressIsKept() {
        assertEquals("Paused", Pause.TITLE)
        assertTrue(Pause.BODY.contains("kept"))
        assertTrue(Pause.BODY.contains("Resume"))
        assertEquals("Paused at 43 percent", Pause.spoken(430))
    }

    @Test fun resumeGoesBackToTheTransferAndAsksForTheSender() {
        val r = UiState.Receiving("a.zip", 10, 100, 3.5, 20, Hints.GOOD)
        assertEquals(r.copy(hint = Hints.LOST), Pause.resumed(r))
    }

    @Test fun leavingOrStartingOverWhilePausedAsksFirst() {
        assertEquals("Stay paused", Asks.startOver(paused = true).safe)
        assertEquals("Keep receiving", Asks.startOver().safe)
        val leave = Asks.leavePaused()
        assertEquals("Leave", leave.risky)
        assertTrue(leave.message.contains("discarded"))
    }
}
