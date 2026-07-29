package dev.infyplus.halo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * One thing Halo needs before it can behave as advertised, and how to go fix it.
 *
 * [satisfied] is re-read every time the gate resumes rather than cached, because the user
 * leaves the app to grant these and comes back.
 */
data class Requirement(
    val key: String,
    val title: String,
    /** Why this matters, in terms of what breaks without it — not a restatement of the title. */
    val why: String,
    val required: Boolean,
    val satisfied: (Context) -> Boolean,
    /** Where to send the user to fix it. Null when it can't be resolved on-device. */
    val fix: (Context) -> Intent?,
)

val REQUIREMENTS: List<Requirement> = listOf(
    Requirement(
        key = "overlay",
        title = "Display over other apps",
        why = "Without this the floating button can't appear outside Halo, so capture stops being one tap away.",
        required = true,
        satisfied = { Settings.canDrawOverlays(it) },
        fix = {
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${it.packageName}"),
            )
        },
    ),
    Requirement(
        key = "notifications",
        title = "Show notifications",
        why = "The overlay runs as a foreground service, which Android requires to post an ongoing notification.",
        required = true,
        satisfied = {
            // POST_NOTIFICATIONS only exists from API 33; below that it is granted implicitly.
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(it, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        },
        fix = {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, it.packageName)
        },
    ),
    Requirement(
        key = "exact_alarms",
        title = "Exact alarms",
        why = "Reminders are scheduled as exact alarms. Without this they fire late — sometimes much later — instead of at the time you asked for.",
        required = true,
        // On API 33+ this is granted at install via USE_EXACT_ALARM, because Halo is a
        // reminder app; only 31-32 can land here unsatisfied.
        satisfied = { AndroidNotifier.canScheduleExact() },
        fix = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:${it.packageName}"),
                )
            } else {
                null
            }
        },
    ),
    Requirement(
        key = "battery",
        title = "Unrestricted background use",
        why = "Under battery optimisation Android may delay reminders while the phone is idle, and can stop the overlay from restarting.",
        // Promoted from optional: now that reminders fire on-device rather than arriving as
        // push, they depend on this device being allowed to wake up.
        required = true,
        satisfied = {
            val pm = it.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(it.packageName)
        },
        fix = { Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) },
    ),
)

/** True when every *required* item is satisfied. Optional ones only affect reliability. */
fun allRequiredSatisfied(context: Context): Boolean =
    REQUIREMENTS.filter { it.required }.all { it.satisfied(context) }
