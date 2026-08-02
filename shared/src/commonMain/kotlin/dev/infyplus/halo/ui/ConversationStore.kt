package dev.infyplus.halo.ui

import dev.infyplus.halo.Turn
import dev.infyplus.halo.loadSetting
import dev.infyplus.halo.nowMillis
import dev.infyplus.halo.saveSetting
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The last conversation, so closing the panel is not the same as ending it.
 *
 * One conversation, not a history. The need is to finish a thought that was interrupted — the
 * overlay closes the moment you tap outside it — and a list of saved conversations would be a
 * browser, a picker and a delete flow for something nobody has asked for yet.
 *
 * Stored through the same key/value store the credentials and the timer settings already use, so
 * there is no new persistence to own. A blob from an older build, or one half-written, is dropped
 * rather than allowed to break the panel — the same defence `Pomodoro.reload()` uses.
 */
object ConversationStore {
    private const val KEY = "conversation"

    /** Roughly what the server will accept, so a stored thread is never mostly discarded on send. */
    private const val MAX_TURNS = 12

    /** Older than this and a follow-up is not a follow-up. */
    private const val MAX_AGE_MS = 36L * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Stored(val at: Long, val turns: List<Turn>)

    fun save(turns: List<Turn>, now: Long = nowMillis()) {
        if (turns.isEmpty()) return clear()
        runCatching {
            saveSetting(KEY, json.encodeToString(Stored(now, turns.takeLast(MAX_TURNS))))
        }
    }

    /**
     * The stored turns, or null when there is nothing usable.
     *
     * Age is enforced here rather than left to the model. The server is told that earlier turns may
     * be from another day, which stops "tomorrow" being misread — but a thread from last week is
     * not context, it is noise, and resuming into it would be confusing rather than helpful.
     */
    fun load(now: Long = nowMillis()): List<Turn>? {
        val raw = loadSetting(KEY) ?: return null
        val stored = runCatching { json.decodeFromString<Stored>(raw) }.getOrNull() ?: return null
        if (now - stored.at > MAX_AGE_MS) {
            clear()
            return null
        }
        return stored.turns.ifEmpty { null }
    }

    fun clear() {
        runCatching { saveSetting(KEY, "") }
    }
}
