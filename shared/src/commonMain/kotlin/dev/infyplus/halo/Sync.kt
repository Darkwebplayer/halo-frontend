package dev.infyplus.halo

import kotlinx.coroutines.delay

/**
 * Keeps this device's armed schedule in step with the server.
 *
 * There is no push, so a change made on another device is only seen at the next sync. That is
 * the accepted trade-off: a stale alarm may fire once for something already handled elsewhere,
 * and self-corrects. Suppressing it would require exactly the push channel we removed.
 *
 * Re-arming is idempotent — notification ids are stable — so syncing often is cheap and safe.
 */
object Sync {

    /** Last schedule seen, so a condition tick can be evaluated without re-fetching. */
    var lastLocation: GeoPoint? = null
        private set

    private var watched: List<Scheduled> = emptyList()

    /**
     * Fetch and arm. Returns false if the sync failed, leaving existing alarms untouched.
     *
     * Failures are logged rather than swallowed: a silently-failing sync looks exactly like
     * "no reminders due", which is indistinguishable from working correctly until something
     * important is missed.
     */
    suspend fun once(api: HaloApi): Boolean = runCatching {
        val schedule = api.sync()
        lastLocation = schedule.location
        watched = schedule.notifications.filter { it.condition != null }
        Notifications.arm(schedule.notifications)
        log("armed ${schedule.notifications.size} notification(s)")
        true
    }.getOrElse {
        log("sync FAILED: ${it::class.simpleName}: ${it.message}")
        false
    }

    /** Deliberately plain output so it shows up in `gradlew run` and logcat alike. */
    fun log(message: String) = println("[halo-sync] $message")

    /**
     * Evaluate weather-conditioned reminders and fire any that are now true.
     *
     * Returns the number fired. A fetch failure fires nothing rather than assuming the condition
     * is false — "couldn't check the weather" must never read as "it isn't raining".
     */
    suspend fun tickConditions(api: HaloApi): Int {
        val location = lastLocation ?: return 0
        if (watched.isEmpty()) return 0

        val due = runCatching { ConditionWatcher.due(watched, location) }.getOrNull() ?: return 0
        for (item in due) {
            Notifications.fireNow(item)
            // Completing it server-side stops it being re-armed on the next sync. Only ever set
            // on a conditional reminder, which always has an item — the item-less summaries and
            // nudges carry no condition, so they never reach here.
            item.itemId?.let { id -> runCatching { api.act(id, "done") } }
        }
        if (due.isNotEmpty()) once(api)
        return due.size
    }

    /**
     * How often a device re-reads the schedule.
     *
     * This is the worst-case delay before a reminder created *somewhere else* is armed here, and
     * therefore the shortest cross-device reminder that can still fire on time. There is no push
     * — deliberately — so nothing tells this device about a new reminder until it asks.
     *
     * A minute, not fifteen. The device you typed on arms immediately (`once` runs right after a
     * capture), so the interval only ever bites cross-device — but "remind me in 2 minutes" typed
     * on the laptop has to reach the phone, and at fifteen minutes it never did: the phone learnt
     * about it long after it was due and could only show it late, as a missed one.
     *
     * The cost is one small GET per minute from a process that is already resident.
     */
    const val EVERY_MILLIS = 60_000L

    /** Long-running loop for platforms with a persistent process. */
    suspend fun loop(api: HaloApi, everyMillis: Long = EVERY_MILLIS): Nothing {
        while (true) {
            once(api)
            tickConditions(api)
            delay(everyMillis)
        }
    }
}
