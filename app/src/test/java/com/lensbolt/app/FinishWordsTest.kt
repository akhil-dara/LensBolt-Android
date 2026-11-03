package com.lensbolt.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Finish words on the receiving screen: shown while the status carries them and the file is not complete; said by
// TalkBack once when they appear and when they change, never on every poll.
class FinishWordsTest {
    @Test fun sameSentenceAsTheBrowserReceiver() {
        assertEquals("Type these on the sender to finish faster", FinishWords.TEXT)
        assertEquals("Type these on the sender to finish faster: closet lullaby", FinishWords.spoken("closet lullaby"))
    }

    @Test fun shownOnlyWhileTheStatusCarriesThemAndTheFileIsNotComplete() {
        assertEquals("closet lullaby", FinishWords.of("closet lullaby", complete = false))
        assertEquals("closet lullaby", FinishWords.of(" closet lullaby ", complete = false))
        assertNull(FinishWords.of(null, complete = false))        // no manifest, first pass, nothing complete or short
        assertNull(FinishWords.of("", complete = false))
        assertNull(FinishWords.of("  ", complete = false))
        assertNull(FinishWords.of("closet lullaby", complete = true))
    }

    @Test fun thePartsTheWordsNameUnderThem() {
        // the status's finish_parts (the core's "Parts 7, 12–15, 30", from 1 as the sender shows them), only with words
        assertEquals("Parts 7, 12–15, 30", FinishWords.parts("Parts 7, 12–15, 30", "closet lullaby"))
        assertEquals("Part 2", FinishWords.parts(" Part 2 ", "closet lullaby"))
        assertNull(FinishWords.parts("Part 2", null))                // no words shown (complete, or none offered)
        assertNull(FinishWords.parts(null, "closet lullaby"))        // a status without the field
        assertNull(FinishWords.parts("", "closet lullaby"))
    }

    @Test fun saidOnceWhenTheyAppearNotOnEveryPoll() {
        val a = WordsAnnouncer()
        assertNull(a.next(null))
        assertNull(a.next(null))
        assertEquals(FinishWords.spoken("closet lullaby"), a.next("closet lullaby"))
        for (i in 0 until 50) assertNull("poll $i", a.next("closet lullaby"))
    }

    @Test fun saidAgainWhenTheyChange() {
        val a = WordsAnnouncer()
        a.next("satin lullaby disco apple")
        // a part finished: the code is shorter now
        assertEquals(FinishWords.spoken("bobcat disco"), a.next("bobcat disco"))
        assertNull(a.next("bobcat disco"))
    }

    @Test fun theSameWordsBackAfterAGapAreNotSaidAgain() {
        val a = WordsAnnouncer()
        a.next("closet lullaby")
        assertNull(a.next(null))                    // say a camera problem screen in between
        assertNull(a.next("closet lullaby"))
        assertEquals(FinishWords.spoken("bobcat disco"), a.next("bobcat disco"))
    }

    @Test fun aNewTransferSaysThemAgain() {
        val a = WordsAnnouncer()
        a.next("closet lullaby")
        a.reset()
        assertEquals(FinishWords.spoken("closet lullaby"), a.next("closet lullaby"))
    }
}
