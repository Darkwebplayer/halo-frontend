package dev.infyplus.halo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import dev.infyplus.halo.ui.HaloState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Hosts the always-available floating assistant.
 *
 * A foreground service, not a bare window: Android kills background processes freely, and the
 * whole promise here is that the orb is there when the app is not.
 *
 * ## Why the window has two shapes
 *
 * Collapsed, it must be small — a full-screen overlay would swallow every touch on whatever is
 * behind it. `FLAG_NOT_TOUCHABLE` is all-or-nothing and the per-region workaround
 * (`setTouchableInsets(TOUCHABLE_INSETS_REGION)`) is `@hide` and non-SDK-blocklisted from API 28,
 * so there is no supported way to be touchable in one part of a window and not another.
 *
 * Expanded, it must be full-screen and focusable: the panel has a text field, and a dialog you
 * can dismiss by tapping outside needs to receive those taps. On Android 12+ this is also the
 * safer shape — touches that pass *through* an overlay above the opacity threshold are discarded
 * outright, so a window that consumes what it covers avoids the problem rather than fighting it.
 *
 * The two shapes are swapped at the moment the panel opens or closes, never per frame.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var view: ComposeView? = null

    /**
     * One host per window, created with the window and thrown away with it.
     *
     * It used to be a single instance reused for the life of the service, which was fine while the
     * window was created once and never removed. It is not fine now that the orb comes and goes:
     * [OverlayHost.detach] drives its `LifecycleRegistry` to DESTROYED, and a `LifecycleRegistry`
     * has no way back up from there — the next `attach` threw out of `performRestore`, which is a
     * crash in a foreground service, which is the whole app. A lifecycle is not reusable, so this
     * does not try to reuse one.
     */
    private var host: OverlayHost? = null
    private lateinit var params: WindowManager.LayoutParams
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // The same process-wide singletons the composables default to. Named here because the window
    // and the notification are decided outside any composition and still have to agree with it.
    private val state = HaloState.shared
    private val timer = Pomodoro.shared

    /** Compose reads this; the window layout follows it. */
    private var expanded by mutableStateOf(false)

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Undone in [onDestroy]; a leaked observer outlives the service and keeps the process busy. */
    private var snapshotPump: androidx.compose.runtime.snapshots.ObserverHandle? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // First, so its crash logger is installed before anything below can fail. The service is
        // already alive for the overlay, which also makes it the natural place to keep the armed
        // schedule fresh and to evaluate weather conditions. Alarms themselves are held by the OS,
        // so nothing is lost if this service is killed — it just stops re-syncing.
        AndroidNotifier.attach(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Android 12+ refuses a foreground start from the background outright
        // (ForegroundServiceStartNotAllowedException). Without a foreground notification this
        // service will be killed shortly anyway, so bow out rather than crash — PermissionGate
        // starts us again on the next resume, from the foreground, where it is allowed.
        val foreground = runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }
        if (foreground.isFailure) {
            Sync.log("foreground start refused: ${foreground.exceptionOrNull()?.message}")
            stopSelf()
            return
        }
        // The overlay is often running with no activity alive, so the timer's settings and its
        // phase-end notifications have to be wired here too. Both calls are idempotent.
        //
        // Ahead of any window work now, not after it: whether there is supposed to *be* a window is
        // one of the things this reads back.
        attachSettings(this)
        Config.load()

        pumpSnapshots()
        watchVisibility()
        watchOpenRequests()
        watchNotification()
        // After attachSettings, which is what supplies the context this needs.
        startNetworkWatch()
        wirePomodoro()
        // Covers the condition-watcher path, which fires through Notifications rather than an
        // alarm. Alarms report from AlarmReceiver instead, where goAsync can hold the process
        // open for the call. Reads the credentials at firing time, so changing servers needs no
        // restart of this service.
        //
        // `banner = false`: Android's own heads-up notification already shows the title, the body
        // and the action buttons, and is dismissed by the swipe everyone knows. Halo used to draw a
        // second copy of that in an overlay window of its own, which was harder to get rid of than
        // the real one. Desktop still passes true — a tray notification cannot carry a button.
        reportFiredTo(syncScope, banner = false)

        // A plain in-process coroutine loop, deliberately — NOT WorkManager or JobScheduler.
        //
        // Those impose a 15-minute floor on periodic work, which would make a two-minute reminder
        // created on another device impossible to arm in time. A `delay()` inside a foreground
        // service's own scope has no such floor, so the cadence is ours to choose. Do not
        // "modernise" this into WorkManager without also solving that.
        //
        // It is best-effort by design: during Doze the process is suspended and the loop stalls.
        // Nothing is lost when it does, because alarms that are already armed are held by the OS
        // (setExactAndAllowWhileIdle) and BootReceiver re-arms after a restart. The loop only has
        // to run often enough to learn about *new* work.
        syncScope.launch { Sync.loop() }
    }

    private val density get() = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    private fun showOverlay() {
        // Re-checked here, not just at the gate: the user can revoke this while we run.
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        if (view != null) return

        // Built once per service, not once per window. The orb can now come and go many times over
        // one service — hidden and brought back, or appearing only when something needs an answer —
        // and rebuilding these each time would return it to the default corner every single time,
        // forgetting wherever it was parked.
        if (!::params.isInitialized) {
            params = WindowManager.LayoutParams(
                dp(COMPACT_W),
                dp(COMPACT_H),
                overlayType(),
                COLLAPSED_FLAGS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = dp(240)
            }
            // Seeded from where the window actually starts. These are what `applyMode` restores on
            // collapse, and left at their 0/0 default the first close of the panel would drop the
            // orb into the top corner even though it had never been dragged there.
            collapsedX = params.x
            collapsedY = params.y
        }

        val composeView = ComposeView(this).apply {
            setContent {
                // Built inside the composition, keyed on the credentials. [Config] is Compose
                // state, so saving new ones in Settings rebuilds this client where it is used —
                // which is what the activity's stop/start of this whole service used to be for.
                HaloOverlayRoot(
                    expanded = expanded,
                    onExpanded = { open -> expanded = open; applyMode(open) },
                    onHide = { Config.saveOrbHidden(true) },
                    onMoveTo = { x, y -> moveTo(x, y) },
                    windowX = { params.x },
                    windowY = { params.y },
                    api = remember(Config.baseUrl, Config.authToken) {
                        HaloApi(Config.baseUrl, Config.authToken)
                    },
                )
            }
        }
        val fresh = OverlayHost()
        fresh.attach(composeView)
        // The permission was checked above, but `canDrawOverlays` and `addView` are not one atomic
        // step — revoking it in between throws BadTokenException, and an unguarded throw in
        // onCreate takes the process down rather than the window. There is nothing to run without
        // a window, so stop cleanly and let PermissionGate ask for the permission again.
        val added = runCatching { windowManager.addView(composeView, params) }
        if (added.isFailure) {
            Sync.log("overlay window refused: ${added.exceptionOrNull()?.message}")
            fresh.detach()
            stopSelf()
            return
        }
        view = composeView
        host = fresh
    }

    /**
     * Take the orb's window down without touching anything else.
     *
     * Deliberately not `stopSelf()`. This service is also the sync loop, the pomodoro wiring and the
     * condition watcher, and since Android 15 the `SYSTEM_ALERT_WINDOW` exemption for starting a
     * foreground service from the background requires an overlay window that is *already visible* —
     * so a service that stopped itself when the bubble was hidden might not be able to start again
     * to bring it back.
     */
    private fun hideOverlay() {
        val v = view ?: return
        // Cleared before anything can throw. A half-removed window that this service still believes
        // in is worse than no window: `showOverlay` would return early on it forever, and the orb
        // could never come back without a restart.
        view = null
        val owner = host
        host = null

        // Collapse first. Removing a full-screen focusable window mid-panel leaves the IME up and
        // the next `showOverlay` would rebuild it at the expanded size.
        if (expanded) {
            expanded = false
            runCatching { applyMode(false) }
        }
        runCatching { windowManager.removeView(v) }
            .onFailure { Sync.log("overlay window would not come down: ${it.message}") }
        // After the view is out of the tree, never before: this disposes the composition, and
        // disposing one that is still attached and dispatching is its own crash.
        runCatching { owner?.detach() }
    }

    /**
     * The one thing that decides whether the orb is on screen, for the life of the service.
     *
     * A `snapshotFlow` rather than callbacks from each of the places that could change the answer:
     * the settings switch, the Quick Settings tile, the notification's action, a reminder firing,
     * the minute poll and the pomodoro all write Compose state on the same process-wide singletons,
     * so reading them is the only subscription needed. [orbVisible] is where the rule itself lives —
     * shared with desktop, and tested.
     *
     * On Main, because `addView`/`removeView` are main-thread calls and because this has to reach
     * the *same* thread the composition writes those singletons from.
     *
     * `yield()` before acting, and it is load-bearing. Every one of these decisions is provoked by
     * something the user just touched — a long-press inside the orb's own `MotionEvent` dispatch, a
     * tap on a menu row, a switch in Settings — and tearing the window down from inside that
     * gesture disposes a composition that is still running, from within itself. Yielding puts the
     * work on the next main-loop message instead, by which time the gesture has finished.
     *
     * Wrapped, because a window operation that fails must not take a foreground service with it.
     * The orb not appearing is a bug; the assistant dying is the app.
     */
    private fun watchVisibility() {
        syncScope.launch(Dispatchers.Main) {
            snapshotFlow {
                orbVisible(
                    always = Config.orbAlways,
                    hidden = Config.orbHidden,
                    unread = state.unread,
                    headsUp = state.headsUp != null,
                    timerRunning = timer.isRunning,
                    open = expanded,
                )
            }
                .distinctUntilChanged()
                .collect { visible ->
                    yield()
                    runCatching { if (visible) showOverlay() else hideOverlay() }
                        .onFailure { Sync.log("overlay could not be made ${if (visible) "visible" else "hidden"}: $it") }
                }
        }
    }

    /**
     * Honour a request for the panel from outside the overlay — the shade notification's tap.
     *
     * Puts the window back first if the bubble was dismissed, without clearing the dismissal: while
     * the panel is open [orbVisible] keeps the window on `open` alone, and closing it drops the orb
     * straight back out of sight. Asking for the panel is not the same as asking for the bubble
     * back, and answering the second question when the first was asked is how a dismissal quietly
     * stops meaning anything.
     */
    private fun watchOpenRequests() {
        syncScope.launch(Dispatchers.Main) {
            snapshotFlow { state.pendingOpen }
                .distinctUntilChanged()
                .collect { wanted ->
                    if (!wanted) return@collect
                    yield()
                    runCatching {
                        showOverlay()
                        if (view != null && !expanded) {
                            expanded = true
                            applyMode(true)
                        }
                    }.onFailure { Sync.log("could not open the panel: $it") }
                    state.clearPendingOpen()
                }
        }
    }

    /**
     * Deliver Compose state changes to the collectors above when no Compose UI is running.
     *
     * `snapshotFlow` only re-reads once something calls `Snapshot.sendApplyNotifications()`. On
     * Android that is normally `GlobalSnapshotManager`, started by the first `ComposeView` in the
     * process — but the entire point here is to react *while there is no window*, and now that the
     * banner window is gone there may be no `ComposeView` in this process at all. Leaning on
     * somebody else's recomposer to notice that the orb should come back is how it silently never
     * does.
     *
     * Posted rather than sent inline: a write observer runs inside the write, and sending apply
     * notifications from there re-enters the snapshot machinery. The flag collapses a burst of
     * writes into one delivery.
     */
    private fun pumpSnapshots() {
        val queued = java.util.concurrent.atomic.AtomicBoolean(false)
        snapshotPump = Snapshot.registerGlobalWriteObserver {
            if (queued.compareAndSet(false, true)) {
                mainHandler.post {
                    queued.set(false)
                    runCatching { Snapshot.sendApplyNotifications() }
                }
            }
        }
    }

    /**
     * Keep the ongoing notification saying what is actually happening.
     *
     * Only while [Config.shadeStatus] is on does this have anything to report — the minimal line has
     * no live content, so collecting for it would re-post a notification nobody can read for the
     * sake of a countdown nobody can see. The setting itself is in the key, so turning it on starts
     * reporting immediately and turning it off puts the quiet line back.
     *
     * On Main for the same reason as [watchVisibility], plus one of its own: `catchUp` below is the
     * only place this service advances the timer, and the overlay's own one-second loop does the
     * same thing from the composition. Both on one thread means "idempotent" is enough — off it,
     * two concurrent catch-ups could hand out two phase-end notifications for one boundary.
     */
    private fun watchNotification() {
        syncScope.launch(Dispatchers.Main) {
            snapshotFlow {
                listOf(Config.shadeStatus, Config.orbHidden, state.unread, state.offline, timer.state)
            }
                .distinctUntilChanged()
                // Cancels the tick below whenever any of those changes, so there is never more than
                // one loop re-posting the same notification.
                .collectLatest {
                    // `timer.display()` is derived from the clock rather than held as state, so a
                    // running countdown produces no snapshot change at all — it has to be ticked.
                    // The loop exits immediately when nothing is counting down, which is why an idle
                    // phone pays nothing for it.
                    while (true) {
                        // Nothing else ticks the timer while the orb is hidden, so a phase would
                        // otherwise run past its end and sit there. Idempotent by design.
                        timer.catchUp()
                        // Re-issuing startForeground on an already-foreground service updates its
                        // notification, and is what lets the channel change when the setting is
                        // toggled. Guarded: the OS can refuse once the service is on its way out.
                        runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }
                        if (!Config.shadeStatus || !timer.isRunning) break
                        delay(1000)
                    }
                }
        }
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    /**
     * Swap the window between its two shapes.
     *
     * Grow before the panel appears and shrink after it is gone, so nothing is ever drawn into a
     * window too small to hold it.
     */
    private fun applyMode(open: Boolean) {
        val v = view ?: return
        if (open) {
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = WindowManager.LayoutParams.MATCH_PARENT
            params.x = 0
            params.y = 0
            // Clearing NOT_FOCUSABLE is what lets the composer receive typing and the back key.
            params.flags = EXPANDED_FLAGS
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        } else {
            params.width = dp(COMPACT_W)
            params.height = dp(COMPACT_H)
            params.x = collapsedX
            params.y = collapsedY
            params.flags = COLLAPSED_FLAGS
        }
        runCatching { windowManager.updateViewLayout(v, params) }
    }

    /** Where the orb was parked, so closing the panel returns it there. */
    private var collapsedX = 0
    private var collapsedY = 0

    private fun moveTo(x: Int, y: Int) {
        val v = view ?: return
        // Clamped to the display, or the orb can be dragged off-screen and never retrieved.
        val bounds = displaySize()
        params.x = x.coerceIn(0, (bounds.first - params.width).coerceAtLeast(0))
        params.y = y.coerceIn(0, (bounds.second - params.height).coerceAtLeast(0))
        collapsedX = params.x
        collapsedY = params.y
        runCatching { windowManager.updateViewLayout(v, params) }
    }

    @Suppress("DEPRECATION")
    private fun displaySize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = android.util.DisplayMetrics().also { windowManager.defaultDisplay.getMetrics(it) }
            m.widthPixels to m.heightPixels
        }

    override fun onDestroy() {
        Notifications.onFired = null
        syncScope.cancel()
        snapshotPump?.dispose()
        snapshotPump = null
        hideOverlay()
        super.onDestroy()
    }

    /**
     * Restarted by the system, or poked by [start].
     *
     * Deliberately not `showOverlay()` any more: a dismissed bubble must stay dismissed across a
     * restart, and [watchVisibility]'s collector re-evaluates on its own the moment it is relaunched
     * in a fresh `onCreate`. A restart into an existing process needs nothing here at all.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    /**
     * The ongoing notification Android insists on while this service runs.
     *
     * Two shapes, and two channels — importance cannot be changed on a channel that already exists,
     * so the quiet line and the useful one cannot share one. Off, it is what it always was: a
     * minimum-importance line that sinks to the bottom of the shade and stays out of the way. On, it
     * does the floating button's job from the shade, which is the whole point of the setting.
     */
    private fun buildNotification(): Notification {
        val rich = Config.shadeStatus
        val channel = if (rich) STATUS_CHANNEL_ID else CHANNEL_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                if (rich) {
                    NotificationChannel(
                        STATUS_CHANNEL_ID,
                        "Halo status",
                        // LOW, not MIN: it is meant to be read. Still silent — there is nothing new
                        // to announce, the reminders channel does that.
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = "What Halo is doing, kept in the shade." }
                } else {
                    NotificationChannel(CHANNEL_ID, "Halo overlay", NotificationManager.IMPORTANCE_MIN)
                        .apply { description = "Keeps the floating Halo assistant available." }
                },
            )
        }

        val builder = Notification.Builder(this, channel)
            .setContentTitle("Halo")
            .setSmallIcon(R.drawable.ic_stat_halo)
            .setOngoing(true)

        if (!rich) {
            return builder.setContentText("Floating assistant is active").build()
        }

        return builder
            .setContentText(statusLine())
            .setLargeIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
            // The timestamp is meaningless on something that has been there since boot, and it
            // costs the width the status line wants.
            .setShowWhen(false)
            // Tapping opens the floating panel, not the app. The panel is the surface the whole
            // assistant is built around — the thing the bubble exists to reach — and it opens over
            // whatever the user is already doing rather than replacing it. It works even when the
            // bubble has been dismissed, and closing it leaves the bubble dismissed.
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    CONTROL_OPEN.hashCode(),
                    Intent(this, OpenPanelActivity::class.java)
                        .setAction(CONTROL_OPEN)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            // Whether the timer control comes first is not cosmetic: Android gives an ongoing
            // notification room for three actions and shows them in order, and on a narrow phone
            // the third is the one that gets clipped.
            .addAction(
                action(
                    when {
                        timer.isRunning -> "Pause"
                        timer.state is TimerState.Paused -> "Resume"
                        else -> "Start focus"
                    },
                    CONTROL_TIMER,
                ),
            )
            .addAction(action(if (Config.orbHidden) "Show button" else "Hide button", CONTROL_ORB))
            .build()
    }

    /**
     * One notification action, carrying which control it is.
     *
     * The request code is derived from the control's name so each keeps its own PendingIntent slot.
     * Sharing one would make every `FLAG_UPDATE_CURRENT` rewrite the others, and both buttons would
     * end up doing whichever was built last.
     */
    private fun action(label: String, control: String) = Notification.Action.Builder(
        // A real icon rather than null: phones render actions as text, but watches, cars and a
        // couple of OEM shades draw the icon instead and show nothing at all without one.
        Icon.createWithResource(this, R.drawable.ic_stat_halo),
        label,
        PendingIntent.getBroadcast(
            this,
            control.hashCode(),
            Intent(this, OverlayControlReceiver::class.java).setAction(control),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ),
    ).build()

    /**
     * One line saying what the orb would be saying, in the same order of precedence it uses.
     *
     * Offline first because it is a hard fact rather than a mood, then the countdown, then what is
     * waiting — the ranking [dev.infyplus.halo.ui.HaloState.rederive] already settled.
     */
    private fun statusLine(): String = when {
        state.offline -> "Can't reach your server"
        timer.isRunning -> "${timer.phase.label()} · ${timer.display()}"
        state.unread == 1 -> "1 thing waiting for you"
        state.unread > 1 -> "${state.unread} things waiting for you"
        else -> "Nothing needs you"
    }

    companion object {
        private const val CHANNEL_ID = "halo_overlay"

        /** The useful line's channel. Separate because a channel's importance is fixed once made. */
        private const val STATUS_CHANNEL_ID = "halo_status"
        private const val NOTIFICATION_ID = 1

        /** Which control a notification tap was. Read back by [OverlayControlReceiver]. */
        const val CONTROL_OPEN = "dev.infyplus.halo.OPEN_PANEL"
        const val CONTROL_ORB = "dev.infyplus.halo.TOGGLE_ORB"
        const val CONTROL_TIMER = "dev.infyplus.halo.TOGGLE_TIMER"

        /**
         * Big enough for the orb *and* what hangs outside it — the drawing bleeds past the orb so
         * the excited hop and ground shadow are not clipped, and the cloud and badge sit outside
         * it too. The window clips; the composable does not.
         */
        // Three quarters of the design's own size: on a phone the bubble sits over whatever the
        // user is actually doing, and at full size it was covering too much of it. Kept in step
        // with ORB_SCALE — the window has to be the size of what is drawn in it, or the hop and
        // the ground shadow get clipped by the window rather than by nothing.
        private const val COMPACT_W = 114
        private const val COMPACT_H = 120

        // NOT_TOUCH_MODAL lets touches outside this window reach whatever is behind it; without
        // it a small overlay still blocks the whole screen. SPLIT_TOUCH keeps multi-touch sane
        // across window boundaries.
        private const val COLLAPSED_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

        // Focusable on purpose: the composer needs a keyboard and back needs to reach us.
        // Deliberately NOT FLAG_LAYOUT_NO_LIMITS — it disables inset dispatch, which kills IME
        // handling and would put the composer under the keyboard.
        private const val EXPANDED_FLAGS = WindowManager.LayoutParams.FLAG_SPLIT_TOUCH

        /**
         * No-op unless the overlay permission is actually held, and never fatal.
         *
         * Guarded here rather than at each call site because the callers are a settings screen, a
         * Quick Settings tile and a broadcast receiver — none of which is in a position to do
         * anything useful about a refusal, and two of which crash the process on an unhandled one.
         * Android 12+ refuses a background start outright, and since Android 15 the
         * `SYSTEM_ALERT_WINDOW` exemption needs an overlay window that is *already* visible — which
         * is exactly what is missing when something is trying to bring the hidden bubble back.
         */
        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, OverlayService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Sync.log("overlay service start refused: ${it.message}") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
