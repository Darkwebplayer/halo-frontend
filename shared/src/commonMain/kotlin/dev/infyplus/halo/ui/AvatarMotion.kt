package dev.infyplus.halo.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * The animation driver every avatar shares.
 *
 * Nothing here knows what is being drawn — it knows only that a character has a pose that
 * interpolates, a set of named loops from [CatCurves], and a body that hops and wobbles about an
 * origin. Both faces feed the same numbers through it, which is what stops a second character from
 * arriving with a second, slightly-different copy of the timing.
 *
 * See [Cat] for why pose and motion are modelled separately in the first place.
 */

/**
 * The reference's shared transition, applied to every animated property at once
 * (`transition: .3s cubic-bezier(.3,1.2,.5,1)`, line 235). The slight overshoot is what gives
 * expression changes their bounce.
 */
private val PoseEasing = CubicBezierEasing(0.3f, 1.2f, 0.5f, 1f)
private const val POSE_MS = 300
private const val SQUASH_MS = 180

/**
 * Interpolate from whatever is currently on screen towards [target].
 *
 * Interrupting mid-flight is position-continuous because `from` captures what is *showing*, not the
 * previous target — otherwise a fast idle→work→idle would visibly jump backwards.
 */
@Composable
internal fun <P> rememberMorph(
    target: P,
    expression: Expression,
    reducedMotion: Boolean,
    lerp: (P, P, Float) -> P,
): P {
    var from by remember { mutableStateOf(target) }
    var shown by remember { mutableStateOf(target) }
    val morph = remember { Animatable(1f) }

    LaunchedEffect(expression, reducedMotion) {
        from = shown
        if (reducedMotion) {
            morph.snapTo(1f)
        } else {
            morph.snapTo(0f)
            morph.animateTo(1f, tween(POSE_MS, easing = PoseEasing))
        }
    }

    return lerp(from, target, morph.value).also { shown = it }
}

/** Every loop's normalised phase for this frame. Shaping happens in [CatCurves], never here. */
@Immutable
internal data class Phases(
    val breathe: Float = 0f,
    val blink: Float = 0f,
    val drift: Float = 0f,
    val lazy: Float = 0f,
    val wobble: Float = 0f,
    val hop: Float = 0f,
    val puff: Float = 0f,
    val gleam: Float = 0f,
    val twinkle: Float = 0f,
    /** Finite: three repeats then still, so it cannot ride the infinite clock. */
    val shake: Float = 0f,
    /** The one-off entry squash. 1 means settled. */
    val squash: Float = 1f,
)

/**
 * One infinite transition for every loop.
 *
 * Each `animateFloat` yields a *phase*, never a value — so a single clock can drive several
 * channels at once (the hop moves the body on three axes and they must stay in step).
 */
@Composable
internal fun rememberPhases(motion: CatMotion, expression: Expression, reducedMotion: Boolean): Phases {
    val loops = rememberInfiniteTransition(label = "avatar-loops")

    @Composable
    fun phase(periodMs: Int?, label: String): Float =
        if (periodMs == null || reducedMotion) 0f else loops.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(periodMs, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = label,
        ).value

    val shake = remember { Animatable(0f) }
    LaunchedEffect(expression, reducedMotion) {
        shake.snapTo(0f)
        val ms = motion.shakeMs
        val repeats = motion.shakeRepeats
        if (!reducedMotion && ms != null && repeats != null) {
            repeat(repeats) { shake.animateTo(1f, tween(ms, easing = LinearEasing)); shake.snapTo(0f) }
        }
    }

    // Every state change squashes on the way in (reference line 343).
    val squash = remember { Animatable(1f) }
    LaunchedEffect(expression, reducedMotion) {
        if (!reducedMotion) {
            squash.snapTo(0f)
            squash.animateTo(1f, tween(SQUASH_MS, easing = LinearEasing))
        }
    }

    return Phases(
        breathe = phase(motion.breatheMs, "breathe"),
        blink = phase(motion.blinkMs, "blink"),
        drift = phase(motion.driftMs, "drift"),
        lazy = phase(motion.lazyMs, "lazy"),
        wobble = phase(motion.wobbleMs, "wobble"),
        hop = phase(motion.hopMs, "hop"),
        puff = phase(motion.puffMs, "puff"),
        gleam = phase(motion.gleamMs, "gleam"),
        twinkle = phase(motion.twinkleMs, "twinkle"),
        shake = shake.value,
        squash = squash.value,
    )
}

/** The whole-character transform for one frame, about the character's own body origin. */
@Immutable
internal data class BodyMotion(
    val tx: Float = 0f,
    val ty: Float = 0f,
    val sx: Float = 1f,
    val sy: Float = 1f,
    val rotation: Float = 0f,
)

/** Fold every active loop's contribution into one transform, on top of the pose's own offsets. */
internal fun Phases.body(motion: CatMotion, poseTy: Float, poseRotation: Float): BodyMotion {
    var tx = 0f
    var ty = poseTy
    var sx = 1f
    var sy = 1f
    var rotation = poseRotation

    if (motion.breatheMs != null) {
        val b = CatCurves.breathe(breathe)
        sx *= b.scale; sy *= b.scale; ty += b.ty
    }
    if (motion.hopMs != null) {
        val h = CatCurves.hop(hop)
        ty += h.ty; sx *= h.sx; sy *= h.sy
    }
    if (motion.wobbleMs != null) rotation += CatCurves.wobbleRotation(wobble)
    if (motion.shakeMs != null) tx += CatCurves.shakeTx(shake)

    // The squash rides on top of everything, so it reads on any expression.
    if (squash < 1f) {
        val s = CatCurves.squash(squash)
        sx *= s.sx; sy *= s.sy
    }

    return BodyMotion(tx, ty, sx, sy, rotation)
}
