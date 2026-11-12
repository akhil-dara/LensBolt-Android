package com.lensbolt.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpTest {
    // the first-run guide: three steps, the computer side first, the check of the file last
    @Test fun theGuideWalksThroughOneTransfer() {
        assertEquals(3, Help.STEPS.size)
        assertTrue(Help.STEPS[0].contains("lensbolt.com"))
        assertTrue(Help.STEPS[1].contains("Point this phone"))
        assertTrue(Help.STEPS[2].contains("SHA-256"))
    }

    // shown once, on the first real start; never over a lab screen
    @Test fun theGuideShowsOnceAndNotInTheLab() {
        assertTrue(Help.showGuide(seen = false, lab = false))
        assertFalse(Help.showGuide(seen = true, lab = false))
        assertFalse(Help.showGuide(seen = false, lab = true))
    }

    // Rate opens the app's Play listing (the Play app, else the web page); feedback opens the project's issues
    @Test fun theLinksPointAtTheListingAndTheIssues() {
        assertEquals("market://details?id=com.lensbolt.app", Help.rateUri("com.lensbolt.app"))
        assertEquals("https://play.google.com/store/apps/details?id=com.lensbolt.app", Help.rateWebUri("com.lensbolt.app"))
        assertEquals("https://github.com/akhil-dara/lensbolt-android/issues", Help.FEEDBACK_URI)
    }

    // the camera menu's extra items, after the camera modes
    @Test fun theMenuOffersHelpRatingAndFeedback() {
        assertEquals(listOf("How it works", "Rate LensBolt", "Send feedback"), Help.Item.entries.map { it.label })
    }
}
