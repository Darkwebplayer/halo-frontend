package dev.infyplus.halo

class JVMPlatform: Platform {
    override val name: String = "Java ${System.getProperty("java.version")}"
}

actual fun getPlatform(): Platform = JVMPlatform()
/**
 * Always false on desktop.
 *
 * macOS exposes this as `NSWorkspace.accessibilityDisplayShouldReduceMotion` and Windows as
 * `SPI_GETCLIENTAREAANIMATION`, but neither is reachable from the JVM without JNI. A settings
 * toggle would be the honest alternative; the orb is not on screen long enough on desktop to
 * justify one before anybody asks.
 */
actual fun prefersReducedMotion(): Boolean = false
