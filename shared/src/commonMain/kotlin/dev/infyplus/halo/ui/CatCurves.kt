package dev.infyplus.halo.ui

import androidx.compose.runtime.Immutable

/**
 * The looping animations, as pure phase-to-delta functions.
 *
 * Each takes a normalised phase in `0f..1f` — one full cycle — and returns the offset to add on
 * top of the current [CatPose]. Keeping them pure means the whole motion sheet is testable
 * without a renderer or a clock, and it lets one animation drive several channels from a single
 * phase (the hop moves the body on three axes at once, which three independent animations would
 * let drift apart).
 *
 * Transcribed from the `@keyframes` blocks in `reference/floating-assistant.html`, lines 247-344.
 * Values are in the cat's SVG user units and degrees.
 */
object CatCurves {

    /**
     * CSS `ease-in-out` between keyframe stops, near enough.
     *
     * The exact curve is `cubic-bezier(.42,0,.58,1)`; smoothstep is within about a percent of it
     * and needs no solver. It is the identity at 0 and 1, so every keyframe value below is hit
     * exactly regardless — which is what the tests assert.
     */
    private fun smooth(t: Float) = t * t * (3f - 2f * t)

    /** Linear position of `p` within `[from, to]`, clamped. Undefined stops collapse to 0. */
    private fun span(p: Float, from: Float, to: Float): Float =
        if (to <= from) 0f else ((p - from) / (to - from)).coerceIn(0f, 1f)

    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

    /**
     * Out to the midpoint and back, eased at both ends: 0 at phase 0, 1 at .5, 0 again at 1.
     *
     * Written as one function rather than as two [span] calls per curve because the second of
     * those calls has to run *backwards*, and [span] answers a backwards range with a flat zero —
     * which held the whole second half of the cycle at rest and then snapped, the jerk that used
     * to be visible at the end of every breath.
     *
     * [smooth] has zero gradient at both ends, so the value not only meets itself across the loop
     * boundary but arrives there with no velocity — which is what makes the seam invisible.
     */
    private fun outAndBack(phase: Float) =
        smooth(if (phase <= 0.5f) phase * 2f else (1f - phase) * 2f)

    /** Breathing, `@keyframes breathe`: scale 1 to 1.025 and a 1-unit rise at the midpoint. */
    fun breathe(phase: Float): Breath {
        val t = outAndBack(phase)
        return Breath(scale = mix(1f, 1.025f, t), ty = mix(0f, -1f, t))
    }

    /**
     * Slow blink, `@keyframes blink`: open until 94%, shut at 97%, open again by 100%.
     *
     * Deliberately not eased — a blink that eases looks like a droop.
     */
    fun blinkScaleY(phase: Float): Float = when {
        phase <= 0.94f -> 1f
        phase <= 0.97f -> mix(1f, 0.08f, span(phase, 0.94f, 0.97f))
        else -> mix(0.08f, 1f, span(phase, 0.97f, 1f))
    }

    /** Idle pupil drift, `@keyframes drift`: still, then a slow glance right, then back. */
    fun driftTx(phase: Float): Float = when {
        phase <= 0.44f -> 0f
        phase <= 0.55f -> mix(0f, 2.6f, smooth(span(phase, 0.44f, 0.55f)))
        phase <= 0.72f -> 2.6f
        else -> mix(2.6f, 0f, smooth(span(phase, 0.72f, 1f)))
    }

    /** Waiting, `@keyframes lazy`: pupils sweep side to side. */
    fun lazyTx(phase: Float): Float =
        if (phase <= 0.5f) mix(-3f, 3f, smooth(span(phase, 0f, 0.5f)))
        else mix(3f, -3f, smooth(span(phase, 0.5f, 1f)))

    /** Working, `@keyframes wobble`: a small rock, in degrees. */
    fun wobbleRotation(phase: Float): Float =
        if (phase <= 0.5f) mix(-1.2f, 1.2f, smooth(span(phase, 0f, 0.5f)))
        else mix(1.2f, -1.2f, smooth(span(phase, 0.5f, 1f)))

    /**
     * The hop, `@keyframes hop`.
     *
     * Five stops, and the shape is the whole joke: it sits still for most of the cycle, *dips*
     * at 68% to gather itself (the anticipation), launches to its peak at 82%, then overshoots
     * flat on landing. Squash and stretch are inverse — wider when shorter.
     */
    fun hop(phase: Float): Hop {
        val stops = listOf(
            0.00f to Hop(0f, 1f, 1f),
            0.62f to Hop(0f, 1f, 1f),
            0.68f to Hop(2f, 1.07f, 0.92f),   // anticipation: squat and widen
            0.82f to Hop(-9f, 0.95f, 1.07f),  // peak: stretch tall and narrow
            0.92f to Hop(0f, 1.03f, 0.97f),   // landing overshoot
            1.00f to Hop(0f, 1f, 1f),
        )
        for (i in 0 until stops.size - 1) {
            val (p0, a) = stops[i]
            val (p1, b) = stops[i + 1]
            if (phase <= p1) {
                val t = smooth(span(phase, p0, p1))
                return Hop(mix(a.ty, b.ty, t), mix(a.sx, b.sx, t), mix(a.sy, b.sy, t))
            }
        }
        return Hop(0f, 1f, 1f)
    }

    /** Grumpy, `@keyframes shake`: a quick left-right jitter. */
    fun shakeTx(phase: Float): Float = when {
        phase <= 0.25f -> mix(0f, -3f, span(phase, 0f, 0.25f))
        phase <= 0.75f -> mix(-3f, 3f, span(phase, 0.25f, 0.75f))
        else -> mix(3f, 0f, span(phase, 0.75f, 1f))
    }

    /** Steam, `@keyframes puff`: rises and fades as it grows. */
    fun puff(phase: Float) = Puff(
        alpha = mix(0.9f, 0f, phase),
        ty = mix(0f, -12f, phase),
        scale = mix(0.7f, 1.2f, phase),
    )

    /** Idle eye-light, `@keyframes gleam`: the glow breathes between .42 and .72. */
    fun gleamAlpha(phase: Float): Float =
        if (phase <= 0.5f) mix(0.42f, 0.72f, smooth(span(phase, 0f, 0.5f)))
        else mix(0.72f, 0.42f, smooth(span(phase, 0.5f, 1f)))

    /** Excited sparkles, `@keyframes twinkle`. Same out-and-back shape as the breath. */
    fun twinkle(phase: Float): Twinkle {
        val t = outAndBack(phase)
        return Twinkle(alpha = mix(0.35f, 1f, t), scale = mix(0.8f, 1.15f, t))
    }

    /**
     * The one-off squash played on every expression change, `@keyframes squash`.
     *
     * A triangle peaking at the midpoint: 6% shorter and wider, then back. The reference restarts
     * it by forcing a reflow (line 819); here it is simply a fresh animation per change.
     */
    fun squash(t: Float): Squash {
        val k = 1f - kotlin.math.abs(2f * t - 1f)
        return Squash(sx = 1f + 0.06f * k, sy = 1f - 0.06f * k)
    }

    @Immutable data class Breath(val scale: Float, val ty: Float)
    @Immutable data class Hop(val ty: Float, val sx: Float, val sy: Float)
    @Immutable data class Puff(val alpha: Float, val ty: Float, val scale: Float)
    @Immutable data class Twinkle(val alpha: Float, val scale: Float)
    @Immutable data class Squash(val sx: Float, val sy: Float)
}
