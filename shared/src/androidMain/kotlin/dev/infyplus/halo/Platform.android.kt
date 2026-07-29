package dev.infyplus.halo

import android.os.Build

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

actual fun getPlatform(): Platform = AndroidPlatform()
/**
 * Cached from an application context, because the shared module has none.
 *
 * Set once by `AndroidNotifier.attach`, which already runs from every entry point — activity,
 * service and receiver — so by the time anything draws, this is populated.
 */
@Volatile
private var reducedMotion: Boolean = false

/** Reads Accessibility ▸ "Remove animations", which zeroes the animator duration scale. */
fun cacheReducedMotion(context: android.content.Context) {
    reducedMotion = runCatching {
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }.getOrDefault(false)
}

actual fun prefersReducedMotion(): Boolean = reducedMotion
