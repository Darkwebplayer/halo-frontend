package dev.infyplus.halo

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** The device's IANA timezone id, so the server can resolve "tomorrow at 5pm" correctly. */
expect fun deviceTimeZone(): String

/** Raised for any non-2xx response, carrying the server's error text for the UI to show. */
class ApiException(message: String) : Exception(message)

class HaloApi(
    private val baseUrl: String,
    private val authToken: String,
    private val client: HttpClient = sharedClient,
) {
    /** Parses free text into a structured item and persists it. Throws if the server refuses. */
    suspend fun parse(text: String): Item = call {
        val res = client.post("$baseUrl/parse") {
            contentType(ContentType.Application.Json)
            header("authorization", "Bearer $authToken")
            setBody(ParseRequest(text = text, tz = deviceTimeZone()))
        }
        if (!res.status.isSuccess()) throw ApiException(res.errorMessage())
        res.body()
    }

    /**
     * Send anything the user typed and let the server work out what it was.
     *
     * One round-trip: the server classifies and acts, then returns both the outcome and what to
     * say. [itemId] scopes it to a notification being replied to.
     */
    suspend fun message(text: String, itemId: String? = null): MessageResponse = call {
        val res = client.post("$baseUrl/message") {
            contentType(ContentType.Application.Json)
            header("authorization", "Bearer $authToken")
            setBody(MessageRequest(text = text, itemId = itemId, tz = deviceTimeZone()))
        }
        if (!res.status.isSuccess()) throw ApiException(res.errorMessage())
        res.body()
    }

    suspend fun items(): List<Item> = call {
        val res = client.get("$baseUrl/items") { header("authorization", "Bearer $authToken") }
        if (!res.status.isSuccess()) throw ApiException(res.errorMessage())
        res.body<ItemsResponse>().items
    }

    /** Ask a factual question. Throws rather than returning an unsourced or stale answer. */
    suspend fun ask(text: String): Answer = call {
        val res = client.post("$baseUrl/ask") {
            contentType(ContentType.Application.Json)
            header("authorization", "Bearer $authToken")
            setBody(CommandRequest(text))
        }
        if (!res.status.isSuccess()) throw ApiException(res.errorMessage())
        res.body()
    }

    /** Everything this device should arm locally. Safe to call repeatedly — ids are stable. */
    suspend fun sync(): SyncResponse = call {
        val res = client.get("$baseUrl/sync") { header("authorization", "Bearer $authToken") }
        if (!res.status.isSuccess()) throw ApiException(res.errorMessage())
        res.body()
    }

    /**
     * Act on an item from a notification: "done", "snooze" or "reschedule".
     *
     * Authenticated directly — no capability token, because a local notification is handled by
     * this app, which already holds the token.
     */
    suspend fun act(itemId: String, verb: String): Unit = call {
        val res = client.post("$baseUrl/items/$itemId/$verb") {
            header("authorization", "Bearer $authToken")
        }
        if (!res.status.isSuccess()) throw ApiException(res.errorMessage())
    }

    /** Check-ins sent but never answered — what the overlay badge counts. */
    suspend fun unreadCount(): Int = call {
        val res = client.get("$baseUrl/checkins/unread") {
            header("authorization", "Bearer $authToken")
        }
        if (!res.status.isSuccess()) throw ApiException(res.errorMessage())
        res.body<UnreadResponse>().unread
    }

    /**
     * Notification history, newest first — what the panel's Notifications tab lists.
     *
     * Separate from [unreadCount] on purpose: the collapsed orb needs a number once a minute,
     * not twenty rows. Both are derived from the same "unanswered" rule server-side, so the
     * badge and the list cannot disagree.
     */
    suspend fun checkins(limit: Int = 20): List<CheckIn> = call {
        val res = client.get("$baseUrl/checkins?limit=$limit") {
            header("authorization", "Bearer $authToken")
        }
        if (!res.status.isSuccess()) throw ApiException(res.errorMessage())
        res.body<CheckInsResponse>().checkins
    }

    /**
     * Report that a notification just fired on this device.
     *
     * Delivery is entirely local, so the server has no other way to know what was actually
     * shown — and that is exactly what the history lists. Safe to call twice for the same
     * [scheduledId]; the server treats a repeat as already recorded.
     */
    suspend fun reportFired(scheduledId: String, itemId: String?): Unit = call {
        val res = client.post("$baseUrl/notifications/fired") {
            contentType(ContentType.Application.Json)
            header("authorization", "Bearer $authToken")
            setBody(FiredReport(scheduledId, itemId))
        }
        if (!res.status.isSuccess()) throw ApiException(res.errorMessage())
    }

    /**
     * A daily digest — what today holds, or what today came to.
     *
     * Worth calling sparingly: the server materialises recurrence instances while building this,
     * so it is a write as much as a read. [dev.infyplus.halo.ui.SummaryStore] caches the result
     * for the local day rather than re-asking on every visit.
     */
    suspend fun summary(kind: SummaryKind): Summary = call {
        val path = if (kind == SummaryKind.Morning) "morning" else "evening"
        val res = client.get("$baseUrl/summary/$path") {
            header("authorization", "Bearer $authToken")
        }
        if (!res.status.isSuccess()) throw ApiException(res.errorMessage())
        res.body()
    }

    /** Every project, archived ones included — items carry only a `project_id`, never a name. */
    suspend fun projects(): List<Project> = call {
        val res = client.get("$baseUrl/projects") { header("authorization", "Bearer $authToken") }
        if (!res.status.isSuccess()) throw ApiException(res.errorMessage())
        res.body<ProjectsResponse>().projects
    }

    /** Every repeating rule, so a recurring item can say how often it comes back. */
    suspend fun recurrences(): List<Recurrence> = call {
        val res = client.get("$baseUrl/recurrences") { header("authorization", "Bearer $authToken") }
        if (!res.status.isSuccess()) throw ApiException(res.errorMessage())
        res.body<RecurrencesResponse>().recurrences
    }

    /** The user's settings, with the server's own defaults already folded in. */
    suspend fun profile(): Profile = call {
        val res = client.get("$baseUrl/profile") { header("authorization", "Bearer $authToken") }
        if (!res.status.isSuccess()) throw ApiException(res.errorMessage())
        res.body()
    }

    /**
     * Change some settings. Only the fields present in [patch] are touched.
     *
     * A partial patch rather than the whole object on purpose: the server treats a missing field
     * as "leave alone" and an explicit null as "back to the default", and sending the whole
     * profile back would turn every save into a race with whatever another device just set.
     */
    suspend fun saveProfile(patch: ProfilePatch): Profile = call {
        val res = client.put("$baseUrl/profile") {
            contentType(ContentType.Application.Json)
            header("authorization", "Bearer $authToken")
            setBody(patch)
        }
        if (!res.status.isSuccess()) throw ApiException(res.errorMessage())
        res.body()
    }

    /** Today's plan: carried-over work plus today's. */
    suspend fun plan(): Plan = call {
        val res = client.get("$baseUrl/plan") { header("authorization", "Bearer $authToken") }
        if (!res.status.isSuccess()) throw ApiException(res.errorMessage())
        res.body()
    }

    /** Send a conversational edit ("move this to tomorrow"). Returns the plan after applying it. */
    suspend fun command(text: String): Plan = call {
        val res = client.post("$baseUrl/command") {
            contentType(ContentType.Application.Json)
            header("authorization", "Bearer $authToken")
            setBody(CommandRequest(text))
        }
        if (!res.status.isSuccess()) throw ApiException(res.errorMessage())
        res.body<CommandResponse>().plan
    }

    /**
     * Keep request building and JSON decoding off whatever dispatcher the caller is on —
     * these run from Compose's main-thread scope.
     */
    private suspend fun <T> call(block: suspend () -> T): T = withContext(Dispatchers.Default) { block() }
}

private fun HttpStatusCode.isSuccess() = value in 200..299

private suspend fun io.ktor.client.statement.HttpResponse.errorMessage(): String =
    runCatching { body<ApiError>().error }.getOrElse { "HTTP ${status.value}" }

/**
 * One client for the whole process.
 *
 * [HaloApi] is deliberately cheap to construct and gets rebuilt often — Compose rebuilds it whenever
 * credentials change, `Sync.loop` builds a fresh one every minute so it cannot go on talking to an
 * old server, and every broadcast receiver builds its own. Each of those used to bring a new engine
 * with its own connection pool and threads, and nothing ever closed them: a tray app left running
 * for a day accumulated well over a thousand, which ends in thread or file-descriptor exhaustion.
 *
 * Nothing here is per-account — credentials travel on the request, not the client — so sharing one
 * costs nothing and the leak goes away. It is intentionally never closed: it lives as long as the
 * process, which is exactly the lifetime it wants.
 */
private val sharedClient by lazy { defaultClient() }

private fun defaultClient() = HttpClient {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    // /parse waits on an LLM round-trip, which routinely exceeds the 10s engine default.
    install(HttpTimeout) {
        requestTimeoutMillis = 60_000
        socketTimeoutMillis = 60_000
        connectTimeoutMillis = 15_000
    }
    defaultRequest { contentType(ContentType.Application.Json) }
}
