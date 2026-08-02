package dev.infyplus.halo.ui

import dev.infyplus.halo.ApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the app says when an action did not get through.
 *
 * The wording is the whole feature. These paths — snooze from a banner, done from a notification —
 * have no screen of their own, so a failure that is not put into words is a failure the user never
 * learns about, having already watched the banner slide away.
 */
class FailureNoticeTest {

    private val what = "“Call the plumber” was not snoozed"

    @Test
    fun anUnreachableServerIsToldApartFromOneThatRefused() {
        // isConnectivity treats anything that is not an ApiException as "never got there".
        val offline = failureNotice(RuntimeException("Connection refused"), what)
        assertTrue(offline.startsWith("No connection"), "got: $offline")
        assertTrue(offline.contains(what), "the notice must name what did not happen")

        val refused = failureNotice(ApiException("item already completed"), what)
        assertTrue(!refused.startsWith("No connection"), "a refusal is not an outage: $refused")
        assertTrue(refused.contains("item already completed"), "the server's own words survive")
    }

    @Test
    fun aRefusalWithNothingToSayStillSaysSomething() {
        assertEquals(
            "Your server refused that — $what.",
            failureNotice(ApiException(""), what),
        )
    }

    @Test
    fun theNoticeOutranksTheCountdownAndTheCount() {
        // The glance is normally for the timer; a failed action is more urgent than either.
        assertEquals(
            "Failed",
            cloudFor(open = false, offline = false, countdown = "24:31", unread = 3, failed = true),
        )
        // …but never over the open panel, which is saying it properly.
        assertNull(cloudFor(open = true, offline = false, countdown = null, unread = 0, failed = true))
    }

    @Test
    fun aNoticeIsSetAndClearedOnState() {
        val state = HaloState()
        assertNull(state.notice)

        state.noteFailure(ApiException("no"), what)
        assertTrue(state.notice!!.contains(what))

        state.clearNotice()
        assertNull(state.notice, "a success clears it — the failure it described is over")
    }

    @Test
    fun aRefusalDoesNotGreyTheCharacterOut() {
        val state = HaloState()
        state.noteFailure(ApiException("item already completed"), what)
        assertTrue(!state.offline, "the server answered; blaming the network points at the wrong fix")

        state.noteFailure(RuntimeException("timeout"), what)
        assertTrue(state.offline, "…but never getting there is exactly what offline means")
    }
}
