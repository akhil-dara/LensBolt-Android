package com.lensbolt.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraChoiceTest {
    private fun r(cell: Double, ok: Long, all: Long = 111, pitch: Int = 2, bw: Boolean = true) = TileRead(pitch, bw, cell, ok, all)

    /** Feeds `read` every `stepMs` from `from` until `to` (inclusive); the times at which the stream changed. */
    private fun CameraChoice.feed(read: TileRead, from: Long, to: Long, stepMs: Long = 200): List<Long> {
        val at = ArrayList<Long>()
        var t = from
        while (t <= to) { if (observe(read, t)) at += t; t += stepMs }
        return at
    }

    @Test fun starts_at_full_hd() {
        assertEquals(Tier.FULL_HD, CameraChoice().tier)
    }

    // the 10-30 phone session: Auto moved a 2 px tile read at 1.67 px to 4096x3072, where it decoded at
    // 200-580 ms and transferred slower than Full HD; 2 and 3 px tiles now stay at Full HD however they read
    @Test fun two_and_three_px_tiles_never_move_up() {
        for (pitch in listOf(2, 3)) for (bw in listOf(true, false)) {
            val c = CameraChoice()
            assertTrue(c.feed(r(1.2, 0, pitch = pitch, bw = bw), 0, 60_000).isEmpty())
            assertTrue(c.feed(r(1.67, 51, pitch = pitch, bw = bw), 60_200, 120_000).isEmpty())
            assertEquals(Tier.FULL_HD, c.tier)
        }
    }

    @Test fun a_1px_tile_takes_the_large_stream_after_two_reads() {
        val c = CameraChoice()
        assertFalse(c.observe(r(1.0, 0, 444, pitch = 1), 0))
        assertTrue(c.observe(r(1.0, 0, 444, pitch = 1), 200))
        assertEquals(Tier.MAX, c.tier)
        // and a coarser tile brings Full HD back, also after two reads
        assertFalse(c.observe(r(4.0, 111, pitch = 2), 400))
        assertTrue(c.observe(r(4.0, 111, pitch = 2), 600))
        assertEquals(Tier.FULL_HD, c.tier)
    }

    @Test fun one_odd_read_changes_nothing() {
        val c = CameraChoice()
        c.observe(r(1.0, 0, pitch = 1), 0)
        c.observe(r(1.8, 111, pitch = 2), 200)
        c.observe(r(1.0, 0, pitch = 1), 400)
        assertEquals(Tier.FULL_HD, c.tier)
    }

    @Test fun the_manifest_and_unknown_pitches_are_ignored() {
        val c = CameraChoice()
        assertTrue(c.feed(r(29.0, 0, pitch = 12, bw = false), 0, 3000).isEmpty())
        assertTrue(c.feed(r(1.0, 0, pitch = 0), 3200, 6000).isEmpty())
        assertEquals(Tier.FULL_HD, c.tier)
    }

    @Test fun manual_choices_win() {
        assertEquals(Tier.MAX, CameraChoice.streamFor(CameraMode.MAX, Tier.FULL_HD))
        assertEquals(Tier.FULL_HD, CameraChoice.streamFor(CameraMode.FULL_HD, Tier.MAX))
        assertEquals(Tier.MAX, CameraChoice.streamFor(CameraMode.AUTO, Tier.MAX))
        assertEquals(Tier.FULL_HD, CameraChoice.streamFor(CameraMode.AUTO, Tier.FULL_HD))
    }

    @Test fun reset_returns_to_full_hd() {
        val c = CameraChoice()
        c.feed(r(1.0, 0, pitch = 1), 0, 400)
        assertEquals(Tier.MAX, c.tier)
        c.reset()
        assertEquals(Tier.FULL_HD, c.tier)
    }

    // the 2 px b/w floor measured on degraded real captures (docs/2026-10-31-fhd-2px-floor.md), with margin; 3 px
    // and coarser b/w use the same floor (larger cells read at least as well)
    @Test fun two_px_bw_floor_is_1_45() {
        assertEquals(1.45, Guidance.floor(2, true), 1e-9)
        assertEquals(1.45, Guidance.floor(3, true), 1e-9)
        assertEquals(1.45, Guidance.floor(4, true), 1e-9)
        assertEquals(2.45, Guidance.floor(1, true), 1e-9)
        assertEquals(2.45, Guidance.floor(2, false), 1e-9)
        // 1.6 px reads well enough at 2 px b/w: no "Move closer" while codewords fail; 1.5 px is within 10% of the floor
        assertEquals(Hints.STEADY, Guidance.hint(Reading(true, null, 2, true, 1.6, doubleArrayOf(1.0, 1.0, 1.0, 1.0), 30, 111, true)))
        assertEquals(Hints.CLOSER, Guidance.hint(Reading(true, null, 2, true, 1.5, doubleArrayOf(1.0, 1.0, 1.0, 1.0), 30, 111, true)))
    }

    // M1: "Changing camera…" ends on the first frame of the new stream, never on a late frame of the old one
    @Test fun changing_camera_ends_on_the_new_streams_frame_only() {
        val c = StreamChange()
        val old = c.nextBind()
        val new = c.nextBind()
        c.await(new)
        assertFalse(c.frame(old))
        assertTrue(c.frame(new))
        assertFalse("once", c.frame(new))
        c.await(c.nextBind()); c.cancel()
        assertFalse(c.frame(new))
    }
}
