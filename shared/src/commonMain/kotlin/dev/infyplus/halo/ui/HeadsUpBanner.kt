package dev.infyplus.halo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.infyplus.halo.Scheduled
import kotlinx.coroutines.delay

/**
 * What a notification is, in words rather than the scheduler's own vocabulary.
 *
 * `due`, `checkin` and `nudge` are internal kinds; printed verbatim they read as jargon on the
 * one surface with the least room to explain itself.
 */
private fun bannerKind(kind: String) = when (kind) {
    "due" -> "REMINDER"
    "checkin" -> "CHECKING IN"
    "nudge" -> "A NUDGE"
    "summary" -> "SUMMARY"
    "pomodoro" -> "FOCUS"
    else -> kind.uppercase()
}

private val BannerShape = HaloShapes.Banner
private val DropIn = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1f)

/** How long it stays before withdrawing on its own (reference line 996). */
const val HEADS_UP_MS = 6_500L

/**
 * The card that drops in when a reminder fires while the overlay is alive.
 *
 * This exists *alongside* the system notification, not instead of it. The system one is the
 * reliable path — it survives the process being killed and shows on the lock screen — but it
 * looks like every other app's. This is the character's own voice, and it can act without
 * sending you to the shade first.
 *
 * Withdraws itself after [HEADS_UP_MS]. A banner that waits to be dismissed becomes another
 * thing to tidy up.
 *
 * @param onOpen tapping the body: reveal the item in the panel rather than acting blind.
 * @param onAct one of `done` / `snooze` / `reschedule` — the only three verbs the server takes.
 */
@Composable
fun HeadsUpBanner(
    notification: Scheduled?,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
    /** How many more are queued behind this one, so the user knows to expect them. */
    waiting: Int = 0,
    onOpen: (Scheduled) -> Unit = {},
    onAct: (Scheduled, String) -> Unit = { _, _ -> },
    onDismiss: () -> Unit = {},
) {
    // Withdraws on its own only when there is nothing to decide. A banner offering Done or Snooze
    // is a question, and a question that erases itself after six seconds is one the user is not
    // actually being asked — step away from the desk and it has been asked and answered without
    // them. Those wait to be acted on or dismissed.
    LaunchedEffect(notification) {
        if (notification != null && notification.actions.isEmpty()) {
            delay(HEADS_UP_MS)
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = notification != null,
        enter = if (reducedMotion) fadeIn(tween(200)) else
            fadeIn(tween(300)) + slideInVertically(tween(440, easing = DropIn)) { -it },
        exit = if (reducedMotion) fadeOut(tween(200)) else
            fadeOut(tween(300)) + slideOutVertically(tween(300)) { -it },
        modifier = modifier,
    ) {
        // Held so the card keeps its content through the exit animation instead of going blank
        // the instant the state clears.
        val shown = notification ?: return@AnimatedVisibility

        Column(
            Modifier
                .fillMaxWidth()
                .clip(BannerShape)
                .background(HaloPalette.cream)
                .border(2.dp, HaloPalette.navy, BannerShape),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(shown) }
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // The same face as the orb, drawn small. It scales off its layout size, so this is
                // the identical drawing rather than a second asset that could drift.
                AvatarFace(Expression.Happy, Modifier.size(46.dp))

                Column(Modifier.weight(1f)) {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Mono("HALO · ${bannerKind(shown.kind)}")
                        // Was hardcoded "NOW", which was a lie for exactly the notifications that
                        // most needed the truth: `late` means this came due while the device was
                        // asleep, so it may be hours old. The server keeps `at` at the time it
                        // should have fired precisely so the history stays honest.
                        Mono(
                            if (shown.late) "WAS DUE ${timeLabel(shown.at)?.uppercase() ?: "EARLIER"}"
                            else timeLabel(shown.at)?.uppercase() ?: "NOW",
                            color = if (shown.late) HaloPalette.warm else HaloPalette.navy.copy(alpha = 0.78f),
                        )
                    }
                    Text(
                        text = shown.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = HaloPalette.ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (shown.body.isNotBlank()) {
                        Text(
                            text = shown.body,
                            fontSize = 12.sp,
                            color = HaloPalette.navy.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // A summary or a general nudge has nothing to act on, so it gets no buttons.
            if (shown.actions.isNotEmpty() && shown.itemId != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(HaloPalette.navy.copy(alpha = 0.22f)),
                )
                Row(Modifier.fillMaxWidth().padding(6.dp)) {
                    shown.actions.forEach { action ->
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onAct(shown, action.verb) }
                                .padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = action.label,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = HaloPalette.navy,
                            )
                        }
                    }
                }
            }

            // Said out loud so a burst does not look like one alert that keeps changing its mind.
            if (waiting > 0) {
                Mono(
                    if (waiting == 1) "1 MORE WAITING" else "$waiting MORE WAITING",
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                    color = HaloPalette.navy.copy(alpha = 0.7f),
                )
            }
        }
    }
}

