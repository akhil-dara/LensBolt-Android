package com.lensbolt.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The multi-file UX pass: the batch screen's states as the browser has them (waiting for the list, receiving, done
// with every file verified, done with some missing or failed), Save all's progress, the dialogs' safe choice, the
// SHA-256 laid out as both Done screens show it, and the history's hash behind its menu.
class UxPassTest {
    private val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test fun the_batch_screen_states() {
        assertEquals(BatchState.WAITING_LIST, BatchWords.stateOf(done = false, total = 0, received = 0, failed = 0))
        assertEquals(BatchState.RECEIVING, BatchWords.stateOf(done = false, total = 40, received = 12, failed = 1))
        assertEquals(BatchState.DONE_ALL, BatchWords.stateOf(done = true, total = 40, received = 40, failed = 0))
        assertEquals(BatchState.DONE_PARTIAL, BatchWords.stateOf(done = true, total = 40, received = 38, failed = 2))
        assertEquals(BatchState.DONE_PARTIAL, BatchWords.stateOf(done = true, total = 40, received = 39, failed = 0))   // one missing
        // the Done screen's line under the summary: verified for all, said plainly otherwise (amber on screen)
        assertEquals("SHA-256 verified, each file", BatchWords.verifiedLine(BatchState.DONE_ALL))
        assertEquals("Each file received is SHA-256 verified; the rest did not arrive", BatchWords.verifiedLine(BatchState.DONE_PARTIAL))
    }

    @Test fun save_all_progress_in_numbers_and_a_bar() {
        assertEquals("Saving 12 of 40 · photo.jpg", BatchWords.saving(done = 11, run = 40, name = "photo.jpg"))
        assertEquals("Saving 1 of 1 · a.bin", BatchWords.saving(0, 1, "a.bin"))
        assertEquals("Saving 1,201 of 10,000 · x", BatchWords.saving(1200, 10_000, "x"))
        assertEquals((11 + 0.4f) / 40, BatchWords.saveFraction(done = 11, run = 40, pct = 40), 1e-6f)
        assertEquals(1f, BatchWords.saveFraction(40, 40, 100), 1e-6f)
        assertEquals(0f, BatchWords.saveFraction(0, 0, 0), 1e-6f)
    }

    @Test fun dialogs_put_the_safe_choice_last() {
        val all = listOf(Asks.startOver(), Asks.clearHistory(), Asks.notSaved("photo.jpg"),
            Asks.batch(BatchAsk.leaving(40, 3, 0, newTransfer = true)!!), Asks.batch(BatchAsk.leaving(40, 3, 37, newTransfer = false)!!))
        for (c in all) {
            assertNotEquals(c.safe, c.risky)
            assertFalse(c.safe, c.safe.contains("Discard") || c.safe.contains("Clear") || c.safe.contains("Start over"))
        }
        assertEquals(Confirm("Start over?", "What has been received so far is discarded.", risky = "Start over", safe = "Keep receiving"), Asks.startOver())
        assertEquals("Keep", Asks.clearHistory().safe); assertEquals("Clear", Asks.clearHistory().risky)
        assertEquals("Save", Asks.notSaved("photo.jpg").safe); assertEquals("Discard", Asks.notSaved("photo.jpg").risky)
        assertEquals("Save all", Asks.batch(BatchAsk.leaving(40, 3, 0, newTransfer = true)!!).safe)
        assertEquals("Keep saving", Asks.batch(BatchAsk.leaving(40, 3, 37, newTransfer = false)!!).safe)
    }

    @Test fun sha_lines_of_eight_or_four_never_a_group_split() {
        assertEquals(ShaText.groups(abc), ShaText.lines(abc, 8))
        assertEquals("ba78 16bf 8f01 cfea\n4141 40de 5dae 2223\nb003 61a3 9617 7a9c\nb410 ff61 f200 15ad", ShaText.lines(abc, 4))
        // the width a line of eight needs decides (as the browser's quarters wrap)
        assertEquals(8, ShaText.perLine(available = 400f) { it.length * 8f })    // 39 chars: 312
        assertEquals(4, ShaText.perLine(available = 300f) { it.length * 8f })
    }

    @Test fun history_hash_behind_its_menu() {
        val h = HistoryItem(name = "a.jpg", size = 9, date = 0, savedTo = "Download", uri = "content://a", batch = null, sha = abc, id = "x")
        assertEquals(listOf(RowAction.OPEN, RowAction.SHARE, RowAction.SHOW_SHA, RowAction.COPY_SHA), HistoryWords.actions(h))
        assertEquals(listOf(RowAction.OPEN, RowAction.SHARE, RowAction.HIDE_SHA, RowAction.COPY_SHA), HistoryWords.actions(h, shown = true))
        assertTrue(HistoryWords.actions(h.copy(sha = null)).none { it == RowAction.SHOW_SHA || it == RowAction.COPY_SHA })
    }
}
