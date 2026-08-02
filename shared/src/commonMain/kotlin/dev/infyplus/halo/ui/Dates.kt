package dev.infyplus.halo.ui

import dev.infyplus.halo.LocalParts
import dev.infyplus.halo.epochMillisOf
import dev.infyplus.halo.localPartsAt
import dev.infyplus.halo.localPartsOf
import dev.infyplus.halo.nowMillis

/**
 * How the app says when something is.
 *
 * Every instant the server sends is ISO-8601 UTC — no `GET` endpoint returns a formatted time — so
 * turning one into words is entirely the client's job. Before this file that job was simply not
 * done: the Alerts list sliced the raw string with `takeLast(14).take(5)` and printed `T06:3`, and
 * the Today list printed all 24 characters of `2026-08-02T04:00:00.000Z`.
 *
 * The rules live here rather than at each call site so there is one answer to "what does 9am
 * tomorrow look like", and so they can be tested without a screen. Calendar work stays on the
 * platform, which already has a correct one; nothing here counts days or knows about leap years.
 */

private const val DAY_MS = 86_400_000L

private val MONTHS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

private val DAY_NAMES = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

/** `14:05` → `2:05 PM`. Twelve-hour because every other surface in the app reads conversationally. */
private fun clock(hour: Int, minute: Int): String {
    val h = when {
        hour % 12 == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    return "$h:${minute.toString().padStart(2, '0')} ${if (hour < 12) "AM" else "PM"}"
}

private fun clock(p: LocalParts) = clock(p.hour, p.minute)

private fun sameDate(a: LocalParts, b: LocalParts) =
    a.year == b.year && a.month == b.month && a.day == b.day

/**
 * A due date as a person would say it: `Today 9:00 AM`, `Tomorrow 6:00 PM`, `4 Aug 9:00 AM`.
 *
 * Null in, null out — an item with no due date has nothing to say, and the caller decides whether
 * that means hiding the line or writing "no date".
 *
 * "Today" is decided by comparing local calendar dates, never by dividing elapsed milliseconds: a
 * day is not always 86,400,000ms across a DST change, and the user's zone may be offset by half an
 * hour. Nudging `now` by a day and asking the platform what date that lands on is exact, because
 * the comparison only ever looks at the resulting date — the same reasoning the backend's own
 * resolver documents.
 */
fun whenLabel(iso: String?, now: Long = nowMillis()): String? {
    val at = iso?.let(::localPartsOf) ?: return null
    return when {
        sameDate(at, localPartsAt(now)) -> "Today ${clock(at)}"
        sameDate(at, localPartsAt(now + DAY_MS)) -> "Tomorrow ${clock(at)}"
        sameDate(at, localPartsAt(now - DAY_MS)) -> "Yesterday ${clock(at)}"
        else -> "${at.day} ${MONTHS[at.month - 1]} ${clock(at)}"
    }
}

/** Just the time of day, for a row that already says which day it belongs to. */
fun timeLabel(iso: String?): String? = iso?.let(::localPartsOf)?.let(::clock)

/** `Today`, `Tomorrow`, or `4 Aug` — a heading for a group of items sharing a day. */
fun dayLabel(iso: String?, now: Long = nowMillis()): String? {
    val at = iso?.let(::localPartsOf) ?: return null
    return when {
        sameDate(at, localPartsAt(now)) -> "Today"
        sameDate(at, localPartsAt(now + DAY_MS)) -> "Tomorrow"
        sameDate(at, localPartsAt(now - DAY_MS)) -> "Yesterday"
        else -> "${at.day} ${MONTHS[at.month - 1]}"
    }
}

/**
 * How long ago something happened, coarsely: `just now`, `18m ago`, `3h ago`, `2d ago`.
 *
 * Coarse on purpose — this labels a notification in a history list, where "about two hours" is the
 * whole of what anybody wants to know.
 */
fun agoLabel(iso: String?, now: Long = nowMillis()): String? {
    val at = iso?.let(::epochMillisOf) ?: return null
    val minutes = ((now - at) / 60_000L).toInt()
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        else -> "${minutes / (60 * 24)}d ago"
    }
}

/** The local calendar date of an instant, as `YYYY-MM-DD` — the key summaries are cached under. */
fun localDateKey(now: Long = nowMillis()): String {
    val p = localPartsAt(now)
    return "${p.year}-${p.month.toString().padStart(2, '0')}-${p.day.toString().padStart(2, '0')}"
}

/** True once today's local wall clock has passed `HH:MM`. Decides which summary tab opens first. */
fun hasPassed(hhmm: String, now: Long = nowMillis()): Boolean {
    val h = hhmm.substringBefore(':').toIntOrNull() ?: return false
    val m = hhmm.substringAfter(':', "").toIntOrNull() ?: return false
    val p = localPartsAt(now)
    return p.hour > h || (p.hour == h && p.minute >= m)
}

/**
 * A cadence rule as a sentence: `every day at 8:00 AM`, `every Mon, Fri at 8:00 AM`.
 *
 * A port of the server's own `describeRecurrence`. It exists twice because no `GET` endpoint
 * returns the sentence — `/recurrences` hands over the raw rule fields and nothing else — and a
 * repeating item has to say something about itself.
 */
fun describeRecurrence(
    mode: String?,
    weekdays: String?,
    intervalDays: Int?,
    atHour: Int,
): String {
    val at = "at ${clock(atHour, 0)}"
    if (mode == "relative") return "when the last is done, $at"

    val days = weekdays.orEmpty().split(",").mapNotNull { it.trim().toIntOrNull() }.sorted().distinct()
    return when {
        days.size == 7 -> "every day $at"
        days.isNotEmpty() -> "every ${days.joinToString(", ") { DAY_NAMES[it % 7] }} $at"
        intervalDays == 1 -> "every day $at"
        intervalDays != null && intervalDays > 1 -> "every $intervalDays days $at"
        else -> "repeating $at"
    }
}
