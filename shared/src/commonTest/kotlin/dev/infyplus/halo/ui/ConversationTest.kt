package dev.infyplus.halo.ui

import dev.infyplus.halo.HaloApi
import dev.infyplus.halo.Item
import dev.infyplus.halo.Summary
import dev.infyplus.halo.SummaryKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rules about what counts as a conversation, and what a summary says.
 *
 * No network: nothing here sends anything, so the address is deliberately one that would fail.
 */
class ConversationTest {

    private fun conversation() = HaloConversation(HaloApi("http://127.0.0.1:1", "none"), HaloState())

    @Test
    fun aGreetingIsNotAConversation() {
        val c = conversation()
        c.greet()
        assertFalse(c.hasUserTurn, "the app talking to itself is not a session to come back to")

        c.entries.add(ThreadEntry.Said("move it to tomorrow", fromUser = true))
        assertTrue(c.hasUserTurn)
    }

    @Test
    fun openingANotificationDropsAnEarlierDigest() {
        val c = conversation()
        c.referTo(SummaryKind.Morning, Summary(description = "Three things today."))
        assertEquals(SummaryKind.Morning, c.reference?.first)

        c.scopeTo(Item("i1", "task", "Call the plumber", createdAt = ""))
        assertEquals(null, c.reference, "a digest from an earlier visit is not about this alert")
    }

    @Test
    fun anAimSurvivesExactlyOneOpen() {
        val c = conversation()
        c.referTo(SummaryKind.Evening, Summary(description = "Two left."))

        assertTrue(c.consumeAim(), "the panel opening for this digest must not reset over it")
        assertFalse(c.consumeAim(), "…but coming back to it later is an ordinary open")
    }

    @Test
    fun aSummarySaysWhatTheServerWroteWhenItWroteAnything() {
        val written = Summary(description = "A quiet morning — one thing, and it can wait.")
        assertEquals(written.description, summaryLine(SummaryKind.Morning, written))

        // Blank description: counts, so the card is never empty.
        val bare = Summary(today = listOf(Item("i1", "task", "One", createdAt = "")))
        assertEquals("One thing today.", summaryLine(SummaryKind.Morning, bare))
        assertEquals("0 done, 0 still open.", summaryLine(SummaryKind.Evening, bare))
    }
}
