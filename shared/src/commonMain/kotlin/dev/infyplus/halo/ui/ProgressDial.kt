package dev.infyplus.halo.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

/** Below this fraction remaining, the ring turns from mustard to tomato (reference line 873). */
private const val LATE_FRACTION = 0.2f

/**
 * The focus timer, as a ring around the orb.
 *
 * Note the dial has its **own** viewBox (`0 0 92 92`), a different space from the cat's — see
 * [CatGeometry]. It is drawn in its own Canvas layered under the cat rather than sharing one.
 *
 * The reference animates an SVG `stroke-dashoffset` because SVG has no partial-arc primitive.
 * `drawArc` *is* that primitive, so the dasharray is deliberately not ported.
 *
 * @param progress how much of the timer has elapsed, 0f..1f.
 */
@Composable
fun ProgressDial(
    progress: Float,
    modifier: Modifier = Modifier,
    late: Boolean = false,
    reducedMotion: Boolean = false,
) {
    // Driven off the same one-second tick as the countdown label, so a linear second-long tween
    // makes it sweep smoothly instead of stepping.
    val sweep by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = if (reducedMotion) snap() else tween(1000, easing = LinearEasing),
        label = "dial-sweep",
    )
    val color by animateColorAsState(
        targetValue = if (late) HaloPalette.warm else HaloPalette.sun,
        animationSpec = if (reducedMotion) snap() else tween(400),
        label = "dial-colour",
    )

    Canvas(modifier) {
        val s = size.minDimension / CatGeometry.DIAL_VIEW
        val radius = 43f * s
        val stroke = 4.5f * s
        val topLeft = Offset(center.x - radius, center.y - radius)
        val arcSize = Size(radius * 2, radius * 2)

        drawArc(
            color = HaloPalette.navy.copy(alpha = 0.34f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(stroke),
        )

        // A round cap at zero sweep still paints a visible dot, which reads as "1% done" the
        // instant a timer starts — so nothing is drawn until there is genuinely an arc.
        if (sweep > 0.002f) {
            drawArc(
                color = color,
                startAngle = -90f, // 12 o'clock; the CSS does this with a -90deg group rotation
                sweepAngle = 360f * sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/** Whether [remainingMs] of [totalMs] has crossed into the "running out" band. */
fun isLate(remainingMs: Long, totalMs: Long): Boolean =
    totalMs > 0 && remainingMs.toFloat() / totalMs < LATE_FRACTION

/** Elapsed fraction of a timer, clamped — what [ProgressDial] wants for `progress`. */
fun dialProgress(remainingMs: Long, totalMs: Long): Float =
    if (totalMs <= 0) 0f else (1f - remainingMs.toFloat() / totalMs).coerceIn(0f, 1f)
