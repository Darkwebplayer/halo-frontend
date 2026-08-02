package dev.infyplus.halo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import dev.infyplus.halo.ui.AvatarFace
import dev.infyplus.halo.ui.Expression
import dev.infyplus.halo.ui.HaloButton
import dev.infyplus.halo.ui.HaloCard
import dev.infyplus.halo.ui.HaloChip
import dev.infyplus.halo.ui.HaloPalette
import dev.infyplus.halo.ui.HaloShapes
import dev.infyplus.halo.ui.HaloState
import dev.infyplus.halo.ui.Mono
import dev.infyplus.halo.ui.ProgressDial
import dev.infyplus.halo.ui.apiCatching
import dev.infyplus.halo.ui.dialProgress
import dev.infyplus.halo.ui.isLate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Focus timer UI, in two sizes: [PomodoroStrip] for the overlay panel and [FocusScreen] for the
 * app. Both read [Pomodoro.shared] rather than owning a timer, so the same countdown is visible
 * everywhere and closing any one of them resets nothing.
 *
 * The 1s loops only refresh the label and call [Pomodoro.catchUp] — remaining time is always
 * recomputed from the clock, so a missed tick cannot make the countdown drift.
 */

/** Runs the label loop for whichever surfaces are alive, and returns the current mm:ss. */
@Composable
private fun rememberTicking(timer: Pomodoro): String {
    var label by remember { mutableStateOf(timer.display()) }
    LaunchedEffect(timer.state) {
        // Before the loop as well as inside it: this effect restarts on every state change, and a
        // timer that expired while the surface was gone must settle on the first pass.
        timer.catchUp()
        label = timer.display()
        while (timer.isRunning) {
            delay(1000)
            // Ahead of re-reading isRunning, or the tick that expires the phase is the one skipped.
            timer.catchUp()
            label = timer.display()
        }
    }
    return label
}

/** "late" is a focus-session idea — a break winding down is not a problem, so it never reddens. */
private fun Pomodoro.dialIsLate() = phase == Phase.Focus && isLate(remainingMs(), totalMs())

@Composable
private fun CycleDots(timer: Pomodoro, modifier: Modifier = Modifier) {
    // Zero after a long break rather than staying full: the set is finished, a new one starts here.
    val done = timer.completedFocus % timer.settings.cycleLength
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(timer.settings.cycleLength) { i ->
            Box(
                Modifier
                    .size(6.dp)
                    .clip(HaloShapes.Pill)
                    .background(if (i < done) HaloPalette.sun else HaloPalette.navy.copy(alpha = 0.22f)),
            )
        }
    }
}

/**
 * The quick controls in the overlay panel — enough to drive a session without opening the app.
 *
 * Sized for the panel's 388dp inner width: the label, clock and dots are intrinsically sized, the
 * task title absorbs the slack and ellipsises, and there are never more than two chips.
 */
@Composable
fun PomodoroStrip(timer: Pomodoro = Pomodoro.shared, modifier: Modifier = Modifier) {
    val label = rememberTicking(timer)
    val state = timer.state

    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state is TimerState.Idle) {
            CycleDots(timer)
            Spacer(Modifier.weight(1f))
            HaloChip("Start ${timer.phase.label().lowercase()} · ${timer.settings.minutesFor(timer.phase)}m") {
                timer.start()
            }
        } else {
            Mono(
                timer.phase.label(),
                color = if (timer.phase == Phase.Focus) HaloPalette.warm else HaloPalette.navy,
                weight = FontWeight.Bold,
            )
            Text(
                label,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = HaloPalette.ink,
            )
            CycleDots(timer)
            timer.taskTitle?.let {
                Text(
                    it,
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = HaloPalette.navy.copy(alpha = 0.7f),
                )
            } ?: Spacer(Modifier.weight(1f))

            if (state is TimerState.Running) {
                HaloChip("Pause") { timer.pause() }
            } else {
                HaloChip("Resume") { timer.resume() }
            }
            Mono("Stop", Modifier.clickable { timer.stop() }.padding(4.dp))
        }
    }
}

/**
 * The full timer: dial, phase, cycle, the task it is about, and the durations.
 *
 * The dial is the overlay's own [ProgressDial] at a larger size — it scales off its layout box, so
 * there is no second drawing of it to keep in step.
 */
@Composable
fun FocusScreen(
    api: HaloApi,
    timer: Pomodoro = Pomodoro.shared,
    modifier: Modifier = Modifier,
) {
    val label = rememberTicking(timer)
    val state = timer.state
    var picking by remember { mutableStateOf(false) }
    var tasks by remember { mutableStateOf<List<Item>>(emptyList()) }
    var loadingTasks by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            ProgressDial(
                progress = dialProgress(timer.remainingMs(), timer.totalMs()),
                modifier = Modifier.size(200.dp),
                late = timer.dialIsLate(),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AvatarFace(
                    if (timer.isRunning && timer.phase == Phase.Focus) Expression.Work else Expression.Idle,
                    Modifier.size(78.dp),
                )
                Text(
                    if (state is TimerState.Idle) "${timer.settings.minutesFor(timer.phase)}:00" else label,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = HaloPalette.ink,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Mono(
                timer.phase.label(),
                color = if (timer.phase == Phase.Focus) HaloPalette.warm else HaloPalette.navy,
                weight = FontWeight.Bold,
            )
            CycleDots(timer)
            Mono("${timer.completedFocus % timer.settings.cycleLength} of ${timer.settings.cycleLength}")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                is TimerState.Idle -> HaloButton("Start ${timer.settings.minutesFor(timer.phase)}m") { timer.start() }
                is TimerState.Running -> HaloButton("Pause") { timer.pause() }
                is TimerState.Paused -> HaloButton("Resume") { timer.resume() }
            }
            if (state !is TimerState.Idle) {
                HaloButton("Skip", filled = false) { timer.skip() }
                HaloButton("Stop", filled = false) { timer.stop() }
            }
        }

        // ── the task this session is about ────────────────────────────────────────────────────
        HaloCard(Modifier.fillMaxWidth()) {
            Mono("WORKING ON")
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    timer.taskTitle ?: "Nothing attached",
                    modifier = Modifier.weight(1f),
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (timer.taskTitle == null) HaloPalette.navy.copy(alpha = 0.55f) else HaloPalette.ink,
                )
                val taskId = timer.taskId
                if (taskId != null) {
                    // The one place the timer touches the server, and only because you asked.
                    HaloChip("Mark done") {
                        scope.launch {
                            apiCatching { api.act(taskId, "done") }
                                .onSuccess { timer.attach(null); HaloState.shared.flash(Expression.Happy, 1600) }
                                .onFailure { error = it.message ?: "Could not mark it done" }
                        }
                    }
                }
                HaloChip(if (picking) "Cancel" else "Choose") {
                    picking = !picking
                    if (picking) {
                        // Dropped before the call, not after it: keeping the previous list on
                        // screen while a refetch fails would offer items that have since been
                        // completed elsewhere. Same rule as CaptureScreen — never show stale
                        // state as if it were current.
                        tasks = emptyList()
                        error = null
                        loadingTasks = true
                        scope.launch {
                            // /plan already excludes completed items server-side; the filter is
                            // here so a task finished in this session cannot linger in the list.
                            // Blank as well as null, since an empty string is not an absent field.
                            apiCatching { api.plan() }
                                .onSuccess { p -> tasks = (p.rollover + p.today).filter { it.doneAt.isNullOrBlank() } }
                                .onFailure { error = it.message ?: "Could not load your plan" }
                            loadingTasks = false
                        }
                    }
                }
            }

            if (picking) {
                // A plain Column, NOT a LazyColumn: this screen is inside a verticalScroll, and a
                // lazy list there is measured with unbounded height, which throws. A day's plan is
                // a short list, so there is nothing to virtualise anyway.
                Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    // The list is cleared before the refetch, so without this the picker said
                    // "NOTHING ON THE PLAN" for the whole round-trip every single time.
                    if (tasks.isEmpty()) {
                        Mono(
                            when {
                                loadingTasks -> "LOADING YOUR PLAN…"
                                error != null -> "COULDN'T REACH YOUR SERVER"
                                else -> "NOTHING ON THE PLAN"
                            },
                            color = if (error != null && !loadingTasks) HaloPalette.warm else HaloPalette.navy.copy(alpha = 0.78f),
                        )
                    }
                    tasks.forEach { item ->
                        Text(
                            item.title,
                            Modifier
                                .fillMaxWidth()
                                .clickable { timer.attach(item); picking = false }
                                .padding(vertical = 7.dp),
                            fontSize = 13.sp,
                            color = HaloPalette.ink,
                        )
                    }
                }
            }
        }

        error?.let { Mono(it, color = HaloPalette.warm, weight = FontWeight.Bold) }

        // ── durations ─────────────────────────────────────────────────────────────────────────
        HaloCard(Modifier.fillMaxWidth()) {
            Mono("INTERVALS")
            val s = timer.settings
            Stepper("Focus", s.focusMinutes, "m") { timer.apply(s.copy(focusMinutes = it)) }
            Stepper("Short break", s.shortBreakMinutes, "m") { timer.apply(s.copy(shortBreakMinutes = it)) }
            Stepper("Long break", s.longBreakMinutes, "m") { timer.apply(s.copy(longBreakMinutes = it)) }
            Stepper("Long break every", s.cycleLength, "", min = 2, max = 8) {
                timer.apply(s.copy(cycleLength = it))
            }
        }
    }
}

/** A labelled +/- row. Clamped so a zero-length phase — which would expire instantly — is unreachable. */
@Composable
private fun Stepper(
    label: String,
    value: Int,
    suffix: String,
    min: Int = 1,
    max: Int = 120,
    onChange: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), fontSize = 13.sp, color = HaloPalette.ink)
        HaloChip("−") { onChange((value - 1).coerceAtLeast(min)) }
        Text(
            "$value$suffix",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = HaloPalette.ink,
        )
        HaloChip("+") { onChange((value + 1).coerceAtMost(max)) }
    }
}

/**
 * Point the timer at the platform's notifier, and load persisted settings.
 *
 * Called once per process from each host. Uses [notifyLocally] rather than `Notifications.fireNow`
 * so a phase ending is never reported to the server — see that function for why.
 */
fun wirePomodoro(timer: Pomodoro = Pomodoro.shared) {
    timer.reload()
    timer.onPhaseEnd = { ended, next ->
        val taskId = timer.taskId
        notifyLocally(
            Scheduled(
                // The prefix can never collide with a server id, which is what keeps a local
                // event out of anything keyed on notification ids.
                id = "pomodoro-${ended.name}",
                itemId = taskId,
                kind = "pomodoro",
                at = null,
                title = if (ended == Phase.Focus) "Focus done" else "Break over",
                body = when {
                    ended == Phase.Focus && next == Phase.LongBreak ->
                        "Long break — ${timer.settings.longBreakMinutes} minutes."
                    ended == Phase.Focus -> "Break started — ${timer.settings.shortBreakMinutes} minutes."
                    else -> "Ready when you are."
                },
                // Android renders these as real buttons routed through ActionReceiver. Desktop's
                // tray toasts cannot carry them, which is why the same action also lives in the UI.
                actions = if (ended == Phase.Focus && taskId != null) {
                    listOf(ScheduledAction("Mark done", "done"))
                } else {
                    emptyList()
                },
            ),
        )
        HaloState.shared.flash(
            if (ended == Phase.Focus) Expression.Happy else Expression.Work,
            2600,
        )
    }
}
