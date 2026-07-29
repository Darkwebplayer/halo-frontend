package dev.infyplus.halo

actual fun deviceTimeZone(): String = java.util.TimeZone.getDefault().id

actual fun nowMillis(): Long = System.currentTimeMillis()

/**
 * The JDK's own per-user store. No dependency, no file path to choose, and it is already where a
 * desktop app is expected to put a handful of preferences.
 *
 * Resolved lazily and defensively. `Preferences.userRoot()` touches the filesystem and throws on a
 * read-only or sandboxed home directory — as a top-level `val` that made the settings store a
 * *class initialiser*, so a machine where it failed got `ExceptionInInitializerError` on first
 * touch and `NoClassDefFoundError` on every access after, killing the app before a window existed.
 * A preferences file is not worth the whole app, so failure degrades to memory instead.
 */
private val prefs: java.util.prefs.Preferences? by lazy {
    runCatching { java.util.prefs.Preferences.userRoot().node("dev/infyplus/halo") }
        .onFailure { System.err.println("[halo] preferences unavailable; settings won't persist: ${it.message}") }
        .getOrNull()
}

/** Where settings go when the JDK store is unusable. Lasts the session, which beats crashing. */
private val fallback = java.util.concurrent.ConcurrentHashMap<String, String>()

actual fun loadSetting(key: String): String? =
    runCatching { prefs?.get(key, null) }.getOrNull() ?: fallback[key]

actual fun saveSetting(key: String, value: String) {
    fallback[key] = value
    val store = prefs ?: return
    runCatching {
        store.put(key, value)
        // Written on quit otherwise, which a tray app can skip entirely if it is force-killed.
        store.flush()
    }
}
