package dev.infyplus.halo

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
/**
 * Whether the user has asked the system to reduce animation.
 *
 * Compose Multiplatform has no `prefers-reduced-motion` equivalent, and its own
 * `MotionDurationScale` only shortens finite animations — the orb's idle loops run off the
 * infinite frame clock and would keep going regardless. So this is read explicitly and threaded
 * to the drawing.
 *
 * When true the cat stops looping and expression changes snap. Nothing is lost: every expression
 * changes eye and mouth *shape*, so all eight stay legible from a still frame — which is exactly
 * why the reference could afford to strip its own motion the same way.
 */
expect fun prefersReducedMotion(): Boolean
