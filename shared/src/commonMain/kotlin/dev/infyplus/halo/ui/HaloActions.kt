package dev.infyplus.halo.ui

import dev.infyplus.halo.HaloApi
import dev.infyplus.halo.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Answering a reminder — done, snooze, reschedule — from wherever it was answered.
 *
 * The three places that offer those verbs (the panel's scoped card, and the heads-up banner on
 * each platform) each had their own copy of the same eight lines, and each got a different part of
 * it wrong. This owns the whole outcome instead: the notification comes down, the badge drops, the
 * character reacts — or the user is told, in words, that none of that happened.
 *
 * ## Why this holds its own scope
 *
 * Because the surface that asks for an action is usually gone before the answer arrives. The
 * desktop banner is the sharp case: its window renders only while `HaloState.headsUp` is set, so
 * `dismissHeadsUp()` disposes the composition — and with it the `rememberCoroutineScope()` the
 * request was launched on. `apiCatching` rethrows cancellation rather than reporting it, so a
 * snooze cancelled that way left no trace anywhere: no error, no log, no snooze. Running here
 * means the call outlives whatever asked for it, which is the only correct lifetime for a request
 * that has already been acknowledged on screen.
 */
object HaloActions {

    /**
     * Deliberately process-wide and never cancelled. Every launch is one short call that must be
     * allowed to finish; there is nothing here worth tying to a window.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Act on [itemId], and report the outcome wherever the user is.
     *
     * @param title the item's own title, so a failure can name what is still outstanding.
     * @param onSuccess run only when the server agreed — the caller's own follow-up, like
     *   returning to the alerts list. Never run on failure, because nothing happened to follow up.
     */
    fun act(
        api: HaloApi,
        itemId: String,
        title: String,
        verb: String,
        state: HaloState = HaloState.shared,
        /**
         * The notification this came from, if any. Put back on failure: the banner was dismissed
         * the moment the button was pressed, and leaving it gone would take away the one place
         * the action can be tried again.
         */
        retry: dev.infyplus.halo.Scheduled? = null,
        onSuccess: suspend () -> Unit = {},
    ) {
        scope.launch {
            apiCatching { api.act(itemId, verb) }
                .onSuccess {
                    // The notification about it is stale wherever it is still showing.
                    Notifications.dismissFor(itemId)
                    // Acting answers every open check-in for that item server-side, so the count
                    // drops by the item, not by the occurrence.
                    state.noteUnread((state.unread - 1).coerceAtLeast(0))
                    state.markOffline(false)
                    state.clearNotice()
                    state.flash(if (verb == "done") Expression.Happy else Expression.Wink, 1600)
                    onSuccess()
                }
                .onFailure {
                    state.noteFailure(it, notDone(verb, title))
                    retry?.let { fired -> state.showHeadsUp(fired) }
                }
        }
    }

    /** What did not happen, in the words the notice needs. */
    private fun notDone(verb: String, title: String): String = when (verb) {
        "done" -> "“$title” was not marked done"
        "snooze" -> "“$title” was not snoozed"
        else -> "“$title” was not moved"
    }
}

/**
 * A failed request, said in terms of what the user was expecting.
 *
 * Pure so the wording can be pinned by a test. The two cases genuinely need different words: a
 * server that answered "no" is not an outage, and telling someone to check their connection when
 * their connection is fine sends them to fix the wrong thing.
 */
fun failureNotice(error: Throwable, what: String): String = when {
    error.isConnectivity() -> "No connection — $what."
    else -> error.message
        ?.takeIf { it.isNotBlank() }
        ?.let { "$what — $it" }
        ?: "Your server refused that — $what."
}
