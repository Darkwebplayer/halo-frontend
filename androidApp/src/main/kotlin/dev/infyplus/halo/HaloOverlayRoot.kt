package dev.infyplus.halo

import android.content.Intent
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTapGestures
import dev.infyplus.halo.ui.HaloPanel
import dev.infyplus.halo.ui.HaloState
import dev.infyplus.halo.ui.Orb
import dev.infyplus.halo.ui.apiCatching
import dev.infyplus.halo.ui.cloudFor
import dev.infyplus.halo.ui.isConnectivity
import dev.infyplus.halo.ui.dialProgress
import dev.infyplus.halo.ui.isLate
import kotlinx.coroutines.delay
import kotlin.math.abs

/** 25 minutes as milliseconds — what the dial measures progress against. */

/**
 * Everything the floating overlay draws, on both of its window shapes.
 *
 * The window itself is [OverlayService]'s problem; this decides what goes in it and reports back
 * when the shape needs to change.
 *
 * @param onExpanded told when the panel opens or closes, so the service can swap the window
 *   between a small touchable rectangle and a full-screen focusable one.
 * @param onMoveTo absolute screen coordinates for the window's top-left corner while dragging.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HaloOverlayRoot(
    expanded: Boolean,
    onExpanded: (Boolean) -> Unit,
    onMoveTo: (Int, Int) -> Unit,
    windowX: () -> Int,
    windowY: () -> Int,
    api: HaloApi,
    state: HaloState = HaloState.shared,
    timer: Pomodoro = Pomodoro.shared,
) {
    var countdown by remember { mutableStateOf(timer.display()) }
    // Hoisted: both the expanded panel (to launch the app) and the collapsed orb (for the touch
    // slop) need it, and this is a service-hosted ComposeView, so it is the service's context.
    val context = LocalContext.current

    LaunchedEffect(timer.state) {
        state.setTimerRunning(timer.isRunning)
        timer.catchUp()
        countdown = timer.display()
        while (timer.isRunning) {
            delay(1000)
            // Ahead of re-reading isRunning, or the expiring tick is the one that gets skipped.
            timer.catchUp()
            countdown = timer.display()
        }
    }

    // The badge counts things wanting a decision; the same poll is the connectivity signal the
    // cat's face reads.
    //
    // Keyed on the device's network so regaining it restarts this immediately instead of waiting
    // out the rest of a minute — recovery was the slow half. The guard inside is for the other
    // direction: with no network there is nothing to ask, and firing a request at a 15-second
    // timeout once a minute is just a slower way of learning what we already know.
    LaunchedEffect(DeviceNetwork.available) {
        while (true) {
            if (!DeviceNetwork.available) {
                state.markOffline(true)
            } else {
                apiCatching { api.checkins() }
                    .onSuccess {
                        state.setUnread(it.attentionCount(), timerRunning = timer.isRunning)
                        state.markOffline(false)
                    }
                    .onFailure { state.markOffline(it.isConnectivity()) }
            }
            delay(60_000)
        }
    }

    // Receding while the panel is open would be wrong — it is being looked at.
    LaunchedEffect(expanded) { if (expanded) state.holdAwake() else state.wake() }

    // A tapped system notification asks for a specific item. Opening the panel is this layer's
    // job; the panel itself resolves the id once its history has loaded.
    LaunchedEffect(state.pendingScopeId) {
        if (state.pendingScopeId != null && !expanded) onExpanded(true)
    }

    if (expanded) {
        // Back closes the panel rather than being swallowed by a focusable overlay.
        BackHandler { onExpanded(false) }

        Box(
            Modifier
                .fillMaxSize()
                // Tapping outside the panel dismisses it, which is what a dialog does and what
                // makes a full-screen overlay tolerable — otherwise it eats the whole screen
                // with no obvious way out.
                .pointerInput(Unit) { detectTapGestures { onExpanded(false) } }
                .background(Color.Black.copy(alpha = 0.28f)),
        ) {
            HaloPanel(
                api = api,
                state = state,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 10.dp, end = 10.dp, top = 72.dp, bottom = 14.dp),
                onClose = { onExpanded(false) },
                onOpenApp = {
                    // Collapse first: the overlay is drawn over everything, so leaving the panel
                    // up would bury the activity it just launched.
                    onExpanded(false)
                    context.startActivity(
                        Intent(context, MainActivity::class.java)
                            // The overlay is hosted by a service, which has no task of its own to
                            // launch into. singleTop on the activity means an already-running app
                            // is brought forward rather than stacked.
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            )
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp)
                    .pointerInput(Unit) { detectTapGestures { onExpanded(false) } },
            ) {
                Orb(
                    expression = state.expression,
                    progress = dialProgress(timer.remainingMs(), timer.totalMs()),
                    showDial = timer.isRunning,
                    late = timer.phase == Phase.Focus && isLate(timer.remainingMs(), timer.totalMs()),
                    unread = state.unread,
                    reducedMotion = prefersReducedMotion(),
                )
            }
        }
        return
    }

    // ── collapsed ────────────────────────────────────────────────────────────
    val slop = ViewConfiguration.get(context).scaledTouchSlop
    // A plain holder, not remembered `var`s: those are re-initialised on every recomposition, so
    // a drag that spans one would forget where it was grabbed and the orb would jump. And not
    // mutableStateOf either — writing this on every touch event would recompose the whole orb
    // sixty times a second for values nothing draws.
    val drag = remember { DragTracker() }

    Box(
        Modifier
            .fillMaxSize()
            /*
             * Dragging is computed from RAW (screen) coordinates, not from pointer deltas.
             *
             * Compose reports deltas in window-local space, so moving the window under the finger
             * shifts the frame the next delta is measured in — a feedback loop that reads as
             * jitter or a bubble that stalls. Desktop hit this and solved it the same way, with
             * MouseInfo; on Android the raw coordinates live on MotionEvent, which means dropping
             * to pointerInteropFilter rather than using detectDragGestures.
             *
             * Tap and drag share one filter here because they must agree on the same gesture: a
             * separate `clickable` would consume the down event this needs — the bug that made
             * the desktop orb un-openable.
             */
            .pointerInteropFilter { event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        drag.start(event.rawX, event.rawY, windowX(), windowY())
                        state.wake()
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        drag.track(event.rawX, event.rawY)
                        onMoveTo(drag.targetX(event.rawX), drag.targetY(event.rawY))
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        // Only a gesture that stayed put counts as a tap, so repositioning the
                        // orb does not spring the panel open every time.
                        if (drag.wasTap(slop)) onExpanded(true)
                        true
                    }

                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Orb(
            expression = state.expression,
            progress = dialProgress(timer.remainingMs(), timer.totalMs()),
            showDial = timer.isRunning,
            late = timer.phase == Phase.Focus && isLate(timer.remainingMs(), timer.totalMs()),
            say = cloudFor(
                open = false,
                offline = state.offline,
                countdown = if (timer.isRunning) countdown else null,
                unread = state.unread,
            ),
            unread = state.unread,
            resting = state.resting,
            reducedMotion = prefersReducedMotion(),
            onBadgeClick = {
                state.requestTab(dev.infyplus.halo.ui.PanelTab.Notifications)
                onExpanded(true)
            },
        )
    }
}

/**
 * Where a drag started, so the orb follows the finger rather than snapping its corner to it.
 *
 * Plain mutable fields on purpose: this is touched on every MotionEvent and nothing draws from
 * it, so making it Compose state would recompose the orb on every frame of a drag for no reason.
 */
private class DragTracker {
    private var grabX = 0f
    private var grabY = 0f
    private var downX = 0f
    private var downY = 0f
    private var travelled = 0f

    fun start(rawX: Float, rawY: Float, windowX: Int, windowY: Int) {
        // Where inside the window it was grabbed — without this the orb jumps so its top-left
        // corner meets the finger.
        grabX = rawX - windowX
        grabY = rawY - windowY
        downX = rawX
        downY = rawY
        travelled = 0f
    }

    /** Distance from where the finger went down, which is what separates a tap from a drag. */
    fun track(rawX: Float, rawY: Float) {
        travelled = maxOf(travelled, abs(rawX - downX) + abs(rawY - downY))
    }

    fun targetX(rawX: Float) = (rawX - grabX).toInt()
    fun targetY(rawY: Float) = (rawY - grabY).toInt()

    fun wasTap(slop: Int) = travelled < slop
}
