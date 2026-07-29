package dev.infyplus.halo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text

/** The springy curve the reference uses for things popping into existence (lines 358, 378). */
private val PopEasing = CubicBezierEasing(0.34f, 1.5f, 0.5f, 1f)

/**
 * The floating orb: the cat, the progress ring around it, an unread badge, and a speech cloud.
 *
 * Everything interactive is hoisted — this composable neither knows nor cares whether it is
 * inside an Android overlay window or a transparent desktop one. That is what lets both
 * platforms share the same choreography.
 *
 * @param countdown mm:ss while a timer runs, else null. Also decides whether the dial shows.
 * @param progress how much of the running timer has elapsed, 0f..1f.
 * @param say up to [CLOUD_MAX] characters to show in the cloud, or null to hide it.
 * @param unread the badge count; hidden at zero.
 * @param resting whether the overlay has receded — a permanent overlay that never gets out of
 *   the way becomes clutter, so it dims when untouched (reference line 385).
 *
 * **Attaches no gestures of its own.** It briefly had a `clickable` on the cat, and that single
 * modifier broke both platforms at once: `clickable` consumes the pointer-down that an enclosing
 * drag or tap detector needs, so the host's tap never fired — the panel would not open, and
 * because nothing could register as interaction the orb dimmed after a few seconds and stayed
 * dim. Hosts differ in what they need (absolute-coordinate dragging on Android, a window drag
 * area on desktop), so the gesture belongs to whoever is hosting it, via [modifier].
 */
@Composable
fun Orb(
    expression: Expression,
    modifier: Modifier = Modifier,
    progress: Float = 0f,
    showDial: Boolean = false,
    late: Boolean = false,
    say: String? = null,
    unread: Int = 0,
    resting: Boolean = false,
    reducedMotion: Boolean = false,
    /**
     * The one gesture this composable does attach, and only to the badge.
     *
     * The badge is a distinct target with a distinct meaning — "show me the things waiting" —
     * and routing it through the host's single tap handler would lose that: tapping anywhere on
     * the orb would have to mean the same thing. It consumes touches only within its own 28dp
     * circle, so dragging from anywhere else is unaffected; dragging *by the badge* is not
     * possible, which is a fair trade for a target this small.
     */
    onBadgeClick: (() -> Unit)? = null,
) {
    val dim by animateFloatAsState(
        targetValue = if (resting) 0.62f else 1f,
        animationSpec = if (reducedMotion) snap() else tween(300),
        label = "orb-recede",
    )

    Box(modifier.alpha(dim), contentAlignment = Alignment.Center) {
        // The dial sits in its own coordinate space, inset to the orb — see CatGeometry.
        val dialAlpha by animateFloatAsState(
            targetValue = if (showDial) 1f else 0f,
            animationSpec = if (reducedMotion) snap() else tween(300),
            label = "dial-visibility",
        )
        if (dialAlpha > 0f) {
            ProgressDial(
                progress = progress,
                modifier = Modifier.size(CatGeometry.ORB_DP).alpha(dialAlpha),
                late = late,
                reducedMotion = reducedMotion,
            )
        }

        CatFace(
            expression = expression,
            // Fills the whole box: the canvas is deliberately larger than the orb so the
            // excited hop and the ground shadow have somewhere to go without clipping.
            modifier = Modifier.size(CatGeometry.CANVAS_DP),
            reducedMotion = reducedMotion,
        )

        SpeechCloud(
            text = say,
            reducedMotion = reducedMotion,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        NotificationBadge(
            count = unread,
            reducedMotion = reducedMotion,
            modifier = Modifier.align(Alignment.TopStart),
            onClick = onBadgeClick,
        )
    }
}

/**
 * Ten characters in a comic bubble above the orb's head.
 *
 * Hidden entirely rather than truncated when there is nothing that fits — see [cloudText] for
 * why that is the rule.
 */
@Composable
fun SpeechCloud(
    text: String?,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
) {
    val shown = cloudText(text)
    AnimatedVisibility(
        visible = shown != null,
        enter = if (reducedMotion) fadeIn(tween(200)) else
            fadeIn(tween(200)) + scaleIn(tween(300, easing = PopEasing), initialScale = 0.6f),
        exit = if (reducedMotion) fadeOut(tween(200)) else
            fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.4f),
        modifier = modifier,
    ) {
        Box(
            Modifier
                .drawBehind { drawCloudTail() }
                .clip(RoundedCornerShape(13.dp))
                .background(HaloPalette.cream)
                .border(2.dp, HaloPalette.navy, RoundedCornerShape(13.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Text(
                text = shown.orEmpty(),
                color = HaloPalette.ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

/**
 * The tail: two stacked circles, the storybook way, not a triangle (reference lines 361-367).
 *
 * Drawn behind the bubble so the bubble's own fill covers where they meet.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCloudTail() {
    val big = 4.5.dp.toPx()
    val small = 2.5.dp.toPx()
    val right = size.width - 16.dp.toPx()

    listOf(
        Triple(right, size.height + big * 0.6f, big),
        Triple(right - 3.dp.toPx(), size.height + big * 2.4f, small),
    ).forEach { (cx, cy, r) ->
        val at = Offset(cx, cy)
        drawCircle(HaloPalette.cream, r, at)
        drawCircle(HaloPalette.navy, r, at, style = Stroke(2.dp.toPx()))
    }
}

/** The unread count, as a mustard coin clipped to the orb's shoulder. */
@Composable
fun NotificationBadge(
    count: Int,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    AnimatedVisibility(
        visible = count > 0,
        enter = if (reducedMotion) fadeIn(tween(200)) else
            fadeIn(tween(200)) + scaleIn(tween(380, easing = PopEasing), initialScale = 0f),
        exit = if (reducedMotion) fadeOut(tween(200)) else
            fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.4f),
        modifier = modifier,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(HaloPalette.sun)
                .border(2.5.dp, HaloPalette.navy, CircleShape)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                // Past a point the exact number stops mattering and the badge stops fitting.
                text = if (count > 9) "9+" else count.toString(),
                color = HaloPalette.ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
