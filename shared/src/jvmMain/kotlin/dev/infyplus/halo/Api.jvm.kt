package dev.infyplus.halo

actual fun deviceTimeZone(): String = java.util.TimeZone.getDefault().id

actual fun nowMillis(): Long = System.currentTimeMillis()

/**
 * The JDK's own per-user store. No dependency, no file path to choose, and it is already where a
 * desktop app is expected to put a handful of preferences.
 */
private val prefs = java.util.prefs.Preferences.userRoot().node("dev/infyplus/halo")

actual fun loadSetting(key: String): String? = prefs.get(key, null)

actual fun saveSetting(key: String, value: String) {
    prefs.put(key, value)
    // Written on quit otherwise, which a tray app can skip entirely if it is force-killed.
    runCatching { prefs.flush() }
}
