package dev.infyplus.halo.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CloudTest {

    @Test
    fun copyWithinBudgetIsShown() {
        // The two strings the reference asserts fit (line 1183).
        assertEquals("Due now", cloudText("Due now"))
        assertEquals("Done!", cloudText("Done!"))
        assertEquals("25:00", cloudText("25:00"))
    }

    @Test
    fun overBudgetCopyFallsBackRatherThanClipping() {
        // The rule, stated at reference line 1184: over budget means the cloud is the wrong
        // carrier, so it shows nothing. A clipped word would communicate less than silence.
        assertNull(cloudText("Connection lost"))
        assertNull(cloudText("x".repeat(CLOUD_MAX + 1)))
    }

    @Test
    fun exactlyAtBudgetStillFits() {
        val atLimit = "x".repeat(CLOUD_MAX)
        assertEquals(atLimit, cloudText(atLimit))
    }

    @Test
    fun emptyShowsNothing() {
        assertNull(cloudText(""))
        assertNull(cloudText(null))
    }

    @Test
    fun anOpenPanelSilencesTheCloud() {
        // The panel is already saying it properly; the cloud would be duplicate chrome.
        assertNull(cloudFor(open = true, offline = true, countdown = "24:31", unread = 3))
    }

    @Test
    fun aRunningTimerOutranksAPendingNotification() {
        // The countdown changes every second, so it is what the glance is for.
        assertEquals("24:31", cloudFor(open = false, offline = false, countdown = "24:31", unread = 3))
        assertEquals("Due now", cloudFor(open = false, offline = false, countdown = null, unread = 3))
    }

    @Test
    fun offlineOutranksEverything() {
        assertEquals("Offline", cloudFor(open = false, offline = true, countdown = "24:31", unread = 3))
    }

    @Test
    fun nothingToSayShowsNothing() {
        assertNull(cloudFor(open = false, offline = false, countdown = null, unread = 0))
    }
}
