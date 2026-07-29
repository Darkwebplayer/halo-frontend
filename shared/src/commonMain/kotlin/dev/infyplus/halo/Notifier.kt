package dev.infyplus.halo

import dev.infyplus.halo.ui.apiCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Platform notification scheduling.
 *
 * A plain interface rather than `expect`/`actual`: the implementations live in the app modules
 * (they need `MainActivity` on Android and the tray icon on desktop), and an `actual` has to sit
 * in the same module as its `expect`. Each app registers its implementation at startup.
 *
 * [arm] must be idempotent — called with the same [Scheduled.id] it replaces rather than
 * duplicates. Clients re-sync often, so it is called far more than the schedule changes.
 */
interface Notifier {
    /** Replace the armed schedule with exactly this list. */
    fun arm(items: List<Scheduled>)

    /** Show a notification right now — used when a watched condition becomes true. */
    fun fireNow(item: Scheduled)

    /**
     * Take down anything already showing for [itemId].
     *
     * Once a reminder is dealt with — anywhere: the banner, the panel, another device — every
     * notification about it is stale. Leaving them in the shade means tapping one later offers
     * Snooze and Done for something already finished, which is worse than no notification.
     *
     * The ids are derived, not stored: the server mints them as `due-<itemId>` and friends, so
     * both ends agree without keeping a map.
     */
    fun dismissFor(itemId: String) = Unit

    fun cancelAll()
}

/**
 * Holds the platform implementation.
 *
 * Calls are no-ops until one is registered, so a background sync that runs before startup
 * finishes cannot crash — it simply arms nothing, and the next sync picks it up.
 */
object Notifications : Notifier {
    @Volatile
    var impl: Notifier? = null

    /**
     * Told about every notification that actually reaches the user, whoever showed it.
     *
     * Delivery is entirely local, so this is the only way the server learns what was shown —
     * and that record is what the app's notification tab lists. Set once at startup to
     * [reportFiredTo]; left null in tests, where nothing should be posted anywhere.
     */
    @Volatile
    var onFired: ((Scheduled) -> Unit)? = null

    override fun arm(items: List<Scheduled>) {
        impl?.arm(items)
    }

    override fun fireNow(item: Scheduled) {
        impl?.fireNow(item)
        onFired?.invoke(item)
    }

    override fun dismissFor(itemId: String) {
        impl?.dismissFor(itemId)
    }

    override fun cancelAll() {
        impl?.cancelAll()
    }
}

/**
 * Show a notification the server knows nothing about.
 *
 * Deliberately NOT [Notifications.fireNow]: that also invokes [Notifications.onFired], which
 * [reportFiredTo] points at `POST /notifications/fired`. The pomodoro is entirely local — a phase
 * ending has no business in the server's check-in history, and writing one there would also
 * inflate the orb's unread badge, which is derived from that same history.
 */
fun notifyLocally(item: Scheduled) {
    Notifications.impl?.fireNow(item)
}

/**
 * Wire [Notifications.onFired] to report to the server on [scope].
 *
 * Fire-and-forget, and failures are logged rather than retried: the notification has already
 * been shown by the time this runs, so a lost report costs a row in the history — never a
 * missed reminder. Retrying would mean a queue, which is not worth it for that.
 */
fun reportFiredTo(
    api: HaloApi,
    scope: CoroutineScope,
    state: dev.infyplus.halo.ui.HaloState = dev.infyplus.halo.ui.HaloState.shared,
) {
    Notifications.onFired = { item ->
        // Reflect it locally first. The badge is derived from the server's count, which does not
        // know this fired until the report lands and is only re-read once a minute — so without
        // this the orb sits behind the notification the user is already looking at.
        state.noteFired(hasItem = item.itemId != null)
        // The in-overlay card, alongside the system notification rather than instead of it.
        state.showHeadsUp(item)

        scope.launch {
            apiCatching { api.reportFired(item.id, item.itemId) }
                .onFailure { Sync.log("could not report '${item.title}' as fired: ${it.message}") }
        }
    }
}
