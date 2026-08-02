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

/**
 * Leading date and time of an ISO-8601 instant. Fractional seconds and the trailing `Z` are
 * ignored on purpose: the server mints every timestamp with `toISOString()`, which is always UTC,
 * and a stricter pattern would only turn a harmless format drift into a blank screen.
 */
private val ISO = Regex("""^(\d{4})-(\d{2})-(\d{2})[Tt ](\d{2}):(\d{2})(?::(\d{2}))?""")

actual fun epochMillisOf(isoUtc: String): Long? = utcCalendar(isoUtc)?.timeInMillis

actual fun localPartsAt(epochMillis: Long): LocalParts =
    partsOf(java.util.Calendar.getInstance().apply { timeInMillis = epochMillis })

actual fun localPartsOf(isoUtc: String): LocalParts? {
    val utc = utcCalendar(isoUtc) ?: return null
    return localPartsAt(utc.timeInMillis)
}

/** The instant the string names, held in UTC. Null when it is not an instant. */
private fun utcCalendar(isoUtc: String): java.util.Calendar? {
    val g = ISO.find(isoUtc.trim())?.groupValues ?: return null
    return java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(
            g[1].toInt(), g[2].toInt() - 1, g[3].toInt(),
            g[4].toInt(), g[5].toInt(), g.getOrNull(6)?.toIntOrNull() ?: 0,
        )
    }
}

/**
 * Wall-clock fields of a calendar already sitting in the zone we want to read it in.
 *
 * Going via epoch millis rather than re-assigning a timezone on a populated calendar is what
 * keeps half-hour offsets and DST changes correct.
 */
private fun partsOf(c: java.util.Calendar) = LocalParts(
    year = c.get(java.util.Calendar.YEAR),
    month = c.get(java.util.Calendar.MONTH) + 1,
    day = c.get(java.util.Calendar.DAY_OF_MONTH),
    hour = c.get(java.util.Calendar.HOUR_OF_DAY),
    minute = c.get(java.util.Calendar.MINUTE),
)
