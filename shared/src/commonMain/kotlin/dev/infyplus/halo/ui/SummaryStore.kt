package dev.infyplus.halo.ui

import dev.infyplus.halo.HaloApi
import dev.infyplus.halo.Summary
import dev.infyplus.halo.SummaryKind
import dev.infyplus.halo.loadSetting
import dev.infyplus.halo.saveSetting
import kotlinx.serialization.json.Json

/**
 * Today's digests, kept until the day turns over.
 *
 * Caching here is not a speed trick. The summary endpoints materialise recurrence instances while
 * builds the answer, so it is a write as much as a read — asking again every time the tab is
 * opened would mint rows as a side effect of looking at the screen. One fetch per kind per local
 * day, and the rest comes from disk.
 *
 * The key carries the *local* date, so a summary read at 01:30 belongs to the day the user is
 * actually in rather than whatever UTC thinks. A stored blob from an older build, or one written
 * half-way, falls back to a refetch rather than breaking the tab — the same defence
 * `Pomodoro.reload()` uses on its own settings.
 */
object SummaryStore {
    private val json = Json { ignoreUnknownKeys = true }

    private fun key(kind: SummaryKind, date: String) =
        "summary-${kind.name.lowercase()}-$date"

    /** The cached digest for today, or null when there is nothing usable stored. */
    fun cached(kind: SummaryKind, now: Long): Summary? {
        val raw = loadSetting(key(kind, localDateKey(now))) ?: return null
        return runCatching { json.decodeFromString<Summary>(raw) }.getOrNull()
    }

    fun store(kind: SummaryKind, summary: Summary, now: Long) {
        runCatching { saveSetting(key(kind, localDateKey(now)), json.encodeToString(summary)) }
    }

    /**
     * The digest for today, from disk when it is there and from the server when it is not.
     *
     * [force] is the refresh gesture: it skips the cache but still writes back, so a manual
     * refresh replaces the day's copy rather than bypassing it forever.
     */
    suspend fun load(api: HaloApi, kind: SummaryKind, now: Long, force: Boolean = false): Summary {
        if (!force) cached(kind, now)?.let { return it }
        return api.summary(kind).also { store(kind, it, now) }
    }
}
