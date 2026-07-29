package dev.infyplus.halo

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "HaloAlarm"

/** Fired by AlarmManager at a scheduled instant; turns the alarm into a visible notification. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AndroidNotifier.attach(context)

        val verbs = intent.getStringArrayExtra(AndroidNotifier.EXTRA_VERBS) ?: emptyArray()
        val labels = intent.getStringArrayExtra(AndroidNotifier.EXTRA_LABELS) ?: emptyArray()

        val scheduled = Scheduled(
            id = intent.getStringExtra(AndroidNotifier.EXTRA_ID) ?: return,
            // Null for a summary or a general nudge — those announce the day, not an item.
            itemId = intent.getStringExtra(AndroidNotifier.EXTRA_ITEM_ID),
            kind = intent.getStringExtra(AndroidNotifier.EXTRA_KIND) ?: "due",
            at = null,
            title = intent.getStringExtra(AndroidNotifier.EXTRA_TITLE) ?: "Reminder",
            body = intent.getStringExtra(AndroidNotifier.EXTRA_BODY) ?: "",
            actions = verbs.zip(labels).map { (verb, label) -> ScheduledAction(label, verb) },
        )

        // Show first, report second: the user seeing it on time matters, the bookkeeping does not.
        AndroidNotifier.show(context, scheduled)

        // Reported here rather than through Notifications.onFired because a receiver's process can
        // be killed the moment onReceive returns — goAsync is what holds it open for the call.
        // Losing this costs a row in the notification history, never a reminder.
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                HaloApi(Config.BASE_URL, Config.AUTH_TOKEN)
                    .reportFired(scheduled.id, scheduled.itemId)
            } catch (e: Exception) {
                Log.w(TAG, "could not report '${scheduled.title}' as fired", e)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * A notification action button was tapped.
 *
 * `goAsync` holds the broadcast alive long enough for the network call — without it the process
 * can be killed the moment onReceive returns and the action silently does nothing.
 */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val itemId = intent.getStringExtra(AndroidNotifier.EXTRA_ITEM_ID) ?: return
        val verb = intent.getStringExtra(AndroidNotifier.EXTRA_VERB) ?: return
        val id = intent.getStringExtra(AndroidNotifier.EXTRA_ID)

        // Dismiss immediately so the tap feels instant, regardless of how the call goes.
        id?.let {
            context.getSystemService(NotificationManager::class.java).cancel(it.hashCode())
        }

        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val api = HaloApi(Config.BASE_URL, Config.AUTH_TOKEN)
                api.act(itemId, verb)
                // Snoozing moves the due time, so the schedule this device holds is now stale.
                AndroidNotifier.attach(context)
                AndroidNotifier.arm(api.sync().notifications)
            } catch (e: Exception) {
                Log.e(TAG, "action $verb on $itemId failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Re-arms alarms after a reboot.
 *
 * Android clears every pending alarm on restart. Without this, all reminders vanish silently
 * after a phone restart — the kind of failure a user only discovers by missing something.
 * The server is the source of truth, so this re-fetches rather than keeping a local database.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                AndroidNotifier.attach(context)
                AndroidNotifier.arm(HaloApi(Config.BASE_URL, Config.AUTH_TOKEN).sync().notifications)
                Log.i(TAG, "alarms re-armed after boot")
            } catch (e: Exception) {
                // Network is often not up yet at boot. The next app launch or overlay-service
                // tick re-syncs, so this is a retry opportunity lost rather than a lost reminder.
                Log.w(TAG, "boot re-arm failed; will re-sync on next launch", e)
            } finally {
                pending.finish()
            }
        }
    }
}
