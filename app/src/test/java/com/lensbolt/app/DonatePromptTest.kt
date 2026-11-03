package com.lensbolt.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DonatePromptTest {
    /** The prompt's answer for `n` transfers in a row, from `start`. */
    private fun run(n: Int, start: DonatePrompt.Count = DonatePrompt.Count()): Pair<List<Boolean>, DonatePrompt.Count> {
        var c = start
        val shown = List(n) { val (next, show) = DonatePrompt.afterTransfer(c); c = next; show }
        return shown to c
    }

    @Test fun first_four_transfers_never_ask() {
        val (shown, c) = run(4)
        assertTrue(shown.none { it })
        assertEquals(4, c.transfers)
    }

    @Test fun every_fifth_transfer_at_most() {
        val (shown, _) = run(50)
        assertEquals((1..50).map { it % 5 == 0 }, shown)
    }

    @Test fun never_two_in_a_row() {
        // even from counts as stored by an older or a restored install (the last prompt "ahead" of the count)
        for (start in listOf(DonatePrompt.Count(), DonatePrompt.Count(9, 9), DonatePrompt.Count(3, 40), DonatePrompt.Count(17, 4))) {
            val (shown, _) = run(60, start)
            for (i in 1 until shown.size) assertFalse("$start at $i", shown[i] && shown[i - 1])
            // and never two within five transfers
            val at = shown.withIndex().filter { it.value }.map { it.index }
            for (i in 1 until at.size) assertTrue("$start: $at", at[i] - at[i - 1] >= DonatePrompt.EVERY)
        }
    }

    @Test fun dont_ask_again_is_final() {
        val (shown, c) = run(40, DonatePrompt.Count(transfers = 4, never = true))
        assertTrue(shown.none { it })
        assertTrue(c.never)
        assertEquals(44, c.transfers)
    }
}
