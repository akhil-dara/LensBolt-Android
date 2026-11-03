package com.lensbolt.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraControlTest {
    private fun FocusLock.poll(t: Long, ok: Long = 111, all: Long = 111, cell: Double = 1.8, edge: Double = 1.5, fresh: Boolean = true, newRead: Boolean = true) =
        update(fresh, newRead, ok, all, cell, edge, t)

    // focus: locked once the tile reads whole on 3 captures within 1 s (not one stale count)
    @Test fun focusLocksOnThreeWholeReadsWithinASecond() {
        val f = FocusLock()
        // one whole read polled many times (no new read): never a lock
        assertEquals(FocusLock.Act.NONE, f.poll(0))
        repeat(20) { assertEquals(FocusLock.Act.NONE, f.poll(50L + it * 50, newRead = false)) }
        assertEquals(FocusLock.Act.NONE, f.poll(1100))
        assertEquals(FocusLock.Act.NONE, f.poll(1150))
        assertEquals(FocusLock.Act.LOCK, f.poll(1200))
        assertTrue(f.locked)
    }

    // failing reads with sharp edges (a blend, motion) keep the lock; with edges grown past 1.3x they unlock it
    @Test fun failingReadsUnlockOnlyWhenTheEdgesGrew() {
        val f = FocusLock()
        f.poll(0); f.poll(33); assertEquals(FocusLock.Act.LOCK, f.poll(66))
        var t = 100L
        repeat(100) { assertEquals(FocusLock.Act.NONE, f.poll(t, ok = 4, edge = 1.55)); t += 33 }
        assertTrue(f.locked)
        assertEquals(FocusLock.Act.UNLOCK, f.poll(t, ok = 4, edge = 2.2))
    }

    @Test fun anotherDistanceOrALostTileUnlocks() {
        val f = FocusLock()
        f.poll(0); f.poll(33); f.poll(66)
        assertEquals(FocusLock.Act.UNLOCK, f.poll(100, cell = 2.2))
        f.poll(200); f.poll(233); assertEquals(FocusLock.Act.LOCK, f.poll(266))
        assertEquals(FocusLock.Act.NONE, f.poll(300, fresh = false, newRead = false))
        assertEquals(FocusLock.Act.UNLOCK, f.poll(2400, fresh = false, newRead = false))
    }

    // a lock the camera reports not focused: dropped; after two, the focus stays continuous for the session
    @Test fun twoFailedLocksBackOffForTheSession() {
        val f = FocusLock()
        f.poll(0); f.poll(33); assertEquals(FocusLock.Act.LOCK, f.poll(66))
        f.lockFailed(); assertFalse(f.locked)
        f.poll(100); f.poll(133); assertEquals(FocusLock.Act.LOCK, f.poll(166))
        f.lockFailed()
        var t = 200L
        repeat(60) { assertEquals(FocusLock.Act.NONE, f.poll(t)); t += 33 }
        // a new session tries again; reset says whether a lock was held (to cancel)
        assertFalse(f.reset())
        f.poll(t); f.poll(t + 33); assertEquals(FocusLock.Act.LOCK, f.poll(t + 66))
        assertTrue(f.reset())
    }

    // a poll every 33 ms with a new read; returns whether a step is asked for
    private class Run(val p: PhaseLock) {
        var t = 0L
        fun poll(mix: Double, newRead: Boolean = true, match: Boolean = true, whole: Boolean = false): Boolean { val a = p.update(newRead, mix, match, t, whole); t += 33; return a }
        /** polls at [mix] until a step is asked for (at most [n] polls); the polls it took, or -1 */
        fun untilStep(mix: Double, n: Int = 400, whole: Boolean = false): Int { for (i in 0 until n) if (poll(mix, whole = whole)) return i; return -1 }
    }

    // the second's mean blend at 0.15 or more starts a step of 8 ms; a measured blend under 0.15 holds and locks
    @Test fun aBlendStepsAndAClearBlendHolds() {
        val r = Run(PhaseLock())
        repeat(60) { assertFalse(r.poll(0.05)) }
        assertFalse(r.p.locked)
        assertTrue(r.untilStep(0.54) in 5..8)
        assertEquals(PhaseLock.STEP_MS, r.p.shiftMs, 1e-9)
        r.p.stepDone(8.1, r.t)
        assertEquals(PhaseLock.State.MEASURE, r.p.state)
        repeat(30) { assertFalse(r.poll(0.04)) }
        assertEquals(PhaseLock.State.WATCH, r.p.state)
        assertTrue(r.p.locked)
        assertEquals(0.04, r.p.settleMix, 1e-9)
    }

    // the edge of the transition: clean and blended reads alternate; the second's mean starts a search
    @Test fun alternatingCleanAndBlendedReadsStartASearch() {
        val r = Run(PhaseLock())
        var n = -1
        for (i in 0 until 60) if (r.poll(if (i % 2 == 0) 0.0 else 0.5)) { n = i; break }
        assertTrue("stepped at $n", n in 30..45)
    }

    // steps at least 6 s apart and at most 5 a minute; three failing steps in a row rest 20 s, then 40 s
    // before the first lock the search steps as each measurement ends (a MacBook's narrow clean window took
    // four steps: 29 s at the 6 s gap, 06:48 on 2026-11-03), up to 12 a minute, without resting
    @Test fun theSearchBeforeTheFirstLockStepsAsItMeasures() {
        val r = Run(PhaseLock())
        val times = ArrayList<Long>()
        while (r.t < 20_000) {
            if (r.poll(0.5)) { times += r.t; r.p.stepDone(8.0, r.t) }
        }
        assertTrue("steps in 20 s: ${times.size}", times.size >= 4)
        for (k in 1 until times.size) assertTrue("steps ${times[k - 1]} and ${times[k]}", times[k] - times[k - 1] < PhaseLock.STEP_GAP_MS)
        assertTrue(times.count { it < 60_000 } <= PhaseLock.SEARCH_STEPS_PER_MIN)
    }

    // reset() (a new transfer) brings the search back: steps as it measures, no 6 s gap
    @Test fun aNewTransferSearchesAfresh() {
        val r = Run(PhaseLock())
        repeat(40) { r.poll(0.0, whole = true) }
        assertTrue(r.p.locked)
        r.p.reset()
        val times = ArrayList<Long>()
        val t1 = r.t
        while (r.t < t1 + 20_000) { if (r.poll(0.5)) { times += r.t; r.p.stepDone(8.0, r.t) } }
        assertTrue("steps in 20 s after reset: ${times.size}", times.size >= 4)
        for (k in 1 until times.size) assertTrue(times[k] - times[k - 1] < PhaseLock.STEP_GAP_MS)
    }

    @Test fun stepsAreRateLimitedAndFailuresRest() {
        val r = Run(PhaseLock())
        // once locked, the search is over: the rate limits and rests below apply
        repeat(40) { r.poll(0.0, whole = true) }
        assertTrue(r.p.locked)
        val times = ArrayList<Long>()
        while (r.t < 200_000) {
            if (r.poll(0.5)) { times += r.t; r.p.stepDone(8.0, r.t) }
        }
        for (k in 1 until times.size) assertTrue("steps ${times[k - 1]} and ${times[k]}", times[k] - times[k - 1] >= PhaseLock.STEP_GAP_MS)
        for (k in times.indices) assertTrue(times.count { it in times[k] until times[k] + 60_000 } <= PhaseLock.STEPS_PER_MIN)
        // after 3 steps a rest of 20 s, after the next 3 one of 40 s
        assertTrue("first rest ${times[3] - times[2]}", times[3] - times[2] >= 20_000)
        assertTrue("second rest ${times[6] - times[5]}", times[6] - times[5] >= 40_000)
    }

    // never a step while locked on; locked holds through a 0.25 mean, a second over 0.3 unlocks (then a step)
    @Test fun noStepWhileLockedAndTheLockHasHysteresis() {
        val r = Run(PhaseLock())
        repeat(40) { assertFalse(r.poll(0.0, whole = true)) }
        assertTrue(r.p.locked)
        // the blend at 0.25 and reads failing: still locked, no step
        repeat(200) { assertFalse(r.poll(0.25)) }
        assertTrue(r.p.locked)
        val n = r.untilStep(0.5, 120)
        assertTrue("stepped at $n", n in 30..70)
        assertFalse(r.p.locked)
        // a settle under 0.2 with whole reads locks (19:25: 0.17 with 111 of 111)
        r.p.stepDone(8.0, r.t)
        repeat(30) { r.poll(0.17, whole = true) }
        assertTrue(r.p.locked)
    }

    // a move of whole periods is a move (the phase is taken modulo the period); under 1 ms is ignored, and two of
    // those with none ever moving give the phase up for the session
    @Test fun ignoredOnlyUnderAMillisecond() {
        val p = 1000.0 / 30
        assertEquals(0.0, phaseShift(p, p), 1e-9)
        assertEquals(1.333, phaseShift(32.0, p), 1e-3)
        val r = Run(PhaseLock())
        r.untilStep(0.54); r.p.stepDone(2 * p, r.t)
        assertEquals(0, r.p.ignored)
        assertEquals(PhaseLock.State.MEASURE, r.p.state)
        val q = Run(PhaseLock())
        q.untilStep(0.54); q.p.stepDone(0.2, q.t)
        assertEquals(1, q.p.ignored)
        q.untilStep(0.54); q.p.stepDone(0.0, q.t)
        assertEquals(PhaseLock.State.OFF, q.p.state)
    }

    // no search at another rate, on stale reads, or while the camera's frame time is off (30-37 ms)
    @Test fun noSearchWhenTheRatesDifferReadsStopOrTheFrameTimeIsOff() {
        val r = Run(PhaseLock())
        repeat(100) { assertFalse(r.poll(0.54, match = false)) }
        repeat(15) { assertFalse(r.poll(0.54, newRead = false, match = false)) }
        repeat(25) { assertFalse(r.poll(0.54)) }
        r.p.periodMs = 41.7
        repeat(100) { assertFalse(r.poll(0.54)) }
    }

    // a measurement window without reads is measured once more; a step that never came back times out
    @Test fun noReadsRetryOnceAndALostStepTimesOut() {
        val r = Run(PhaseLock())
        r.untilStep(0.54); r.p.stepDone(8.0, r.t)
        repeat(30) { r.poll(0.0, newRead = false) }
        assertEquals(PhaseLock.State.MEASURE, r.p.state)
        repeat(25) { r.poll(0.05) }
        assertTrue(r.p.locked)
        val s = Run(PhaseLock())
        s.untilStep(0.54)
        var timedOut = false
        repeat(95) { s.poll(0.54); timedOut = timedOut || s.p.stepTimedOut }
        assertTrue(timedOut)
    }

    // an Auto rebind keeps what was learnt (ignored steps); a new session forgets it
    @Test fun aRebindKeepsWhatWasLearnt() {
        val r = Run(PhaseLock())
        assertTrue(r.p.takeRestore()); assertFalse(r.p.takeRestore())
        r.untilStep(0.54); r.p.stepDone(0.2, r.t)
        r.p.cameraChanged()
        assertTrue(r.p.takeRestore())
        assertEquals(1, r.p.ignored)
        r.untilStep(0.54); r.p.stepDone(0.0, r.t)
        assertEquals(PhaseLock.State.OFF, r.p.state)
        r.p.reset()
        assertEquals(0, r.p.ignored); assertEquals(PhaseLock.State.WATCH, r.p.state)
    }

    // the exact step: the first longer frame means it took effect; the move is what the gaps add up to
    @Test fun anExactStepIsMeasuredFromTheGaps() {
        val f = 1000.0 / 30
        val s = PhaseStepper(8.0, f)
        assertEquals(PhaseStepper.Act.NONE, s.frame(f))
        assertEquals(PhaseStepper.Act.NONE, s.frame(f + 0.1))
        assertEquals(PhaseStepper.Act.REVERT, s.frame(f + 8.0))
        assertEquals(PhaseStepper.Act.NONE, s.frame(f + 8.0))
        repeat(3) { assertEquals(PhaseStepper.Act.NONE, s.frame(f)) }
        assertEquals(PhaseStepper.Act.DONE, s.frame(f - 0.05))
        assertEquals(16.05, s.movedMs, 1e-6)
        assertFalse(s.fallback)
    }

    // a camera that ignores the frame duration: the slower rate, reverted at its first slow frame
    @Test fun anIgnoredExactStepFallsBackForOneSlowFrame() {
        val f = 1000.0 / 30
        val s = PhaseStepper(8.0, f)
        var a = PhaseStepper.Act.NONE
        var n = 0
        while (a != PhaseStepper.Act.REVERT && n++ < 30) a = s.frame(f)
        repeat(3) { assertEquals(PhaseStepper.Act.NONE, s.frame(f)) }
        assertEquals(PhaseStepper.Act.SLOW, s.frame(f))
        assertTrue(s.fallback)
        assertEquals(PhaseStepper.Act.NONE, s.frame(f))
        assertEquals(PhaseStepper.Act.REVERT, s.frame(1000.0 / 24))
        repeat(3) { assertEquals(PhaseStepper.Act.NONE, s.frame(f)) }
        assertEquals(PhaseStepper.Act.DONE, s.frame(f))
        assertEquals(1000.0 / 24 - f, s.movedMs, 1e-6)
    }

    // whatever the frames do, a step ends within 2.5 s
    @Test fun aStepEndsWithinItsHardLimit() {
        val s = PhaseStepper(8.0, 1000.0 / 30)
        var a = PhaseStepper.Act.NONE
        var k = 0
        while (a != PhaseStepper.Act.DONE && k < 200) { a = s.frame(if (k % 2 == 0) 30.0 else 40.0); k++ }
        assertEquals(PhaseStepper.Act.DONE, a)
        assertTrue(k * 35.0 <= PhaseStepper.HARD_MS + 40)
    }

    private class FakeCam : PhaseCamera {
        val calls = ArrayList<String>()
        override fun exact(askMs: Double) { calls += "exact %.1f".format(java.util.Locale.ROOT, askMs) }
        override fun slow() { calls += "slow" }
        override fun normal() { calls += "normal" }
    }

    // the controller: the exact request with the stepper (one lock), the revert after it, auto exposure at the end;
    // the carry not known, the first request is 8 ms and teaches it (8 ms over 8 frames: 64 ms, carry 8)
    @Test fun theFirstExactStepTeachesTheCarry() {
        val f = 1000.0 / 30
        val cam = FakeCam()
        var kept = 0
        val c = PhaseController(cam, true, carry = 0) { kept = it }
        c.frame(f); assertTrue(cam.calls.isEmpty())
        c.start(3.0, exposureKnown = true, now = 0)
        assertEquals(listOf("exact 8.0"), cam.calls)
        repeat(8) { c.frame(f + 8.0) }
        repeat(4) { c.frame(f) }
        assertEquals(64.0, c.takeResult()!!, 1e-6)
        assertEquals(8, c.carry); assertEquals(8, kept)
        c.start(8.0, true, 7000)
        assertEquals("exact 1.0", cam.calls.last())
        // a known carry from before: straight to shift / carry
        val cam2 = FakeCam()
        PhaseController(cam2, true, carry = 8).start(8.0, true, 0)
        assertEquals(listOf("exact 1.0"), cam2.calls)
    }

    // an 8 ms request that moves under 1 ms: exact is ignored, the fallback's single slow frame is the step, rate
    // steps from then on; a fallback that moved more than 20 ms (the camera cannot do one slow frame) is off
    @Test fun anIgnoredExactStepAndATooLongFallback() {
        val f = 1000.0 / 30
        val cam = FakeCam()
        val c = PhaseController(cam, true, carry = 0)
        c.start(8.0, true, 0)
        var n = 0
        while (!cam.calls.contains("slow") && n++ < 40) c.frame(f)
        c.frame(1000.0 / 24)
        repeat(4) { c.frame(f) }
        assertEquals(1000.0 / 24 - f, c.takeResult()!!, 1e-6)
        assertFalse(c.fallbackOff)
        c.start(8.0, true, 7000)
        assertEquals("slow", cam.calls.last())
        // this time the slow rate held for 4 frames: 33 ms moved, the fallback off for the session
        repeat(4) { c.frame(1000.0 / 24) }
        repeat(4) { c.frame(f) }
        assertTrue(c.takeResult()!! > 20.0)
        assertTrue(c.fallbackOff)
        val before = cam.calls.size
        c.start(8.0, true, 14000)
        assertEquals(before, cam.calls.size)
        assertEquals(0.0, c.takeResult()!!, 1e-9)
    }

    // an aborted step puts auto exposure back, and its late frames touch nothing; a stalled one ends on the poll;
    // a failed capture reverts; without manual control the fallback only
    @Test fun theControllersSafetyNets() {
        val cam = FakeCam(); val c = PhaseController(cam, true, carry = 1)
        c.start(8.0, true, 0); c.abort()
        c.frame(40.0); c.frame(33.3)
        assertEquals(listOf("exact 8.0", "normal"), cam.calls)
        c.start(8.0, true, 1000); c.tick(3000); assertTrue(c.stepping); c.tick(3600); assertFalse(c.stepping)
        assertEquals("normal", cam.calls.last())
        val cam3 = FakeCam(); val c3 = PhaseController(cam3, true, carry = 1)
        c3.start(8.0, true, 0); c3.failed()
        assertEquals(listOf("exact 8.0", "normal"), cam3.calls)
        val cam4 = FakeCam(); PhaseController(cam4, exactSupported = false).start(8.0, true, 0)
        assertEquals(listOf("slow"), cam4.calls)
    }

    // a dropped result (a gap of two frame times) does not teach the carry
    @Test fun aDroppedFrameTeachesNothing() {
        val cam = FakeCam(); val c = PhaseController(cam, true, carry = 0)
        val f = 1000.0 / 30
        c.start(8.0, true, 0)
        c.frame(2 * f + 8.0)
        repeat(4) { c.frame(f) }
        assertEquals(0, c.carry)
    }

    /** The 19:43 run's camera, simulated: one exact request carried on 8 frames (an 8 ms request moves the frames
     *  64 ms), 1 ms changes lost in the jitter; the panel's transition makes reads blended where the phase (ms
     *  within the period) is under 10, alternating clean and blended at 10-12, clean beyond. The run starts at the
     *  edge (11 ms). Every capture is a data read; whole when clean. */
    @Test fun theNineteenFortyThreeRunReplayed() {
        val f = 1000.0 / 30
        val cam = object : PhaseCamera {
            var carried = 0; var extra = 0.0; val calls = ArrayList<String>()
            override fun exact(askMs: Double) { calls += "exact"; carried = 8; extra = askMs }
            override fun slow() { calls += "slow" }
            override fun normal() { calls += "normal" }
        }
        val lock = PhaseLock()
        val ctl = PhaseController(cam, true, carry = 0)
        var phase = 11.0
        var t = 0.0
        var steps = 0
        var stepsIn30 = 0
        var lockedAt = -1.0
        var k = 0
        while (t < 60_000) {
            // the frame: longer while a request is carried
            var gap = f
            if (cam.carried > 0) { gap += cam.extra; cam.carried--; phase = (phase + cam.extra) % f }
            t += gap
            ctl.frame(gap)
            val now = t.toLong()
            ctl.tick(now)
            ctl.takeResult()?.let { lock.stepDone(it, now) }
            val mix = when { phase < 10 -> 0.5; phase < 12 -> if (k % 2 == 0) 0.0 else 0.5; else -> 0.0 }
            k++
            if (lock.update(true, mix, true, now, whole = mix < 0.15)) {
                ctl.start(lock.shiftMs, true, now); steps++; if (t < 30_000) stepsIn30++
            }
            if (lock.stepTimedOut) ctl.abort()
            if (lock.locked && lockedAt < 0 && phase >= 12) lockedAt = t
        }
        assertFalse("no fallback: ${cam.calls}", cam.calls.contains("slow"))
        assertTrue("steps in 30 s: $stepsIn30", stepsIn30 <= 2)
        assertTrue("locked at $lockedAt ms", lockedAt in 0.0..20_000.0)
        assertEquals(8, ctl.carry)
    }
}
