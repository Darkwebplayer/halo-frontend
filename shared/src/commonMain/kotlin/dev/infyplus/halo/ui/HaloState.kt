package dev.infyplus.halo.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What the orb is feeling, and why.
 *
 * The important rule, inherited from the reference (lines 823-831): **the resting expression is
 * derived, never assigned.** Callers set the *facts* — a timer is running, we lost the network,
 * something is waiting — and [expression] falls out of them. That is what stops the two
 * disagreeing: with a directly-assigned expression, going offline while a "success" flash was in
 * the air would land the cat back on a cheerful face it had no business wearing.
 *
 * Process-wide via [shared], following [dev.infyplus.halo.Pomodoro.shared]: the overlay
 * and the main window are two views of one assistant, and closing either must not reset it.
 */
class HaloState(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    /** Set from the facts, not from taste: Idle, Work while a timer runs, Happy when something waits. */
    var base by mutableStateOf(Expression.Idle)
        private set

    /** True once a call has failed; the cat greys out until one succeeds. */
    var offline by mutableStateOf(false)
        private set

    /** Unanswered notifications — drives the badge, and nudges [base] to Happy. */
    var unread by mutableStateOf(0)
        private set

    /**
     * Whether a focus timer is running.
     *
     * Remembered rather than passed in. It used to be an optional argument on [noteUnread], and the
     * one caller that forgot it — the panel, refreshing its lists on open — silently asserted "no
     * timer" and dropped a working cat back to Idle. It stayed there, because nothing re-derives
     * until the timer next changes state, which a still-running timer never does.
     */
    var timerRunning by mutableStateOf(false)
        private set

    /** Recedes when untouched, so a permanent overlay does not become permanent clutter. */
    var resting by mutableStateOf(false)
        private set

    private var flashed by mutableStateOf<Expression?>(null)
    private var flashJob: Job? = null
    private var restJob: Job? = null

    /** Where the face settles. Offline outranks everything — it is a hard fact, not a mood. */
    val restingExpression: Expression
        get() = if (offline) Expression.Dead else base

    /** What to actually draw: a transient reaction if one is playing, otherwise the resting face. */
    val expression: Expression
        get() = flashed ?: restingExpression

    /**
     * Play a one-off reaction, then fall back.
     *
     * Falls back to [restingExpression] rather than to whatever was showing when it started, so a
     * flash that outlives a state change still lands somewhere correct.
     */
    fun flash(expression: Expression, ms: Long = 1500) {
        flashJob?.cancel()
        flashed = expression
        flashJob = scope.launch {
            delay(ms)
            flashed = null
        }
    }

    /**
     * Drop a held reaction and fall straight back to the resting face.
     *
     * [flash] is also used for reactions with no natural end — the 30-second Wait that a send holds
     * "until the reply lands". If that send never lands normally (the panel was closed mid-flight),
     * nothing replaces it, and on desktop the orb outlives the panel: the cat would sit there
     * waiting for half a minute on a conversation that no longer exists.
     */
    fun clearFlash() {
        flashJob?.cancel()
        flashed = null
    }

    fun noteTimerRunning(running: Boolean) {
        timerRunning = running
        rederive()
    }

    fun noteUnread(count: Int) {
        unread = count
        rederive()
    }

    /**
     * The resting face, from the facts alone.
     *
     * The only place [base] is ever written. Every caller states a fact and this decides what that
     * adds up to — which is the rule the whole class is built on, and the reason a caller can no
     * longer accidentally assert something it does not know.
     */
    private fun rederive() {
        // A reminder is a bouncy hello, not a scowl (reference line 991), and it outranks work.
        base = when {
            unread > 0 -> Expression.Happy
            timerRunning -> Expression.Work
            else -> Expression.Idle
        }
    }

    /**
     * A notification just fired on this device.
     *
     * Counted straight away rather than waiting for the next poll. Delivery is local, so the
     * server does not know a thing has fired until we tell it — and even then the badge would
     * sit a minute behind the notification the user is looking at, which reads as broken.
     *
     * Only for notifications with something to act on: a summary or a general nudge has no
     * decision attached, and the badge counts decisions.
     */
    fun noteFired(hasItem: Boolean) {
        wake()
        if (hasItem) noteUnread(unread + 1) else flash(Expression.Happy, 2600)
    }

    /**
     * The notification currently showing in the heads-up banner, or null.
     *
     * Separate from the system notification, which fires regardless — this is only for the
     * in-overlay card, and it is cleared as soon as the banner withdraws or is acted on.
     */
    var headsUp by mutableStateOf<dev.infyplus.halo.Scheduled?>(null)
        private set

    /**
     * Notifications that fired while another was still showing.
     *
     * There used to be only the single slot above, so a second alert arriving while the first was
     * up replaced it silently — the first was never seen, never acted on, and left no trace except
     * a number on the tray icon. Now they queue and are shown one at a time.
     *
     * Bounded, because this is a courtesy rather than a record: everything that fired is already
     * on the server and listed in the Alerts tab. Twenty is far past the point where anyone is
     * still reading banners.
     */
    private val waiting = ArrayDeque<dev.infyplus.halo.Scheduled>()

    /** How many are still queued behind the one on screen. */
    var headsUpWaiting by mutableStateOf(0)
        private set

    fun showHeadsUp(notification: dev.infyplus.halo.Scheduled) {
        if (headsUp == null) {
            headsUp = notification
            return
        }
        // Same notification re-reported (a re-arm, a duplicate fire) must not stack up.
        if (headsUp?.id == notification.id || waiting.any { it.id == notification.id }) return
        if (waiting.size < 20) waiting.addLast(notification)
        headsUpWaiting = waiting.size
    }

    /** Dismiss the one on screen and show whatever was behind it. */
    fun dismissHeadsUp() {
        headsUp = waiting.removeFirstOrNull()
        headsUpWaiting = waiting.size
    }

    /**
     * An item to open the panel on, set from outside the UI — a tapped system notification.
     *
     * Held as an id rather than an [dev.infyplus.halo.Item] because whoever sets it (a
     * broadcast receiver, an Activity intent) has only the id; the panel resolves it against the
     * data it already loads.
     */
    var pendingScopeId by mutableStateOf<String?>(null)
        private set

    fun requestScope(itemId: String?) {
        pendingScopeId = itemId
    }

    fun clearPendingScope() {
        pendingScopeId = null
    }

    /**
     * Which tab the panel should open on, when something outside it has an opinion.
     *
     * Tapping the badge means "show me what is waiting", which is the Alerts list — opening on
     * Chat would answer a different question from the one that was asked.
     */
    var pendingTab by mutableStateOf<PanelTab?>(null)
        private set

    fun requestTab(tab: PanelTab) {
        pendingTab = tab
    }

    fun clearPendingTab() {
        pendingTab = null
    }

    /**
     * Record whether the last call reached the server.
     *
     * Deliberately not a connectivity API: what matters is whether *our* calls are working, and a
     * device can be on wifi with the server unreachable.
     */
    fun markOffline(value: Boolean) {
        offline = value
    }

    /** Something happened worth acknowledging. Grumpy while offline — a brief huff, then dead again. */
    fun reactToSend(succeeded: Boolean) {
        if (!succeeded && offline) flash(Expression.Cross, 1400) else flash(Expression.Wink, 1100)
    }

    /** Called on any interaction; the overlay dims again if nothing follows within [afterMs]. */
    fun wake(afterMs: Long = 4500) {
        resting = false
        restJob?.cancel()
        restJob = scope.launch {
            delay(afterMs)
            resting = true
        }
    }

    /** Stop dimming — used while the panel is open, where receding would be wrong. */
    fun holdAwake() {
        restJob?.cancel()
        resting = false
    }

    /** Recede immediately, without waiting out the idle timer. */
    fun recedeNow() {
        restJob?.cancel()
        resting = true
    }

    companion object {
        val shared = HaloState()
    }
}
