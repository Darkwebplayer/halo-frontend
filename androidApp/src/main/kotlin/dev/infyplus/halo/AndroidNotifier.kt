package dev.infyplus.halo

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Android scheduling via [AlarmManager] exact alarms.
 *
 * Exact alarms are used rather than a timer inside our foreground service because they survive
 * the process being killed — the OS holds the schedule and wakes us. OEM battery managers kill
 * foreground services aggressively, so an in-process timer would silently lose reminders.
 *
 * Requires [attach] to be called once with an application context before use, since the shared
 * `expect object` has no constructor to inject one.
 */
object AndroidNotifier : Notifier {

    private lateinit var appContext: Context
    private const val CHANNEL_ID = "halo_reminders"

    /** Ids currently armed, so [arm] can cancel what is no longer in the schedule. */
    private var armed: Set<String> = emptySet()

    /** Idempotent; safe to call from every entry point (activity, service, receiver). */
    fun attach(context: Context) {
        appContext = context.applicationContext
        createChannel()
        Notifications.impl = this
        // Every entry point calls attach, which makes it the one place guaranteed to have a
        // context before anything draws.
        cacheReducedMotion(appContext)
    }

    private val alarms: AlarmManager
        get() = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * True when the OS will honour exact alarms.
     *
     * On API 33+ this is granted at install because Halo declares USE_EXACT_ALARM — its core
     * function is reminders. On 31–32 the user may have to grant it in Settings.
     */
    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    @SuppressLint("MissingPermission") // guarded by canScheduleExact()
    override fun arm(items: List<Scheduled>) {
        if (!::appContext.isInitialized) return

        val timed = items.filter { it.at != null }
        val now = System.currentTimeMillis()

        // Drop anything no longer scheduled — completed elsewhere, or already fired.
        (armed - timed.map { it.id }.toSet()).forEach { cancel(it) }

        for (item in timed) {
            // Came due while nothing was running. The server has already judged it recent enough
            // to be worth showing, so show it now instead of dropping it for being in the past.
            if (item.late) {
                Notifications.fireNow(item)
                continue
            }

            val at = parseIsoMillis(item.at!!) ?: continue
            if (at <= now) continue // never replay the past on a device that was asleep

            val pending = pendingIntentFor(item, PendingIntent.FLAG_UPDATE_CURRENT)
            if (canScheduleExact()) {
                // ...AndAllowWhileIdle is what makes this fire during Doze rather than being
                // batched to the next maintenance window.
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                // Degraded but not silent: inexact alarms still fire, just not to the minute.
                alarms.set(AlarmManager.RTC_WAKEUP, at, pending)
            }
        }
        armed = timed.map { it.id }.toSet()
    }

    override fun fireNow(item: Scheduled) {
        if (!::appContext.isInitialized) return
        show(appContext, item)
    }

    /**
     * Pull every notification for this item out of the shade.
     *
     * The ids the server mints are deterministic, so the whole set is reconstructable from the
     * item id alone — no bookkeeping needed. `cancel` on an id that was never posted is a no-op,
     * which is why blindly trying all three is fine.
     */
    override fun dismissFor(itemId: String) {
        if (!::appContext.isInitialized) return
        val manager = appContext.getSystemService(NotificationManager::class.java)
        listOf("due-$itemId", "checkin-$itemId", "cond-$itemId")
            .forEach { manager.cancel(it.hashCode()) }
    }

    override fun cancelAll() {
        armed.forEach { cancel(it) }
        armed = emptySet()
    }

    private fun cancel(id: String) {
        val intent = Intent(appContext, AlarmReceiver::class.java).setAction(id)
        val pending = PendingIntent.getBroadcast(
            appContext,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        pending?.let {
            alarms.cancel(it)
            it.cancel()
        }
    }

    private fun pendingIntentFor(item: Scheduled, flags: Int): PendingIntent {
        // The action makes each PendingIntent distinct; request code keys replacement, so
        // re-arming the same notification updates rather than stacking duplicates.
        val intent = Intent(appContext, AlarmReceiver::class.java)
            .setAction(item.id)
            .putExtra(EXTRA_ID, item.id)
            .putExtra(EXTRA_ITEM_ID, item.itemId)
            .putExtra(EXTRA_KIND, item.kind)
            .putExtra(EXTRA_TITLE, item.title)
            .putExtra(EXTRA_BODY, item.body)
            .putExtra(EXTRA_VERBS, item.actions.map { it.verb }.toTypedArray())
            .putExtra(EXTRA_LABELS, item.actions.map { it.label }.toTypedArray())

        return PendingIntent.getBroadcast(
            appContext,
            item.id.hashCode(),
            intent,
            flags or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Reminders and check-ins from Halo." },
        )
    }

    /** Builds and posts the notification. Called from [AlarmReceiver] when an alarm fires. */
    fun show(context: Context, item: Scheduled) {
        // The pre-due "coming up in 30 minutes" nudge is answered by the thing itself arriving.
        // Leaving both in the shade is two notifications for one event.
        if (item.kind == "due" && item.itemId != null) {
            context.getSystemService(NotificationManager::class.java)
                .cancel("checkin-${item.itemId}".hashCode())
        }

        // Carries which item this was about, so tapping the notification opens the panel already
        // scoped to it. Without the extra the app opens blind and the user has to find the thing
        // they were just told about.
        val open = PendingIntent.getActivity(
            context,
            item.id.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_ITEM_ID, item.itemId)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(item.title)
            .setContentText(item.body)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setAutoCancel(true)
            .setContentIntent(open)

        item.actions.forEach { action ->
            val intent = Intent(context, ActionReceiver::class.java)
                .setAction("${item.id}:${action.verb}")
                .putExtra(EXTRA_ID, item.id)
                .putExtra(EXTRA_ITEM_ID, item.itemId)
                .putExtra(EXTRA_VERB, action.verb)
            val pending = PendingIntent.getBroadcast(
                context,
                "${item.id}:${action.verb}".hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                Notification.Action.Builder(null as android.graphics.drawable.Icon?, action.label, pending)
                    .build(),
            )
        }

        context.getSystemService(NotificationManager::class.java)
            .notify(item.id.hashCode(), builder.build())
    }

    const val EXTRA_ID = "id"
    const val EXTRA_ITEM_ID = "item_id"
    const val EXTRA_KIND = "kind"
    const val EXTRA_TITLE = "title"
    const val EXTRA_BODY = "body"
    const val EXTRA_VERB = "verb"
    const val EXTRA_VERBS = "verbs"
    const val EXTRA_LABELS = "labels"
}

/**
 * Minimal ISO-8601 parse for the UTC instants the server sends (always `...Z`, optional millis).
 * Avoids pulling in a date library for one format we control both ends of.
 */
fun parseIsoMillis(iso: String): Long? {
    val m = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})""").find(iso) ?: return null
    val (y, mo, d, h, mi, s) = m.destructured
    val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    cal.clear()
    cal.set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), s.toInt())
    return cal.timeInMillis
}
