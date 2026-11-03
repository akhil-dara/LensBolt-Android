package com.lensbolt.app

// When the Done screen asks for support: at most every fifth successful transfer, never two in a row, never
// again after "Don't ask again". Pure (tested on the JVM); MainActivity keeps the counts in its preferences.

object DonatePrompt {
    const val EVERY = 5
    const val COFFEE = "https://buymeacoffee.com/akhildara1l"
    const val SPONSORS = "https://github.com/sponsors/akhil-dara"
    /// The app's source (shown in every build: a plain link, not a request for money).
    const val SOURCE = "https://github.com/akhil-dara/lensbolt-android"
    const val TEXT = "LensBolt is free. If it helped, you can support it."

    /** `transfers`: successful transfers so far; `lastShown`: the transfer the prompt last showed on (0: never). */
    data class Count(val transfers: Int = 0, val lastShown: Int = 0, val never: Boolean = false)

    /** One more successful transfer: the new counts, and whether its Done screen shows the prompt. */
    fun afterTransfer(c: Count): Pair<Count, Boolean> {
        val n = c.transfers + 1
        // a last prompt "ahead" of the count (stored by a reinstall or a restore) counts as the last transfer
        val last = minOf(c.lastShown, c.transfers)
        val show = !c.never && n - last >= EVERY
        return Count(n, if (show) n else last, c.never) to show
    }
}
