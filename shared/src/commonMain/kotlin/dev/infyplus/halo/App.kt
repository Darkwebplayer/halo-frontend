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
import dev.infyplus.halo.ui.HaloIcon
import dev.infyplus.halo.ui.HaloTab
import dev.infyplus.halo.ui.HaloTheme

enum class AppSection { Today, Focus, Projects, Assistant, Settings }

/**
 * The glyph each section is known by while it is not the one being looked at.
 *
 * Assistant borrows the speech bubble the orb talks in, so the tab and the thing it opens are
 * plainly the same feature.
 */
private fun AppSection.icon() = when (this) {
    AppSection.Today -> HaloIcon.Today
    AppSection.Focus -> HaloIcon.Focus
    AppSection.Projects -> HaloIcon.Projects
    AppSection.Assistant -> HaloIcon.Chat
    AppSection.Settings -> HaloIcon.Settings
}

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
    /**
     * Which tab to open on. The tray's "Open Halo" uses it to land on the assistant when something
     * is waiting, rather than always dropping the user on Today and letting them find it.
     */
    initialSection: AppSection = AppSection.Today,
) {
    var section by remember { mutableStateOf(initialSection) }

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
                            HaloTab(s.name, section == s, icon = s.icon()) { section = s }
                        }
                    }
                }

                when (section) {
                    // Scrollable, and deliberately not a LazyColumn: the summary card, the capture
                    // box and the grouped plan are one continuous column, and a lazy list nested
                    // inside a scrolling parent throws.
                    AppSection.Today -> Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    ) {
                        CaptureScreen(
                            api = api,
                            planFromChat = conversation.latestPlan,
                        ) { kind, summary ->
                            conversation.referTo(kind, summary)
                            section = AppSection.Assistant
                        }
                    }
                    // Scrollable because the dial, the task card and four steppers do not fit a
                    // phone in portrait.
                    AppSection.Focus -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        FocusScreen(api)
                    }
                    AppSection.Projects -> Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    ) {
                        ProjectsScreen(api)
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
