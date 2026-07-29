package dev.infyplus.halo

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.infyplus.halo.ui.HaloButton
import dev.infyplus.halo.ui.HaloCard
import dev.infyplus.halo.ui.HaloChip
import dev.infyplus.halo.ui.HaloPalette
import dev.infyplus.halo.ui.Mono
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Setup checklist shown before the app proper.
 *
 * Permissions are granted in system Settings, so the user leaves and returns — the checklist
 * re-reads every requirement on ON_RESUME rather than trusting a snapshot taken at launch.
 *
 * Optional items never block entry; they are shown so that unreliable delivery is a visible
 * choice rather than a mystery later.
 */
@Composable
fun PermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var checked by remember { mutableStateOf(0) }        // bump to force re-evaluation
    val prefs = remember { context.getSharedPreferences("halo", Context.MODE_PRIVATE) }
    // Setup is dismissed by an explicit tap, and that choice is remembered — otherwise the
    // checklist would reappear on every launch once the permissions were already granted.
    var started by remember { mutableStateOf(prefs.getBoolean(KEY_SETUP_DONE, false)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) checked++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Each probe touches a different system service, and a device that answers one of them with an
    // exception must not take the checklist down — this is the screen that explains how to fix
    // things. An unanswerable requirement counts as unsatisfied, which is the safe reading.
    val statuses = remember(checked) {
        REQUIREMENTS.map { it to runCatching { it.satisfied(context) }.getOrDefault(false) }
    }
    val blocking = statuses.filter { (req, ok) -> req.required && !ok }

    // Something required was revoked after setup — go back to the checklist rather than
    // running with a broken overlay.
    if (blocking.isNotEmpty() && started) started = false

    if (started) {
        DisposableEffect(checked) {
            OverlayService.start(context)
            onDispose {}
        }
        content()
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Deliberately the same heading weights as SetupGate: this screen follows it immediately
        // on a fresh install, and the two reading as one flow is the whole point.
        Text(
            "Before we start",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = HaloPalette.ink,
        )
        Text(
            "Halo needs a few things to work the way it's meant to. " +
                "You'll be sent to Settings and come back here.",
            fontSize = 14.sp,
            color = HaloPalette.navy.copy(alpha = 0.8f),
        )

        statuses.forEach { (req, ok) -> RequirementCard(req, ok, context) }

        val optionalPending = statuses.count { (req, ok) -> !req.required && !ok }
        Mono(
            when {
                blocking.isNotEmpty() ->
                    "GRANT THE ${blocking.size} REQUIRED ITEM${if (blocking.size == 1) "" else "S"} ABOVE TO CONTINUE"
                optionalPending > 0 ->
                    "READY · $optionalPending OPTIONAL ITEM${if (optionalPending == 1) "" else "S"} STILL OPEN"
                else -> "EVERYTHING'S SET"
            },
            color = if (blocking.isNotEmpty()) HaloPalette.warm else HaloPalette.navy.copy(alpha = 0.78f),
            weight = FontWeight.Bold,
        )

        HaloButton(
            label = "Get started",
            modifier = Modifier.fillMaxWidth(),
            enabled = blocking.isEmpty(),
        ) {
            prefs.edit().putBoolean(KEY_SETUP_DONE, true).apply()
            started = true
        }
    }
}

private const val KEY_SETUP_DONE = "setup_done"

/**
 * Open a settings screen, falling back to this app's own details page.
 *
 * Not every OEM ships every screen these intents name — `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`
 * in particular is missing on a fair number of devices — and an `ActivityNotFoundException` here
 * crashes the app from the one screen whose whole job is telling the user how to make it work. The
 * app details page always exists and holds all of these permissions, so it is a real fallback and
 * not just a way of not crashing.
 */
private fun Context.openSettings(intent: android.content.Intent) {
    runCatching { startActivity(intent) }.getOrElse {
        runCatching {
            startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:$packageName"),
                ),
            )
        }
    }
}

@Composable
private fun RequirementCard(req: Requirement, satisfied: Boolean, context: Context) {
    // A satisfied item recedes: the point of the list is what is still outstanding, and four
    // identical cards make the one you have to act on harder to find.
    HaloCard(
        modifier = Modifier.fillMaxWidth(),
        tint = if (satisfied) HaloPalette.cream else HaloPalette.body.copy(alpha = 0.16f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (satisfied) "✓" else "•",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = if (satisfied) HaloPalette.sun else HaloPalette.warm,
            )
            Text(
                req.title,
                modifier = Modifier.weight(1f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = HaloPalette.ink,
            )
            if (!req.required) Mono("OPTIONAL")
        }

        if (!satisfied) {
            Text(
                req.why,
                modifier = Modifier.padding(top = 6.dp),
                fontSize = 13.sp,
                color = HaloPalette.navy.copy(alpha = 0.75f),
            )

            val intent = req.fix(context)
            if (intent != null) {
                Row(Modifier.padding(top = 8.dp)) {
                    HaloChip("Open settings") { context.openSettings(intent) }
                }
            }
        }
    }
}
