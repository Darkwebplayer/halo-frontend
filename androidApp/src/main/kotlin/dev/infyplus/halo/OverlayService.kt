package dev.infyplus.halo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
    private val host = OverlayHost()
    private var bannerView: ComposeView? = null
    // Its own host: a second window means a second view tree, and each needs its own lifecycle,
    // ViewModelStore and SavedStateRegistry or Compose crashes on first composition.
    private val bannerHost = OverlayHost()
    private lateinit var params: WindowManager.LayoutParams
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Compose reads this; the window layout follows it. */
    private var expanded by mutableStateOf(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIFICATION_ID, buildNotification())
        showOverlay()

        // The service is already alive for the overlay, so it is the natural place to keep the
        // armed schedule fresh and to evaluate weather conditions. Alarms themselves are held by
        // the OS, so nothing is lost if this service is killed — it just stops re-syncing.
        AndroidNotifier.attach(this)
        // The overlay is often running with no activity alive, so the timer's settings and its
        // phase-end notifications have to be wired here too. Both calls are idempotent.
        attachSettings(this)
        wirePomodoro()
        val api = HaloApi(Config.BASE_URL, Config.AUTH_TOKEN)
        // Covers the condition-watcher path, which fires through Notifications rather than an
        // alarm. Alarms report from AlarmReceiver instead, where goAsync can hold the process
        // open for the call.
        reportFiredTo(api, syncScope)

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
        syncScope.launch { Sync.loop(api) }
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

        val api = HaloApi(Config.BASE_URL, Config.AUTH_TOKEN)
        val composeView = ComposeView(this).apply {
            setContent {
                HaloOverlayRoot(
                    expanded = expanded,
                    onExpanded = { open -> expanded = open; applyMode(open) },
                    onMoveTo = { x, y -> moveTo(x, y) },
                    windowX = { params.x },
                    windowY = { params.y },
                    api = api,
                )
            }
        }
        host.attach(composeView)
        windowManager.addView(composeView, params)
        view = composeView

        showBanner(api)
    }

    /**
     * The heads-up banner, in a window of its own.
     *
     * Not part of the main overlay because the two need opposite shapes. The banner spans the
     * screen's width, and the collapsed orb is 150dp wide — sharing a window would mean keeping
     * the orb's window screen-wide all the time, which would swallow every touch behind it for
     * the sake of a card that shows for six seconds.
     *
     * `WRAP_CONTENT` height means only the strip itself is touchable and everything below passes
     * through. Added after the orb, so it stacks above it.
     */
    private fun showBanner(api: HaloApi) {
        if (bannerView != null) return

        val bannerParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            COLLAPSED_FLAGS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            y = dp(8)
        }

        val v = ComposeView(this).apply {
            setContent {
                HaloBannerHost(
                    api = api,
                    onOpenPanel = {
                        if (!expanded) {
                            expanded = true
                            applyMode(true)
                        }
                    },
                )
            }
        }
        bannerHost.attach(v)
        runCatching { windowManager.addView(v, bannerParams) }
        bannerView = v
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
        view?.let { runCatching { windowManager.removeView(it) } }
        bannerView?.let { runCatching { windowManager.removeView(it) } }
        host.detach()
        bannerHost.detach()
        view = null
        bannerView = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showOverlay()
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Halo overlay", NotificationManager.IMPORTANCE_MIN)
                    .apply { description = "Keeps the floating Halo assistant available." },
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Halo")
            .setContentText("Floating assistant is active")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "halo_overlay"
        private const val NOTIFICATION_ID = 1

        /**
         * Big enough for the orb *and* what hangs outside it — the drawing bleeds past the orb so
         * the excited hop and ground shadow are not clipped, and the cloud and badge sit outside
         * it too. The window clips; the composable does not.
         */
        private const val COMPACT_W = 150
        private const val COMPACT_H = 160

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

        /** No-op unless the overlay permission is actually held. */
        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
