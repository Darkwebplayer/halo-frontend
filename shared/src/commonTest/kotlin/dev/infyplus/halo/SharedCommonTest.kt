package dev.infyplus.halo

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val json = Json { ignoreUnknownKeys = true }

class SharedCommonTest {

    /** Verbatim /parse response from the deployed Worker — the snake_case mapping is easy to break. */
    @Test
    fun decodesParseResponse() {
        val item = json.decodeFromString<Item>(
            """{"id":"50fbbeee-800a-4346-897f-7787b321bf16","kind":"reminder","title":"Call mom",
               "due_at":"2026-07-29T09:00:00Z","priority":2,"done_at":null,
               "created_at":"2026-07-27T20:18:49.986Z"}"""
        )
        assertEquals("reminder", item.kind)
        assertEquals("Call mom", item.title)
        assertEquals("2026-07-29T09:00:00Z", item.dueAt)
        assertEquals("2026-07-27T20:18:49.986Z", item.createdAt)
        assertNull(item.doneAt)
    }

    @Test
    fun decodesUndatedItem() {
        val item = json.decodeFromString<Item>(
            """{"id":"x","kind":"task","title":"Buy milk","due_at":null,"priority":2,
               "done_at":null,"created_at":"2026-07-27T20:18:49.986Z"}"""
        )
        assertNull(item.dueAt)
    }

    @Test
    fun decodesErrorBody() {
        val err = json.decodeFromString<ApiError>("""{"error":"gemini: API key not valid."}""")
        assertEquals("gemini: API key not valid.", err.error)
    }
}
