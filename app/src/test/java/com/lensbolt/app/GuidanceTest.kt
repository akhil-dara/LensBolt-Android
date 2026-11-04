package com.lensbolt.app

import org.junit.Assert.assertEquals
import org.junit.Test

class GuidanceTest {
    private val square = doubleArrayOf(1.0, 1.0, 1.0, 1.0)
    private fun read(cellPx: Double, ok: Long, all: Long, pitch: Int = 2, bw: Boolean = true, edges: DoubleArray = square,
                     fresh: Boolean = true, failure: String? = null, receiving: Boolean = true) =
        Reading(fresh, failure, pitch, bw, cellPx, edges, ok, all, receiving)

    // the lock (the blue line): the tile read without a gap for 0.5 s and a codeword decoded in the last 10 reads
    // too close to focus: the lens at its nearest and the autofocus not focused for 0.8 s (a phone's main
    // camera focuses no nearer than 13 cm: a phone sender held at 8 cm never sharpens); a sharp picture at the
    // nearest setting, a lens short of it, or a scan in progress are not
    // the phone log (2026-11-03, a phone sender at about 8 cm): the lens parked at 14.29 D, autofocus between a scan
    // (1) and not focused (5) every second, 0 of 49 codewords; the scans in between must not restart the watch
    // a phone camera reports its lens at the nearest end (14.29 D) and not focused even while the tile reads 49 of
    // 49 codewords (2026-11-03): one whole read there shows this camera's report cannot be trusted, and the hint
    // stays off (it said "move back" over sharp captures and over cells too small to read)
    // nearer than the lens focuses, seen in the picture itself (2026-11-04, a phone at 4096x3072 reading a
    // phone sender's 2 px tile): at 8 cm the cells measured 4.1 px with edges of 5.8-7.9 px and read 0-102 of 111; at
    // 15 cm, 2.8 px cells with 3.5 px edges read 111 of 111. Big cells with edges wider than [Guidance.SOFT_RATIO]
    // of a cell: move back, whatever the lens reports
    // a blend the measure under-reports: a session log (2026-11-04) read 99 -> 31-36 of 111 codewords for 5 s with the
    // border sharp, the cells big enough and the measured blend 0.0-0.1; the phase step waited until reads fell under a
    // fifth (18 of 111), then one step brought 111 back. Under half, sharp and big enough is a blend
    @Test fun sharp_big_enough_cells_reading_under_half_are_a_blend() {
        assertEquals(true, Guidance.blendSuspected(ok = 31, all = 111, cellPx = 1.86, edgePx = 1.83, pitch = 2, bw = true))
        assertEquals(true, Guidance.blendSuspected(ok = 18, all = 111, cellPx = 1.86, edgePx = 1.83, pitch = 2, bw = true))
        assertEquals(false, Guidance.blendSuspected(ok = 96, all = 111, cellPx = 1.86, edgePx = 1.83, pitch = 2, bw = true))
        // soft edges or cells under the floor: not a blend (focus, distance), the phase must not step for them
        assertEquals(false, Guidance.blendSuspected(ok = 31, all = 111, cellPx = 1.86, edgePx = 3.0, pitch = 2, bw = true))
        assertEquals(false, Guidance.blendSuspected(ok = 31, all = 111, cellPx = 1.3, edgePx = 1.83, pitch = 2, bw = true))
        assertEquals(false, Guidance.blendSuspected(ok = 0, all = 0, cellPx = 1.86, edgePx = 1.83, pitch = 2, bw = true))
    }

    @Test fun big_soft_cells_say_move_back() {
        val near = Reading(true, null, 2, true, 4.1, square, 5, 111, true, edgePx = 6.3)
        assertEquals(Hints.TOO_CLOSE, Guidance.hint(near))
        // the same cells sharp: reading, not too close
        assertEquals(Hints.GOOD, Guidance.hint(near.copy(codewordsOk = 111, edgePx = 3.5)))
        // soft cells that are small: too far or simply soft, never "move back"
        val small = Reading(true, null, 2, true, 1.8, square, 0, 111, true, edgePx = 2.6)
        assertEquals(false, Guidance.hint(small) == Hints.TOO_CLOSE)
        // 15 cm at Max: comfortable cells, edges under the ratio, reading
        assertEquals(Hints.GOOD, Guidance.hint(Reading(true, null, 2, true, 2.8, square, 111, 111, true, edgePx = 3.5)))
    }

    @Test fun a_whole_read_at_the_nearest_end_silences_the_watch() {
        val w = TooCloseWatch()
        assertEquals(false, w.update(now = 0, afState = 5, focusDiopters = 14.29f, minDiopters = 7.69f, readsWell = true))
        assertEquals(false, w.update(now = 900, afState = 5, focusDiopters = 14.29f, minDiopters = 7.69f))
        assertEquals(false, w.update(now = 5000, afState = 5, focusDiopters = 14.29f, minDiopters = 7.69f))
        // a new camera: trusted again
        w.reset()
        assertEquals(false, w.update(now = 6000, afState = 5, focusDiopters = 14.29f, minDiopters = 7.69f))
        assertEquals(true, w.update(now = 6900, afState = 5, focusDiopters = 14.29f, minDiopters = 7.69f))
    }

    @Test fun too_close_counts_scans_at_the_nearest_end_as_unfocused() {
        val w = TooCloseWatch()
        assertEquals(false, w.update(now = 0, afState = 1, focusDiopters = 14.29f, minDiopters = 7.69f))
        assertEquals(false, w.update(now = 400, afState = TooCloseWatch.AF_NOT_FOCUSED_LOCKED, focusDiopters = 14.29f, minDiopters = 7.69f))
        assertEquals(true, w.update(now = 900, afState = 1, focusDiopters = 14.29f, minDiopters = 7.69f))
    }

    @Test fun too_close_to_focus_needs_the_lens_at_its_nearest_and_no_focus() {
        val min = 7.69f
        val w = TooCloseWatch()
        assertEquals(false, w.update(now = 0, afState = TooCloseWatch.AF_NOT_FOCUSED_LOCKED, focusDiopters = 7.6f, minDiopters = min))
        assertEquals(false, w.update(now = 700, afState = TooCloseWatch.AF_PASSIVE_UNFOCUSED, focusDiopters = 7.69f, minDiopters = min))
        assertEquals(true, w.update(now = 800, afState = TooCloseWatch.AF_PASSIVE_UNFOCUSED, focusDiopters = 7.69f, minDiopters = min))
        // focused there after all: off once it has been so for 1.5 s (not at once: the scan between)
        assertEquals(true, w.update(now = 1000, afState = TooCloseWatch.AF_PASSIVE_FOCUSED, focusDiopters = 7.69f, minDiopters = min))
        assertEquals(false, w.update(now = 2600, afState = TooCloseWatch.AF_PASSIVE_FOCUSED, focusDiopters = 7.69f, minDiopters = min))
        // the lens well short of its nearest: never, focused or not
        val v = TooCloseWatch()
        assertEquals(false, v.update(now = 0, afState = TooCloseWatch.AF_NOT_FOCUSED_LOCKED, focusDiopters = 5f, minDiopters = min))
        assertEquals(false, v.update(now = 2000, afState = TooCloseWatch.AF_NOT_FOCUSED_LOCKED, focusDiopters = 5f, minDiopters = min))
        // a fixed-focus camera (no minimum reported): never
        val f = TooCloseWatch()
        assertEquals(false, f.update(now = 0, afState = TooCloseWatch.AF_NOT_FOCUSED_LOCKED, focusDiopters = 0f, minDiopters = 0f))
        assertEquals(false, f.update(now = 2000, afState = TooCloseWatch.AF_NOT_FOCUSED_LOCKED, focusDiopters = 0f, minDiopters = 0f))
    }

    @Test fun too_close_says_move_back_never_move_closer() {
        // the cells read small (it would say Move closer) but the camera cannot focus this near: Move back
        val small = Reading(true, null, 2, true, 1.29, square, 0, 111, true, tooClose = true)
        assertEquals(Hints.TOO_CLOSE, Guidance.hint(small))
        // not fresh: the frames blurred (TooBlurry): Move back too
        assertEquals(Hints.TOO_CLOSE, Guidance.hint(Reading(false, "TooBlurry(2.1)", 2, true, 0.0, square, 0, 0, true, tooClose = true)))
        // reading well: nothing to say
        assertEquals(Hints.GOOD, Guidance.hint(Reading(true, null, 3, true, 2.3, square, 49, 49, true, tooClose = true)))
        // not too close: Move closer as before
        assertEquals(Hints.CLOSER, Guidance.hint(Reading(true, null, 2, true, 1.29, square, 0, 111, true)))
    }

    @Test fun locked_needs_half_a_second_of_tracking_and_a_codeword() {
        val w = LockWatch()
        assertEquals(false, w.update(fresh = true, newReads = 1, codewordsOk = 4, now = 0))
        assertEquals(false, w.update(fresh = true, newReads = 1, codewordsOk = 0, now = 400))
        assertEquals(true, w.update(fresh = true, newReads = 1, codewordsOk = 0, now = 500))
        // ten reads in a row with no codeword: still on for 1 s after the last good poll, then off
        var t = 500L
        repeat(9) { t += 33; w.update(fresh = true, newReads = 1, codewordsOk = 0, now = t) }
        assertEquals(true, w.update(fresh = true, newReads = 1, codewordsOk = 0, now = t + 33))
        // (the codeword of the first read left the last 10 at 764 ms: on until 731, held 1 s)
        assertEquals(true, w.update(fresh = true, newReads = 1, codewordsOk = 0, now = 1700))
        assertEquals(false, w.update(fresh = true, newReads = 1, codewordsOk = 0, now = 1740))
        t = 1740L
        assertEquals(true, w.update(fresh = true, newReads = 1, codewordsOk = 3, now = t + 66))
        // the tile lost: the half second starts again
        assertEquals(false, w.update(fresh = false, newReads = 0, codewordsOk = 0, now = t + 2000))
        assertEquals(false, w.update(fresh = true, newReads = 1, codewordsOk = 50, now = t + 2100))
        assertEquals(true, w.update(fresh = true, newReads = 1, codewordsOk = 50, now = t + 2600))
    }

    // the last 10 are data READS (the status's data_reads), not polls: one 200 ms poll may bring several reads, and
    // the status shows only the latest one's codewords (the reads between count as reads without a codeword)
    @Test fun locked_counts_data_reads_not_polls() {
        val w = LockWatch()
        w.update(fresh = true, newReads = 1, codewordsOk = 0, now = 0)
        assertEquals(true, w.update(fresh = true, newReads = 6, codewordsOk = 2, now = 600))     // six reads, the latest decoding
        // a poll with nothing new does not age it; nine more reads keep it among the last 10
        assertEquals(true, w.update(fresh = true, newReads = 0, codewordsOk = 2, now = 800))
        assertEquals(true, w.update(fresh = true, newReads = 9, codewordsOk = 0, now = 1000))
        // the tenth read after it: out of the last 10 (as ten polls of one read each would have it), held 1 s
        assertEquals(true, w.update(fresh = true, newReads = 1, codewordsOk = 0, now = 1200))
        assertEquals(true, w.update(fresh = true, newReads = 0, codewordsOk = 0, now = 1999))
        assertEquals(false, w.update(fresh = true, newReads = 0, codewordsOk = 0, now = 2000))
        // one poll of twelve reads, the latest decoding: on again at once (the tile tracked all along)
        assertEquals(true, w.update(fresh = true, newReads = 12, codewordsOk = 1, now = 2200))
        // a poll of eleven dry reads: out of the window in one poll (ten polls before)
        assertEquals(true, w.update(fresh = true, newReads = 11, codewordsOk = 0, now = 2400))
        assertEquals(false, w.update(fresh = true, newReads = 0, codewordsOk = 0, now = 3400))
    }

    // reads failing with the tile locked: "Reading…" (the blue line) with why, never "Hold steady" alone
    @Test fun failing_reads_while_locked_say_reading_with_the_cause() {
        val r = Reading(true, null, 2, true, 1.8, square, 4, 111, true, mix = 0.0, edgePx = 1.5, locked = true)
        assertEquals(Hints.READING, Guidance.hint(r))
        assertEquals(Hints.READING_OVERLAP, Guidance.hint(r.copy(mix = 0.54)))
        assertEquals(Hints.READING_FOCUS, Guidance.hint(r.copy(edgePx = 3.2)))
        assertEquals(Tone.GOOD, Hints.READING_OVERLAP.tone)
        // not locked: hold steady, with the cause when one shows; a blend says reading (holding steady cannot fix it)
        assertEquals(Hints.STEADY, Guidance.hint(r.copy(locked = false)))
        assertEquals(Hints.READING_OVERLAP, Guidance.hint(r.copy(locked = false, mix = 0.54)))
        // the app is moving the camera's timing itself (PhaseLock searching or stepping): the line says so, and
        // never sends the user to the sender's rate setting while it is at work
        assertEquals(Hints.READING_TIMING, Guidance.hint(r.copy(mix = 0.54, phaseFixing = true)))
        assertEquals(Hints.READING_TIMING, Guidance.hint(r.copy(locked = false, mix = 0.54, phaseFixing = true)))
        assertEquals(Tone.GOOD, Hints.READING_TIMING.tone)
        assertEquals(Hints.READING_OVERLAP, Guidance.hint(r.copy(mix = 0.15)))
        assertEquals(Hints.READING, Guidance.hint(r.copy(mix = 0.03)))
        assertEquals(Hints.STEADY_FOCUS, Guidance.hint(r.copy(locked = false, edgePx = 3.2)))
        // too small still says closer, locked or not
        assertEquals(Hints.CLOSER, Guidance.hint(r.copy(cellPx = 1.5)))
        // reading well: the good line
        assertEquals(Hints.GOOD, Guidance.hint(r.copy(codewordsOk = 111)))
    }

    // the tile tracked with not one codeword decoding (so never locked on): "Reading…" in the warning tone, never
    // "Hold steady" alone (as the browser, web/receive-logic.js readingUnlocked)
    @Test fun tracked_with_no_codeword_says_reading_unlocked() {
        val r = Reading(true, null, 2, true, 1.8, square, 0, 111, true, mix = 0.0, edgePx = 1.5, locked = false)
        assertEquals(Hints.READING_UNLOCKED, Guidance.hint(r))
        assertEquals("Reading…", Hints.READING_UNLOCKED.text)
        assertEquals(Tone.WARN, Hints.READING_UNLOCKED.tone)
        assertEquals(Hints.READING_UNLOCKED_FOCUS, Guidance.hint(r.copy(edgePx = 3.2)))
        assertEquals(Tone.WARN, Hints.READING_UNLOCKED_FOCUS.tone)
        // a blend, or cells too small, keep their lines; a codeword decoding: hold steady as before
        assertEquals(Hints.READING_OVERLAP, Guidance.hint(r.copy(mix = 0.5)))
        assertEquals(Hints.CLOSER, Guidance.hint(r.copy(cellPx = 1.5)))
        assertEquals(Hints.STEADY, Guidance.hint(r.copy(codewordsOk = 4)))
    }

    @Test fun codewords_that_decode_never_say_closer() {
        // cells far below every floor, but the codewords read: hold steady, never "Move closer"
        assertEquals(Hints.GOOD, Guidance.hint(read(cellPx = 1.0, ok = 83, all = 83)))
        assertEquals(Hints.GOOD, Guidance.hint(read(cellPx = 1.2, ok = 76, all = 83)))
    }

    @Test fun small_cells_that_lose_codewords_say_closer() {
        assertEquals(Hints.CLOSER, Guidance.hint(read(cellPx = 1.4, ok = 30, all = 83)))
        // 2 px b/w reads from 1.45: 1.7 camera px is not a reason to move
        assertEquals(Hints.STEADY, Guidance.hint(read(cellPx = 1.7, ok = 30, all = 83)))
        // the floor is per format: 2.1 camera px is enough for 2 px b/w, not for 1 px or colour
        assertEquals(Hints.STEADY, Guidance.hint(read(cellPx = 2.1, ok = 30, all = 83, pitch = 2)))
        assertEquals(Hints.CLOSER, Guidance.hint(read(cellPx = 2.1, ok = 30, all = 83, pitch = 1)))
        assertEquals(Hints.CLOSER, Guidance.hint(read(cellPx = 2.1, ok = 30, all = 83, pitch = 4, bw = false)))
        // grey alphabets (g4, g8) are held to the 2.45 floor until measured
        assertEquals(Hints.CLOSER, Guidance.hint(read(cellPx = 2.1, ok = 30, all = 83, pitch = 2, bw = false)))
    }

    @Test fun perspective_says_square_up() {
        val tilted = doubleArrayOf(1.0, 1.2, 1.0, 0.98)
        assertEquals(Hints.SQUARE, Guidance.hint(read(cellPx = 3.0, ok = 30, all = 83, edges = tilted)))
        // a mild tilt that still reads is left alone; a strong one is worth fixing even while reading
        assertEquals(Hints.GOOD, Guidance.hint(read(cellPx = 3.0, ok = 83, all = 83, edges = tilted)))
        assertEquals(Hints.SQUARE, Guidance.hint(read(cellPx = 3.0, ok = 83, all = 83, edges = doubleArrayOf(1.0, 1.4, 1.0, 1.0))))
    }

    @Test fun no_read_searches_or_holds_steady() {
        assertEquals(Hints.SEARCH, Guidance.hint(read(0.0, 0, 0, fresh = false, failure = "NoMarkers", receiving = false)))
        assertEquals(Hints.LOST, Guidance.hint(read(0.0, 0, 0, fresh = false, failure = "NoMarkers", receiving = true)))
        assertEquals(Hints.STEADY, Guidance.hint(read(0.0, 0, 0, fresh = false, failure = "TooBlurry(0.8)")))
        assertEquals(Hints.STEADY, Guidance.hint(read(0.0, 0, 0, fresh = false, failure = "NoHeader")))
    }

    @Test fun skew_is_largest_over_smallest_edge() {
        assertEquals(1.5, Guidance.skew(doubleArrayOf(1.0, 1.5, 1.2, 1.1)), 1e-9)
        assertEquals(1.0, Guidance.skew(DoubleArray(0)), 1e-9)
        assertEquals(1.0, Guidance.skew(doubleArrayOf(0.0, 1.0, 1.0, 1.0)), 1e-9)
    }

    @Test fun durations_and_sizes_read_plainly() {
        assertEquals("12.4 s", Fmt.duration(12.4))
        assertEquals("1:26", Fmt.duration(86.0))
        assertEquals("1:02:03", Fmt.duration(3723.0))
        assertEquals("1:12 left", Fmt.left(72))
        assertEquals("37.5 MB", Fmt.bytes(39_321_600))
    }
}
