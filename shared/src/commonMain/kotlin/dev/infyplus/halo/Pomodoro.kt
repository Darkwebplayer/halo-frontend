package dev.infyplus.halo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Wall-clock milliseconds. Injectable so the timer's arithmetic is testable. */
expect fun nowMillis(): Long

/**
 * The smallest key/value store that works on both platforms, for the one thing worth keeping.
 *
 * Deliberately two functions over a `String?` rather than a settings library: there is a single
 * value to persist. Android backs this with the `"halo"` SharedPreferences file that
 * [PermissionGate] already opens; desktop uses the JDK's own user preferences.
 *
 * Returns null until a platform attaches a store, in which case defaults apply — a sync that runs
 * before startup finishes cannot crash, it just reads nothing.
 */
expect fun loadSetting(key: String): String?

expect fun saveSetting(key: String, value: String)

const val POMODORO_MINUTES = 25

private const val SETTINGS_KEY = "pomodoro"
private val settingsJson = Json { ignoreUnknownKeys = true }

/** Which kind of interval is running, or — while [TimerState.Idle] — which one is up next. */
enum class Phase { Focus, ShortBreak, LongBreak }

/**
 * How long each interval lasts and how many focus sessions earn the long break.
 *
 * The one piece of state that survives a restart. A running timer deliberately does not: coming
 * back to a countdown you did not start, for work you have long since stopped doing, is worse
 * than starting a fresh one.
 */
@Serializable
data class PomodoroSettings(
    val focusMinutes: Int = POMODORO_MINUTES,
    val shortBreakMinutes: Int = 5,
    val longBreakMinutes: Int = 15,
    /** Focus sessions before a long break. */
    val cycleLength: Int = 4,
) {
    fun minutesFor(phase: Phase): Int = when (phase) {
        Phase.Focus -> focusMinutes
        Phase.ShortBreak -> shortBreakMinutes
        Phase.LongBreak -> longBreakMinutes
    }
}

sealed interface TimerState {
    data object Idle : TimerState
    /** Counting down; [endsAt] is a wall-clock instant, not a tick count. */
    data class Running(val endsAt: Long) : TimerState
    data class Paused(val remainingMs: Long) : TimerState
}

/**
 * Focus timer. Deliberately holds an *end instant* rather than decrementing a counter, so
 * remaining time stays correct across the overlay being closed and reopened — nothing has to
 * keep ticking while the UI is gone.
 *
 * Entirely local: no network call on any path, so it works offline. The one exception is the
 * optional task attached to a focus session, which is just an id and a title copied out of an
 * [Item] — the timer never fetches anything itself.
 */
class Pomodoro(private val clock: () -> Long = ::nowMillis) {
    var state by mutableStateOf<TimerState>(TimerState.Idle)
        private set

    /** What is running now, or what [start] would begin while idle. */
    var phase by mutableStateOf(Phase.Focus)
        private set

    /** Focus sessions finished in the current set. Drives the "2 of 4" dots. */
    var completedFocus by mutableStateOf(0)
        private set

    var settings by mutableStateOf(PomodoroSettings())
        private set

    var taskId by mutableStateOf<String?>(null)
        private set

    var taskTitle by mutableStateOf<String?>(null)
        private set

    /**
     * Told which phase just ended and which one follows, once per [catchUp] regardless of how many
     * boundaries were crossed. Set by the platform hosts to raise a notification; left null in
     * tests, where nothing should be shown anywhere.
     */
    var onPhaseEnd: ((ended: Phase, next: Phase) -> Unit)? = null

    fun start(minutes: Int = settings.minutesFor(phase)) {
        state = TimerState.Running(clock() + minutes * 60_000L)
    }

    fun pause() {
        val s = state
        if (s is TimerState.Running) {
            state = TimerState.Paused(maxOf(0L, s.endsAt - clock()))
        }
    }

    fun resume() {
        val s = state
        if (s is TimerState.Paused) {
            state = TimerState.Running(clock() + s.remainingMs)
        }
    }

    /** Abandon the whole set: back to an unstarted focus session, counter and task cleared. */
    fun stop() {
        state = TimerState.Idle
        phase = Phase.Focus
        completedFocus = 0
        attach(null)
    }

    /** End the current interval now and move to the next, exactly as if it had run out. */
    fun skip() {
        state = TimerState.Running(clock())
        catchUp()
    }

    /** Attach the item a focus session is about, or clear it. Only the id and title are kept. */
    fun attach(item: Item?) {
        taskId = item?.id
        taskTitle = item?.title
    }

    fun apply(new: PomodoroSettings) {
        settings = new
        saveSetting(SETTINGS_KEY, settingsJson.encodeToString(new))
    }

    /** Read persisted settings. Called once per process, after a platform has attached a store. */
    fun reload() {
        val stored = loadSetting(SETTINGS_KEY) ?: return
        // A value written by an older build, or half-written, must not brick the timer.
        settings = runCatching { settingsJson.decodeFromString<PomodoroSettings>(stored) }
            .getOrDefault(PomodoroSettings())
    }

    /**
     * Advance past every boundary the clock has crossed, and return what expired.
     *
     * Expiry is *derived*, exactly like [remainingMs] — there is no scheduler anywhere in this app,
     * only the one-second UI loops, which stop the moment their surface goes away. Calling this
     * from those loops is what turns a lapsed end instant into a phase change.
     *
     * The loop runs at most twice by construction: a focus session auto-starts its break, but a
     * break always lands on [TimerState.Idle], which ends it. So an app left closed for three
     * hours resumes at "break finished, start your next focus" rather than silently burning
     * through six cycles.
     *
     * Idempotent — this is the only thing that mutates on expiry, so a second caller gets an empty
     * list. That matters because up to three one-second loops (popup, main window, focus screen)
     * can be driving the same shared timer at once.
     */
    fun catchUp(): List<Phase> {
        val expired = mutableListOf<Phase>()
        var next = phase
        while (true) {
            val s = state as? TimerState.Running ?: break
            if (clock() < s.endsAt) break
            val ended = phase
            expired += ended
            if (ended == Phase.Focus) {
                completedFocus++
                phase = if (completedFocus % settings.cycleLength == 0) Phase.LongBreak else Phase.ShortBreak
                // Anchored to the boundary that just passed, NOT to now: noticing ten minutes late
                // must not hand out a full fresh break.
                state = TimerState.Running(s.endsAt + settings.minutesFor(phase) * 60_000L)
            } else {
                // A break never rolls into the next focus session on its own — you have to come back.
                phase = Phase.Focus
                state = TimerState.Idle
            }
            next = phase
            if (state is TimerState.Idle) break
        }
        // One notification for the whole catch-up. Waking to a backlog of stale ones is worse
        // than a single accurate "your break is over".
        if (expired.isNotEmpty()) onPhaseEnd?.invoke(expired.last(), next)
        return expired
    }

    /** Milliseconds left, clamped at zero. Recomputed from the clock on every read. */
    fun remainingMs(): Long = when (val s = state) {
        is TimerState.Idle -> 0L
        is TimerState.Paused -> s.remainingMs
        is TimerState.Running -> maxOf(0L, s.endsAt - clock())
    }

    /** Full length of the current phase — what the dial measures against. */
    fun totalMs(): Long = settings.minutesFor(phase) * 60_000L

    val isRunning: Boolean get() = state is TimerState.Running

    /** mm:ss for display on the overlay badge and the timer row. */
    fun display(): String {
        val total = remainingMs() / 1000
        val m = total / 60
        val s = total % 60
        return "${m}:${s.toString().padStart(2, '0')}"
    }

    companion object {
        /**
         * Process-wide instance. The overlay and the main screen are different composables over
         * the same timer, and closing either must not reset it.
         */
        val shared = Pomodoro()
    }
}

/** "FOCUS" / "SHORT BREAK" / "LONG BREAK" — the strip and the focus screen both label with this. */
fun Phase.label(): String = when (this) {
    Phase.Focus -> "FOCUS"
    Phase.ShortBreak -> "SHORT BREAK"
    Phase.LongBreak -> "LONG BREAK"
}
