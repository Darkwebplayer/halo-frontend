package dev.infyplus.halo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.infyplus.halo.ui.HaloButton
import kotlinx.coroutines.launch

/**
 * One input box with two things it can do: capture something new, or edit the plan
 * conversationally ("move this to tomorrow", "bump the report up").
 *
 * Voice needs no code here — the platform keyboard's dictation writes into this same field,
 * so dictated text follows the identical path as typed text.
 *
 * Slice 5 re-hosts this composable inside the always-available overlay unchanged.
 */
@Composable
fun CaptureScreen(api: HaloApi = remember { HaloApi(Config.BASE_URL, Config.AUTH_TOKEN) }) {
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var plan by remember { mutableStateOf<Plan?>(null) }
    // Shown on its own: an item captured for a future day won't be in today's plan.
    var captured by remember { mutableStateOf<Item?>(null) }
    var answer by remember { mutableStateOf<Answer?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        runCatching { api.plan() }
            .onSuccess { plan = it }
            .onFailure { error = it.message }
    }

    LaunchedEffect(Unit) { refresh() }

    /** Both buttons share this: run the call, surface failures, never half-apply locally. */
    fun submit(action: suspend (String) -> Unit) {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        scope.launch {
            busy = true
            error = null
            runCatching { action(text) }
                .onSuccess { input = "" }
                .onFailure {
                    // Nothing partial is left on screen: a failed call shows an error only.
                    captured = null
                    answer = null
                    error = it.message ?: "Something went wrong"
                }
            busy = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("What's on your mind?") },
            placeholder = { Text("remind me to call mom tomorrow at 5pm") },
            enabled = !busy,
            singleLine = false,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HaloButton(
                "Capture",
                // Arm immediately rather than waiting for the next sync tick — otherwise
                // "remind me in one minute" would be missed by several minutes.
                onClick = {
                    submit {
                        captured = api.parse(it)
                        answer = null
                        Sync.once(api)
                        refresh()
                    }
                },
                enabled = !busy && input.isNotBlank(),
            )

            HaloButton(
                "Change plan",
                // Also re-arms: "move this to tomorrow" changes when things fire.
                onClick = {
                    submit {
                        plan = api.command(it)
                        captured = null
                        answer = null
                        Sync.once(api)
                    }
                },
                enabled = !busy && input.isNotBlank(),
                filled = false,
            )

            HaloButton(
                "Ask",
                onClick = { submit { answer = api.ask(it); captured = null } },
                enabled = !busy && input.isNotBlank(),
                filled = false,
            )

            if (busy) CircularProgressIndicator(modifier = Modifier.padding(4.dp))
        }

        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        captured?.let {
            Text("Captured", style = MaterialTheme.typography.labelLarge)
            ItemRow(it)
        }

        answer?.let { AnswerCard(it) }

        val current = plan
        val empty = current == null || (current.rollover.isEmpty() && current.today.isEmpty())

        if (empty && captured == null && !busy) {
            Text(
                text = "Nothing on the plan.",
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (current != null) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (current.rollover.isNotEmpty()) {
                    item { Text("Carried over", style = MaterialTheme.typography.labelLarge) }
                    items(current.rollover, key = { it.id }) { ItemRow(it) }
                }
                if (current.today.isNotEmpty()) {
                    item { Text("Today", style = MaterialTheme.typography.labelLarge) }
                    items(current.today, key = { it.id }) { ItemRow(it) }
                }
            }
        }
    }
}

/**
 * A quick answer with the pages it came from. Sources are always listed — the whole point of
 * grounding is that the user can check the claim rather than take it on faith.
 */
@Composable
private fun AnswerCard(answer: Answer) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(answer.answer, style = MaterialTheme.typography.bodyMedium)
            if (answer.sources.isNotEmpty()) {
                Text("Sources", style = MaterialTheme.typography.labelMedium)
                answer.sources.forEach { source ->
                    Text(
                        text = "• ${source.title}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = source.url,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ItemRow(item: Item) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = listOfNotNull(item.kind, item.dueAt, "P${item.priority}").joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
