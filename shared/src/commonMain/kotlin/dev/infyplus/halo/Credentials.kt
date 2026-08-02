package dev.infyplus.halo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.infyplus.halo.ui.HaloButton
import dev.infyplus.halo.ui.HaloCard
import dev.infyplus.halo.ui.HaloPalette
import dev.infyplus.halo.ui.HaloField
import dev.infyplus.halo.ui.Mono
import dev.infyplus.halo.ui.TailVisible
import dev.infyplus.halo.ui.apiCatching
import dev.infyplus.halo.ui.isConnectivity
import kotlinx.coroutines.launch

/**
 * Where the user tells Halo which backend is theirs.
 *
 * The same form serves first run and the Settings section — there is one set of rules about what a
 * valid pair looks like, and a second copy of it would be the thing that drifted.
 */

/** How a probe went. [ok] is the only thing that unlocks saving. */
private data class Probe(val ok: Boolean, val message: String)

/**
 * Try the credentials against the server before letting them be saved.
 *
 * One authenticated GET is enough to check both halves: the worker rejects a bad bearer token at a
 * single point before it routes anything, so reaching *and* being accepted is the whole test.
 * [HaloApi.unreadCount] is the cheapest such call — a count, no model behind it.
 */
private suspend fun probe(url: String, token: String): Probe {
    val normalized = Config.normalize(url)
    if (normalized.isEmpty()) return Probe(false, "Enter the address of your Halo server.")
    if (token.isBlank()) return Probe(false, "Enter your access token.")

    return apiCatching { HaloApi(normalized, token.trim()).unreadCount() }
        .fold(
            onSuccess = { Probe(true, "Connected. $normalized is answering.") },
            onFailure = { Probe(false, credentialError(it, normalized)) },
        )
}

/**
 * Turn a failed probe into something that says which field to go and fix.
 *
 * The three outcomes need different actions from the user, and a single "couldn't connect" sends
 * them to the wrong one. [isConnectivity] is the existing test for "we never got there" — an
 * [ApiException] means the server answered and said no.
 */
internal fun credentialError(error: Throwable, target: String): String {
    val text = error.message.orEmpty()
    return when {
        error.isConnectivity() -> "Couldn't reach $target. Check the address."
        text.contains("unauthor", ignoreCase = true) || text.contains("401") ->
            "$target answered, but rejected that token."
        else -> text.ifBlank { "That server refused the request." }
    }
}

/**
 * The form itself. [onSaved] fires only after credentials were accepted and written, so hosts can
 * use it to rebuild whatever was holding the old ones.
 */
@Composable
fun CredentialsCard(onSaved: () -> Unit = {}, modifier: Modifier = Modifier) {
    var url by remember { mutableStateOf(Config.baseUrl) }
    var token by remember { mutableStateOf(Config.authToken) }
    var busy by remember { mutableStateOf(false) }
    var peek by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Probe?>(null) }
    val scope = rememberCoroutineScope()

    // Editing invalidates the last verdict — otherwise a green "connected" would sit above a URL
    // that has since been changed, and Save would look safe when it is not.
    LaunchedEffect(url, token) { result = null }

    HaloCard(modifier.fillMaxWidth()) {
        Mono("HALO SERVER")
        Text(
            "The address of your backend and the token it expects.",
            Modifier.padding(top = 4.dp, bottom = 10.dp),
            fontSize = 13.sp,
            color = HaloPalette.navy.copy(alpha = 0.75f),
        )

        Mono("ADDRESS")
        HaloField(
            value = url,
            placeholder = "halo.example.workers.dev",
            onChange = { url = it },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp),
            enabled = !busy,
        )

        // Masked by default: this is a standing credential, and a settings page is the one screen
        // people open with someone looking over their shoulder. The last four characters stay
        // visible so a changed or mis-pasted token is still recognisable without revealing it.
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Mono("ACCESS TOKEN")
            Mono(
                if (peek) "HIDE" else "SHOW",
                color = HaloPalette.warm,
                modifier = Modifier.clickable(enabled = token.isNotEmpty()) { peek = !peek },
            )
        }
        HaloField(
            value = token,
            placeholder = "the AUTH_TOKEN your server was deployed with",
            onChange = { token = it },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
            enabled = !busy,
            visualTransformation = if (peek) VisualTransformation.None else TailVisible(),
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HaloButton(
                label = if (busy) "Checking…" else "Test and save",
                enabled = !busy && url.isNotBlank() && token.isNotBlank(),
            ) {
                scope.launch {
                    busy = true
                    val outcome = probe(url, token)
                    // Written only on success. Saving something that does not work would clear the
                    // setup gate and leave the app looking broken rather than unconfigured.
                    if (outcome.ok) {
                        Config.save(url, token)
                        url = Config.baseUrl
                        onSaved()
                    }
                    result = outcome
                    busy = false
                }
            }
        }

        result?.let {
            Text(
                it.message,
                Modifier.padding(top = 10.dp),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (it.ok) HaloPalette.ink else HaloPalette.warm,
            )
        }
    }
}

/** The Settings section: the credentials, then everything that needs a server to already work. */
@Composable
fun SettingsScreen(onSaved: () -> Unit = {}, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CredentialsCard(onSaved)
        // Below the credentials, and only once they exist: these settings live on the server, so
        // there is nothing to load or save until it can be reached.
        if (Config.isConfigured) ProfileCard()
    }
}

/**
 * Shows [content] only once there is a server to talk to.
 *
 * A hard gate rather than a banner: every screen behind it assumes it can reach a backend, and an
 * unconfigured app that renders normally just produces a wall of failures that look like bugs.
 */
@Composable
fun SetupGate(content: @Composable () -> Unit) {
    if (Config.isConfigured) {
        content()
        return
    }
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "Halo",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = HaloPalette.ink,
            )
            Text(
                "Point this device at your assistant to get started.",
                fontSize = 14.sp,
                color = HaloPalette.navy.copy(alpha = 0.8f),
            )
            CredentialsCard()
        }
    }
}
