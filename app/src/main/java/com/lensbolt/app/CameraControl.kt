package com.lensbolt.app

// Focus and phase rules for the camera, pure (no Android types) so they read and test on their own;
// MainActivity applies them through CameraX.

import kotlin.math.abs
import kotlin.math.roundToInt

/** Focus: continuous while the tile is being found; locked once it has read whole on [WHOLE_READS] captures within
 *  [WHOLE_MS] (a lens that keeps hunting passes through focus and back, a sharp capture now and then); continuous
 *  again when the cells change size by more than [CELL_CHANGE] (another distance), when the tile is gone for
 *  [FAIL_MS], or when reads fail for [FAIL_MS] AND the border's edges have grown past [EDGE_GROWTH] times their
 *  width at the lock (failing reads with sharp edges are something else: two sender frames blended, motion). A
 *  lock the camera reports as not focused is dropped at once ([lockFailed]); after [MAX_FAILED] of those the
 *  focus stays continuous for the session. */
class FocusLock {
    companion object {
        const val WHOLE_MS = 1000L; const val WHOLE_READS = 3; const val FAIL_MS = 2000L; const val CELL_CHANGE = 0.15
        const val EDGE_GROWTH = 1.3; const val READS_WELL = 0.9; const val MAX_FAILED = 2
    }
    enum class Act { NONE, LOCK, UNLOCK }
    var locked = false
        private set
    var failedLocks = 0
        private set
    private var lockCell = 0.0
    private var lockEdge = 0.0
    private val wholeAt = ArrayDeque<Long>()
    private var failSince = -1L
    private var goneSince = -1L

    /** A new session: everything forgotten. True when a lock was held (the caller cancels it). */
    fun reset(): Boolean { val was = locked; unlock(); failedLocks = 0; return was }

    private fun unlock() { locked = false; wholeAt.clear(); failSince = -1L; goneSince = -1L }

    /** The camera reported the lock not focused: unlocked at once (the caller cancels it). */
    fun lockFailed() { failedLocks++; unlock() }

    /** One poll: whether a tile read lately, whether this poll brought a new data read, the last stitched frame's
     *  codewords, the cells' camera px and the border's edge width (camera px, 0 unknown). */
    fun update(fresh: Boolean, newRead: Boolean, ok: Long, all: Long, cellPx: Double, edgePx: Double, now: Long): Act {
        if (!locked) {
            if (!fresh) { wholeAt.clear(); return Act.NONE }
            if (newRead && all > 0 && ok >= all) wholeAt.addLast(now)
            while (wholeAt.isNotEmpty() && now - wholeAt.first() > WHOLE_MS) wholeAt.removeFirst()
            if (failedLocks < MAX_FAILED && wholeAt.size >= WHOLE_READS) {
                locked = true; lockCell = cellPx; lockEdge = edgePx; failSince = -1L; goneSince = -1L
                return Act.LOCK
            }
            return Act.NONE
        }
        if (!fresh) { if (goneSince < 0) goneSince = now } else goneSince = -1L
        val failing = fresh && all > 0 && ok < all * READS_WELL
        if (failing) { if (failSince < 0) failSince = now } else if (fresh) failSince = -1L
        val moved = fresh && cellPx > 0 && lockCell > 0 && abs(cellPx / lockCell - 1) > CELL_CHANGE
        val soft = lockEdge <= 0 || edgePx <= 0 || edgePx > lockEdge * EDGE_GROWTH
        val failedLong = failSince >= 0 && now - failSince >= FAIL_MS && soft
        val goneLong = goneSince >= 0 && now - goneSince >= FAIL_MS
        if (moved || failedLong || goneLong) { unlock(); return Act.UNLOCK }
        return Act.NONE
    }
}

/** How far a move of [movedMs] shifts the phase within a period of [periodMs]: the distance to the nearest whole
 *  period (a move of one period, or of four frames' 8 ms on a 33.3 ms period less 1.3 ms, shifts it by that
 *  remainder only). */
fun phaseShift(movedMs: Double, periodMs: Double): Double {
    val d = ((movedMs % periodMs) + periodMs) % periodMs
    return minOf(d, periodMs - d)
}

/** The camera's phase against the sender's panel. A laptop panel scans its rows top to bottom and its pixels take
 *  much of a refresh to settle, so for part of every sender frame the whole panel shows a gradient between the
 *  frame and the one before. A camera whose capture lands in that window sees two frames blended in a ramp down
 *  the tile on every capture, whatever its exposure (2026-11-02: the header frame's share 0.85 at the top to
 *  0.00 at the bottom at auto exposure and at 3 ms alike; 0-4 of 111 codewords; a run that started in the clean
 *  phase read 3.52 Mbit/s with the blend at 0.0; one exact step took a camera at the edge of the transition from
 *  alternating clean and blended reads to whole ones, 19:25).
 *  A search starts when the read's blend (status `mix`) averages [MIX_HIGH] or more over the data reads of the
 *  last [HIGH_MS] (a full second of reads at most [STALE_MS] apart: a phase at the edge of the transition
 *  alternates clean and blended reads), the phase is not locked on, the sender runs at the camera's rate (within
 *  [RATE_MATCH]) and the camera's frame time is within 30-37 ms. A step of [STEP_MS] (the caller moves the frames
 *  and reports the measured move, [stepDone]), then the blend over [MEASURE_MS] from [MEASURE_SKIP_MS] after it:
 *  under [MIX_HIGH], or under [LOCK_MIX] with the reads whole, the phase is held there and locked on; else another
 *  step. Steps are at least [STEP_GAP_MS] apart and at most [STEPS_PER_MIN] a minute (a step costs a few frames
 *  and the user feels many of them); [FAILS_REST] steps in a row that did not get the blend under [LOCK_MIX] rest
 *  [REST_MS], then [REST_MAX_MS]. Never a step while locked on: locked on is the second's blend under [LOCK_MIX]
 *  with whole reads ([WHOLE_READS]), and it goes only after a second over [UNLOCK_MIX]. A step that moved the
 *  frames less than [MOVED_MS] is ignored (a move of whole periods is harmless: the phase is taken modulo the
 *  period); the phase is given up for the session ([State.OFF]) after [IGNORED_OFF] such steps if none ever
 *  moved. */
class PhaseLock {
    companion object {
        const val MIX_HIGH = Guidance.MIX_SHOWS
        const val HIGH_MS = 1000L; const val STALE_MS = 400L; const val RATE_MATCH = 0.10
        const val STEP_MS = 8.0; const val MOVED_MS = 1.0; const val MEASURE_SKIP_MS = 300L; const val MEASURE_MS = 600L
        const val STEP_TIMEOUT_MS = 3000L; const val IGNORED_OFF = 2
        const val STEP_GAP_MS = 6000L; const val STEPS_PER_MIN = 5; const val FAILS_REST = 3
        /** steps a minute before the first lock (the search), and the failed measurements it bears before a rest */
        const val SEARCH_STEPS_PER_MIN = 12; const val SEARCH_FAILS_REST = 8
        const val REST_MS = 20_000L; const val REST_MAX_MS = 40_000L
        const val FRAME_MIN_MS = 30.0; const val FRAME_MAX_MS = 37.0
        const val LOCK_MIX = 0.2; const val UNLOCK_MIX = 0.3; const val WHOLE_READS = 3
    }
    enum class State { WATCH, STEP, MEASURE, WAIT, OFF }
    var state = State.WATCH
        private set
    /** the camera's frame time (ms): the period the phase moves in */
    var periodMs = 1000.0 / 30
    /** the move to make now (ms), valid when [update] returns true */
    var shiftMs = 0.0
        private set
    var steps = 0
        private set
    var searches = 0
        private set
    var ignored = 0
        private set
    var lastMoveMs = 0.0
        private set
    /** the phase of this search: the moves added up (ms, modulo the period) */
    var cumulativeMs = 0.0
        private set
    /** the blend measured at the last settle (-1: none yet) */
    var settleMix = -1.0
        private set
    /** locked on: the blend low with whole reads (hysteresis) */
    var locked = false
        private set
    /** waiting out a rest before the next search */
    fun resting(now: Long) = state == State.WATCH && restUntil >= 0 && now < restUntil
    /** set by [update] when the step in flight ran past [STEP_TIMEOUT_MS] */
    var stepTimedOut = false
        private set
    private var anyMoved = false
    private var restore = true
    // the data reads of the last second: (when, blend, whole); since when reads have come without a gap
    private val window = ArrayDeque<Triple<Long, Double, Boolean>>()
    private var watchSince = -1L
    private var unlockSince = -1L
    private var lastRead = -1L
    private var at = 0L
    private var restUntil = -1L
    private var restLevel = 0
    private var fails = 0
    private var retried = false
    private var pending = 0.0
    private val stepTimes = ArrayDeque<Long>()
    private val measured = ArrayList<Double>()
    private var measuredWhole = 0

    /** A new session: everything forgotten, the camera's rate to be put back ([takeRestore]). */
    fun reset() {
        cameraChanged(); state = State.WATCH; steps = 0; searches = 0; ignored = 0; anyMoved = false
        restUntil = -1L; restLevel = 0; fails = 0; settleMix = -1.0; stepTimes.clear()
        // a new transfer searches afresh (the second transfer of an app session on the MacBook, 07:15: three steps
        // at the 6 s gap again, 17 s to the first byte)
        everLocked = false
    }

    /** Another camera stream (an Auto rebind) in the same session: any step is over, the rate to be put back; what
     *  was learnt about this camera (ignored steps, a step that moved) is kept. */
    fun cameraChanged() {
        state = if (state == State.OFF) State.OFF else State.WATCH
        window.clear(); watchSince = -1L; unlockSince = -1L; lastRead = -1L; locked = false; cumulativeMs = 0.0; measured.clear(); restore = true
    }

    /** True once after [reset] / [cameraChanged]: the caller sets the camera's own rate and exposure again. */
    fun takeRestore(): Boolean { val r = restore; restore = false; return r }

    /** Whether the phase ever locked this session: until it has, the search runs step after step as each
     *  measurement ends (a MacBook's slow panel leaves a narrow clean window: four steps of 8 ms, 06:48 on
     *  2026-11-03, took 29 s at the 6 s gap before the first byte; the file then read 96/96 at 4.5 Mbit/s). */
    private var everLocked = false
    private fun canStep(now: Long): Boolean {
        while (stepTimes.isNotEmpty() && now - stepTimes.first() >= 60_000L) stepTimes.removeFirst()
        if (!everLocked) return stepTimes.size < SEARCH_STEPS_PER_MIN
        return (stepTimes.isEmpty() || now - stepTimes.last() >= STEP_GAP_MS) && stepTimes.size < STEPS_PER_MIN
    }

    private fun step(shift: Double, now: Long): Boolean { shiftMs = shift; steps++; stepTimes.addLast(now); at = now; state = State.STEP; return true }

    /** Back to watching: the second of reads starts again (the next search needs a full second of the new phase). */
    private fun settle(now: Long) { state = State.WATCH; window.clear(); watchSince = now; unlockSince = -1L }

    private fun rest(now: Long) { restUntil = now + minOf(REST_MS shl restLevel, REST_MAX_MS); restLevel = minOf(restLevel + 1, 1) }

    /** One poll: [newRead] a new data read with its blend [mix] and whether it read [whole] (90% of its frame's
     *  codewords); [ratesMatch] the sender's rate within [RATE_MATCH] of the camera's. True: step the phase by
     *  [shiftMs] now (and report it with [stepDone]). A step in flight past [STEP_TIMEOUT_MS] sets [stepTimedOut]:
     *  the caller ends it (auto exposure back). */
    fun update(newRead: Boolean, mix: Double, ratesMatch: Boolean, now: Long, whole: Boolean = false): Boolean {
        stepTimedOut = false
        // the last second of data reads, in every state (the lock line follows them)
        if (newRead) {
            if (lastRead < 0 || now - lastRead > STALE_MS) { window.clear(); watchSince = now }
            lastRead = now
            window.addLast(Triple(now, mix, whole))
        } else if (lastRead >= 0 && now - lastRead > STALE_MS) { window.clear(); watchSince = -1L }
        while (window.isNotEmpty() && now - window.first().first > HIGH_MS) window.removeFirst()
        val full = watchSince >= 0 && now - watchSince >= HIGH_MS
        val mean = if (window.isEmpty()) -1.0 else window.sumOf { it.second } / window.size
        val wholeReads = window.count { it.third }
        // locked on: the blend low and the reads whole; off only when the blend is well up for a second
        if (full) {
            if (mean in 0.0..LOCK_MIX && wholeReads >= WHOLE_READS) { locked = true; everLocked = true; unlockSince = -1L }
            if (mean > UNLOCK_MIX) { if (unlockSince < 0) unlockSince = now; if (now - unlockSince >= HIGH_MS) locked = false } else unlockSince = -1L
        }
        val allowed = ratesMatch && periodMs in FRAME_MIN_MS..FRAME_MAX_MS
        when (state) {
            State.WATCH -> {
                if (full && !locked && mean >= MIX_HIGH && allowed && !resting(now) && canStep(now)) {
                    searches++; cumulativeMs = 0.0; fails = 0
                    return step(STEP_MS, now)
                }
            }
            State.STEP -> if (now - at >= STEP_TIMEOUT_MS) { settle(now); stepTimedOut = true }
            State.MEASURE -> {
                if (newRead && now - at >= MEASURE_SKIP_MS) { measured.add(mix); if (whole) measuredWhole++ }
                if (now - at >= MEASURE_SKIP_MS + MEASURE_MS) {
                    if (measured.isEmpty()) {
                        // no reads in the window: once more, then back to watching
                        if (!retried) { retried = true; at = now - MEASURE_SKIP_MS; return false }
                        settle(now); return false
                    }
                    val m = measured.average()
                    settleMix = m
                    if (m < MIX_HIGH || (m < LOCK_MIX && measuredWhole >= WHOLE_READS)) {
                        locked = true; everLocked = true; fails = 0; restLevel = 0; settle(now)
                    } else if (++fails >= (if (everLocked) FAILS_REST else SEARCH_FAILS_REST)) {
                        fails = 0; rest(now); settle(now)
                    } else { pending = STEP_MS; state = State.WAIT }
                }
            }
            State.WAIT -> when {
                locked || !allowed -> settle(now)
                canStep(now) -> return step(pending, now)
            }
            State.OFF -> {}
        }
        return false
    }

    /** The step's measured move (ms; the exact step's, or the fallback's when the exact one did not move). */
    fun stepDone(movedMs: Double, now: Long) {
        if (state != State.STEP) return
        lastMoveMs = movedMs
        if (kotlin.math.abs(movedMs) < MOVED_MS) {
            ignored++
            if (!anyMoved && ignored >= IGNORED_OFF) { state = State.OFF; return }
            settle(now)
            return
        }
        anyMoved = true
        cumulativeMs = ((cumulativeMs + movedMs) % periodMs + periodMs) % periodMs
        measured.clear(); measuredWhole = 0; retried = false; at = now; state = State.MEASURE
    }
}

/** One phase step, frame by frame (the capture timestamps' gaps against the camera's frame time [frameMs]):
 *  first the exact step (one longer frame asked for with a manual exposure equal to auto exposure's own: the
 *  caller has sent it; [exact] false: the camera cannot, straight to the fallback); the first gap longer than the
 *  frame time by [SEEN_MS], or a failed capture, means it took effect: back to auto exposure ([Act.REVERT]);
 *  nothing within [EXACT_WAIT_MS]: back too. The move is what the gaps add up to until [CALM_FRAMES] frames in a
 *  row keep the frame time (within [CALM_MS]). An exact step that moved the frames less than [movedMin] falls back
 *  to the slower frame rate ([Act.SLOW]), reverted at its first slow frame ([Act.REVERT]). Whatever happens, the
 *  step ends within [HARD_MS] ([Act.DONE], the caller puts auto exposure back). */
class PhaseStepper(@Suppress("unused") private val targetMs: Double, private val frameMs: Double, exact: Boolean = true, private val movedMin: Double = PhaseLock.MOVED_MS) {
    companion object {
        const val SEEN_MS = 0.5; const val EXACT_WAIT_MS = 500.0; const val CALM_MS = 0.5; const val CALM_FRAMES = 4
        const val FALLBACK_MAX_MS = 1500.0; const val HARD_MS = 2500.0
    }
    enum class Act { NONE, REVERT, SLOW, DONE }
    private enum class Stage { EXACT_WAIT, EXACT_SETTLE, FB_RUN, FB_SETTLE, DONE }
    private var stage = if (exact) Stage.EXACT_WAIT else Stage.FB_RUN
    private var stageMs = 0.0
    private var totalMs = 0.0
    private var calm = 0
    /** the move so far (ms), and whether the fallback made it */
    var movedMs = 0.0
        private set
    var fallback = !exact
        private set
    /** an exact request was sent (and moved nothing, when [fallback]) */
    val exactTried = exact
    /** the exact step's own move (ms), kept when the fallback ran */
    var exactMovedMs = 0.0
        private set
    /** a gap of about two frame times was seen (a result dropped): the move is not a measure of the request */
    var droppedFrame = false
        private set
    /** frames from the start to the first longer one (how deep the request pipeline is) */
    var framesToEffect = 0
        private set
    val done get() = stage == Stage.DONE

    /** A failed capture: the request took effect one way or another; back to auto exposure. */
    fun failed(): Act = if (stage == Stage.EXACT_WAIT) { stage = Stage.EXACT_SETTLE; calm = 0; Act.REVERT } else Act.NONE

    /** One capture: the gap since the previous one (ms). */
    fun frame(gapMs: Double): Act {
        if (stage == Stage.DONE) return Act.DONE
        val extra = gapMs - frameMs
        if (gapMs > 1.8 * frameMs) droppedFrame = true
        movedMs += extra
        stageMs += gapMs; totalMs += gapMs
        calm = if (kotlin.math.abs(extra) < CALM_MS) calm + 1 else 0
        if (totalMs >= HARD_MS) { stage = Stage.DONE; return Act.DONE }
        return when (stage) {
            Stage.EXACT_WAIT -> {
                framesToEffect++
                if (extra > SEEN_MS || stageMs >= EXACT_WAIT_MS) { stage = Stage.EXACT_SETTLE; calm = 0; Act.REVERT } else Act.NONE
            }
            Stage.EXACT_SETTLE -> if (calm < CALM_FRAMES) Act.NONE else {
                exactMovedMs = movedMs
                if (kotlin.math.abs(movedMs) >= movedMin) { stage = Stage.DONE; Act.DONE }
                else { fallback = true; movedMs = 0.0; stageMs = 0.0; calm = 0; stage = Stage.FB_RUN; Act.SLOW }
            }
            // the first slow frame: the rate again at once (the frames still in the pipeline add what they add)
            Stage.FB_RUN -> if (extra > SEEN_MS || stageMs >= FALLBACK_MAX_MS) { stage = Stage.FB_SETTLE; calm = 0; Act.REVERT } else Act.NONE
            Stage.FB_SETTLE -> if (calm < CALM_FRAMES) Act.NONE else { stage = Stage.DONE; Act.DONE }
            Stage.DONE -> Act.DONE
        }
    }
}

/** What the phase steps ask of the camera (MainActivity: Camera2 options through CameraX). */
interface PhaseCamera {
    /** one longer frame: auto exposure off at its own last exposure and gain, the frame duration + [askMs] */
    fun exact(askMs: Double)
    /** the slower fixed rate (the fallback) */
    fun slow()
    /** auto exposure at the camera's own rate */
    fun normal()
}

/** The steps in flight, between the status poll (start, timeout) and the camera's callback thread (frames,
 *  failures): one lock around the stepper and every camera request it makes, so a step's requests go out in its
 *  order and a stale step can never undo a newer one. [exactSupported]: the camera has manual sensor control
 *  with auto exposure off among its modes (else the fallback only). [carry]: how many frames an exact request
 *  lands on, as learnt before on this camera (0: not known). Not known, the first exact step asks for
 *  [PhaseLock.STEP_MS] whatever the shift and the carry is its move over that (19:25: 8 ms asked, 63.9 ms moved:
 *  carry 8; an overshoot of whole periods is harmless); known, a step asks for shift / carry. [onCarry] keeps what
 *  was learnt (MainActivity: per camera id). The exact step counts as ignored only when an 8 ms request moved
 *  the frames less than 1 ms; then the fallback (the slower rate, reverted at its first slow frame); a fallback
 *  that moved them more than [FALLBACK_MAX_MOVE_MS] (the camera cannot do a single slow frame: 19:43 measured
 *  37-142 ms, the camera at 11-19 fps for seconds) is off for the session ([fallbackOff]). */
class PhaseController(private val cam: PhaseCamera, private val exactSupported: Boolean, carry: Int = 0, private val onCarry: (Int) -> Unit = {}) {
    companion object { const val CARRY_MAX = 12; const val FALLBACK_MAX_MOVE_MS = 20.0 }
    private val lock = Any()
    private var stepper: PhaseStepper? = null
    private var asked = 0.0
    private var startedAt = 0L
    private var result: Double? = null
    private var exactWorks = true
    /** the camera's frame time (ms), learnt from calm gaps between steps */
    var frameMs = 1000.0 / 30
        private set
    /** frames an exact request lands on (0: not known yet) */
    var carry = carry
        private set
    var lastExact = true
        private set
    /** the fallback moved the frames by more than [FALLBACK_MAX_MOVE_MS]: no more of it this session */
    var fallbackOff = false
        private set
    val stepping get() = synchronized(lock) { stepper != null }

    /** A step of [shiftMs] at [now] (ms): the stepper published and its first request sent under one lock. */
    fun start(shiftMs: Double, exposureKnown: Boolean, now: Long) = synchronized(lock) {
        result = null; startedAt = now
        val exact = exactSupported && exactWorks && exposureKnown
        if (!exact && fallbackOff) { result = 0.0; return@synchronized }
        val ask = if (carry <= 0) PhaseLock.STEP_MS else (shiftMs / carry).coerceIn(0.3, frameMs / 2)
        val st = PhaseStepper(shiftMs, frameMs, exact)
        stepper = st; asked = ask
        if (exact) cam.exact(ask) else cam.slow()
    }

    /** The camera's callback: one capture's gap since the previous one (ms). */
    fun frame(gapMs: Double) = synchronized(lock) {
        val st = stepper
        if (st == null) { if (gapMs in PhaseLock.FRAME_MIN_MS..PhaseLock.FRAME_MAX_MS) frameMs = (frameMs * 15 + gapMs) / 16; return@synchronized }
        when (st.frame(gapMs)) {
            PhaseStepper.Act.REVERT -> cam.normal()
            PhaseStepper.Act.SLOW -> cam.slow()
            PhaseStepper.Act.DONE -> finish(st)
            PhaseStepper.Act.NONE -> {}
        }
    }

    /** The camera's callback: a failed capture (a request it rejected): back to auto exposure. */
    fun failed() = synchronized(lock) { val st = stepper ?: return@synchronized; if (st.failed() == PhaseStepper.Act.REVERT) cam.normal() }

    private fun finish(st: PhaseStepper) {
        cam.normal()
        lastExact = !st.fallback
        if (stepper === st) stepper = null
        if (st.fallback) {
            // the exact request moved nothing (when one was sent): rate steps from now on, if they are single frames
            if (st.exactTried) exactWorks = false
            if (kotlin.math.abs(st.movedMs) > FALLBACK_MAX_MOVE_MS) fallbackOff = true
        } else if (!st.droppedFrame && kotlin.math.abs(st.movedMs) >= PhaseLock.MOVED_MS && asked > 0) {
            val c = (st.movedMs / asked).roundToInt().coerceIn(1, CARRY_MAX)
            if (c != carry) { carry = c; onCarry(c) }
        }
        result = st.movedMs
    }

    /** The status poll: the step past [PhaseStepper.HARD_MS] (no frames came: the camera stalled) is ended with
     *  auto exposure back; or [abort] (the phase lock's own timeout). */
    fun tick(now: Long) = synchronized(lock) {
        val st = stepper ?: return@synchronized
        if (now - startedAt > PhaseStepper.HARD_MS) { stepper = null; cam.normal(); result = st.movedMs }
    }

    fun abort() = synchronized(lock) { stepper = null; result = null; cam.normal() }

    /** The finished step's measured move, once. */
    fun takeResult(): Double? = synchronized(lock) { val r = result; result = null; r }
}
