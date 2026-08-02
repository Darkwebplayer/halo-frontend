package dev.infyplus.halo

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.infyplus.halo.ui.HaloState
import dev.infyplus.halo.ui.HeadsUpBanner
import dev.infyplus.halo.ui.apiCatching
import dev.infyplus.halo.ui.isConnectivity
import kotlinx.coroutines.launch

/**
 * The heads-up banner's own window content.
 *
 * Acting from here talks to the server directly rather than going through the panel: the whole
 * point of the banner is answering a reminder without opening anything.
 */
@Composable
fun HaloBannerHost(
    api: HaloApi,
    onOpenPanel: () -> Unit,
    state: HaloState = HaloState.shared,
) {
    val scope = rememberCoroutineScope()

    HeadsUpBanner(
        notification = state.headsUp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
        reducedMotion = prefersReducedMotion(),
        onOpen = {
            state.dismissHeadsUp()
            state.requestScope(it.itemId)
            onOpenPanel()
        },
        onAct = { notification, verb ->
            state.dismissHeadsUp()
            val itemId = notification.itemId ?: return@HeadsUpBanner
            scope.launch {
                apiCatching { api.act(itemId, verb) }
                    .onSuccess {
                        Notifications.dismissFor(itemId)
                        // Acting answers every open check-in for that item server-side, so the
                        // local count drops by the item, not by the occurrence.
                        state.noteUnread((state.unread - 1).coerceAtLeast(0))
                        state.flash(
                            if (verb == "done") dev.infyplus.halo.ui.Expression.Happy
                            else dev.infyplus.halo.ui.Expression.Wink,
                            1600,
                        )
                    }
                    // Only a failure to *reach* the server is offline. A refusal means we got
                    // there, and greying the cat out for it would blame the wrong thing.
                    .onFailure { state.markOffline(it.isConnectivity()) }
            }
        },
        onDismiss = { state.dismissHeadsUp() },
    )
}
