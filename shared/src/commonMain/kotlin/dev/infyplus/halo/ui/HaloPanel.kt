package dev.infyplus.halo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.infyplus.halo.CheckIn
import dev.infyplus.halo.Item
import dev.infyplus.halo.HaloApi
import dev.infyplus.halo.PomodoroStrip
import dev.infyplus.halo.attentionCount
import kotlinx.coroutines.launch

private val PanelShape = HaloShapes.Panel
private val CardShape = HaloShapes.Card
private val Pill = HaloShapes.Pill

/** Fixed 30 minutes server-side, so the chip says so rather than implying a choice. */
private const val SNOOZE_LABEL = "Snooze 30m"

enum class PanelTab { Chat, Notifications }

/**
 * The expanded assistant: a conversation, and the list of what has already fired.
 *
 * Deliberately two tabs rather than one merged feed. A notification is a thing that *happened*
 * and may still want a decision; the chat is a thing you are *doing*. Interleaving them would
 * bury a pending reminder under whatever you last asked.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HaloPanel(
    api: HaloApi,
    modifier: Modifier = Modifier,
    state: HaloState = HaloState.shared,
    conversation: HaloConversation = remember(api) { HaloConversation(api, state) },
    initialScope: Item? = null,
    initialTab: PanelTab = PanelTab.Chat,
    onClose: () -> Unit = {},
    /**
     * False when the panel is embedded in a screen it does not own — the in-app Assistant tab.
     * There is no orb straddling the top edge there and nothing to close back to, so both the
     * Close affordance and the padding that clears the orb come off.
     */
    showClose: Boolean = true,
    /**
     * Open the full app. Supplied by the overlay hosts; null when the panel already *is* the app,
     * which is what keeps the title from being a button that goes nowhere.
     */
    onOpenApp: (() -> Unit)? = null,
) {
    var tab by remember { mutableStateOf(initialTab) }
    var draft by remember { mutableStateOf("") }
    var checkins by remember { mutableStateOf<List<CheckIn>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (conversation.entries.isEmpty()) conversation.scopeTo(initialScope)
    }

    suspend fun refreshCheckins() {
        apiCatching { api.checkins() }
            .onSuccess { checkins = it; state.setUnread(it.attentionCount()) ; state.markOffline(false) }
            .onFailure { state.markOffline(it.isConnectivity()) }
    }

    // Loaded as soon as the panel opens, not only when the Alerts tab is selected: the badge is
    // derived from this, and a tapped system notification needs the list to resolve its item.
    LaunchedEffect(Unit) { refreshCheckins() }
    LaunchedEffect(tab) { if (tab == PanelTab.Notifications) refreshCheckins() }

    /**
     * Hand the user back to the list once a notification has been dealt with.
     *
     * Staying on the scoped card leaves three buttons that have already been pressed and a
     * conversation about something now settled — the next question is always "what else is
     * waiting?". The pause is so the confirmation is actually read before the view changes.
     */
    suspend fun returnToAlerts() {
        kotlinx.coroutines.delay(1_200)
        conversation.scopeTo(null)
        tab = PanelTab.Notifications
        refreshCheckins()
    }

    // Somebody outside the UI asked for a specific item — a tapped system notification. Resolve
    // it against the history we just loaded and open the chat on it, which is exactly what
    // tapping a row in the Alerts list does.
    LaunchedEffect(state.pendingTab) {
        state.pendingTab?.let { tab = it; state.clearPendingTab() }
    }

    LaunchedEffect(state.pendingScopeId, checkins) {
        val wanted = state.pendingScopeId ?: return@LaunchedEffect
        val item = checkins.firstNotNullOfOrNull { c -> c.item?.takeIf { it.id == wanted } }
        if (item != null) {
            conversation.scopeTo(item)
            tab = PanelTab.Chat
            state.clearPendingScope()
        }
    }

    Column(
        modifier
            .clip(PanelShape)
            .background(HaloPalette.cream)
            .border(2.dp, HaloPalette.navy, PanelShape)
            // Clears the half of the orb that hangs over this edge. Too small and the orb sits
            // on top of the tab row, swallowing taps on it — which looks like a dead tab.
            .padding(start = 16.dp, end = 16.dp, top = if (showClose) 70.dp else 16.dp, bottom = 14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The title doubles as the way into the full app. Underlined only when it actually
            // does something, so it never looks like a dead affordance in the embedded panel.
            Text(
                "Halo",
                modifier = if (onOpenApp != null) {
                    Modifier.clickable(onClick = onOpenApp).padding(vertical = 2.dp)
                } else {
                    Modifier
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = HaloPalette.ink,
                textDecoration = if (onOpenApp != null) TextDecoration.Underline else null,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HaloTab("Chat", tab == PanelTab.Chat) { tab = PanelTab.Chat }
                HaloTab(
                    label = if (state.unread > 0) "Alerts ${state.unread}" else "Alerts",
                    selected = tab == PanelTab.Notifications,
                ) { tab = PanelTab.Notifications }
                if (showClose) Mono("Close", Modifier.clickable(onClick = onClose).padding(4.dp))
            }
        }

        // Above the tab body rather than inside it, so the timer is reachable from Chat and Alerts
        // alike — the whole point of putting it here is not having to hunt for it.
        PomodoroStrip(modifier = Modifier.padding(bottom = 10.dp))

        when (tab) {
            PanelTab.Chat -> {
                conversation.scope?.let { item ->
                    ScopedCard(item) { verb ->
                        scope.launch {
                            apiCatching { api.act(item.id, verb) }
                                .onSuccess {
                                    // The notification about it is now stale wherever it shows.
                                    dev.infyplus.halo.Notifications.dismissFor(item.id)
                                    conversation.entries.add(
                                        ThreadEntry.Routed("command → edit on known item", question = false),
                                    )
                                    conversation.entries.add(
                                        ThreadEntry.Said(verb.pastTense(item.title), fromUser = false),
                                    )
                                    state.setUnread((state.unread - 1).coerceAtLeast(0))
                                    state.flash(Expression.Happy, 1600)
                                    refreshCheckins()
                                    returnToAlerts()
                                }
                                .onFailure { state.markOffline(it.isConnectivity()) }
                        }
                    }
                }
                ThreadView(conversation.entries, Modifier.weight(1f))
            }

            PanelTab.Notifications -> NotificationList(
                checkins = checkins,
                modifier = Modifier.weight(1f),
                onOpen = { checkin ->
                    // Tapping a notification reveals its item: scope the conversation to it and
                    // switch to the chat, which is where you can actually do something about it.
                    checkin.item?.let {
                        conversation.scopeTo(it)
                        tab = PanelTab.Chat
                    }
                },
            )
        }

        Composer(
            value = draft,
            enabled = !conversation.busy,
            placeholder = if (conversation.scope != null) "Reply — e.g. push to 6pm" else "Ask or tell me anything",
            onChange = { draft = it },
            onSend = {
                val text = draft
                draft = ""
                scope.launch {
                    val settled = conversation.send(text)
                    // Answering a notification in prose settles it just as much as tapping a
                    // chip does, so it goes back to the list the same way.
                    if (settled) returnToAlerts()
                }
            },
        )

        if (state.offline) {
            Text(
                "Connection lost · nothing is being sent",
                Modifier.fillMaxWidth().padding(top = 6.dp),
                color = HaloPalette.warm,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private fun String.pastTense(title: String) = when (this) {
    "done" -> "Marked “$title” done."
    "snooze" -> "Snoozed “$title” 30 minutes."
    else -> "Moved “$title” to tomorrow, 9:00 AM."
}

/** The item a notification fired for, with the only three actions the server actually supports. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScopedCard(item: Item, onAct: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(CardShape)
            .background(HaloPalette.body.copy(alpha = 0.22f))
            .border(2.dp, HaloPalette.navy.copy(alpha = 0.30f), CardShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Mono(item.kind.uppercase(), color = HaloPalette.warm, weight = FontWeight.Bold)
        Text(
            item.title,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = HaloPalette.ink,
            modifier = Modifier.padding(top = 3.dp),
        )
        item.dueAt?.let { Mono(it, color = HaloPalette.navy.copy(alpha = 0.85f)) }

        FlowRow(
            Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HaloChip(SNOOZE_LABEL) { onAct("snooze") }
            HaloChip("Tomorrow 9am") { onAct("reschedule") }
            HaloChip("Mark done") { onAct("done") }
        }
    }
}

@Composable
private fun ThreadView(entries: List<ThreadEntry>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.lastIndex)
    }

    LazyColumn(modifier.fillMaxWidth(), listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(entries) { entry ->
            when (entry) {
                is ThreadEntry.Said -> Bubble(entry.text, entry.fromUser)
                is ThreadEntry.Routed -> RouteChip(entry)
                is ThreadEntry.Info -> InfoCard(entry.answer)
                is ThreadEntry.Card -> CapturedCard(entry.item)
                ThreadEntry.Typing -> TypingDots()
            }
        }
    }
}

@Composable
private fun Bubble(text: String, fromUser: Boolean) {
    val shape = if (fromUser) {
        RoundedCornerShape(16.dp, 16.dp, 5.dp, 16.dp)
    } else {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 5.dp)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start) {
        Box(
            Modifier
                .fillMaxWidth(0.84f)
                .clip(shape)
                .background(if (fromUser) HaloPalette.navy else HaloPalette.body.copy(alpha = 0.40f))
                .then(
                    if (fromUser) Modifier
                    else Modifier.border(2.dp, HaloPalette.navy.copy(alpha = 0.24f), shape),
                )
                .padding(horizontal = 13.dp, vertical = 9.dp),
        ) {
            Text(
                text,
                fontSize = 14.sp,
                color = if (fromUser) HaloPalette.cream else HaloPalette.ink,
            )
        }
    }
}

@Composable
private fun RouteChip(entry: ThreadEntry.Routed) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(if (entry.question) HaloPalette.warm else HaloPalette.navy),
        )
        Mono(entry.label.uppercase())
    }
}

@Composable
private fun InfoCard(answer: dev.infyplus.halo.Answer) {
    Column(
        Modifier
            .fillMaxWidth(0.84f)
            .clip(CardShape)
            .border(2.dp, HaloPalette.navy.copy(alpha = 0.30f), CardShape)
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Mono("QUICK INFO")
        Text(answer.answer, fontSize = 13.sp, color = HaloPalette.navy)
        // Sources are shown whenever they exist — the point of grounding is that a claim can be
        // checked rather than taken on faith.
        answer.sources.forEach { source ->
            Text("• ${source.title}", fontSize = 12.sp, color = HaloPalette.ink)
            Mono(source.url, color = HaloPalette.warm)
        }
    }
}

@Composable
private fun CapturedCard(item: Item) {
    Column(
        Modifier
            .fillMaxWidth(0.84f)
            .clip(CardShape)
            .background(HaloPalette.sun.copy(alpha = 0.28f))
            .border(2.dp, HaloPalette.navy.copy(alpha = 0.30f), CardShape)
            .padding(horizontal = 13.dp, vertical = 10.dp),
    ) {
        Mono("CAPTURED · ${item.kind.uppercase()}")
        Text(item.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = HaloPalette.ink)
        item.dueAt?.let { Mono(it, color = HaloPalette.navy.copy(alpha = 0.85f)) }
    }
}

@Composable
private fun TypingDots() {
    Row(
        Modifier
            .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 5.dp))
            .background(HaloPalette.body.copy(alpha = 0.40f))
            .padding(horizontal = 15.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(3) {
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(999.dp)).background(HaloPalette.navy))
        }
    }
}

/** What has already fired, newest first. */
@Composable
private fun NotificationList(
    checkins: List<CheckIn>,
    modifier: Modifier = Modifier,
    onOpen: (CheckIn) -> Unit,
) {
    if (checkins.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Mono("NOTHING HAS FIRED YET")
        }
        return
    }

    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(checkins) { checkin ->
            val item = checkin.item
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(CardShape)
                    .background(
                        if (checkin.open) HaloPalette.sun.copy(alpha = 0.22f)
                        else HaloPalette.body.copy(alpha = 0.14f),
                    )
                    .border(2.dp, HaloPalette.navy.copy(alpha = 0.22f), CardShape)
                    .clickable(enabled = item != null) { onOpen(checkin) }
                    .padding(horizontal = 13.dp, vertical = 10.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Mono(checkin.sentAt.takeLast(14).take(5)) // HH:MM out of the ISO instant
                    Mono(
                        text = checkin.outcome?.uppercase() ?: "NEEDS A DECISION",
                        color = if (checkin.open) HaloPalette.warm else HaloPalette.navy.copy(alpha = 0.6f),
                        weight = if (checkin.open) FontWeight.Bold else FontWeight.Normal,
                    )
                }
                Text(
                    // A summary or general nudge carries no item, so it has no title of its own.
                    text = item?.title ?: "Check-in",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = HaloPalette.ink,
                )
                if (item == null) Mono("NO ITEM ATTACHED")
            }
        }
    }
}

@Composable
private fun Composer(
    value: String,
    enabled: Boolean,
    placeholder: String,
    onChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HaloField(
            value = value,
            placeholder = placeholder,
            onChange = onChange,
            modifier = Modifier.weight(1f),
            enabled = enabled,
        )
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(if (enabled) HaloPalette.sun else HaloPalette.sun.copy(alpha = 0.45f))
                .border(2.dp, HaloPalette.navy, RoundedCornerShape(999.dp))
                .clickable(enabled = enabled && value.isNotBlank(), onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            Text("→", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = HaloPalette.ink)
        }
    }
}

// Tab, Chip and Mono now live in HaloControls.kt as HaloTab/HaloChip/Mono — the in-app screens
// needed the same vocabulary, and a second private copy of it was how the two halves of this app
// drifted apart in the first place.
