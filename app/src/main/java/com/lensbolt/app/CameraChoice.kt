package com.lensbolt.app

// Auto camera: which stream the session reads. Pure: no Android types, tested on the JVM (CameraChoiceTest).

/** The camera stream: the Full HD class (1440x1080 on the phones measured) or the largest 4:3 YUV stream. */
enum class Tier { FULL_HD, MAX }

/** One data read (the 12 px manifest is not one): its cell pitch and alphabet, camera px per cell, and the
 *  codewords of its frame (corrected / all). */
data class TileRead(val pitch: Int, val bw: Boolean, val cellPx: Double, val codewordsOk: Long, val codewords: Long)

/** Auto is Full HD at 30 fps for every format but 1 px: a 1 px tile (which cannot read at 1440x1080) takes the
 *  largest stream, after two 1 px reads in a row; a 2 px or coarser read brings Full HD back the same way. Cells
 *  that read small never move a 2 or 3 px tile to the large stream: measured on the phone, 4096x3072 decodes at
 *  200-580 ms a capture (30 ms at Full HD), drops a quarter of the captures and transfers slower than Full HD
 *  held close; "Move closer" is the answer there. Max stays a manual choice ([streamFor]). */
class CameraChoice {
    companion object {
        private const val VOTES = 2
        private const val MAX_PITCH = 11        // 12 px is the manifest

        /** The stream a camera mode reads: a manual choice always wins over Auto's. */
        fun streamFor(mode: CameraMode, auto: Tier): Tier = when (mode) {
            CameraMode.FULL_HD -> Tier.FULL_HD
            CameraMode.MAX -> Tier.MAX
            CameraMode.AUTO -> auto
        }
    }

    var tier = Tier.FULL_HD
        private set
    private var vote: Tier? = null
    private var votes = 0

    /** A new session: Full HD. */
    fun reset() { tier = Tier.FULL_HD; clear() }

    /** Forget a vote in progress (the user switched back to Auto). */
    fun clear() { vote = null; votes = 0 }

    /** One data read at `now` (ms). True when [tier] changed: the camera rebinds. */
    @Suppress("UNUSED_PARAMETER")
    fun observe(r: TileRead, now: Long): Boolean {
        if (r.pitch !in 1..MAX_PITCH) return false
        val want = if (r.pitch == 1) Tier.MAX else Tier.FULL_HD
        if (want == tier) { clear(); return false }
        if (want == vote) votes++ else { vote = want; votes = 1 }
        if (votes < VOTES) return false
        tier = want; clear()
        return true
    }
}

/** "Changing camera…" ends on the first frame of the new stream. Each binding numbers its analyzer's frames
 *  ([nextBind], captured in the analyzer lambda); a late frame of the stream being replaced (it can still arrive
 *  while the camera reopens) never ends it. [frame] runs on the camera thread, the rest on the UI thread. */
class StreamChange {
    private val seq = java.util.concurrent.atomic.AtomicInteger()
    private val awaited = java.util.concurrent.atomic.AtomicInteger(-1)

    fun nextBind(): Int = seq.incrementAndGet()
    /** The stream of binding `bind` is coming: its first frame ends the change. */
    fun await(bind: Int) = awaited.set(bind)
    fun cancel() = awaited.set(-1)
    /** A binding's first frame is still awaited. */
    fun waiting() = awaited.get() != -1
    /** A frame of binding `bind`: true once, for the first frame of the awaited binding. */
    fun frame(bind: Int): Boolean = awaited.get() == bind && awaited.compareAndSet(bind, -1)
}
