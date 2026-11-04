package com.lensbolt.app

import org.junit.Assert.assertEquals
import org.junit.Test

// "Only this time": Android revokes the camera when the app closes and asks again on the next request, yet it also
// reports no rationale then, so "asked before and no rationale" took those people to Settings instead of asking.
// Blocked is now learnt only from a request's answer.
class CameraPermissionTest {
    @Test fun a_refusal_without_a_rationale_is_a_block() {
        assertEquals(true, CameraPermission.blockedAfter(granted = false, rationale = false))
    }

    @Test fun a_first_refusal_or_a_grant_is_not() {
        assertEquals(false, CameraPermission.blockedAfter(granted = false, rationale = true))
        assertEquals(false, CameraPermission.blockedAfter(granted = true, rationale = false))
        assertEquals(false, CameraPermission.blockedAfter(granted = true, rationale = true))
    }

    @Test fun a_one_time_grant_that_expired_asks_again() {
        // the app starts with the camera not granted and no rationale, as after "Only this time"; nothing was refused
        assertEquals(false, CameraPermission.showSettings(blockedSeen = false))
        // a request that came back refused with no rationale: only Settings can turn the camera on
        assertEquals(true, CameraPermission.showSettings(blockedSeen = true))
    }
}
