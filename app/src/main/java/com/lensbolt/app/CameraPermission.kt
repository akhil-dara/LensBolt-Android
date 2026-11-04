package com.lensbolt.app

/** When the camera screen offers Settings instead of asking. "Asked before and no rationale" is not enough: after
 *  "Only this time" Android revokes the camera when the app closes, reports no rationale, and still shows its
 *  dialog on the next request. Blocked is learnt only from a request's answer: refused with no rationale means
 *  Android will not ask again (`cameraBlocked`, cleared by a grant). */
object CameraPermission {
    /** After a request's answer: whether Android will no longer show its dialog. */
    fun blockedAfter(granted: Boolean, rationale: Boolean): Boolean = !granted && !rationale
    /** The permission screen offers Settings (not Allow) only once a refusal said so. */
    fun showSettings(blockedSeen: Boolean): Boolean = blockedSeen
}
