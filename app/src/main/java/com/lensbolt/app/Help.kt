package com.lensbolt.app

// The first-run guide and the camera menu's extra items: how a transfer goes, rating the app, sending feedback.
// Pure: the activity shows the guide and opens the links.
object Help {
    const val TITLE = "How LensBolt works"
    val STEPS = listOf(
        "On your computer, open lensbolt.com, choose a file and press Start. A square of tiles appears.",
        "Point this phone at the square so it fills most of the picture, about 20 to 40 cm away, and hold steady.",
        "When it says Received, the file has been checked with SHA-256. Save it or share it.",
    )
    const val FOOT = "No internet, cable or account needed. Open this again from the camera button at the top."
    const val OK = "Got it"

    /** The guide on the first real start only; never over a lab screen. */
    fun showGuide(seen: Boolean, lab: Boolean): Boolean = !seen && !lab

    /** The app's Play listing in the Play app; [rateWebUri] where that app is missing. */
    fun rateUri(pkg: String) = "market://details?id=$pkg"
    fun rateWebUri(pkg: String) = "https://play.google.com/store/apps/details?id=$pkg"
    const val FEEDBACK_URI = "https://github.com/akhil-dara/lensbolt-android/issues"

    /** The camera menu's items after the camera modes, in order. */
    enum class Item(val label: String) { GUIDE("How it works"), RATE("Rate LensBolt"), FEEDBACK("Send feedback") }
}
