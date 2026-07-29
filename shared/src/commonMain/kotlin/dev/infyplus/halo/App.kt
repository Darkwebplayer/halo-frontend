package dev.infyplus.halo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.infyplus.halo.ui.HaloConversation
import dev.infyplus.halo.ui.HaloPalette
import dev.infyplus.halo.ui.HaloPanel
import dev.infyplus.halo.ui.HaloState
import dev.infyplus.halo.ui.HaloTab
import dev.infyplus.halo.ui.HaloTheme

enum class AppSection { Today, Focus, Assistant, Settings }

/**
 * The app proper, as opposed to the overlay: the same three things the popup can do, with room to
 * do them in.
 *
 * Assistant embeds [HaloPanel] rather than reimplementing it. That is the whole reason the two
 * surfaces cannot drift — there is one chat, one alerts list, one set of behaviours, rendered at
 * two sizes.
 */
@Composable
@Preview
fun App(
    api: HaloApi = remember { HaloApi(Config.baseUrl, Config.authToken) },
    /**
     * Hoisted by the desktop host so the main window and the popup share one transcript. Left to
     * its default on Android, where only one panel is ever mounted at a time.
     */
    conversation: HaloConversation = remember(api) { HaloConversation(api, HaloState.shared) },
    /**
     * Fired after new credentials are saved, for hosts holding a [HaloApi] that Compose cannot
     * rebuild for them — on Android, the one the overlay service closed over.
     */
    onCredentialsChanged: () -> Unit = {},
) {
    var section by remember { mutableStateOf(AppSection.Today) }

    HaloTheme {
        Surface(modifier = Modifier.fillMaxSize().safeContentPadding()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Halo", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = HaloPalette.ink)
                    Row(Modifier.padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AppSection.entries.forEach { s ->
                            HaloTab(s.name, section == s) { section = s }
                        }
                    }
                }

                when (section) {
                    AppSection.Today -> CaptureScreen(api)
                    // Scrollable because the dial, the task card and four steppers do not fit a
                    // phone in portrait.
                    AppSection.Focus -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        FocusScreen(api)
                    }
                    AppSection.Assistant -> HaloPanel(
                        api = api,
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        conversation = conversation,
                        showClose = false,
                    )
                    AppSection.Settings -> Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    ) {
                        SettingsScreen(onSaved = onCredentialsChanged)
                    }
                }
            }
        }
    }
}
