package dev.infyplus.halo.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.infyplus.halo.Config
import dev.infyplus.halo.HaloApi
import dev.infyplus.halo.Profile
import dev.infyplus.halo.Summary
import dev.infyplus.halo.SummaryKind
import dev.infyplus.halo.nowMillis
import dev.infyplus.halo.prefersReducedMotion
import kotlinx.coroutines.launch

/**
 * The day, in a few sentences.
 *
 * This used to render every task as a card inside a card, which said the same thing the list below
 * already said and took more room to say it. The server now writes a short description — once per
 * summary occurrence, in the user's own voice — and the job here is to show it, offer the rest, and
 * get out of the way.
 *
 * No local cache: the server writes the description once per occurrence and serves it from a column
 * after that, so a second cache here could only ever disagree with it.
 */

/**
 * What a summary actually says: the server's own description, or counts when it wrote none.
 *
 * One function because two places show a summary — this card and the reference card above the
 * chat it opens — and they were disagreeing. The chat card recomputed the counts from scratch, so
 * tapping "Chat" on a paragraph about your day replaced it with "3 due today · 1 carried over".
 */
internal fun summaryLine(kind: SummaryKind, summary: Summary): String =
    summary.description.ifBlank { counts(kind, summary) }

/**
 * What to call a summary that is not today's, and when the next one lands.
 *
 * Both halves matter. "Yesterday's" alone reads like something is broken; the time is what makes it
 * read as waiting rather than as stale. The time comes from the profile the card already holds, so
 * someone whose morning summary is at 08:30 is told 08:30 — the whole reason this is not a
 * hardcoded hour.
 */
internal fun staleLabel(kind: SummaryKind, profile: Profile?): String {
    val name = if (kind == SummaryKind.Morning) "MORNING" else "EVENING"
    val at = if (kind == SummaryKind.Morning) profile?.morningSummaryTime else profile?.eveningSummaryTime
    return buildString {
        append("YESTERDAY'S $name SUMMARY")
        if (!at.isNullOrBlank()) append(" · TODAY'S ARRIVES AT $at")
    }
}

/** The plain fallback when no description could be written. Counts are better than an empty card. */
private fun counts(kind: SummaryKind, s: Summary): String =
    if (kind == SummaryKind.Morning) {
        val n = s.today.size
        buildString {
            append(if (n == 1) "One thing today" else "$n things today")
            if (s.unattended.isNotEmpty()) append(", ${s.unattended.size} carried over")
            append(".")
        }
    } else {
        "${s.done.size} done, ${s.open.size} still open."
    }

/**
 * @param onChat opens the assistant with this digest shown for reference. It carries no special
 *   scope: the model already receives the whole item list on every message, so a normal
 *   conversation can answer anything the summary raises. The card is there for the reader.
 */
@Composable
fun SummaryCard(
    api: HaloApi,
    profile: Profile?,
    modifier: Modifier = Modifier,
    onChat: (SummaryKind, Summary) -> Unit = { _, _ -> },
) {
    val now = nowMillis()
    // Evening once the hour the user set for it has passed; morning before that. Reading the
    // user's own setting rather than a hardcoded hour is what makes it land right for someone
    // whose evening summary is at 21:15.
    var kind by remember(profile) {
        mutableStateOf(
            if (profile != null && hasPassed(profile.eveningSummaryTime, now)) SummaryKind.Evening
            else SummaryKind.Morning,
        )
    }
    var summary by remember { mutableStateOf<Summary?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    var rewriting by remember { mutableStateOf(false) }

    suspend fun load() {
        loading = true
        error = null
        apiCatching { api.summary(kind) }
            .onSuccess { summary = it }
            .onFailure { error = it.message ?: "Could not load your summary" }
        loading = false
    }

    LaunchedEffect(kind, Config.baseUrl) { load() }

    val reduced = remember { prefersReducedMotion() }
    // Animating the collapsed line count rather than a height keeps the reveal interruptible:
    // tapping again mid-expand re-targets from wherever it is instead of jumping and coming back.
    val reveal by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = if (reduced) snap() else spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "summaryReveal",
    )

    HaloCard(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HaloTab("Morning", kind == SummaryKind.Morning) {
                kind = SummaryKind.Morning; expanded = false
            }
            HaloTab("Evening", kind == SummaryKind.Evening) {
                kind = SummaryKind.Evening; expanded = false
            }
        }

        val s = summary
        when {
            // Three distinct states. They used to be one: an empty card read the same whether it
            // was still loading, genuinely empty, or the server was unreachable.
            loading -> Mono("READING YOUR DAY…")
            error != null -> Column {
                Mono("COULDN'T REACH YOUR SERVER", color = HaloPalette.warm, weight = FontWeight.Bold)
                Text(
                    error ?: "",
                    Modifier.padding(top = 4.dp),
                    fontSize = 12.sp,
                    color = HaloPalette.navy.copy(alpha = 0.8f),
                )
            }
            s == null -> Mono("NOTHING TO SUMMARISE YET")
            else -> {
                val text = summaryLine(kind, s)
                // Three lines collapsed, all of it expanded. Interpolated rather than switched so
                // the growth is continuous and can be reversed halfway.
                val lines = (3 + reveal * 17).toInt()

                // Whose day this is. Said only when the text actually came out of storage — a
                // label on the current summary would be noise, and one on a description written a
                // moment ago from today's own list would be wrong.
                if (s.summarySaved && s.description.isNotBlank()) {
                    Mono(
                        staleLabel(kind, profile),
                        Modifier.padding(bottom = 6.dp),
                        color = HaloPalette.navy.copy(alpha = 0.6f),
                    )
                }

                Text(
                    text,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    color = HaloPalette.ink,
                    maxLines = lines,
                    overflow = TextOverflow.Ellipsis,
                )

                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Only offered when there is more to see. A Read more that reveals nothing is
                    // worse than no button.
                    if (text.length > 140) {
                        HaloButton(
                            label = if (expanded) "Less" else "Read more",
                            filled = false,
                        ) { expanded = !expanded }
                    }
                    HaloButton(label = "Chat", filled = false) { onChat(kind, s) }
                    // Only on a summary that is actually today's. Re-reading an older one could
                    // not change it — the server serves the same saved column — and rewriting one
                    // is refused outright, so a button here would have been a button that lied.
                    //
                    // It is also no longer a re-fetch. "Refresh" always meant "write me a
                    // different one", and now that is what it does.
                    if (s.summaryFresh) {
                        HaloButton(
                            label = if (rewriting) "Rewriting…" else "Rewrite",
                            filled = false,
                            enabled = !rewriting,
                        ) {
                            scope.launch {
                                rewriting = true
                                apiCatching { api.refreshSummary(kind) }
                                    .onSuccess { summary = it }
                                    .onFailure { error = it.message ?: "Could not rewrite it" }
                                rewriting = false
                            }
                        }
                    }
                }
            }
        }
    }
}
