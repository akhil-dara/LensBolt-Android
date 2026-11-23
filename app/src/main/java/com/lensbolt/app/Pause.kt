package com.lensbolt.app

/** Pause: the camera stops and the session keeps what it has received. */
object Pause {
    const val TITLE = "Paused"
    const val BODY = "Progress is kept while LensBolt stays open. Leave the sender running, then press Resume and point the phone at it again."
    const val PAUSE = "Pause"
    const val RESUME = "Resume"

    /** Offered while a file arrives, not while it is being put together. */
    fun offered(finishing: Boolean) = !finishing

    fun spoken(permille: Int) = "Paused at ${permille / 10} percent"

    /** The receiving screen to go back to: as it was, asking for the sender until the camera reads again. */
    fun resumed(r: UiState.Receiving) = r.copy(hint = Hints.LOST)

    /** Seconds of the transfer without its pauses. */
    fun active(seconds: Double, pausedMs: Long) = (seconds - pausedMs / 1000.0).coerceAtLeast(0.0)

    /** The rate over the time spent reading. */
    fun rate(mbit: Double, seconds: Double, pausedMs: Long): Double {
        val a = active(seconds, pausedMs)
        return if (a > 0) mbit * seconds / a else mbit
    }
}

/** Total time paused in one transfer. */
class PauseClock {
    private var since = -1L
    private var total = 0L

    val paused get() = since >= 0

    fun pause(now: Long) { if (since < 0) since = now }

    fun resume(now: Long) { if (since >= 0) { total += now - since; since = -1 } }

    fun pausedMs(now: Long) = total + if (since >= 0) now - since else 0

    fun reset() { since = -1; total = 0 }
}
