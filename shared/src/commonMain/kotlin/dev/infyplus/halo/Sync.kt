package dev.infyplus.halo

import dev.infyplus.halo.ui.apiCatching
import kotlinx.coroutines.CancellationException
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
    suspend fun once(api: HaloApi): Boolean = apiCatching {
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

        val due = apiCatching { ConditionWatcher.due(watched, location) }.getOrNull() ?: return 0
        for (item in due) {
            // Guarded per item: showing a notification goes through platform code that can refuse
            // (a revoked permission, a dead window token), and an unguarded throw here escapes all
            // the way out of `loop`, which would stop this device re-arming anything ever again.
            apiCatching { Notifications.fireNow(item) }
                .onFailure { log("could not show '${item.title}': ${it.message}") }
            // Completing it server-side stops it being re-armed on the next sync. Only ever set
            // on a conditional reminder, which always has an item — the item-less summaries and
            // nudges carry no condition, so they never reach here.
            item.itemId?.let { id -> apiCatching { api.act(id, "done") } }
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

    /**
     * Long-running loop for platforms with a persistent process.
     *
     * Builds its own client each tick rather than being handed one, because it outlives any change
     * made in Settings — a [HaloApi] keeps whatever credentials it was constructed with, so a
     * captured one would keep talking to the old server forever. Constructing it is cheap next to
     * the network round-trip it is about to make.
     */
    suspend fun loop(everyMillis: Long = EVERY_MILLIS): Nothing {
        while (true) {
            // The one thing this loop must never do is stop. Every reminder on this device is armed
            // from here, and a single throw escaping would cancel the coroutine and take the whole
            // schedule down silently — indistinguishable, from the outside, from having nothing due.
            // `once` and `tickConditions` guard themselves, so this is a backstop for what they
            // cannot see coming, including an Error out of platform code.
            //
            // Cancellation is rethrown, because that one *is* the caller shutting us down.
            try {
                // Skipped outright with no network: this would only sit on a connect timeout and
                // arm nothing, and on a phone in a tunnel that is a wakeup a minute for no reason.
                // Nothing is missed — alarms already armed are held by the OS, and regaining the
                // network is itself a change this loop will see on its next tick.
                if (Config.isConfigured && DeviceNetwork.available) {
                    val api = HaloApi(Config.baseUrl, Config.authToken)
                    once(api)
                    tickConditions(api)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log("tick FAILED: ${e::class.simpleName}: ${e.message}")
            }
            delay(everyMillis)
        }
    }
}
