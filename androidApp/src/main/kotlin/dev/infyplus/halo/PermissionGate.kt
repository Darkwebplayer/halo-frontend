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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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

    val statuses = remember(checked) { REQUIREMENTS.map { it to it.satisfied(context) } }
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
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Before we start", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Halo needs a few things to work the way it's meant to. " +
                "You'll be sent to Settings and come back here.",
            style = MaterialTheme.typography.bodyMedium,
        )

        statuses.forEach { (req, ok) -> RequirementCard(req, ok, context) }

        val optionalPending = statuses.count { (req, ok) -> !req.required && !ok }
        Text(
            when {
                blocking.isNotEmpty() ->
                    "Grant the ${blocking.size} required item${if (blocking.size == 1) "" else "s"} above to continue."
                optionalPending > 0 ->
                    "Ready. The $optionalPending optional item${if (optionalPending == 1) "" else "s"} " +
                        "above can be granted now — you won't be asked again."
                else -> "Everything's set."
            },
            style = MaterialTheme.typography.bodySmall,
        )

        Button(
            onClick = {
                prefs.edit().putBoolean(KEY_SETUP_DONE, true).apply()
                started = true
            },
            enabled = blocking.isEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Get started") }
    }
}

private const val KEY_SETUP_DONE = "setup_done"

@Composable
private fun RequirementCard(req: Requirement, satisfied: Boolean, context: Context) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (satisfied) "✓" else "•", style = MaterialTheme.typography.titleMedium)
                Text(
                    req.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!req.required) {
                    Text("optional", style = MaterialTheme.typography.labelSmall)
                }
            }

            if (!satisfied) {
                Text(req.why, style = MaterialTheme.typography.bodySmall)

                val intent = req.fix(context)
                if (intent != null) {
                    Button(onClick = { context.startActivity(intent) }) { Text("Open settings") }
                }
            }
        }
    }
}
