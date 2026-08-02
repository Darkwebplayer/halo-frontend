package dev.infyplus.halo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.infyplus.halo.ui.HaloButton
import dev.infyplus.halo.ui.HaloCard
import dev.infyplus.halo.ui.HaloField
import dev.infyplus.halo.ui.HaloPalette
import dev.infyplus.halo.ui.ItemRow
import dev.infyplus.halo.ui.Mono
import dev.infyplus.halo.ui.SummaryCard
import dev.infyplus.halo.ui.apiCatching
import dev.infyplus.halo.ui.describeRecurrence
import kotlinx.coroutines.launch

/**
 * Today: the digest, one input box, and the day's work grouped by what it belongs to.
 *
 * One input with three things it can do — capture something new, edit the plan conversationally
 * ("move this to tomorrow"), or ask a question. Voice needs no code here: the platform keyboard's
 * dictation writes into this same field, so dictated text follows the identical path as typed.
 *
 * The items are grouped by project because a flat list of everything answers "what is on my plate"
 * but not "what am I working on" — and the server has been sending `project_id`, `tags` and
 * `recurrence_id` on every row all along, which this screen simply never read.
 */
@Composable
fun CaptureScreen(
    api: HaloApi = remember { HaloApi(Config.baseUrl, Config.authToken) },
    /**
     * The plan as the assistant last left it. `POST /message` returns it whenever an action
     * changed something, so a task completed in chat disappears from here without a refetch.
     */
    planFromChat: Plan? = null,
    onChatAboutSummary: (SummaryKind, Summary) -> Unit = { _, _ -> },
) {
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var plan by remember { mutableStateOf<Plan?>(null) }
    // Shown on its own: an item captured for a future day won't be in today's plan.
    var captured by remember { mutableStateOf<Item?>(null) }
    var answer by remember { mutableStateOf<Answer?>(null) }
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var rules by remember { mutableStateOf<List<Recurrence>>(emptyList()) }
    var profile by remember { mutableStateOf<Profile?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        apiCatching { api.plan() }
            .onSuccess { plan = it; error = null }
            .onFailure { error = it.message }
        loading = false
    }

    // Names and cadences are fetched once and joined locally: items carry only a `project_id` and a
    // `recurrence_id`, and no endpoint returns either the project's name or the rule's sentence.
    // Free of charge: the assistant already had the server's answer, so adopting it beats asking
    // again. Only ever moves forward — a null means the chat has not changed anything yet.
    LaunchedEffect(planFromChat) { planFromChat?.let { plan = it } }

    LaunchedEffect(Config.baseUrl, Config.authToken) {
        refresh()
        apiCatching { api.projects() }.onSuccess { projects = it }
        apiCatching { api.recurrences() }.onSuccess { rules = it }
        apiCatching { api.profile() }.onSuccess { profile = it }
    }

    /** Every button shares this: run the call, surface failures, never half-apply locally. */
    fun submit(action: suspend (String) -> Unit) {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        scope.launch {
            busy = true
            error = null
            // try/finally: `busy` disables the buttons, so a cancellation escaping here would
            // leave the screen permanently unusable.
            try {
                apiCatching { action(text) }
                    .onSuccess { input = "" }
                    .onFailure {
                        // Nothing partial is left on screen: a failed call shows an error only.
                        captured = null
                        answer = null
                        error = it.message ?: "Something went wrong"
                    }
            } finally {
                busy = false
            }
        }
    }

    val projectNames = remember(projects) { projects.associate { it.id to it.name } }
    val cadences = remember(rules) {
        rules.associate { it.id to describeRecurrence(it.mode, it.weekdays, it.intervalDays, it.atHour) }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SummaryCard(api = api, profile = profile, onChat = onChatAboutSummary)

        HaloCard(Modifier.fillMaxWidth()) {
            Mono("WHAT'S ON YOUR MIND")
            HaloField(
                value = input,
                placeholder = "remind me to call mom tomorrow at 5pm",
                onChange = { input = it },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp),
                enabled = !busy,
                singleLine = false,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HaloButton(
                    label = if (busy) "Working…" else "Capture",
                    // Arm immediately rather than waiting for the next sync tick — otherwise
                    // "remind me in one minute" would be missed by several minutes.
                    enabled = !busy && input.isNotBlank(),
                ) {
                    submit {
                        captured = api.parse(it)
                        answer = null
                        Sync.once(api)
                        refresh()
                    }
                }

                HaloButton(
                    label = "Change plan",
                    enabled = !busy && input.isNotBlank(),
                    filled = false,
                ) {
                    // Also re-arms: "move this to tomorrow" changes when things fire.
                    submit {
                        plan = api.command(it)
                        captured = null
                        answer = null
                        Sync.once(api)
                    }
                }

                HaloButton(
                    label = "Ask",
                    enabled = !busy && input.isNotBlank(),
                    filled = false,
                ) { submit { answer = api.ask(it); captured = null } }
            }
        }

        error?.let {
            Text(
                text = it,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = HaloPalette.warm,
            )
        }

        captured?.let {
            Mono("CAPTURED", weight = FontWeight.Bold)
            ItemRow(it, cadence = it.recurrenceId?.let(cadences::get))
        }

        answer?.let { AnswerCard(it) }

        val current = plan
        val items = buildList {
            current?.rollover?.let(::addAll)
            current?.today?.let(::addAll)
        }

        when {
            // Three states, not one. "Nothing on the plan" used to show while the first fetch was
            // still in flight and again when the server could not be reached, so an outage read as
            // an empty day.
            loading -> Mono("READING YOUR DAY…", Modifier.padding(top = 16.dp))
            error != null && current == null ->
                Mono("COULDN'T REACH YOUR SERVER", Modifier.padding(top = 16.dp), color = HaloPalette.warm)
            items.isEmpty() && captured == null ->
                Mono("NOTHING ON THE PLAN", Modifier.padding(top = 16.dp))
        }

        // Carried-over work stays called out — it is the part most likely to be forgotten — and
        // the rest is grouped under whatever it belongs to.
        current?.rollover?.takeIf { it.isNotEmpty() }?.let { carried ->
            Mono("CARRIED OVER", weight = FontWeight.Bold)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                carried.forEach { ItemRow(it, cadence = it.recurrenceId?.let(cadences::get)) }
            }
        }

        current?.today?.takeIf { it.isNotEmpty() }?.let { today ->
            // Unfiled work last: a named project is a stronger signal than the absence of one.
            val groups = today.groupBy { it.projectId }
                .toList()
                .sortedWith(compareBy({ it.first == null }, { projectNames[it.first] ?: "" }))

            groups.forEach { (projectId, rows) ->
                Mono(
                    projectNames[projectId]?.uppercase() ?: "NO PROJECT",
                    Modifier.padding(top = 4.dp),
                    weight = FontWeight.Bold,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    rows.forEach { ItemRow(it, cadence = it.recurrenceId?.let(cadences::get)) }
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
    HaloCard(Modifier.fillMaxWidth()) {
        Mono("QUICK INFO")
        Text(
            answer.answer,
            Modifier.padding(top = 4.dp),
            fontSize = 13.sp,
            color = HaloPalette.navy,
        )
        answer.sources.forEach { source ->
            Text(
                "• ${source.title}",
                Modifier.padding(top = 4.dp),
                fontSize = 12.sp,
                color = HaloPalette.ink,
            )
            Mono(source.url, color = HaloPalette.warm)
        }
    }
}
