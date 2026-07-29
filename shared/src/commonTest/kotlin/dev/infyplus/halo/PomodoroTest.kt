package dev.infyplus.halo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/** A clock we can move by hand, so elapsed time is asserted rather than waited for. */
private class FakeClock(var now: Long = 1_000_000L) {
    fun advance(ms: Long) { now += ms }
}

class PomodoroTest {

    @Test
    fun startsAtFullDuration() {
        val clock = FakeClock()
        val p = Pomodoro(clock::now)
        p.start(25)
        assertTrue(p.isRunning)
        assertEquals(25 * 60_000L, p.remainingMs())
        assertEquals("25:00", p.display())
    }

    /**
     * The slice's second test: closing and reopening the overlay mid-timer must show the
     * correct remaining time. Nothing ticks while the UI is gone, so remaining has to be
     * derived from the clock — this fails if the timer ever decrements a counter instead.
     */
    @Test
    fun remainingSurvivesTheUiGoingAway() {
        val clock = FakeClock()
        val p = Pomodoro(clock::now)
        p.start(25)

        clock.advance(7 * 60_000L + 30_000L) // 7m30s pass with no UI on screen

        assertEquals("17:30", p.display())
        assertEquals(17 * 60_000L + 30_000L, p.remainingMs())
    }

    @Test
    fun pauseFreezesRemainingAndResumeContinuesFromIt() {
        val clock = FakeClock()
        val p = Pomodoro(clock::now)
        p.start(10)

        clock.advance(60_000L)
        p.pause()
        assertFalse(p.isRunning)

        clock.advance(5 * 60_000L) // time passes while paused; must not count down
        assertEquals("9:00", p.display())

        p.resume()
        assertTrue(p.isRunning)
        clock.advance(60_000L)
        assertEquals("8:00", p.display())
    }

    @Test
    fun neverGoesNegativeOnceElapsed() {
        val clock = FakeClock()
        val p = Pomodoro(clock::now)
        p.start(1)
        clock.advance(10 * 60_000L)
        assertEquals(0L, p.remainingMs())
        assertEquals("0:00", p.display())
    }

    @Test
    fun stopResetsToIdle() {
        val clock = FakeClock()
        val p = Pomodoro(clock::now)
        p.start(25)
        p.stop()
        assertEquals(TimerState.Idle, p.state)
        assertEquals(0L, p.remainingMs())
    }

    // ── phases ────────────────────────────────────────────────────────────────────────────────

    /** A break you have to remember to start is a break you skip. */
    @Test
    fun finishingAFocusSessionStartsTheBreakByItself() {
        val clock = FakeClock()
        val p = Pomodoro(clock::now)
        p.start()
        clock.advance(25 * 60_000L)

        assertEquals(listOf(Phase.Focus), p.catchUp())
        assertEquals(Phase.ShortBreak, p.phase)
        assertTrue(p.isRunning)
        assertEquals(1, p.completedFocus)
    }

    /**
     * The opposite rule, and the one most likely to be "helpfully" made symmetric later: a break
     * ending must NOT roll into the next focus session. Coming back to find you are twenty minutes
     * into work you never started is how a timer loses your trust.
     */
    @Test
    fun aBreakEndingDoesNotStartTheNextFocusSession() {
        val clock = FakeClock()
        val p = Pomodoro(clock::now)
        p.start()
        clock.advance(25 * 60_000L)
        p.catchUp()

        clock.advance(5 * 60_000L)
        assertEquals(listOf(Phase.ShortBreak), p.catchUp())
        assertEquals(TimerState.Idle, p.state)
        assertEquals(Phase.Focus, p.phase)
    }

    /**
     * The break is anchored to the boundary that passed, not to now. Anchoring to the clock would
     * silently hand a full fresh break to anyone who glanced at their phone ten minutes late.
     */
    @Test
    fun theBreakIsAnchoredToTheBoundaryNotToWhenYouLooked() {
        val clock = FakeClock()
        val p = Pomodoro(clock::now)
        p.start()
        clock.advance(26 * 60_000L) // a minute past the end of the focus session

        p.catchUp()
        assertEquals(Phase.ShortBreak, p.phase)
        assertEquals(4 * 60_000L, p.remainingMs()) // 5-minute break, one minute already gone
    }

    @Test
    fun everyFourthFocusSessionEarnsTheLongBreak() {
        val clock = FakeClock()
        val p = Pomodoro(clock::now)
        val seen = mutableListOf<Phase>()

        repeat(4) {
            p.start()
            clock.advance(25 * 60_000L)
            p.catchUp()
            seen += p.phase
            // Sit out the break so the next focus session starts clean.
            clock.advance(p.remainingMs())
            p.catchUp()
        }

        assertEquals(
            listOf(Phase.ShortBreak, Phase.ShortBreak, Phase.ShortBreak, Phase.LongBreak),
            seen,
        )
        assertEquals(4, p.completedFocus)
    }

    /**
     * A laptop asleep for three hours must not silently burn through six cycles. The catch-up
     * stops at the first break that ends, because a break never auto-starts the next focus.
     */
    @Test
    fun catchingUpAfterALongAbsenceStopsAtTheEndOfTheBreak() {
        val clock = FakeClock()
        val p = Pomodoro(clock::now)
        p.start()
        clock.advance(3 * 60 * 60_000L)

        assertEquals(listOf(Phase.Focus, Phase.ShortBreak), p.catchUp())
        assertEquals(TimerState.Idle, p.state)
        assertEquals(Phase.Focus, p.phase)
        assertEquals(1, p.completedFocus)
        // Idempotent: three one-second loops drive the same shared timer, and the second and third
        // callers must find nothing left to do rather than advancing it again.
        assertEquals(emptyList(), p.catchUp())
        assertEquals(1, p.completedFocus)
    }

    /** A paused timer has no end instant to pass, so walking away cannot expire it. */
    @Test
    fun pausedTimersNeverExpire() {
        val clock = FakeClock()
        val p = Pomodoro(clock::now)
        p.start()
        clock.advance(60_000L)
        p.pause()
        clock.advance(60 * 60_000L)

        assertEquals(emptyList(), p.catchUp())
        assertEquals(Phase.Focus, p.phase)
        assertEquals(24 * 60_000L, p.remainingMs())
    }

    /** One notification per catch-up, naming the last boundary — not one per phase crossed. */
    @Test
    fun aLongAbsenceRaisesOneNotificationNotABacklog() {
        val clock = FakeClock()
        val p = Pomodoro(clock::now)
        val fired = mutableListOf<Pair<Phase, Phase>>()
        p.onPhaseEnd = { ended, next -> fired += ended to next }

        p.start()
        clock.advance(3 * 60 * 60_000L)
        p.catchUp()

        assertEquals(listOf(Phase.ShortBreak to Phase.Focus), fired)
    }

    @Test
    fun skipEndsThePhaseWithoutWaiting() {
        val clock = FakeClock()
        val p = Pomodoro(clock::now)
        p.start()
        p.skip()
        assertEquals(Phase.ShortBreak, p.phase)
        assertEquals(5 * 60_000L, p.remainingMs())
    }

    /** The dial measures against the phase that is running, not against a fixed 25 minutes. */
    @Test
    fun totalTracksTheCurrentPhase() {
        val clock = FakeClock()
        val p = Pomodoro(clock::now)
        assertEquals(25 * 60_000L, p.totalMs())
        p.start()
        clock.advance(25 * 60_000L)
        p.catchUp()
        assertEquals(5 * 60_000L, p.totalMs())
    }

    @Test
    fun stoppingAbandonsTheWholeSetIncludingTheAttachedTask() {
        val clock = FakeClock()
        val p = Pomodoro(clock::now)
        p.attach(Item(id = "a1", kind = "task", title = "Ship the deck", createdAt = "now"))
        p.start()
        clock.advance(25 * 60_000L)
        p.catchUp()

        p.stop()
        assertEquals(Phase.Focus, p.phase)
        assertEquals(0, p.completedFocus)
        assertNull(p.taskId)
        assertNull(p.taskTitle)
    }
}

/**
 * Settings persistence, tested through the encoding only.
 *
 * Deliberately never calls [loadSetting]/[saveSetting]: on the JVM those write to the developer's
 * real user preferences, so a test that exercised them would leave a mess outside the build
 * directory. The platform actuals are three lines each; the format is the part worth pinning.
 */
class PomodoroSettingsTest {

    @Test
    fun settingsSurviveARoundTrip() {
        val json = Json { ignoreUnknownKeys = true }
        val original = PomodoroSettings(focusMinutes = 50, shortBreakMinutes = 8, cycleLength = 3)
        assertEquals(original, json.decodeFromString<PomodoroSettings>(json.encodeToString(original)))
    }

    /** A value written by a newer build must not brick the timer for an older one. */
    @Test
    fun anUnknownFieldIsIgnoredRatherThanFatal() {
        val json = Json { ignoreUnknownKeys = true }
        val stored = """{"focusMinutes":30,"tickSound":"chime"}"""
        assertEquals(30, json.decodeFromString<PomodoroSettings>(stored).focusMinutes)
    }
}

/**
 * The invariant behind [notifyLocally]: a pomodoro phase ending is shown to the user but never
 * reported to the server. Routing it through `Notifications.fireNow` instead would write a row
 * into the check-in history and inflate the orb's unread badge, which is derived from that count.
 */
class LocalNotificationTest {

    private class Recorder : Notifier {
        val shown = mutableListOf<Scheduled>()
        override fun arm(items: List<Scheduled>) = Unit
        override fun fireNow(item: Scheduled) { shown += item }
        override fun cancelAll() = Unit
    }

    @Test
    fun aPhaseEndIsShownButNeverReported() {
        val recorder = Recorder()
        var reported = false
        Notifications.impl = recorder
        Notifications.onFired = { reported = true }
        try {
            notifyLocally(
                Scheduled(
                    id = "pomodoro-Focus",
                    itemId = null,
                    kind = "pomodoro",
                    title = "Focus done",
                    body = "Break started.",
                ),
            )
            assertEquals(1, recorder.shown.size)
            assertFalse(reported)
        } finally {
            Notifications.impl = null
            Notifications.onFired = null
        }
    }
}
