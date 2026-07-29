package dev.infyplus.halo

actual fun deviceTimeZone(): String = java.util.TimeZone.getDefault().id

actual fun nowMillis(): Long = System.currentTimeMillis()

@Volatile
private var prefs: android.content.SharedPreferences? = null

/**
 * The application context, for the parts of the shared module that need one and have no other way
 * to be handed it. Kept here because [attachSettings] is already called from every entry point.
 */
@Volatile
internal var androidAppContext: android.content.Context? = null
    private set

/**
 * Hand the settings store a context. Mirrors [AndroidNotifier.attach] — the launcher activity and
 * the overlay service each call it, because either one can be the first thing the user reaches.
 *
 * Deliberately the same `"halo"` file `PermissionGate` already writes its setup flag to.
 */
fun attachSettings(context: android.content.Context) {
    val app = context.applicationContext
    androidAppContext = app
    prefs = app.getSharedPreferences("halo", android.content.Context.MODE_PRIVATE)
}

actual fun loadSetting(key: String): String? = prefs?.getString(key, null)

actual fun saveSetting(key: String, value: String) {
    prefs?.edit()?.putString(key, value)?.apply()
}
