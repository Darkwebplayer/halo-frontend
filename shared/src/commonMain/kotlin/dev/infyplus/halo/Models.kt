package dev.infyplus.halo

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class Item(
    val id: String,
    val kind: String, // task | event | reminder
    val title: String,
    @SerialName("due_at") val dueAt: String? = null,
    val priority: Int = 2,
    @SerialName("done_at") val doneAt: String? = null,
    @SerialName("created_at") val createdAt: String,
    // The three below have always been on the wire — every item endpoint does SELECT * — but were
    // absent here, so `ignoreUnknownKeys` dropped them on the floor and the app could not show a
    // project or say that something repeats.
    @SerialName("project_id") val projectId: String? = null,
    /** Comma-FRAMED on the server (`,work,urgent,`) so a LIKE can match whole tokens. See [tagList]. */
    val tags: String? = null,
    @SerialName("recurrence_id") val recurrenceId: String? = null,
    /**
     * Whatever the title had no room for, in the user's own words.
     *
     * The title is a short imperative because it is what a notification shows; the detail behind
     * it lives here. The server puts it in the model's prompt too, so the assistant can answer
     * from it rather than asking again.
     */
    val notes: String? = null,
) {
    /** The tags as words. The framing commas are storage detail and never belong on screen. */
    val tagList: List<String> get() = tags.orEmpty().split(",").mapNotNull { it.trim().ifEmpty { null } }

    /** Whether this is one occurrence of a repeating rule. */
    val repeats: Boolean get() = !recurrenceId.isNullOrBlank()
}

/** A named bucket of work. Created server-side by naming one during capture; there is no form. */
@Serializable
data class Project(
    val id: String,
    val name: String,
    /** active | paused | archived */
    val status: String = "active",
    val notes: String? = null,
)

@Serializable
data class ProjectsResponse(val projects: List<Project> = emptyList())

/**
 * A repeating rule, as the server stores it.
 *
 * Raw fields, not a sentence: `GET /recurrences` returns the columns and nothing else, so the
 * phrasing is `describeRecurrence`'s job on this side.
 */
@Serializable
data class Recurrence(
    val id: String,
    val title: String,
    /** fixed | relative */
    val mode: String = "fixed",
    /** CSV of day numbers, Sunday = 0. */
    val weekdays: String? = null,
    @SerialName("interval_days") val intervalDays: Int? = null,
    @SerialName("at_hour") val atHour: Int = 9,
    @SerialName("ended_at") val endedAt: String? = null,
)

@Serializable
data class RecurrencesResponse(val recurrences: List<Recurrence> = emptyList())

@Serializable
data class ParseRequest(val text: String, val tz: String)

@Serializable
data class ItemsResponse(val date: String, val items: List<Item>)

/** Today's plan: what carried over from earlier days, and what belongs to today. */
@Serializable
data class Plan(
    val date: String,
    val rollover: List<Item> = emptyList(),
    val today: List<Item> = emptyList(),
)

@Serializable
data class UnreadResponse(val unread: Int)

/**
 * A running timer nobody stopped, from the overview.
 *
 * Carries only `item_id`, never the item's title — the server does not join it — so a name has to
 * be looked up locally or left out.
 */
@Serializable
data class StaleWork(
    val id: String,
    @SerialName("item_id") val itemId: String? = null,
    val note: String? = null,
    @SerialName("start_at") val startAt: String,
    val seconds: Int = 0,
)

/** Something pushed back enough times that the server thinks it is worth mentioning. */
@Serializable
data class Postponed(val id: String, val title: String, val times: Int = 0)

/**
 * A daily digest. One shape covers both ends of the day: the morning fills `today`, the evening
 * fills `done` and `open`, and the four overview fields are common to both.
 *
 * Entirely structured — the server's prose renderers were deleted — so the wording on screen is
 * this app's to choose.
 */
@Serializable
data class Summary(
    /**
     * The day in a few sentences, written for this user in their chosen voice.
     *
     * The server writes it once per summary occurrence and caches it, so asking twice — or from a
     * second device — costs nothing. Empty when it could not be written, which the card treats as
     * "show the counts instead" rather than as an error.
     */
    val description: String = "",
    /**
     * Which occurrence [description] actually describes, as `2026-08-02:morning`.
     *
     * Not always the current one. Before the user's own summary time the most recent elapsed
     * occurrence is yesterday's, and the server serves what it saved rather than writing a fresh
     * description of a day that is already over — so this is how the card knows whose day it is
     * showing. Null when nothing has ever been written.
     */
    @SerialName("summary_for") val summaryFor: String? = null,
    /**
     * Whether [description] is today's, and therefore whether rewriting it is a thing that can be
     * asked for. Defaults false, so a server that predates this field offers no Refresh rather
     * than offering one that would 409.
     */
    @SerialName("summary_fresh") val summaryFresh: Boolean = false,
    val date: String = "",
    @SerialName("as_of") val asOf: String = "",
    val today: List<Item> = emptyList(),
    val done: List<Item> = emptyList(),
    val open: List<Item> = emptyList(),
    val unattended: List<Item> = emptyList(),
    @SerialName("stale_work") val staleWork: List<StaleWork> = emptyList(),
    val postponed: List<Postponed> = emptyList(),
)

/** Which end of the day a [Summary] describes. */
enum class SummaryKind { Morning, Evening }

/**
 * One notification this device already showed, as the app's notification tab lists it.
 *
 * Written by reporting it to the server when it fires, so this is a record of what was actually
 * shown rather than what was merely scheduled — a device that was asleep has no row.
 *
 * [item] is null for a summary or a general nudge: those announce the day, not one thing.
 */
@Serializable
data class CheckIn(
    val id: String,
    @SerialName("sent_at") val sentAt: String,
    /** null while unanswered; otherwise done | snoozed | rescheduled | seen. */
    val outcome: String? = null,
    @SerialName("answered_at") val answeredAt: String? = null,
    val item: Item? = null,
) {
    /** Whether this still wants a decision — the same rule the orb badge counts. */
    val open: Boolean get() = outcome == null
}

@Serializable
data class CheckInsResponse(val checkins: List<CheckIn> = emptyList())

/**
 * How many *things* still want a decision — what the orb badge shows.
 *
 * Distinct items, not rows. Each occurrence writes its own check-in, so a reminder nudged nine
 * times is nine rows; counting those gave a badge that only climbed and matched nothing the user
 * could see. Acting on an item closes all of its open rows at once, so the item is the honest
 * unit. Must agree with the same rule in `GET /checkins/unread`.
 *
 * Completed items are excluded — there is nothing left to decide — as are check-ins carrying no
 * item, which have no action attached.
 */
fun List<CheckIn>.attentionCount(): Int =
    filter { it.open && it.item != null && it.item.doneAt == null }
        .mapNotNull { it.item?.id }
        .distinct()
        .size

/** Told to the server when a local notification actually fires, so it can be listed later. */
@Serializable
data class FiredReport(
    @SerialName("scheduled_id") val scheduledId: String,
    @SerialName("item_id") val itemId: String? = null,
)

/** What a notification's buttons can do. `verb` maps to POST /items/{id}/{verb}. */
@Serializable
data class ScheduledAction(val label: String, val verb: String)

@Serializable
data class WeatherCondition(val metric: String, val op: String, val threshold: Double)

/**
 * One notification for this device to arm.
 *
 * Either [at] is set (fire at that instant) or [condition] is (watch for it to become true).
 * [id] is stable across syncs, so re-arming replaces rather than duplicates — and it is also
 * what gets reported back once the notification fires, so the same id identifies it everywhere.
 *
 * [itemId] is null for a summary or a general nudge, which announce the day rather than an item;
 * those also arrive with no [actions], since there is nothing to mark done.
 */
@Serializable
data class Scheduled(
    val id: String,
    @SerialName("item_id") val itemId: String? = null,
    val kind: String, // due | checkin | summary | nudge
    val at: String? = null,
    val title: String,
    val body: String,
    val actions: List<ScheduledAction> = emptyList(),
    val condition: WeatherCondition? = null,
    /**
     * Already due when the server built this — show it now rather than arming it for [at].
     *
     * Both notifiers refuse to arm anything in the past, so that a device waking after a long
     * sleep does not replay a backlog. This is the server saying it has already applied that
     * judgement: the reminder came due recently, nothing delivered it, and you should still hear
     * about it. [at] stays the time it should have fired, so the history stays truthful.
     */
    val late: Boolean = false,
)

@Serializable
data class GeoPoint(val lat: Double, val lon: Double)

/**
 * The full schedule. [location] travels with it so weather conditions can be checked on-device
 * without asking for a location permission — these are the user's configured coordinates.
 *
 * Progress nudges and the daily summaries arrive as ordinary dated entries in [notifications],
 * so there is exactly one kind of thing to arm.
 */
@Serializable
data class SyncResponse(
    val now: String,
    val location: GeoPoint,
    val notifications: List<Scheduled> = emptyList(),
)

@Serializable
data class Source(val title: String, val url: String)

/** A quick factual answer. Sources are mandatory — an unsourced answer isn't shown. */
@Serializable
data class Answer(val answer: String, val sources: List<Source> = emptyList())

@Serializable
data class CommandRequest(val text: String)

/**
 * Anything the user typed, plus the item they are replying to if there is one.
 *
 * [itemId] is what makes a pronoun resolvable: "push it to 6pm" has no subject, so without it the
 * server has to guess which item was meant — and it guesses wrong.
 */
/** One earlier turn, as it is sent back to the server and stored on disk. */
@Serializable
data class Turn(val role: String, val text: String)

@Serializable
data class MessageRequest(
    val text: String,
    @SerialName("item_id") val itemId: String? = null,
    val tz: String,
    /**
     * What was said before this, oldest first.
     *
     * Words only — no record of which tools ran. Tool results carry relative times ("tomorrow at
     * 09:00") that would be false if replayed a day later, and the server re-reads the real list
     * on every message regardless, so carrying them would add staleness and no information.
     */
    val history: List<Turn> = emptyList(),
)

/**
 * One thing the server did, with the result it produced.
 *
 * [output] is the tool's own return value and its shape depends on [tool] — `created`/`when`/`id`
 * for a capture, `deleted` for a removal, `answer` for a question. Kept as a raw [JsonObject]
 * rather than a sealed hierarchy: the UI reads three or four keys out of it, and a class per tool
 * would be nine declarations that have to be edited every time the server grows a capability.
 */
@Serializable
data class ActionRecord(
    val tool: String,
    val output: JsonObject? = null,
) {
    /** A string field of the result, or null. */
    fun str(key: String): String? = (output?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/**
 * What the server decided to do, and what to say about it.
 *
 * Routing lives on the server because it needs a model to do well. The device used to pattern-
 * match locally, which had no category for input that was not a request at all — so "hello"
 * fell through to the command branch and became a task.
 *
 * [actions] is the list, in order, because one message can ask for several things: "add yoga
 * tomorrow 8am, add tv at 9, bump karate to 11" is three actions in one round-trip. [route]
 * names only the LAST of them and is kept for older clients — read [actions].
 */
@Serializable
data class MessageResponse(
    /** question | capture | edit | complete | chat — the last action only. Prefer [actions]. */
    val route: String,
    /** What Halo says. Empty for routes that answer with structured data instead. */
    val reply: String = "",
    val actions: List<ActionRecord> = emptyList(),
    val plan: Plan? = null,
)

/**
 * The user's settings, as the server reports them.
 *
 * Every field arrives with the deployment's default already folded in — never null — so the
 * settings page can render a value without knowing what the server would otherwise have used.
 */
@Serializable
data class Profile(
    val tz: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    @SerialName("morning_summary_time") val morningSummaryTime: String = "07:00",
    @SerialName("evening_summary_time") val eveningSummaryTime: String = "20:00",
    /** Which face the orb wears. Unknown values fall back to the one we can draw. */
    val avatar: String = DEFAULT_AVATAR,
    /** A character Halo plays, written by the user. Empty means none of their own. */
    val personality: String = "",
    /** The chosen character from the shared set, if one is selected. Null means "my own words". */
    @SerialName("personality_id") val personalityId: String? = null,
    /**
     * What Halo actually speaks in, once a selection and the free text have been resolved against
     * each other. Read-only — the settings page shows it so a built-in is not just a name.
     */
    val voice: String = "",
)

/**
 * A character the assistant can play.
 *
 * [builtin] rows ship with the server and belong to nobody; the rest are the user's own and are
 * the only ones that can be deleted. Sent by the server rather than derived here, so the app never
 * has to reason about who owns what.
 */
@Serializable
data class Personality(
    val id: String,
    val name: String,
    val persona: String,
    val builtin: Boolean = false,
)

@Serializable
internal data class PersonalitiesResponse(val personalities: List<Personality> = emptyList())

/**
 * A partial change to the profile.
 *
 * Null means "leave this alone", which is why every field is null by default — the server reads a
 * missing field exactly that way. Clearing a setting back to the default is a separate gesture the
 * settings page does not currently offer, so it is not modelled here.
 */
@Serializable
data class ProfilePatch(
    @SerialName("morning_summary_time") val morningSummaryTime: String? = null,
    @SerialName("evening_summary_time") val eveningSummaryTime: String? = null,
    val avatar: String? = null,
    val personality: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
)

/**
 * Choosing a character, or choosing none.
 *
 * Its own type rather than a field on [ProfilePatch], because the two need opposite things from the
 * encoder. Every field there is null-by-default and omitted when unset, which is exactly what makes
 * "leave this alone" work — and exactly what makes "clear it" impossible to express. [EncodeDefault]
 * forces this one onto the wire even when it is null, so selecting nothing reads as an explicit
 * null on the server and clears the selection.
 */
@Serializable
data class PersonalityChoice(
    @EncodeDefault
    @SerialName("personality_id")
    val personalityId: String? = null,
)

/** The face drawn when the user has never chosen one. Matches the server's own default. */
const val DEFAULT_AVATAR = "default"

/** How long a personality may be, mirroring the server's cap so the field can stop at it. */
const val PERSONALITY_MAX = 200

@Serializable
data class CommandResponse(val plan: Plan)

@Serializable
data class ApiError(val error: String)
