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

/** A moment as it reads on the device's own wall clock. */
data class LocalParts(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
)

/**
 * An ISO-8601 UTC instant from the server, converted to the device's local wall-clock parts.
 * Null when the string is not one — a malformed row should read as "no date", never crash a list.
 *
 * The *only* thing that crosses the platform boundary is the timezone conversion, because that is
 * the only part a calendar library is needed for. Every naming rule ("Today", "Tomorrow", the
 * 12-hour clock) stays in commonMain where it can be tested once. See ui/Dates.kt.
 *
 * Deliberately not `java.time`: this module is minSdk 24 with core library desugaring off, so
 * `Instant`/`ZonedDateTime` would compile happily and then throw on API 24-25 devices.
 */
expect fun localPartsOf(isoUtc: String): LocalParts?

/** The same conversion for a moment we already hold as epoch millis, such as "now". */
expect fun localPartsAt(epochMillis: Long): LocalParts

/** An ISO-8601 UTC instant as epoch millis, or null when the string is not one. */
expect fun epochMillisOf(isoUtc: String): Long?
