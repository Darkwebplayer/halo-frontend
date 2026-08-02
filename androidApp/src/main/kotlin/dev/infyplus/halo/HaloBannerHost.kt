package dev.infyplus.halo

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.infyplus.halo.ui.HaloActions
import dev.infyplus.halo.ui.HaloState
import dev.infyplus.halo.ui.HeadsUpBanner

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
    HeadsUpBanner(
        notification = state.headsUp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
        reducedMotion = prefersReducedMotion(),
        notice = state.notice,
        onOpen = {
            state.dismissHeadsUp()
            state.requestScope(it.itemId)
            onOpenPanel()
        },
        onAct = { notification, verb ->
            state.dismissHeadsUp()
            val itemId = notification.itemId ?: return@HeadsUpBanner
            // Not on this composition's scope: the dismiss above can take the banner off screen
            // before the answer lands, and a request cancelled by its own success looks exactly
            // like one that never happened. HaloActions owns the outcome and the reporting.
            HaloActions.act(api, itemId, notification.title, verb, state, retry = notification)
        },
        onDismiss = { state.dismissHeadsUp() },
    )
}
