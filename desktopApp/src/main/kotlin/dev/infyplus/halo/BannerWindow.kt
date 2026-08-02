package dev.infyplus.halo

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.infyplus.halo.ui.HaloActions
import dev.infyplus.halo.ui.HaloState
import dev.infyplus.halo.ui.HeadsUpBanner

/**
 * The heads-up card, in its own always-on-top window.
 *
 * Separate from the bubble because the two want different sizes, and the bubble's window is
 * deliberately small so it does not eat clicks on whatever is behind it.
 *
 * Deliberately **not** screen-width like the Android one. `DesktopNotifier` already raises a tray
 * notification for the same event, and a full-width banner sitting beside the OS one reads as the
 * app alerting you twice. Top-right, notification-sized, is the convention here.
 *
 * `focusable = false` matters: stealing focus would interrupt whatever the user is typing in
 * another app, which is unforgivable for something that appears unbidden.
 */
@Composable
fun BannerWindow(
    api: HaloApi,
    state: HaloState = HaloState.shared,
    onOpenPanel: () -> Unit,
) {
    val notification = state.headsUp ?: return

    Window(
        onCloseRequest = { state.dismissHeadsUp() },
        state = rememberWindowState(
            size = DpSize(400.dp, 190.dp),
            position = WindowPosition(Alignment.TopEnd),
        ),
        title = "Halo",
        alwaysOnTop = true,
        undecorated = true,
        transparent = true,
        resizable = false,
        focusable = false,
    ) {
        HeadsUpBanner(
            notification = notification,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            reducedMotion = prefersReducedMotion(),
            waiting = state.headsUpWaiting,
            notice = state.notice,
            onOpen = {
                state.dismissHeadsUp()
                state.requestScope(it.itemId)
                onOpenPanel()
            },
            onAct = { fired, verb ->
                state.dismissHeadsUp()
                val itemId = fired.itemId ?: return@HeadsUpBanner
                // Deliberately NOT this window's scope. This window renders only while there is a
                // notification to show, so the dismiss above disposes it — and used to cancel the
                // very request it had just sent, silently. HaloActions outlives the window.
                HaloActions.act(api, itemId, fired.title, verb, state, retry = fired)
            },
            onDismiss = { state.dismissHeadsUp() },
        )
    }
}
