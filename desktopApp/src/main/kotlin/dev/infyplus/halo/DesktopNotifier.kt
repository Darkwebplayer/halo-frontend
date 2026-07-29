package dev.infyplus.halo

import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Desktop scheduling.
 *
 * An in-process scheduler is enough here, unlike Android: the app already runs persistently as
 * a tray application, so there is no process-death problem to solve. If it is quit, no
 * reminders are expected from it anyway.
 *
 * Notifications are shown through the existing tray icon. AWT tray notifications carry no
 * action buttons — clicking one opens the panel instead. That is a genuine gap versus Android,
 * not something worked around.
 */
object DesktopNotifier : Notifier {

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "halo-notifier").apply { isDaemon = true }
    }

    private val pending = mutableMapOf<String, ScheduledFuture<*>>()

    /**
     * How to actually show a notification, supplied by the app once its tray exists.
     * Kept as a function rather than a TrayIcon so Compose's TrayState can provide it.
     */
    @Volatile
    var send: ((title: String, body: String) -> Unit)? = null

    /** Invoked when a notification fires, so the app can surface itself. */
    @Volatile
    var onFired: ((Scheduled) -> Unit)? = null

    @Synchronized
    override fun arm(items: List<Scheduled>) {
        val timed = items.filter { it.at != null }
        val wanted = timed.map { it.id }.toSet()

        // Drop anything no longer scheduled — completed elsewhere, or already fired.
        pending.keys.toList().filterNot { it in wanted }.forEach { id ->
            pending.remove(id)?.cancel(false)
        }

        val now = System.currentTimeMillis()
        for (item in timed) {
            if (pending.containsKey(item.id)) continue // already armed; ids are stable

            // Came due while nothing was running. The server has already decided it is recent
            // enough to be worth showing, so show it now rather than dropping it for being past.
            if (item.late) {
                Sync.log("catching up on missed '${item.title}'")
                Notifications.fireNow(item)
                continue
            }

            val at = runCatching { Instant.parse(item.at).toEpochMilli() }.getOrNull() ?: continue
            val delay = at - now
            if (delay <= 0) continue // never replay the past

            pending[item.id] = scheduler.schedule(
                {
                    synchronized(this) { pending.remove(item.id) }
                    // A throw here would be swallowed whole by the executor — the Future holds it
                    // and nobody ever calls get(). A reminder that failed to appear would then be
                    // indistinguishable from one that was never scheduled.
                    try {
                        // Through Notifications, not straight to fireNow: that is the one place
                        // that reports a fired notification to the server for the history.
                        Notifications.fireNow(item)
                    } catch (e: Throwable) {
                        Sync.log("failed to fire '${item.title}': ${e::class.simpleName}: ${e.message}")
                    }
                },
                delay,
                TimeUnit.MILLISECONDS,
            )
            Sync.log("scheduled '${item.title}' in ${delay / 1000}s")
        }
    }

    override fun fireNow(item: Scheduled) {
        val deliver = send
        Sync.log("firing '${item.title}'${if (deliver == null) " — NO DELIVERY CHANNEL" else ""}")
        // Called from the scheduler thread as well as from the sync loop, and `send` ends up in
        // Compose's TrayState — Swing state that must only be touched from the event thread.
        // invokeLater rather than invokeAndWait: this must not be able to deadlock against a UI
        // thread that is itself waiting on the scheduler.
        deliver?.let { channel -> onSwing { channel(item.title, item.body) } }
        // Kept off the same hop: this updates Compose snapshot state and posts a network report,
        // neither of which wants the event thread, and one failing must not lose the other.
        runCatching { onFired?.invoke(item) }
            .onFailure { Sync.log("post-fire handling of '${item.title}' failed: ${it.message}") }
    }

    /** Run on the AWT event thread, logging rather than dying if the toolkit refuses. */
    private fun onSwing(block: () -> Unit) {
        val guarded = Runnable {
            runCatching(block).onFailure { Sync.log("notification delivery failed: ${it.message}") }
        }
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            guarded.run()
        } else {
            runCatching { javax.swing.SwingUtilities.invokeLater(guarded) }
                .onFailure { Sync.log("could not reach the event thread: ${it.message}") }
        }
    }

    @Synchronized
    override fun cancelAll() {
        pending.values.forEach { it.cancel(false) }
        pending.clear()
    }
}
