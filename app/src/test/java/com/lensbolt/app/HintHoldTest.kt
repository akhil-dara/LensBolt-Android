package com.lensbolt.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HintHoldTest {
    @Test fun found_tile_shows_at_once() {
        // searching -> reading well: no 600 ms of "Point at the LensBolt screen" under "Locked on"
        val h = HintHold(600)
        assertEquals(Hints.GOOD, h.next(Hints.GOOD, 0))
        // searching -> a warning: also at once (the shown line is searching)
        h.reset()
        assertEquals(Hints.CLOSER, h.next(Hints.CLOSER, 0))
    }

    @Test fun lost_tile_gives_way_at_once() {
        val h = HintHold(600)
        h.next(Hints.LOST, 0)
        assertEquals(Hints.SQUARE, h.next(Hints.SQUARE, 200))
    }

    @Test fun all_well_again_shows_at_once() {
        val h = HintHold(600)
        h.next(Hints.CLOSER, 0)
        assertEquals(Hints.GOOD, h.next(Hints.GOOD, 200))
    }

    @Test fun one_warning_to_another_waits_out_the_hold() {
        val h = HintHold(600)
        h.next(Hints.CLOSER, 0)
        assertEquals(Hints.CLOSER, h.next(Hints.SQUARE, 200))
        assertEquals(Hints.CLOSER, h.next(Hints.SQUARE, 600))
        assertEquals(Hints.SQUARE, h.next(Hints.SQUARE, 800))
        // a single odd poll never flips the line, and resets the wait
        assertEquals(Hints.SQUARE, h.next(Hints.STEADY, 1000))
        assertEquals(Hints.SQUARE, h.next(Hints.SQUARE, 1200))
        assertEquals(Hints.SQUARE, h.next(Hints.STEADY, 1400))
        assertEquals(Hints.SQUARE, h.next(Hints.STEADY, 1800))
        assertEquals(Hints.STEADY, h.next(Hints.STEADY, 2000))
    }

    // a good line to a warning waits out the hold too (one odd capture never flips "Hold steady" to a warning);
    // a lost tile shows at once
    @Test fun good_to_a_warning_waits_lost_does_not() {
        val h = HintHold(600)
        h.next(Hints.GOOD, 0)
        assertEquals(Hints.GOOD, h.next(Hints.SQUARE, 100))
        assertEquals(Hints.GOOD, h.next(Hints.SQUARE, 600))
        assertEquals(Hints.SQUARE, h.next(Hints.SQUARE, 700))
        h.next(Hints.READING, 800)
        assertEquals(Hints.READING, h.next(Hints.STEADY, 900))
        assertEquals(Hints.LOST, h.next(Hints.LOST, 1000))
    }

    @Test fun reset_starts_from_searching() {
        val h = HintHold(600)
        h.next(Hints.GOOD, 0)
        h.reset()
        assertEquals(Hints.SEARCH, h.shown)
        assertEquals(Hints.SEARCH, h.next(Hints.SEARCH, 100))
    }

    @Test fun finishing_only_once_every_segment_is_verified() {
        // every block counted (received_bytes capped at the stream) but segments not all verified: not finishing
        assertFalse(Guidance.finishing(bytesDone = 32L shl 20, streamSize = 37L shl 20))
        assertTrue(Guidance.finishing(bytesDone = 37L shl 20, streamSize = 37L shl 20))
        assertFalse(Guidance.finishing(bytesDone = 0, streamSize = 0))
    }
}
