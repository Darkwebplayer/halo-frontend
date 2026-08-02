package dev.infyplus.halo

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The floating assistant's one visibility rule.
 *
 * Worth a test of its own because three surfaces read it — the Android overlay window, the desktop
 * bubble and the Quick Settings tile — and two of them cannot be tested at all. If they ever
 * disagree about when the bubble is on screen, the tile starts reporting "On" over an empty screen.
 */
class OrbVisibilityTest {

    /** Nothing waiting, always-on: the behaviour every existing install already has. */
    @Test
    fun alwaysOnShowsWithNothingWaiting() {
        assertTrue(orbVisible(always = true, hidden = false, unread = 0, headsUp = false, timerRunning = false))
    }

    /** Turned off, and genuinely nothing to say. */
    @Test
    fun whenNeededHidesWithNothingWaiting() {
        assertFalse(orbVisible(always = false, hidden = false, unread = 0, headsUp = false, timerRunning = false))
    }

    /** Each fact on its own is enough to bring it back. */
    @Test
    fun whenNeededShowsForAnythingWaiting() {
        assertTrue(orbVisible(always = false, hidden = false, unread = 1, headsUp = false, timerRunning = false))
        assertTrue(orbVisible(always = false, hidden = false, unread = 0, headsUp = true, timerRunning = false))
        assertTrue(orbVisible(always = false, hidden = false, unread = 0, headsUp = false, timerRunning = true))
    }

    /**
     * Dismissal outranks everything, including a notification that just fired.
     *
     * This is the rule the whole feature turns on: a bubble that reappears the moment something
     * arrives is not one that can be dismissed, it is one that can be postponed.
     */
    @Test
    fun dismissalBeatsEverything() {
        assertFalse(orbVisible(always = true, hidden = true, unread = 3, headsUp = true, timerRunning = true))
    }

    /**
     * ...except an open panel, which is not a fact about what is waiting but about what is already
     * on screen. The shade notification opens the panel without undoing a dismissal, and this is
     * what stops that from asking for a panel in a window that is not there.
     */
    @Test
    fun anOpenPanelKeepsItsWindow() {
        assertTrue(
            orbVisible(
                always = false, hidden = true, unread = 0, headsUp = false, timerRunning = false,
                open = true,
            ),
        )
        // And closing it drops straight back to hidden, rather than leaving the bubble behind.
        assertFalse(
            orbVisible(
                always = false, hidden = true, unread = 0, headsUp = false, timerRunning = false,
                open = false,
            ),
        )
    }
}
