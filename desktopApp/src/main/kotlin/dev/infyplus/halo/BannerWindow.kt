package dev.infyplus.halo

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.infyplus.halo.ui.Expression
import dev.infyplus.halo.ui.HaloState
import dev.infyplus.halo.ui.HeadsUpBanner
import dev.infyplus.halo.ui.apiCatching
import dev.infyplus.halo.ui.isConnectivity
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()

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
            onOpen = {
                state.dismissHeadsUp()
                state.requestScope(it.itemId)
                onOpenPanel()
            },
            onAct = { fired, verb ->
                state.dismissHeadsUp()
                val itemId = fired.itemId ?: return@HeadsUpBanner
                scope.launch {
                    apiCatching { api.act(itemId, verb) }
                        .onSuccess {
                            Notifications.dismissFor(itemId)
                            // Acting closes every open check-in for that item server-side, so the
                            // count drops by the item rather than by the occurrence.
                            state.setUnread((state.unread - 1).coerceAtLeast(0))
                            state.flash(
                                if (verb == "done") Expression.Happy else Expression.Wink,
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
}
