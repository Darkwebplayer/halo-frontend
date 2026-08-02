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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.infyplus.halo.Config
import dev.infyplus.halo.HaloApi
import dev.infyplus.halo.Item
import dev.infyplus.halo.Profile
import dev.infyplus.halo.Summary
import dev.infyplus.halo.SummaryKind
import dev.infyplus.halo.nowMillis
import dev.infyplus.halo.prefersReducedMotion
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * The morning and evening digests, on the tab where the day already lives.
 *
 * The server has always scheduled these notifications and always served the data behind them, but
 * nothing in the app ever called the endpoints — so a summary notification arrived with nowhere to
 * go. This is that somewhere.
 *
 * Two lines and a Read more rather than the whole thing: the digest is a glance, and a wall of
 * text at the top of Today would push the actual plan off the screen.
 */

/** How a digest reads at a glance: a count line, then the one thing most worth knowing. */
private fun digest(kind: SummaryKind, s: Summary, now: Long): Pair<String, String?> {
    fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"
    return if (kind == SummaryKind.Morning) {
        val head = buildList {
            add(plural(s.today.size, "thing today", "things today"))
            if (s.unattended.isNotEmpty()) add("${s.unattended.size} carried over")
        }.joinToString(" · ")
        head to s.today.firstOrNull()?.let { line(it, now) }
    } else {
        val head = buildList {
            add(plural(s.done.size, "done", "done"))
            if (s.open.isNotEmpty()) add("${s.open.size} still open")
        }.joinToString(" · ")
        head to s.open.firstOrNull()?.let { line(it, now) }
    }
}

private fun line(item: Item, now: Long): String =
    listOfNotNull(item.title, whenLabel(item.dueAt, now)).joinToString(" — ")

@Composable
private fun Section(title: String, items: List<Item>, now: Long) {
    if (items.isEmpty()) return
    Mono(title, Modifier.padding(top = 10.dp, bottom = 4.dp), weight = FontWeight.Bold)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { ItemRow(it, now = now) }
    }
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

    // Cached per kind per local day, so switching tabs back and forth costs nothing and does not
    // keep re-triggering the recurrence materialisation the endpoint performs.
    LaunchedEffect(kind, Config.baseUrl) {
        summary = SummaryStore.cached(kind, now)
        loading = summary == null
        error = null
        if (summary == null) {
            apiCatching { SummaryStore.load(api, kind, now) }
                .onSuccess { summary = it }
                .onFailure { error = it.message ?: "Could not load your summary" }
            loading = false
        }
    }

    val reduced = remember { prefersReducedMotion() }
    // Animating a fraction rather than a height keeps the reveal interruptible: grabbing Read more
    // again mid-expand re-targets from wherever it is instead of jumping to the end and back.
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
            // Three distinct states. They used to be one: an empty list rendered as "nothing here"
            // whether it was still loading, genuinely empty, or the server was unreachable.
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
                val (head, detail) = digest(kind, s, now)
                Text(head, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = HaloPalette.ink)
                detail?.let {
                    Text(
                        it,
                        Modifier.padding(top = 2.dp),
                        fontSize = 13.sp,
                        color = HaloPalette.navy.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (reveal > 0.01f) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clipToBounds()
                            // Scaled and faded together so the body reads as unfolding out of the
                            // two lines above it, anchored at the top edge where it came from.
                            .graphicsLayer {
                                alpha = reveal
                                scaleY = 0.96f + 0.04f * reveal
                                transformOrigin = TransformOrigin(0.5f, 0f)
                            }
                            // The height IS the animation: measured at full size, then given only
                            // as much room as the spring has reached. Everything below moves out
                            // of the way continuously rather than snapping once at the end.
                            .layout { measurable, constraints ->
                                val placed = measurable.measure(constraints)
                                val h = (placed.height * reveal).roundToInt().coerceAtLeast(0)
                                layout(placed.width, h) { placed.place(0, 0) }
                            },
                    ) {
                        if (kind == SummaryKind.Morning) {
                            Section("DUE TODAY", s.today, now)
                        } else {
                            Section("DONE", s.done, now)
                            Section("STILL OPEN", s.open, now)
                        }
                        Section("MISSED EARLIER", s.unattended, now)

                        if (s.staleWork.isNotEmpty()) {
                            Mono("STILL RUNNING", Modifier.padding(top = 10.dp, bottom = 4.dp), weight = FontWeight.Bold)
                            s.staleWork.forEach {
                                Text(
                                    listOfNotNull(
                                        it.note ?: "Untitled session",
                                        "${it.seconds / 3600}h so far",
                                        timeLabel(it.startAt)?.let { t -> "since $t" },
                                    ).joinToString(" · "),
                                    fontSize = 13.sp,
                                    color = HaloPalette.navy,
                                )
                            }
                        }

                        if (s.postponed.isNotEmpty()) {
                            Mono("KEEPS GETTING PUSHED", Modifier.padding(top = 10.dp, bottom = 4.dp), weight = FontWeight.Bold)
                            s.postponed.forEach {
                                Text(
                                    "${it.title} · moved ${it.times} times",
                                    fontSize = 13.sp,
                                    color = HaloPalette.navy,
                                )
                            }
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HaloButton(
                        label = if (expanded) "Show less" else "Read more",
                        filled = false,
                    ) { expanded = !expanded }
                    HaloButton(label = "Chat", filled = false) { onChat(kind, s) }
                    HaloButton(label = "Refresh", filled = false) {
                        scope.launch {
                            loading = true
                            error = null
                            apiCatching { SummaryStore.load(api, kind, now, force = true) }
                                .onSuccess { summary = it }
                                .onFailure { error = it.message ?: "Could not refresh" }
                            loading = false
                        }
                    }
                }
            }
        }
    }
}
