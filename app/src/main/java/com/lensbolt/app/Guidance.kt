package com.lensbolt.app

// The one guidance line under the preview, from what the decoder reports about the latest captures. Pure: no
// Android types, so the rules read (and can be tested) on their own.

import kotlin.math.abs
import kotlin.math.roundToInt

enum class Tone { NEUTRAL, GOOD, WARN }

data class Hint(val text: String, val tone: Tone)

object Hints {
    val SEARCH = Hint("Point at the LensBolt screen", Tone.NEUTRAL)
    // the tile was lost mid-transfer: the same words, as a warning (progress has stopped)
    val LOST = Hint("Point at the LensBolt screen", Tone.WARN)
    val CLOSER = Hint("Move closer: the square should fill the corners", Tone.WARN)
    val SQUARE = Hint("Square up to the screen", Tone.WARN)
    val STEADY = Hint("Hold steady", Tone.WARN)
    val GOOD = Hint("Hold steady", Tone.GOOD)
    // locked on (the tile tracked, codewords decoding now and then) while most do not: reading, and why when the
    // status shows it (two sender frames blended in each picture; the border's edges soft)
    val READING = Hint("Reading…", Tone.GOOD)
    val READING_OVERLAP = Hint("Reading… two frames overlap in each picture: a slower sender rate helps (20 or 15 fps)", Tone.GOOD)
    val READING_TIMING = Hint("Reading… finding the screen's timing", Tone.GOOD)
    val READING_FOCUS = Hint("Reading… focusing", Tone.GOOD)
    // the tile tracked, not one codeword decoding (so never locked on): reading, in the warning tone, never "Hold
    // steady" alone (the browser's readingUnlocked / readingUnlockedFocus)
    val READING_UNLOCKED = Hint("Reading…", Tone.WARN)
    val READING_UNLOCKED_FOCUS = Hint("Reading… focusing", Tone.WARN)
    val STEADY_FOCUS = Hint("Hold steady: focusing", Tone.WARN)
    // the camera cannot focus this near ([TooCloseWatch]): never "Move closer", whatever the cells measure
    val TOO_CLOSE = Hint("Too close to focus: move back a little", Tone.WARN)
}

/** Whether the phone is nearer than its camera can focus: the lens at its nearest setting (within [NEAR] of the
 *  camera's minimum focus distance, in diopters) and the autofocus reporting not focused, for [ON_MS]; off once
 *  that has not held for [OFF_MS]. Measured 2026-11-03: a phone's main camera (the default back camera, a
 *  logical one over the ultrawide and main) focuses no nearer than 13 cm (7.69 D), so a phone sender held at
 *  8 cm never sharpened, the tap ring went amber, and the guidance said "Move closer" (the cells read small).
 *  A sharp picture at the nearest setting, or a camera that reports no minimum (fixed focus), never counts; one
 *  whole read while the lens reports its nearest end silences it until [reset] (that camera's report is wrong). */
class TooCloseWatch {
    companion object {
        const val NEAR = 0.92f; const val ON_MS = 800L; const val OFF_MS = 1500L
        // CaptureResult.CONTROL_AF_STATE values (kept here so the rule is tested on the JVM)
        const val AF_PASSIVE_FOCUSED = 2; const val AF_FOCUSED_LOCKED = 4; const val AF_NOT_FOCUSED_LOCKED = 5; const val AF_PASSIVE_UNFOCUSED = 6
    }
    private var since = -1L
    private var clearSince = -1L
    var on = false
        private set
    // a whole read while the lens reported its nearest end: this camera's report cannot be trusted (until reset)
    private var distrusted = false
    /** One status poll; `readsWell`: the tile read whole on this poll. */
    fun update(now: Long, afState: Int, focusDiopters: Float, minDiopters: Float, readsWell: Boolean = false): Boolean {
        val near = minDiopters > 0f && focusDiopters >= minDiopters * NEAR
        // a phone camera reports its lens at the nearest end, not focused, while the tile reads 49 of 49 (2026-11-03)
        if (near && readsWell) distrusted = true
        if (distrusted) { since = -1L; clearSince = -1L; on = false; return false }
        // not focused: any known state but focused; a scan there counts (it keeps coming back to no focus)
        val unfocused = afState >= 0 && afState != AF_PASSIVE_FOCUSED && afState != AF_FOCUSED_LOCKED
        if (near && unfocused) {
            clearSince = -1L
            if (since < 0) since = now
            if (now - since >= ON_MS) on = true
        } else {
            since = -1L
            if (on) { if (clearSince < 0) clearSince = now; if (now - clearSince >= OFF_MS) { on = false; clearSince = -1L } }
        }
        return on
    }
    fun reset() { since = -1L; clearSince = -1L; on = false; distrusted = false }
}

/** What the decoder said lately (one UI poll). */
data class Reading(
    /** a tile was read (or its colour header seen) within the last [Guidance.FRESH_MS] */
    val fresh: Boolean,
    /** the decoder's last error, when a capture failed within the same window */
    val failure: String?,
    /** the last data read's cell pitch (screen px per cell) and whether its alphabet is black and white */
    val pitch: Int, val bw: Boolean,
    /** camera px per cell of the last read, and camera px per screen px along its four edges */
    val cellPx: Double, val edges: DoubleArray,
    /** codewords of the last stitched frame: corrected / all */
    val codewordsOk: Long, val codewords: Long,
    /** the manifest is in (a lost tile then stalls a transfer) */
    val receiving: Boolean,
    /** the last data read: the share of the tile two sender frames blend in (status `mix`), the border's edge
     *  width in camera px (`edge_px`; 0 unknown), and whether the tile is locked on ([LockWatch]) */
    val mix: Double = 0.0, val edgePx: Double = 0.0, val locked: Boolean = false,
    /** the app is moving the camera's timing itself (PhaseLock searching, stepping or measuring, the rates
     *  matching): a blend is its job, not the user's */
    val phaseFixing: Boolean = false,
    /** the phone is nearer than the camera can focus ([TooCloseWatch]) */
    val tooClose: Boolean = false,
)

/** "Locked on" (the blue line): the tile read without a gap (every poll fresh) for [trackMs], and at least one
 *  codeword decoded among the last [LAST_READS] data reads; once on, it stays on [HOLD_MS] after the codewords
 *  stop (a dry spell of a few captures never blinks it), and goes at once when the tile is lost. A transfer whose
 *  reads mostly fail but still decode now and then is being read, not lost (the 2026-11-02 run: 0-4 of 111 per
 *  capture, "Hold steady" for 90 s). Reads, not polls: a 200 ms poll may bring several data reads (the status's
 *  `data_reads` delta), of which the status shows the latest one's codewords; the others count as reads without
 *  one. The browser receiver has the same rule (web/receive-logic.js `LockWatch`, one status per read). */
class LockWatch(private val trackMs: Long = TRACK_MS) {
    companion object { const val TRACK_MS = 500L; const val LAST_READS = 10; const val HOLD_MS = 1000L }
    private var since = -1L
    private var onAt = -1L
    // data reads since the latest one seen with a codeword decoded (0: the latest read); -1 none seen yet
    private var sinceCodeword = -1L

    fun reset() { since = -1L; onAt = -1L; sinceCodeword = -1L }

    /** One poll: [fresh] a tile read lately; [newReads] the data reads since the last poll (the status's
     *  `data_reads` delta), the latest of them with [codewordsOk]. */
    fun update(fresh: Boolean, newReads: Long, codewordsOk: Long, now: Long): Boolean {
        if (!fresh) { reset(); return false }
        if (since < 0) since = now
        if (newReads > 0) sinceCodeword = when { codewordsOk > 0 -> 0L; sinceCodeword < 0 -> -1L; else -> sinceCodeword + newReads }
        val on = now - since >= trackMs && sinceCodeword in 0 until LAST_READS
        if (on) onAt = now
        return on || (onAt >= 0 && now - onAt < HOLD_MS)
    }
}

/** Which guidance line shows. A new warning waits out `holdMs` after a warning or a good line (one odd capture
 *  never flips "Move closer" to "Square up", nor "Hold steady" to a warning); it shows at once when the tile is
 *  found (the shown line is searching or lost), when the tile is lost, and when all is well again (the new line
 *  is a good one). */
class HintHold(private val holdMs: Long) {
    var shown: Hint = Hints.SEARCH
        private set
    private var cand: Hint? = null
    private var since = 0L

    fun reset() { shown = Hints.SEARCH; cand = null }

    fun next(h: Hint, now: Long): Hint {
        if (h == shown) { cand = null; return shown }
        // a warning after a warning or after a good line waits out the hold (one odd capture never flips the line);
        // lost and searching show at once
        val hold = shown.tone != Tone.NEUTRAL && shown != Hints.LOST && h.tone == Tone.WARN && h != Hints.LOST
        if (!hold) { shown = h; cand = null; return shown }
        if (h != cand) { cand = h; since = now; return shown }
        if (now - since >= holdMs) { shown = h; cand = null }
        return shown
    }
}

object Guidance {
    const val FRESH_MS = 1200L

    /** "Finishing…" only once every segment is verified (`bytes_done` reaches the stream): the received-block
     *  count reaches the stream size while the fountain may still need frames. */
    fun finishing(bytesDone: Long, streamSize: Long): Boolean = streamSize > 0 && bytesDone >= streamSize
    // codewords this share or better: the tile reads, so the user is never told to move
    const val READS_WELL = 0.9
    // edge-to-edge sampling spread that costs reads, and one that is worth fixing even while reading
    private const val SKEW_HURTS = 1.15
    private const val SKEW_STRONG = 1.3
    // the blend that costs reads: two sender frames share the light over this much of the tile (the 2026-11-02
    // captures that read 0-4 of 111: 0.49-0.59; every clean capture measured: 0.00-0.03). One line for the
    // "two frames overlap" text and the camera's phase steps (PhaseLock.MIX_HIGH)
    const val MIX_SHOWS = 0.15
    // border edges this wide (10-90%, camera px) are out of focus or smeared: 1.48-1.53 on every phone capture
    // measured so far, sharp or blended (docs/2026-11-02-soft-frames.md); a 5 px box blur adds more than 1.5
    const val EDGE_SOFT = 2.5

    /** Camera px per cell at which a format starts to read: 1 px b/w from ~2.45 (the 1 px verdict on real
     *  captures), 2 px b/w and coarser from 1.45 (the reader's local equaliser, docs/2026-10-31-fhd-2px-floor.md:
     *  real 1.76 px captures degraded to 1.3 px read 98.2% of codewords cold, 1.4 px 100%, and 98.6% at 1.5 px
     *  with a little soft focus; larger cells read at least as well); grey (g4, g8) and colour alphabets are held
     *  to the higher figure until measured. "Move closer" also needs the codewords to be failing (see [hint]). */
    fun floor(pitch: Int, bw: Boolean): Double = if (!bw || pitch <= 1) 2.45 else 1.45

    /** Largest over smallest edge sampling: 1.0 is square-on. */
    fun skew(edges: DoubleArray): Double {
        val lo = edges.minOrNull() ?: return 1.0
        val hi = edges.maxOrNull() ?: return 1.0
        return if (lo > 0) hi / lo else 1.0
    }

    /** Edges wider than this many cells, on cells of at least [SOFT_BIG] times the format's floor, are the
     *  picture of a phone nearer than its lens focuses: measured on a phone at 4096x3072 reading a
     *  phone sender's 2 px tile (2026-11-04), 4.1 px cells with 5.8-7.9 px edges read 0-102 of 111 at 8 cm, and 2.8 px
     *  cells with 3.5 px edges read 111 of 111 at 15 cm. Seen in the picture, so it holds on every phone (the
     *  lens's own report is wrong on some: [TooCloseWatch]). */
    const val SOFT_RATIO = 1.4
    const val SOFT_BIG = 2.0
    /** A data read losing codewords that only a blend of two sender frames explains: under half of its frame's
     *  codewords while the border is sharp and the cells are at least the format's floor (soft edges or small cells
     *  are focus or distance, not timing). The phase step then treats it as a full blend: a blend the measure
     *  under-reports (a slow LCD panel) read 31-36 of 111 for 5 s at a measured blend of 0.0-0.1, and the step
     *  waited until reads fell under a fifth; one step brought 111 back (a session log, 2026-11-04). */
    fun blendSuspected(ok: Long, all: Long, cellPx: Double, edgePx: Double, pitch: Int, bw: Boolean): Boolean =
        all > 0 && ok * 2 < all && cellPx >= floor(pitch, bw) && edgePx < EDGE_SOFT

    fun softAndBig(r: Reading): Boolean =
        r.edgePx > 0 && r.cellPx >= floor(r.pitch, r.bw) * SOFT_BIG && r.edgePx > SOFT_RATIO * r.cellPx

    fun hint(r: Reading): Hint {
        if (r.fresh) {
            val reads = r.codewords <= 0 || r.codewordsOk >= r.codewords * READS_WELL
            val sk = skew(r.edges)
            if (sk > SKEW_STRONG) return Hints.SQUARE
            if (!reads) {
                // nearer than the camera focuses: moving closer would only make it worse
                if (r.tooClose || softAndBig(r)) return Hints.TOO_CLOSE
                // the tile is found but loses codewords: too small for its format, at an angle, or moving
                if (r.cellPx > 0 && r.cellPx < floor(r.pitch, r.bw) * 1.1) return Hints.CLOSER
                if (sk > SKEW_HURTS) return Hints.SQUARE
                // two frames blended: holding steady cannot fix it (the camera's phase can): reading, locked or not
                if (r.mix >= MIX_SHOWS) return if (r.phaseFixing) Hints.READING_TIMING else Hints.READING_OVERLAP
                val soft = r.edgePx > EDGE_SOFT
                if (r.locked) return if (soft) Hints.READING_FOCUS else Hints.READING
                if (r.codewordsOk <= 0) return if (soft) Hints.READING_UNLOCKED_FOCUS else Hints.READING_UNLOCKED
                return if (soft) Hints.STEADY_FOCUS else Hints.STEADY
            }
            return Hints.GOOD
        }
        val f = r.failure
        // the markers were found but the frame did not read: blur, or the header lost in motion (blur from being
        // nearer than the camera focuses: say so)
        if (f != null && (f.startsWith("TooBlurry") || f.startsWith("NoHeader"))) return if (r.tooClose) Hints.TOO_CLOSE else Hints.STEADY
        return if (r.receiving) Hints.LOST else Hints.SEARCH
    }
}

/** The other-version banner: the decoder read the sender's header whole and it names another format version
 *  (`OtherVersion(v)`: the two ends are different LensBolt versions and nothing will ever decode). Up once such
 *  reports have gone on for [HOLD_MS] with no tile of this version read; other failures in between (a blurred
 *  capture, a missed header) neither start nor end the run; a read ends it, and so does no report for
 *  [Guidance.FRESH_MS] (the camera turned away). The browser receiver has the same rule (web/receive-logic.js). */
class VersionWatch(private val holdMs: Long = HOLD_MS) {
    private var firstAt = -1L
    private var lastAt = -1L

    fun reset() { firstAt = -1L; lastAt = -1L }

    /** One poll: the decoder's last failure (when one is fresh) and whether a tile read lately. True: show it. */
    fun update(failure: String?, fresh: Boolean, now: Long): Boolean {
        if (fresh) { reset(); return false }
        if (failure != null && failure.startsWith("OtherVersion")) {
            if (firstAt < 0) firstAt = now
            lastAt = now
        } else if (lastAt >= 0 && now - lastAt >= Guidance.FRESH_MS) reset()
        return firstAt >= 0 && now - firstAt >= holdMs
    }

    companion object {
        const val HOLD_MS = 1000L
        const val TEXT = "The sender is a different LensBolt version. Update both to the same version."
    }
}

/** The sender-rate banner: "The sender shows 60 fps; this camera takes 30. Set the sender to 30 fps ...". A
 *  camera within 10% of a standard rate counts as that rate (29.7 fps is a 30 fps camera); the suggestion is the
 *  fastest rate the sender offers that the camera keeps up with. */
object SenderFps {
    // the rates the sender page offers (calibration words can pick others, but only in the lab), as the browser
    // receiver's SENDER_FPS (web/receive-logic.js): never a rate the sender cannot be set to
    val OPTIONS = intArrayOf(15, 30, 60)
    private const val NEAR = 0.10

    /** The camera's rate as a standard one when it is within 10% of it, else as measured. */
    fun effective(cam: Double): Double = OPTIONS.firstOrNull { kotlin.math.abs(cam - it) <= NEAR * it }?.toDouble() ?: cam

    fun suggestion(cam: Double): Int { val e = effective(cam); return OPTIONS.filter { it <= e + 1e-9 }.maxOrNull() ?: OPTIONS.min() }

    /** Advice, not an order: the rate to set (only one below the sender's), or keep going and about how much
     *  longer that takes (the camera reads about cam / sender of the frames shown). As web/receive-logic.js
     *  fpsBannerText. */
    fun banner(sender: Double, cam: Double): String {
        val e = effective(cam); val s = suggestion(cam)
        val head = "The sender shows ${sender.roundToInt()} fps; this camera reads ${e.roundToInt()}."
        val rest = "keep going: it will take about ${longer(sender / e)} as long."
        return if (s < effective(sender) - 1e-9) "$head Set the sender to $s fps or $rest" else "$head ${rest.replaceFirstChar { it.uppercase() }}"
    }

    /** "twice", "4 times", "1.3 times". */
    fun longer(f: Double): String = when {
        f >= 2.5 -> "${f.roundToInt()} times"
        f >= 1.75 -> "twice"
        else -> "${(f * 10).roundToInt() / 10.0} times"
    }
}

/** When the sender-rate banner is up: the sender's rate over 1.2x the camera's for [HOLD_MS] in a row, the camera
 *  taken at its best over the last [PEAK_MS] (a heavy colour decode that slows it for a moment is not its rate). */
class SenderFpsWatch(private val holdMs: Long = HOLD_MS, private val peakMs: Long = PEAK_MS) {
    companion object { const val HOLD_MS = 3000L; const val PEAK_MS = 3000L; const val FASTER = 1.2 }
    private val cams = ArrayDeque<Pair<Long, Double>>()
    private var since = -1L

    /** The camera's rate the banner names (standard when near one). */
    var camRate = 0.0
        private set

    fun reset() { cams.clear(); since = -1L; camRate = 0.0 }

    /** One poll: the sender's and camera's measured rates. The rate to suggest while the banner is up, else null. */
    fun update(sender: Double, cam: Double, now: Long): Int? {
        if (cam > 0) cams.addLast(now to cam)
        while (cams.isNotEmpty() && cams.first().first <= now - peakMs) cams.removeFirst()
        val peak = cams.maxOfOrNull { it.second } ?: 0.0
        camRate = if (peak > 0) SenderFps.effective(peak) else 0.0
        val faster = sender > 0 && camRate > 0 && sender > FASTER * camRate
        if (!faster) { since = -1L; return null }
        if (since < 0) since = now
        return if (now - since >= holdMs) SenderFps.suggestion(camRate) else null
    }
}

/** A value that reports only its changes (the window's keep-screen-on flag is set on transitions only). */
class Changes<T> {
    private var last: Any? = UNSET
    fun changed(v: T): Boolean { if (last == v) return false; last = v; return true }
    private companion object { val UNSET = Any() }
}
