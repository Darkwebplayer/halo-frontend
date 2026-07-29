package dev.infyplus.halo.ui

/**
 * What the orb says in its speech cloud.
 *
 * This file used to also decide whether typed text was a question or a command, by pattern
 * matching on verbs and question words. That is gone: the rules had no category for input that
 * was not a request at all, so "hello" matched no question pattern, fell through to the command
 * branch, and became a task called "hello". Routing now happens on the server, where a model can
 * see the difference and can also resolve "it" against the actual item list — see
 * `POST /message` and [dev.infyplus.halo.HaloApi.message].
 */

/**
 * The hard character budget for the speech cloud above the orb.
 *
 * The cloud is a shape, not a text box — at this size anything longer stops reading as a comic
 * bubble and starts reading as a tooltip.
 */
const val CLOUD_MAX = 10

/**
 * What the cloud should say, or null to hide it.
 *
 * Over-budget copy returns null rather than an ellipsis. That is the important rule and the
 * reference asserts it directly (line 1184): being over budget means the cloud is the wrong
 * carrier for that message, and a clipped word communicates less than showing nothing while the
 * panel says it properly.
 */
fun cloudText(text: String?): String? {
    val t = text ?: return null
    if (t.isEmpty() || t.length > CLOUD_MAX) return null
    return t
}

/**
 * The cloud's copy for the current state, in the reference's priority order (lines 855-862).
 *
 * A running timer outranks a pending notification: the countdown is changing every second, so it
 * is the thing the glance is for.
 */
fun cloudFor(
    open: Boolean,
    offline: Boolean,
    countdown: String?,
    unread: Int,
): String? = when {
    open -> null                        // the panel is saying it properly
    offline -> cloudText("Offline")
    countdown != null -> cloudText(countdown)
    unread > 0 -> cloudText("Due now")
    else -> null
}
