package dev.infyplus.halo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

private const val URL_KEY = "base_url"
private const val TOKEN_KEY = "auth_token"
private const val AVATAR_KEY = "avatar"
private const val ORB_ALWAYS_KEY = "orb_always"
private const val ORB_HIDDEN_KEY = "orb_hidden"
private const val SHADE_STATUS_KEY = "shade_status"

/**
 * Whether the floating assistant should be on screen right now.
 *
 * The whole rule, in one place, because three surfaces have to agree on it: the Android overlay
 * service (which adds and removes the window), the desktop bubble, and the Quick Settings tile that
 * reports it back as an on/off state. Two of those are untestable platform code, which is the
 * reason this is a plain function taking plain values rather than reading [Config] itself.
 *
 * [hidden] outranks everything: dismissing the bubble means dismissed, even with something waiting.
 * The only ways back are the settings toggle, the tile, and the shade notification's action.
 */
fun orbVisible(
    always: Boolean,
    hidden: Boolean,
    unread: Int,
    headsUp: Boolean,
    timerRunning: Boolean,
): Boolean = !hidden && (always || unread > 0 || headsUp || timerRunning)

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

    /**
     * Which face the orb wears — the account's setting, cached here.
     *
     * Cached locally because the orb is drawn long before, and often without, a call to the
     * server: the overlay comes up on boot, and a notification banner draws a face while offline.
     * The server stays the source of truth; this is what the last successful `/profile` said.
     */
    var avatar by mutableStateOf(DEFAULT_AVATAR)
        private set

    /**
     * Keep the floating assistant on screen at all times.
     *
     * False means it only appears when something wants an answer — see [orbVisible]. On by default
     * because that is what every existing install already does; making the new behaviour the
     * default would silently take the bubble away from someone who never asked.
     */
    var orbAlways by mutableStateOf(true)
        private set

    /**
     * The user closed it. Sticky, and it beats [orbAlways] and anything waiting.
     *
     * Persisted rather than held in memory: "I dismissed this" is a decision, and a reboot bringing
     * it back would make the dismissal feel broken rather than respected.
     */
    var orbHidden by mutableStateOf(false)
        private set

    /**
     * Keep a proper status notification in the shade — the same job as the bubble, without a window.
     *
     * Off by default. Android insists on *an* ongoing notification while the overlay service runs;
     * this only decides whether that line is a minimal one that sinks to the bottom of the shade or
     * a useful one carrying the count, the countdown and a Show/Hide action.
     */
    var shadeStatus by mutableStateOf(false)
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
        avatar = loadSetting(AVATAR_KEY)?.ifBlank { null } ?: DEFAULT_AVATAR
        orbAlways = flag(ORB_ALWAYS_KEY, default = true)
        orbHidden = flag(ORB_HIDDEN_KEY, default = false)
        shadeStatus = flag(SHADE_STATUS_KEY, default = false)
    }

    /** A stored boolean, falling back to [default] for both "never written" and "written wrong". */
    private fun flag(key: String, default: Boolean) =
        loadSetting(key)?.toBooleanStrictOrNull() ?: default

    /** @see orbAlways */
    fun saveOrbAlways(value: Boolean) {
        orbAlways = value
        saveSetting(ORB_ALWAYS_KEY, value.toString())
    }

    /** @see orbHidden */
    fun saveOrbHidden(value: Boolean) {
        orbHidden = value
        saveSetting(ORB_HIDDEN_KEY, value.toString())
    }

    /** @see shadeStatus */
    fun saveShadeStatus(value: Boolean) {
        shadeStatus = value
        saveSetting(SHADE_STATUS_KEY, value.toString())
    }

    /** Remember what the server last said the avatar is. Called from [HaloApi], not from the UI. */
    fun rememberAvatar(value: String) {
        if (value.isBlank() || value == avatar) return
        avatar = value
        saveSetting(AVATAR_KEY, value)
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
