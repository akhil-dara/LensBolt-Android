package com.lensbolt.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// "The sender is a different LensBolt version": the decoder reads the sender's header whole and it names another
// format version (`OtherVersion(8)`). Said once that has gone on for about a second with no tile read.
class VersionWatchTest {
    private val v8 = "OtherVersion(8)"

    @Test fun wordsTellTheUserWhatToDo() {
        assertEquals("The sender is a different LensBolt version. Update both to the same version.", VersionWatch.TEXT)
    }

    @Test fun showsAfterASecondOfOtherVersionReports() {
        val w = VersionWatch()
        for (t in 0L until 1000L step 100) assertFalse("at $t ms", w.update(v8, fresh = false, now = 1_000 + t))
        assertTrue(w.update(v8, fresh = false, now = 2_000))
        assertTrue(w.update(v8, fresh = false, now = 2_500))
    }

    @Test fun otherFailuresNeitherStartNorEndIt() {
        val w = VersionWatch()
        assertFalse(w.update("NoMarkers", fresh = false, now = 0))
        assertFalse(w.update("NoHeader", fresh = false, now = 2_000))
        assertFalse(w.update(v8, fresh = false, now = 3_000))
        assertFalse(w.update("TooBlurry(1.2)", fresh = false, now = 3_500))   // a blurred capture in the run
        assertTrue(w.update(v8, fresh = false, now = 4_000))
        assertTrue(w.update(null, fresh = false, now = 4_500))
    }

    @Test fun aTileReadOrQuietEndsIt() {
        val w = VersionWatch()
        w.update(v8, fresh = false, now = 0); assertTrue(w.update(v8, fresh = false, now = 1_000))
        // this version's tile read: the sender is the same version after all (the user updated it)
        assertFalse(w.update(v8, fresh = true, now = 1_100))
        assertFalse(w.update(v8, fresh = false, now = 1_500))
        assertTrue(w.update(v8, fresh = false, now = 2_500))
        // no report of another version for longer than a read stays fresh: the camera moved away, start again
        assertFalse(w.update(null, fresh = false, now = 2_500 + Guidance.FRESH_MS))
        assertFalse(w.update(v8, fresh = false, now = 4_000))
        assertTrue(w.update(v8, fresh = false, now = 5_000))
        w.reset()
        assertFalse(w.update(v8, fresh = false, now = 5_100))
    }

    @Test fun guidanceDoesNotSayHoldSteadyForAnotherVersion() {
        // holding steady cannot help: the line stays on finding the screen while the banner says why
        val r = Reading(false, v8, 2, true, 0.0, DoubleArray(0), 0, 0, false)
        assertEquals(Hints.SEARCH, Guidance.hint(r))
    }
}
