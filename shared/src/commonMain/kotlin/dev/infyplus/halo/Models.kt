package dev.infyplus.halo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Item(
    val id: String,
    val kind: String, // task | event | reminder
    val title: String,
    @SerialName("due_at") val dueAt: String? = null,
    val priority: Int = 2,
    @SerialName("done_at") val doneAt: String? = null,
    @SerialName("created_at") val createdAt: String,
)

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
@Serializable
data class MessageRequest(
    val text: String,
    @SerialName("item_id") val itemId: String? = null,
    val tz: String,
)

/**
 * What the server decided to do, and what to say about it.
 *
 * Routing lives on the server because it needs a model to do well. The device used to pattern-
 * match locally, which had no category for input that was not a request at all — so "hello"
 * fell through to the command branch and became a task.
 */
@Serializable
data class MessageResponse(
    /** question | capture | edit | complete | chat */
    val route: String,
    /** What Halo says. Empty for routes that answer with structured data instead. */
    val reply: String = "",
    val answer: Answer? = null,
    val item: Item? = null,
    val plan: Plan? = null,
)

@Serializable
data class CommandResponse(val plan: Plan)

@Serializable
data class ApiError(val error: String)
