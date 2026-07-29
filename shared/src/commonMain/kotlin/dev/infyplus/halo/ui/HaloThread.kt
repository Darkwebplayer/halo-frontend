package dev.infyplus.halo.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.infyplus.halo.Answer
import dev.infyplus.halo.Item
import dev.infyplus.halo.HaloApi
import dev.infyplus.halo.Sync

/** One thing in the transcript. */
sealed interface ThreadEntry {
    data class Said(val text: String, val fromUser: Boolean) : ThreadEntry

    /**
     * How the last message was routed.
     *
     * Shown rather than hidden: a single input box has to guess between answering and doing, and
     * a guess the user cannot see is a guess they cannot correct.
     */
    data class Routed(val label: String, val question: Boolean) : ThreadEntry

    /** A factual answer with its sources. */
    data class Info(val answer: Answer) : ThreadEntry

    /** An item that was created or changed, shown as a card rather than prose. */
    data class Card(val item: Item) : ThreadEntry

    /** Halo is working. Removed when the reply lands. */
    data object Typing : ThreadEntry
}

/** How the server described what it did, in the panel's own words. */
private fun routeLabel(route: String) = when (route) {
    "question" -> "question → quick info"
    "capture" -> "command → create"
    "edit" -> "command → edit on known item"
    "complete" -> "command → mark done"
    else -> "chat"
}

/**
 * The conversation, and what happens when you send something.
 *
 * Routing is the *server's* job, not this class's. It used to be decided here by pattern-matching
 * the text, which had no category for input that was not a request at all — "hello" matched no
 * question pattern, fell through to the command branch, and became a task. A model on the server
 * can tell the difference, and it already has the item list it needs to resolve "it".
 */
class HaloConversation(
    private val api: HaloApi,
    private val state: HaloState = HaloState.shared,
) {
    val entries = mutableStateListOf<ThreadEntry>()

    var busy by mutableStateOf(false)
        private set

    /** The item this conversation is about, when opened from a notification. */
    var scope by mutableStateOf<Item?>(null)
        private set

    fun scopeTo(item: Item?) {
        scope = item
        entries.clear()
        greet()
    }

    fun greet() {
        entries.add(
            ThreadEntry.Said(
                text = if (scope != null) {
                    "This one just fired. Reply and I'll apply it as an edit — no need to name it again."
                } else {
                    "What do you need? I'll work out whether it's a question or a command."
                },
                fromUser = false,
            ),
        )
    }

    /**
     * Send [text]. One call — the server decides what it was and does it.
     *
     * Nothing here inspects the text. Whatever comes back describes both the route taken and the
     * outcome, so the chip in the transcript reports what actually happened rather than what the
     * client predicted would happen.
     */
    /** @return true when this settled the scoped notification, so the caller can move on. */
    suspend fun send(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || busy) return false

        entries.add(ThreadEntry.Said(trimmed, fromUser = true))
        entries.add(ThreadEntry.Typing)
        busy = true
        state.flash(Expression.Wait, ms = 30_000) // held until the reply lands

        val result = runCatching {
            val response = api.message(trimmed, itemId = scope?.id)
            buildList {
                // Chat is the one route with nothing to report — showing "chat → nothing
                // happened" above a greeting is noise, and the absence of a chip is already
                // the signal that nothing was recorded.
                if (response.route != "chat") {
                    add(ThreadEntry.Routed(routeLabel(response.route), response.route == "question"))
                }
                response.answer?.let { add(ThreadEntry.Info(it)) }
                response.item?.let { add(ThreadEntry.Card(it)) }
                if (response.reply.isNotBlank()) {
                    add(ThreadEntry.Said(response.reply, fromUser = false))
                }
            } to response
        }

        entries.remove(ThreadEntry.Typing)
        busy = false
        // Only a scoped reply that actually changed or completed the item counts as settled;
        // a question about it, or a failure, leaves it outstanding.
        val settled = scope != null &&
            result.getOrNull()?.second?.route in setOf("complete", "edit")

        result
            .onSuccess { (added, response) ->
                entries.addAll(added)
                state.markOffline(false)
                // A new or moved reminder changes what this device should be waking up for, so
                // re-arm now rather than waiting for the next sync tick — otherwise "remind me
                // in one minute" is missed by several.
                if (response.route == "capture" || response.route == "edit") Sync.once(api)
                // Settling it in prose leaves the notification just as stale as tapping a chip.
                if (settled) scope?.id?.let { dev.infyplus.halo.Notifications.dismissFor(it) }
                state.reactToSend(succeeded = true)
            }
            .onFailure { error ->
                // Distinguish "the server said no" from "we could not reach it" — only the
                // second one means offline, and the cat's face is the fastest way to say which.
                val unreachable = error.isConnectivity()
                state.markOffline(unreachable)
                entries.add(
                    ThreadEntry.Routed(
                        if (unreachable) "queued · offline" else "failed",
                        question = false,
                    ),
                )
                entries.add(
                    ThreadEntry.Said(
                        if (unreachable) "No connection — nothing was sent. Try again once you're back."
                        else error.message ?: "That didn't work.",
                        fromUser = false,
                    ),
                )
                state.reactToSend(succeeded = false)
            }

        return settled
    }
}

/**
 * Whether a failure was "could not reach the server" rather than "the server refused".
 *
 * [dev.infyplus.halo.ApiException] is only thrown for a non-2xx response, which means we
 * got there — anything else came out of the socket.
 */
internal fun Throwable.isConnectivity(): Boolean =
    this !is dev.infyplus.halo.ApiException
