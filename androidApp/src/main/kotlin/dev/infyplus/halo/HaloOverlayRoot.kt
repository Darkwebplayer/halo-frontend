package dev.infyplus.halo

import android.content.Intent
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.unit.sp
import dev.infyplus.halo.ui.HaloButton
import dev.infyplus.halo.ui.HaloCard
import dev.infyplus.halo.ui.HaloPalette
import dev.infyplus.halo.ui.Mono
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.Text
import dev.infyplus.halo.ui.HaloConversation
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
 * How big the bubble is on a phone, against the design's own 92dp.
 *
 * Smaller than on desktop on purpose: this floats over whatever the user is actually using, and a
 * full-size orb covers too much of it. [OverlayService]'s collapsed window is sized to match.
 */
private const val ORB_SCALE = 0.75f

/**
 * Everything the floating overlay draws, on both of its window shapes.
 *
 * The window itself is [OverlayService]'s problem; this decides what goes in it and reports back
 * when the shape needs to change.
 *
 * @param onExpanded told when the panel opens or closes, so the service can swap the window
 *   between a small touchable rectangle and a full-screen focusable one.
 * @param onHide the user is done with the floating button altogether — long-pressed it, or used
 *   Hide in the panel's header. The service takes the window down; the flag behind it is sticky.
 * @param onMoveTo absolute screen coordinates for the window's top-left corner while dragging.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HaloOverlayRoot(
    expanded: Boolean,
    onExpanded: (Boolean) -> Unit,
    onHide: () -> Unit,
    onMoveTo: (Int, Int) -> Unit,
    windowX: () -> Int,
    windowY: () -> Int,
    api: HaloApi,
    state: HaloState = HaloState.shared,
    timer: Pomodoro = Pomodoro.shared,
) {
    // Owned here rather than inside the panel, which is what the desktop host already does. The
    // panel leaves composition every time the overlay collapses — which is every tap outside it —
    // and a conversation created in there went with it, so there was never anything to come back
    // to. Held across collapse, and persisted by the conversation itself.
    val conversation = remember(api) { HaloConversation(api, state) }
    var countdown by remember { mutableStateOf(timer.display()) }
    // Hoisted: both the expanded panel (to launch the app) and the collapsed orb (for the touch
    // slop) need it, and this is a service-hosted ComposeView, so it is the service's context.
    val context = LocalContext.current

    LaunchedEffect(timer.state) {
        state.noteTimerRunning(timer.isRunning)
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
                        state.noteUnread(it.attentionCount())
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

    // Long-pressing the orb offers to put it away rather than doing it. Hiding on the gesture alone
    // was too easy to trigger by accident, and the thing it does is not obviously undoable from
    // where it happens — the way back is a Quick Settings tile or a settings screen, neither of
    // which is on the phone's face at that moment.
    var menu by remember { mutableStateOf(false) }

    /**
     * Where the orb was standing when the hold began.
     *
     * Captured rather than read at draw time, and that is not a refinement — expanding the window
     * moves it to the top-left corner and zeroes the very coordinates the menu needs, so reading
     * [windowX] from inside the menu answers 0,0 every time and the bubble appears in the corner
     * of the screen instead of beside the orb.
     */
    var anchor by remember { mutableStateOf(0 to 0) }

    // Whatever closes the panel closes this too. It lives in the same expanded window, so a menu
    // left standing would come back the next time the panel opened, over the panel.
    LaunchedEffect(expanded) { if (!expanded) menu = false }

    if (expanded && menu) {
        BackHandler { onExpanded(false) }
        OrbMenu(
            anchorX = anchor.first,
            anchorY = anchor.second,
            onHide = {
                // Collapse first, so the panel is not left up over a window that is about to go.
                onExpanded(false)
                onHide()
            },
            onDismiss = { onExpanded(false) },
        )
        return
    }

    if (expanded) {
        // Back closes the panel rather than being swallowed by a focusable overlay.
        BackHandler { onExpanded(false) }

        // How much of the screen the keyboard is covering, right now.
        //
        // The window is already ADJUST_RESIZE and deliberately not FLAG_LAYOUT_NO_LIMITS — which
        // is what makes insets reach it at all — but nothing was consuming them, so the panel went
        // on being measured against the whole screen and the composer ended up behind the keyboard.
        val imeDp = with(LocalDensity.current) { WindowInsets.ime.getBottom(this).toDp() }

        // A ceiling, not a target: the panel wraps its content, and this is where it stops. It has
        // to exist — the thread and the alert list are lazy lists, and a lazy list measured with
        // an unbounded height throws rather than growing.
        //
        // Measured against what is actually left rather than against the screen. With the keyboard
        // up, 70% of the display is more room than there is, and the difference is exactly the part
        // that used to overflow. The floor keeps it usable on a small screen with a tall keyboard,
        // where the honest answer would be almost nothing.
        val screenDp = LocalConfiguration.current.screenHeightDp.dp
        val maxPanel = maxOf(220.dp, (screenDp - imeDp) * 0.86f)

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
                conversation = conversation,
                // Sized to its content rather than to the screen. The window and the scrim above
                // stay full-screen — they are what makes tap-outside work — but the part being
                // read and typed into is only as tall as it needs to be, and grows as the
                // conversation does. Bottom-aligned so it grows upward, away from the composer,
                // which stays where the thumb already is.
                growWithContent = true,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // Sits on top of the keyboard rather than under it. Order matters: the padding
                    // has to be applied before the height constraint, or the panel is sized for a
                    // screen it is then pushed off the bottom of.
                    .imePadding()
                    .heightIn(min = 280.dp, max = maxPanel)
                    .padding(start = 10.dp, end = 10.dp, top = 72.dp, bottom = 14.dp),
                onClose = { onExpanded(false) },
                // Collapse first, so the panel is not left up over a window that is about to go.
                onHide = { onExpanded(false); onHide() },
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
                    scale = ORB_SCALE,
                )
            }
        }
        return
    }

    // ── collapsed ────────────────────────────────────────────────────────────
    val slop = ViewConfiguration.get(context).scaledTouchSlop
    // The system's own threshold rather than a number of our own: this gesture has to feel like
    // every other long-press on the device, including on the OEMs that tune it.
    val longPress = ViewConfiguration.getLongPressTimeout().toLong()
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
                        // orb does not spring the panel open every time. A press that stayed put
                        // for longer than the system's own long-press timeout is the other
                        // gesture: put the bubble away.
                        //
                        // Both live in this one filter because they are the same gesture until the
                        // finger comes up. Compose's detectTapGestures could tell them apart, but
                        // it cannot see the raw screen coordinates the drag needs, and two
                        // detectors would fight over the down event — which is the bug the class
                        // comment above is about.
                        if (drag.wasTap(slop)) {
                            // A hold offers the menu; a tap opens the panel. Both need the
                            // full-screen window, so both go through onExpanded — and the orb's
                            // position is read *before* that call, which is the moment it is
                            // still the collapsed window's own.
                            anchor = windowX() to windowY()
                            menu = drag.wasLongPress(longPress)
                            onExpanded(true)
                        }
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
                failed = state.notice != null,
            ),
            unread = state.unread,
            resting = state.resting,
            reducedMotion = prefersReducedMotion(),
            onBadgeClick = {
                state.requestTab(dev.infyplus.halo.ui.PanelTab.Notifications)
                onExpanded(true)
            },
            scale = ORB_SCALE,
        )
    }
}

/** How wide the menu bubble is, and how much room the orb takes up beside it. */
private val MENU_WIDTH = 208.dp
private val ORB_FOOTPRINT = 114.dp

/**
 * What a hold on the orb offers: a small bubble beside it, not a sheet over everything.
 *
 * Deliberately one action and a way out. This is the answer to "get out of my way", reached from
 * the thing that is in the way — so it appears *at* that thing, and every row added to it is
 * another thing to read while whatever you were doing waits underneath.
 *
 * Placed against the orb's own window coordinates, which is why they are passed in: while this is
 * up, the window is full-screen, so nothing about the layout knows where the bubble was parked. It
 * flips to whichever side has room, and the scrim is fully transparent — the point is to keep the
 * screen behind it readable, unlike the panel, which is a thing you are meant to be looking at.
 *
 * Says how to get the button back, because the gesture that opens this is the one that used to
 * hide the orb outright, and nothing on screen at that moment explains that a tile exists.
 */
@Composable
private fun OrbMenu(anchorX: Int, anchorY: Int, onHide: () -> Unit, onDismiss: () -> Unit) {
    val density = LocalDensity.current
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    val orbX = with(density) { anchorX.toDp() }
    val orbY = with(density) { anchorY.toDp() }

    // Beside the orb, on whichever side it fits. Parked against the right edge — which is where a
    // right-handed person leaves it — there is no room to the right, so it goes left instead of
    // off-screen.
    val toTheRight = orbX + ORB_FOOTPRINT + MENU_WIDTH <= screenWidth
    val x = if (toTheRight) orbX + ORB_FOOTPRINT - 12.dp else orbX - MENU_WIDTH + 12.dp
    // Roughly level with the orb, pulled back inside the screen at both ends — the orb can sit at
    // the very top or the very bottom, and the bubble is taller than it is.
    val y = orbY - 8.dp

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    ) {
        HaloCard(
            Modifier
                .offset(
                    x = x.coerceIn(8.dp, (screenWidth - MENU_WIDTH - 8.dp).coerceAtLeast(8.dp)),
                    y = y.coerceIn(8.dp, (screenHeight - 168.dp).coerceAtLeast(8.dp)),
                )
                .width(MENU_WIDTH)
                // Its own tap handler, consuming: without one, a tap on the card falls through to
                // the scrim behind and closes the menu you were reaching into.
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Mono("FLOATING BUTTON")
            Text(
                "Hide it until you ask for it back.",
                Modifier.padding(top = 6.dp, bottom = 10.dp),
                fontSize = 12.sp,
                color = HaloPalette.navy.copy(alpha = 0.75f),
            )
            HaloButton(label = "Hide it", modifier = Modifier.fillMaxWidth(), onClick = onHide)
            Text(
                "Bring it back from the Halo tile in Quick Settings, the notification, or Settings.",
                Modifier.padding(top = 8.dp),
                fontSize = 10.sp,
                color = HaloPalette.navy.copy(alpha = 0.6f),
            )
        }
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
    private var downAt = 0L

    fun start(rawX: Float, rawY: Float, windowX: Int, windowY: Int) {
        downAt = android.os.SystemClock.uptimeMillis()
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

    /**
     * How long the finger was down. Only meaningful together with [wasTap] — a slow drag is not a
     * long press, however long it took.
     *
     * `uptimeMillis` rather than wall-clock, so a clock change mid-gesture cannot make a tap look
     * like a long press.
     */
    fun wasLongPress(timeoutMs: Long) = android.os.SystemClock.uptimeMillis() - downAt >= timeoutMs
}
