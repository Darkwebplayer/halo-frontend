package dev.infyplus.halo

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import dev.infyplus.halo.ui.HaloConversation
import dev.infyplus.halo.ui.HaloPanel
import dev.infyplus.halo.ui.HaloState
import dev.infyplus.halo.ui.HaloTheme
import dev.infyplus.halo.ui.Orb
import dev.infyplus.halo.ui.PanelTab
import dev.infyplus.halo.ui.apiCatching
import dev.infyplus.halo.ui.cloudFor
import dev.infyplus.halo.ui.dialProgress
import dev.infyplus.halo.ui.isConnectivity
import dev.infyplus.halo.ui.isLate
import kotlinx.coroutines.delay
import java.awt.Color as AwtColor
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.image.BufferedImage

// The collapsed window has to hold more than the orb: the drawing bleeds past it so the excited
// hop and the ground shadow are not clipped, and the speech cloud and badge sit outside it too.
// AWT does not hit-test alpha, so this whole rectangle is clickable — hence no bigger than needed.
private val BUBBLE_SIZE = DpSize(150.dp, 160.dp)
private val PANEL_SIZE = DpSize(420.dp, 700.dp)

/** One place every unexpected failure is written, so "it just closed" stops being the report. */
internal fun logCrash(where: String, error: Throwable) {
    System.err.println("[halo] unhandled failure in $where — the app is staying up")
    error.printStackTrace()
}

/**
 * Keep the window alive when composition throws.
 *
 * Compose Desktop's default handler shows a modal dialog and then sends the window a CLOSING event,
 * so a single bad frame — a stale class, a null nobody expected, a transient state mismatch — takes
 * the whole assistant down. Halo is a tray app that is supposed to be there all day; a frame that
 * failed to render is not worth that. This logs and swallows, and the next frame recomposes.
 *
 * Deliberately not silent: it prints, and the cat goes Dead, so a persistent fault is visible
 * rather than looking like the app quietly doing nothing.
 */
@OptIn(ExperimentalComposeUiApi::class)
private val KeepRunning = WindowExceptionHandlerFactory { window ->
    WindowExceptionHandler { error ->
        logCrash("window '${window.name}'", error)
        HaloState.shared.flash(dev.infyplus.halo.ui.Expression.Dead, 3000)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // Nothing above the UI catches these otherwise: AWT prints the stack trace, kills the event
    // thread and starts a fresh one, which leaves a window that is up but no longer painting.
    Thread.setDefaultUncaughtExceptionHandler { thread, error -> logCrash(thread.name, error) }

    // Read before any composition: the setup gate below is decided on the very first frame, and
    // the JVM store needs no attach step. Guarded because an unreadable preferences store must
    // land on the setup screen, not on a stack trace before a window exists.
    runCatching { Config.load() }.onFailure { logCrash("Config.load", it) }

    // So losing wifi greys the cat out in seconds rather than after a request times out.
    runCatching { startNetworkWatch() }.onFailure { logCrash("startNetworkWatch", it) }

    application {
        CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides KeepRunning) {
            Halo()
        }
    }
}

/**
 * Desktop counterpart to the Android floating bubble — the same idea, not a different product:
 * a small always-on-top circle that follows you across apps and opens the panel on click.
 *
 * It is one window that changes size, rather than two windows, so dragging the bubble also
 * decides where the panel appears.
 *
 * The tray icon stays as a second way in (and the only way to quit) for when the bubble has
 * been dragged somewhere awkward or is hidden.
 *
 * Split out of [main] so the whole application sits inside the [KeepRunning] handler — the
 * CompositionLocal has to be provided from a composable, and this is what it wraps.
 */
@Composable
private fun ApplicationScope.Halo() {
    val timer = Pomodoro.shared
    val halo = HaloState.shared
    // Keyed on the credentials so saving new ones in Settings rebuilds this and everything
    // remembered from it (the conversation) and keyed on it (the effects below).
    val api = remember(Config.baseUrl, Config.authToken) { HaloApi(Config.baseUrl, Config.authToken) }

    // One conversation for both panels. The popup and the main window's Assistant tab are two
    // renderings of the same assistant; letting each remember its own would give you two
    // transcripts over one HaloState, and whichever you looked at last would seem to have amnesia.
    val conversation = remember(api) { HaloConversation(api, halo) }

    var expanded by remember { mutableStateOf(false) }
    var showApp by remember { mutableStateOf(false) }
    // Which tab the app window opens on. Set just before showing it, so "Open Halo" with alerts
    // waiting lands on the assistant instead of on Today with a number in the tray.
    var openAppOn by remember { mutableStateOf(AppSection.Today) }
    var badge by remember { mutableStateOf("J") }
    val unread = halo.unread

    // The badge counts unanswered notifications, and the same poll doubles as the connectivity
    // signal the cat's face reads: whether *our* calls are working, which is not the same
    // question as whether there is wifi.
    // Also keyed on the device's network, so regaining it re-checks the server at once rather than
    // waiting out the rest of the minute. The guard inside covers the opposite edge: with no
    // network there is nothing to ask, and a doomed request per minute only burns a timeout.
    LaunchedEffect(api, DeviceNetwork.available) {
        while (true) {
            if (!DeviceNetwork.available) {
                halo.markOffline(true)
            } else {
                apiCatching { api.checkins() }
                    .onSuccess { list ->
                        halo.noteUnread(list.attentionCount())
                        halo.markOffline(false)
                    }
                    .onFailure { halo.markOffline(it.isConnectivity()) }
            }
            delay(60_000)
        }
    }

    // The expression follows the timer whenever nothing is waiting on an answer.
    LaunchedEffect(timer.state) { halo.noteTimerRunning(timer.isRunning) }

    // Receding while the panel is open would be wrong — you are looking straight at it. Closing
    // restarts the idle countdown rather than leaving it stuck bright.
    LaunchedEffect(expanded) { if (expanded) halo.holdAwake() else halo.wake() }

    // A running timer outranks the unread count — same precedence as Android.
    LaunchedEffect(timer.state, unread) {
        while (true) {
            // Ahead of reading isRunning, or the tick that ends a phase is the one that gets
            // skipped and the countdown sits at 0:00 until something else nudges it.
            timer.catchUp()
            badge = when {
                timer.isRunning -> timer.display()
                unread > 0 -> unread.toString()
                else -> "J"
            }
            if (!timer.isRunning) break
            delay(1000)
        }
    }

    // Whether the bubble is on screen at all — the same rule Android's overlay service applies, from
    // the same shared function, so "only when needed" and a dismissal mean the same thing on both.
    val bubbleVisible = orbVisible(
        always = Config.orbAlways,
        hidden = Config.orbHidden,
        unread = unread,
        headsUp = halo.headsUp != null,
        timerRunning = timer.isRunning,
        open = expanded,
    )

    DisposableEffect(Unit) {
        // The hotkey is an explicit request for the bubble, so it overrides a dismissal — unlike a
        // notification arriving, which does not.
        GlobalHotkey.register { expanded = true; Config.saveOrbHidden(false) }
        onDispose { GlobalHotkey.unregister() }
    }

    // Reminders are scheduled in-process here: the app runs persistently as a tray app, so
    // there is no process-death problem to solve as there is on Android.
    val trayState = rememberTrayState()
    val appScope = rememberCoroutineScope()
    // No longer keyed on api: reportFiredTo reads the credentials at firing time, so changing
    // servers in Settings needs nothing rewired here.
    DisposableEffect(Unit) {
        Notifications.impl = DesktopNotifier
        DesktopNotifier.send = { title, body ->
            trayState.sendNotification(Notification(title, body, Notification.Type.Info))
        }
        // Let the cat react. It no longer forces the bubble back on screen: a fired notification
        // raises `unread` and `headsUp`, which `orbVisible` already answers in "only when needed"
        // mode — and if the bubble was dismissed outright, overriding that would make the dismissal
        // a snooze.
        DesktopNotifier.onFired = {
            halo.wake()
            halo.flash(dev.infyplus.halo.ui.Expression.Happy, 2600)
        }
        // Delivery is local, so the server only learns what was actually shown by being told.
        reportFiredTo(appScope)
        // Phase-end toasts and persisted durations. Goes through notifyLocally, so unlike the
        // line above it never reports anything to the server.
        wirePomodoro(timer)
        onDispose {
            DesktopNotifier.cancelAll()
            Notifications.onFired = null
        }
    }

    // Cadence is shared with Android now — see Sync.EVERY_MILLIS for why it is a minute.
    LaunchedEffect(Unit) { Sync.loop() }

    Tray(
        state = trayState,
        icon = remember(badge) { badgeIcon(badge).toPainter() },
        tooltip = buildString {
            append(if (timer.isRunning) "Focus: $badge" else "Halo")
            append(if (GlobalHotkey.registered) "  (Ctrl/Cmd+Shift+J)" else "  — hotkey unavailable")
        },
        // Clicking the tray when the icon is showing a count should go to the thing being counted.
        // It used to open on whatever tab was last looked at, so the badge pointed at something
        // the click did not take you to.
        onAction = {
            Config.saveOrbHidden(false)
            if (unread > 0) halo.requestTab(PanelTab.Notifications)
            expanded = true
        },
        menu = {
            Item("Open Halo") { openAppOn = if (unread > 0) AppSection.Assistant else AppSection.Today; showApp = true }
            // Persisted now, and shared with Android: the tray is desktop's equivalent of the Quick
            // Settings tile, and like it, it is the way back after the bubble has been dismissed.
            Item(if (Config.orbHidden) "Show bubble" else "Hide bubble") {
                Config.saveOrbHidden(!Config.orbHidden)
            }
            Item("Quit") { exitApplication() }
        },
    )

    // Below the Tray so the application scope still has something keeping it alive, and above
    // everything else so one return covers the banner, the app window and the bubble.
    //
    // Closing quits rather than hiding: the return below means the tray's "Open Halo" window is
    // never composed while unconfigured, so a merely-hidden setup window would strand the app in
    // the tray with no way back to it.
    if (!Config.isConfigured) {
        Window(
            onCloseRequest = ::exitApplication,
            state = rememberWindowState(size = DpSize(560.dp, 520.dp)),
            title = "Halo — Setup",
        ) {
            HaloTheme { Surface(Modifier.fillMaxSize()) { SettingsScreen() } }
        }
        return
    }

    // Clicking the banner is an explicit ask for the panel, so like the hotkey it overrides a
    // dismissal. The banner itself keeps showing regardless — it is a card that announces something
    // that just fired, not the permanent bubble.
    BannerWindow(api = api, onOpenPanel = { Config.saveOrbHidden(false); expanded = true })

    // Declared above the `hidden` early return on purpose: the bubble and the app window are
    // independent surfaces, and hiding the bubble must not take the window down with it.
    //
    // Closing it only hides it. The Tray alone keeps the application scope alive — which is why
    // the early return below does not quit either — so exitApplication stays a tray-only action.
    if (showApp) {
        Window(
            onCloseRequest = { showApp = false },
            state = rememberWindowState(size = DpSize(920.dp, 720.dp)),
            title = "Halo",
        ) {
            App(api = api, conversation = conversation, initialSection = openAppOn)
        }
    }

    if (!bubbleVisible) return

    val state = rememberWindowState(
        size = BUBBLE_SIZE,
        position = WindowPosition(Alignment.CenterEnd),
    )

    // Where the bubble sat before it was opened, so closing returns it there.
    var bubbleAnchor by remember { mutableStateOf<WindowPosition.Absolute?>(null) }
    // Cursor offset within the bubble at the moment a drag starts.
    var grabX by remember { mutableStateOf(0f) }
    var grabY by remember { mutableStateOf(0f) }

    // Resizing the same window keeps the bubble's position, so the panel opens where the bubble
    // was rather than jumping to the centre of the screen. A bubble parked against an edge would
    // push the much larger panel off-screen, so the position is pulled back inside the visible
    // area — and that shift has to be undone on close, or the bubble creeps inward every time.
    LaunchedEffect(expanded) {
        val current = state.position as? WindowPosition.Absolute

        if (expanded) {
            if (current != null) bubbleAnchor = current
            state.size = PANEL_SIZE
            if (current != null) state.position = clampToScreen(current, PANEL_SIZE)
        } else {
            state.size = BUBBLE_SIZE
            // The bubble always returns to where it was put. Moving the panel is a temporary
            // thing you do while reading it; it should not relocate the bubble afterwards.
            val target = bubbleAnchor ?: current
            if (target != null) state.position = clampToScreen(target, BUBBLE_SIZE)
        }
    }

    Window(
        onCloseRequest = { expanded = false },
        state = state,
        title = "Halo",
        alwaysOnTop = true,
        undecorated = true,   // transparency requires this
        transparent = true,   // so the orb reads as a character, not a square
        resizable = false,
    ) {
        if (expanded) {
            Column(Modifier.fillMaxSize()) {
                GlobalHotkey.failure?.let {
                    Text(
                        "Ctrl/Cmd+Shift+J is off: $it",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Box(Modifier.fillMaxSize()) {
                    // The orb straddles the panel's top edge — which is what the 56dp of top
                    // padding inside HaloPanel is reserving room for.
                    HaloPanel(
                        api = api,
                        state = halo,
                        conversation = conversation,
                        modifier = Modifier.fillMaxSize().padding(top = 62.dp),
                        onClose = { expanded = false },
                        // Collapse first: the window is about to stop being composed entirely.
                        onHide = { expanded = false; Config.saveOrbHidden(true) },
                        // The popup stays open behind it — the two are independent surfaces here,
                        // unlike Android where the overlay would sit on top of the app.
                        onOpenApp = { showApp = true },
                    )
                    // The orb is both the drag handle for a title-bar-less panel and the way to
                    // close it. WindowDraggableArea cannot do that — it consumes the whole
                    // gesture, so the orb could be dragged but never tapped. This is the same
                    // explicit drag+tap pair the collapsed bubble uses.
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = {
                                        val cursor = MouseInfo.getPointerInfo()?.location
                                        val at = state.position as? WindowPosition.Absolute
                                        if (cursor != null && at != null) {
                                            grabX = cursor.x - at.x.value
                                            grabY = cursor.y - at.y.value
                                        }
                                    },
                                ) { change, _ ->
                                    change.consume()
                                    val cursor = MouseInfo.getPointerInfo()?.location
                                        ?: return@detectDragGestures
                                    state.position = clampToScreen(
                                        WindowPosition((cursor.x - grabX).dp, (cursor.y - grabY).dp),
                                        PANEL_SIZE,
                                    )
                                }
                            }
                            .pointerInput(Unit) { detectTapGestures { expanded = false } },
                    ) {
                        Orb(
                            expression = halo.expression,
                            progress = dialProgress(timer.remainingMs(), timer.totalMs()),
                            showDial = timer.isRunning,
                            late = timer.phase == Phase.Focus && isLate(timer.remainingMs(), timer.totalMs()),
                            unread = halo.unread,
                        )
                    }
                }
            }
        } else {
            // Drag and tap are handled explicitly rather than via WindowDraggableArea +
            // clickable: `clickable` consumes the pointer-down that the draggable area needs,
            // so the two together produce a bubble that opens but cannot be moved.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Dragging is computed from the cursor's SCREEN position, not from pointer
                    // deltas. Deltas are reported in window-local coordinates, so moving the
                    // window shifts the frame the next delta is measured in — a feedback loop
                    // that reads as jitter. Screen coordinates have no such loop.
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                halo.wake()
                                val cursor = MouseInfo.getPointerInfo()?.location
                                val at = state.position as? WindowPosition.Absolute
                                if (cursor != null && at != null) {
                                    // Where inside the bubble it was grabbed, so it does not
                                    // jump to align its corner with the cursor.
                                    grabX = cursor.x - at.x.value
                                    grabY = cursor.y - at.y.value
                                }
                            },
                        ) { change, _ ->
                            change.consume()
                            val cursor = MouseInfo.getPointerInfo()?.location ?: return@detectDragGestures
                            state.position = clampToScreen(
                                WindowPosition((cursor.x - grabX).dp, (cursor.y - grabY).dp),
                                BUBBLE_SIZE,
                            )
                        }
                    }
                    // Separate detector: a drag never resolves as a tap, so the bubble does
                    // not spring open every time it is repositioned.
                    .pointerInput(Unit) {
                        detectTapGestures { halo.wake(); expanded = true }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Orb(
                    expression = halo.expression,
                    progress = dialProgress(timer.remainingMs(), timer.totalMs()),
                    showDial = timer.isRunning,
                    late = timer.phase == Phase.Focus && isLate(timer.remainingMs(), timer.totalMs()),
                    say = cloudFor(
                        open = false,
                        offline = halo.offline,
                        countdown = if (timer.isRunning) badge else null,
                        unread = halo.unread,
                        failed = halo.notice != null,
                    ),
                    unread = halo.unread,
                    resting = halo.resting,
                    reducedMotion = prefersReducedMotion(),
                    onBadgeClick = {
                        halo.requestTab(PanelTab.Notifications)
                        expanded = true
                    },
                )
            }
        }
    }
}

/**
 * Pull a window back inside the visible desktop.
 *
 * Uses the screen the window is currently on, so dragging the bubble to a second monitor and
 * opening it there does not yank the panel back to the primary display. Screen insets are
 * subtracted, keeping the panel clear of the menu bar, Dock and taskbar.
 *
 * AWT reports these bounds in logical points, which is the same unit Compose Desktop uses for
 * window position and size, so no density conversion is involved.
 *
 * Every AWT call here can fail — `HeadlessException`, or a display that was unplugged between the
 * drag starting and this running. None of that is worth a crash: the fallback is the position the
 * caller asked for, which is at worst a window slightly off the edge of a screen the user is
 * already rearranging.
 */
private fun clampToScreen(position: WindowPosition.Absolute, size: DpSize): WindowPosition =
    runCatching { clampToScreenOrThrow(position, size) }
        .getOrElse {
            logCrash("clampToScreen", it)
            position
        }

private fun clampToScreenOrThrow(position: WindowPosition.Absolute, size: DpSize): WindowPosition {
    val screens = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
    val x = position.x.value
    val y = position.y.value

    val config = screens
        .map { it.defaultConfiguration }
        .firstOrNull { it.bounds.contains(x.toInt(), y.toInt()) }
        ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration

    val bounds = config.bounds
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)

    val minX = (bounds.x + insets.left).toFloat()
    val minY = (bounds.y + insets.top).toFloat()
    // coerceAtLeast(minX) guards the case where the window is wider than the screen —
    // otherwise max would fall below min and coerceIn would throw.
    val maxX = (bounds.x + bounds.width - insets.right - size.width.value).coerceAtLeast(minX)
    val maxY = (bounds.y + bounds.height - insets.bottom - size.height.value).coerceAtLeast(minY)

    return WindowPosition(x.coerceIn(minX, maxX).dp, y.coerceIn(minY, maxY).dp)
}

/** Tray icons are AWT images, so the badge is drawn rather than composed. */
internal fun badgeIcon(label: String): BufferedImage {
    val size = 32
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

    g.color = AwtColor(103, 80, 164) // matches the bubble
    g.fillOval(0, 0, size, size)

    // A countdown like "24:31" needs a smaller face than a single digit.
    g.color = AwtColor.WHITE
    g.font = Font(Font.SANS_SERIF, Font.BOLD, if (label.length > 3) 9 else 15)
    val metrics = g.fontMetrics
    val x = (size - metrics.stringWidth(label)) / 2
    val y = (size - metrics.height) / 2 + metrics.ascent
    g.drawString(label, x, y)

    g.dispose()
    return image
}
