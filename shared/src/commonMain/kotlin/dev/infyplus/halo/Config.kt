package dev.infyplus.halo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

private const val URL_KEY = "base_url"
private const val TOKEN_KEY = "auth_token"

/**
 * Where this device talks to, and what it says it is.
 *
 * Supplied by the user at first run rather than compiled in, so the app can be handed to somebody
 * else and pointed at their own backend. Nothing here has a default: a build that shipped with one
 * would quietly work against whoever's server was baked in.
 *
 * Held as Compose state, not plain fields, so [SetupGate] falls away the moment credentials are
 * saved without anything having to notice and republish. Reads from background threads — the
 * broadcast receivers, on a Dispatchers.Default coroutine — see the current global snapshot, which
 * is what those readers want; the only writer is the settings form.
 */
object Config {
    var baseUrl by mutableStateOf("")
        private set

    var authToken by mutableStateOf("")
        private set

    /** Whether there is anything worth trying. Every network path is gated on this. */
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && authToken.isNotBlank()

    /**
     * Read what was saved. Call once per process, after a platform has attached its store —
     * on Android that means after `attachSettings(context)`, including in every broadcast receiver.
     */
    fun load() {
        baseUrl = loadSetting(URL_KEY).orEmpty()
        authToken = loadSetting(TOKEN_KEY).orEmpty()
    }

    fun save(url: String, token: String) {
        baseUrl = normalize(url)
        authToken = token.trim()
        saveSetting(URL_KEY, baseUrl)
        saveSetting(TOKEN_KEY, authToken)
    }

    /**
     * Tidy a hand-typed address into something ktor can use.
     *
     * Only the three mistakes that are certain to be made: surrounding whitespace from a paste, a
     * missing scheme, and a trailing slash — which would otherwise produce "https://host//plan"
     * and a 404 that reads like the server being wrong rather than the address.
     */
    fun normalize(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }
}
