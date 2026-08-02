package dev.infyplus.halo

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.infyplus.halo.ui.HaloState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val TAG = "HaloAlarm"

/**
 * How long a broadcast may spend on the network.
 *
 * Android allows a `goAsync` receiver roughly ten seconds before it kills the process. Staying
 * inside that is what makes the difference between reporting a failure and vanishing with it.
 */
private const val BROADCAST_BUDGET_MS = 8_000L

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
                withTimeout(BROADCAST_BUDGET_MS) {
                    HaloApi(Config.baseUrl, Config.authToken)
                        .reportFired(scheduled.id, scheduled.itemId)
                }
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
        // Enough of the notification to put it back if the action does not get through. Rebuilt
        // from the intent rather than refetched: this receiver may be the only thing alive.
        val restored = Scheduled(
            id = id ?: "due-$itemId",
            itemId = itemId,
            kind = intent.getStringExtra(AndroidNotifier.EXTRA_KIND) ?: "due",
            at = null,
            title = intent.getStringExtra(AndroidNotifier.EXTRA_TITLE) ?: "Reminder",
            body = "Not sent — try again",
            actions = (intent.getStringArrayExtra(AndroidNotifier.EXTRA_VERBS) ?: emptyArray())
                .zip(intent.getStringArrayExtra(AndroidNotifier.EXTRA_LABELS) ?: emptyArray())
                .map { (v, label) -> ScheduledAction(label, v) },
        )

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
                // A broadcast gets about ten seconds before Android kills the process; the shared
                // client allows sixty. Left alone, a slow network means the process dies mid-call
                // with the notification already dismissed — the user's "Done" simply evaporates.
                // Timing out inside that budget is what lets the failure be reported at all.
                withTimeout(BROADCAST_BUDGET_MS) {
                    val api = HaloApi(Config.baseUrl, Config.authToken)
                    api.act(itemId, verb)
                    // Snoozing moves the due time, so the schedule this device holds is now stale.
                    AndroidNotifier.attach(context)
                    AndroidNotifier.arm(api.sync().notifications)
                }
                HaloState.shared.clearNotice()
            } catch (e: Exception) {
                Log.e(TAG, "action $verb on $itemId failed", e)
                // Say so where the app can be seen…
                HaloState.shared.noteFailure(e, "that reminder was not ${if (verb == "done") "marked done" else verb + "d"}")
                // …and put the notification back, because it was taken down on the assumption
                // this would work. Without it the only way to retry is to remember it existed.
                AndroidNotifier.attach(context)
                AndroidNotifier.show(context, restored)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * The status notification's "Show button" / "Hide button" was tapped.
 *
 * No network, no `goAsync`: this writes one flag and returns. [OverlayService]'s visibility
 * collector is watching that flag as Compose state and does the rest — and because a broadcast
 * receiver runs in the same process as the service, that is a direct read, not IPC.
 *
 * [attachSettings] first, because the receiver can be the only thing alive in a cold process (the
 * shade survives the service being killed) and without a store the write would go nowhere. Nothing
 * visible happens in that case, which is correct: there is no window to hide.
 */
class OrbToggleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        attachSettings(context)
        Config.load()
        Config.saveOrbHidden(!Config.orbHidden)
        // Repaint the action's own label. The service does this from its collector when it is
        // running; this covers a shade entry that outlived the process it was posted from. `start`
        // swallows the refusal a background start can earn — see its own comment.
        OverlayService.start(context)
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
                withTimeout(BROADCAST_BUDGET_MS) {
                    AndroidNotifier.attach(context)
                    AndroidNotifier.arm(HaloApi(Config.baseUrl, Config.authToken).sync().notifications)
                }
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
