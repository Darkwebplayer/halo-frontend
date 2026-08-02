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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.infyplus.halo.ui.HaloCard
import dev.infyplus.halo.ui.HaloPalette
import dev.infyplus.halo.ui.ItemRow
import dev.infyplus.halo.ui.Mono
import dev.infyplus.halo.ui.apiCatching
import dev.infyplus.halo.ui.describeRecurrence

/**
 * Every project and what is open under it.
 *
 * Read-only on purpose: projects are made by asking for one in chat, which already handles naming,
 * duplicates and filing something into it in the same breath. A form here would be a second way to
 * do something that has a good one.
 *
 * Note that naming a project while capturing does not create it. An unrecognised name files the
 * item nowhere, on purpose — otherwise a typo would mint a project nobody wanted, and the list
 * would fill with near-duplicates of real ones.
 *
 * The plan is the source of open work: items carry a `project_id` and nothing else, so the join
 * happens here.
 */
@Composable
fun ProjectsScreen(api: HaloApi = remember { HaloApi(Config.baseUrl, Config.authToken) }) {
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var items by remember { mutableStateOf<List<Item>>(emptyList()) }
    var rules by remember { mutableStateOf<List<Recurrence>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Config.baseUrl, Config.authToken) {
        apiCatching { api.projects() }
            .onSuccess { projects = it; error = null }
            .onFailure { error = it.message }
        apiCatching { api.plan() }
            .onSuccess { items = it.rollover + it.today }
            .onFailure { if (error == null) error = it.message }
        apiCatching { api.recurrences() }.onSuccess { rules = it }
        loading = false
    }

    val cadences = remember(rules) {
        rules.associate { it.id to describeRecurrence(it.mode, it.weekdays, it.intervalDays, it.atHour) }
    }
    val byProject = remember(items) { items.groupBy { it.projectId } }

    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            // The same three-way split the rest of the app now makes: still loading, genuinely
            // empty, and could not ask — each says something different to the person reading it.
            loading -> Mono("LOADING YOUR PROJECTS…")
            error != null && projects.isEmpty() -> Column {
                Mono("COULDN'T REACH YOUR SERVER", color = HaloPalette.warm, weight = FontWeight.Bold)
                Text(
                    error ?: "",
                    Modifier.padding(top = 4.dp),
                    fontSize = 12.sp,
                    color = HaloPalette.navy.copy(alpha = 0.8f),
                )
            }
            projects.isEmpty() -> Column {
                Mono("NO PROJECTS YET", weight = FontWeight.Bold)
                Text(
                    // Naming a project while capturing does NOT create it — an unknown name files
                    // the item nowhere, deliberately, so a typo cannot mint one. Asking for it is
                    // the way, and the assistant can do both in a single message.
                    "Ask for one — try \"create a Zenith project\", or \"create a Zenith project " +
                        "and add ship the API to it\".",
                    Modifier.padding(top = 6.dp),
                    fontSize = 13.sp,
                    color = HaloPalette.navy.copy(alpha = 0.85f),
                )
            }
            else -> projects.forEach { project ->
                val open = byProject[project.id].orEmpty().filter { it.doneAt.isNullOrBlank() }
                HaloCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            project.name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = HaloPalette.ink,
                        )
                        // Archived and paused projects stay listed. They are where finished work
                        // lives, and hiding them would make a project look deleted.
                        Mono(
                            project.status.uppercase(),
                            color = if (project.status == "active") HaloPalette.navy else HaloPalette.warm,
                        )
                    }

                    Mono(
                        if (open.isEmpty()) "NOTHING OPEN" else "${open.size} OPEN",
                        Modifier.padding(top = 4.dp, bottom = if (open.isEmpty()) 0.dp else 8.dp),
                    )

                    project.notes?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            Modifier.padding(top = 4.dp),
                            fontSize = 12.sp,
                            color = HaloPalette.navy.copy(alpha = 0.8f),
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        open.forEach {
                            ItemRow(it, cadence = it.recurrenceId?.let(cadences::get))
                        }
                    }
                }
            }
        }
    }
}
