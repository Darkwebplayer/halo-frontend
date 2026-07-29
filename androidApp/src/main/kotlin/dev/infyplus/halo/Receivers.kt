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
        // AlarmManager can start this process cold, with no activity or service having run, so the
        // settings store has to be opened here or the credentials read back empty.
        attachSettings(context)
        Config.load()

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

        // The notification above is built entirely from the intent, so it still fires on a device
        // whose credentials were cleared. Only the bookkeeping below needs a server.
        if (!Config.isConfigured) return

        // Reported here rather than through Notifications.onFired because a receiver's process can
        // be killed the moment onReceive returns — goAsync is what holds it open for the call.
        // Losing this costs a row in the notification history, never a reminder.
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                HaloApi(Config.baseUrl, Config.authToken)
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
        attachSettings(context)
        Config.load()

        val itemId = intent.getStringExtra(AndroidNotifier.EXTRA_ITEM_ID) ?: return
        val verb = intent.getStringExtra(AndroidNotifier.EXTRA_VERB) ?: return
        val id = intent.getStringExtra(AndroidNotifier.EXTRA_ID)

        // Nothing to act against. Unlike an alarm, this whole receiver exists to make the call.
        if (!Config.isConfigured) return

        // Dismiss immediately so the tap feels instant, regardless of how the call goes. Guarded
        // because failing to take a notification down is not a reason to lose the action itself.
        id?.let {
            runCatching {
                context.getSystemService(NotificationManager::class.java).cancel(it.hashCode())
            }
        }

        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val api = HaloApi(Config.baseUrl, Config.authToken)
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

        // Guaranteed cold process — nothing else has run since the reboot, so the store is opened
        // here. Without this the re-arm below reads blank credentials and every reminder is lost.
        attachSettings(context)
        Config.load()
        if (!Config.isConfigured) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                AndroidNotifier.attach(context)
                AndroidNotifier.arm(HaloApi(Config.baseUrl, Config.authToken).sync().notifications)
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
