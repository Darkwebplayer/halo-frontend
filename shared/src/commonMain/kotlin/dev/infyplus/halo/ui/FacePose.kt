package dev.infyplus.halo.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp as lerpColor

/**
 * The mouthless faces, as pure data.
 *
 * The same split as the cat ([CatPose]): **the pose interpolates; the motion does not.** Every
 * continuously-varying property lives in [FacePose] and is lerped by one driver; the loops are
 * named in [CatMotion] and shaped by [CatCurves] — these characters reuse `motionFor` outright, so
 * they differ by *shape*, not timing, and there is one timing sheet to keep right.
 *
 * Shared by [HaloBuddyFace] and [WaterlooFace], which draw the same expression sheet in very
 * different clothes. That is why the fields are named for what they *mean* — an eye that is open,
 * a lid that covers, ears that droop — rather than for any one drawing: a character with no ears
 * simply ignores those, and a flat character ignores the gradient stops.
 */

/**
 * A complete, static pose. Every field interpolates.
 *
 * The eye is the idea worth knowing about: it is a pale capsule with the dark filled in from the
 * top down to [lidCover]. At 1 that is a plain black capsule (idle); at .58 it is the heavy lid of
 * waiting; at .5 with the ears down it is sad. Three hand-drawn eye treatments, two numbers.
 */
@Immutable
data class FacePose(
    // ── eyes (centres 37,62 and 63,62) ───────────────────────────────────────
    val eyeScaleY: Float = 1f,
    val eyeTy: Float = 0f,
    val pupilTx: Float = 0f,
    val pupilTy: Float = 0f,
    /** How much of the capsule the dark lid fills, from the top. 1 is a wide-awake eye. */
    val lidCover: Float = 1f,
    /** The open eye as a whole — arcs, crosses and bars replace it rather than sit on it. */
    val eyeAlpha: Float = 1f,
    /** The wink closes only the left eye, so that side needs its own multiplier. */
    val eyeAlphaL: Float = 1f,
    val glintAlpha: Float = 0f,

    // ── the shapes that stand in for an open eye ─────────────────────────────
    /** The happy arc. Left only is the wink, which is why the sides are separate fields. */
    val arcAlphaL: Float = 0f,
    val arcAlphaR: Float = 0f,
    val deadXAlpha: Float = 0f,
    /** The determined bar. Rotation is the whole read: a little is Work, more is Cross. */
    val angryAlpha: Float = 0f,
    val angryRotation: Float = 0f,
    val tearAlpha: Float = 0f,

    // ── ears (origins 29,40 and 71,40) ───────────────────────────────────────
    val earRotationL: Float = 0f,
    val earRotationR: Float = 0f,
    val earTy: Float = 0f,
    val earScaleY: Float = 1f,

    // ── skin ─────────────────────────────────────────────────────────────────
    val topColor: Color = BuddyPalette.top,
    val bottomColor: Color = BuddyPalette.bottom,

    // ── the whole character (origin 50,92) ───────────────────────────────────
    val bodyTy: Float = 0f,
    val bodyRotation: Float = 0f,

    // ── decorations ──────────────────────────────────────────────────────────
    val sparkleAlpha: Float = 0f,
    val steamAlpha: Float = 0f,
    /** Speed lines. Only actually drawn at the top of a hop — see the renderer. */
    val speedAlpha: Float = 0f,
)

private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * Interpolate two poses.
 *
 * The endpoints short-circuit rather than falling through the arithmetic, for the same reason the
 * cat's does: Compose's colour lerp round-trips through Oklab, so at t=0 it returns a value a
 * rounding step away from the input — leaving a settled expression forever slightly off its own
 * palette entry.
 */
fun lerp(a: FacePose, b: FacePose, t: Float): FacePose = when {
    t <= 0f -> a
    t >= 1f -> b
    else -> FacePose(
        eyeScaleY = lerpF(a.eyeScaleY, b.eyeScaleY, t),
        eyeTy = lerpF(a.eyeTy, b.eyeTy, t),
        pupilTx = lerpF(a.pupilTx, b.pupilTx, t),
        pupilTy = lerpF(a.pupilTy, b.pupilTy, t),
        lidCover = lerpF(a.lidCover, b.lidCover, t),
        eyeAlpha = lerpF(a.eyeAlpha, b.eyeAlpha, t),
        eyeAlphaL = lerpF(a.eyeAlphaL, b.eyeAlphaL, t),
        glintAlpha = lerpF(a.glintAlpha, b.glintAlpha, t),
        arcAlphaL = lerpF(a.arcAlphaL, b.arcAlphaL, t),
        arcAlphaR = lerpF(a.arcAlphaR, b.arcAlphaR, t),
        deadXAlpha = lerpF(a.deadXAlpha, b.deadXAlpha, t),
        angryAlpha = lerpF(a.angryAlpha, b.angryAlpha, t),
        angryRotation = lerpF(a.angryRotation, b.angryRotation, t),
        tearAlpha = lerpF(a.tearAlpha, b.tearAlpha, t),
        earRotationL = lerpF(a.earRotationL, b.earRotationL, t),
        earRotationR = lerpF(a.earRotationR, b.earRotationR, t),
        earTy = lerpF(a.earTy, b.earTy, t),
        earScaleY = lerpF(a.earScaleY, b.earScaleY, t),
        topColor = lerpColor(a.topColor, b.topColor, t),
        bottomColor = lerpColor(a.bottomColor, b.bottomColor, t),
        bodyTy = lerpF(a.bodyTy, b.bodyTy, t),
        bodyRotation = lerpF(a.bodyRotation, b.bodyRotation, t),
        sparkleAlpha = lerpF(a.sparkleAlpha, b.sparkleAlpha, t),
        steamAlpha = lerpF(a.steamAlpha, b.steamAlpha, t),
        speedAlpha = lerpF(a.speedAlpha, b.speedAlpha, t),
    )
}

/** The resting pose for an expression, before any looping motion is added on top. */
fun facePoseFor(expression: Expression): FacePose = when (expression) {
    Expression.Idle -> FacePose(glintAlpha = 1f)

    Expression.Wait -> FacePose(
        eyeScaleY = 0.86f,
        eyeTy = 3f,
        lidCover = 0.58f,
        earRotationL = -12f,
        earRotationR = 12f,
        earTy = 1f,
    )

    Expression.Work -> FacePose(
        eyeAlpha = 0f,
        angryAlpha = 1f,
        angryRotation = 16f,
        earRotationL = -6f,
        earRotationR = 6f,
    )

    Expression.Happy -> FacePose(
        eyeAlpha = 0f,
        arcAlphaL = 1f,
        arcAlphaR = 1f,
        earRotationL = -10f,
        earRotationR = 10f,
        earTy = -2f,
        sparkleAlpha = 1f,
        speedAlpha = 1f,
    )

    Expression.Sad -> FacePose(
        eyeScaleY = 0.8f,
        eyeTy = -1f,
        pupilTy = -1f,
        lidCover = 0.5f,
        tearAlpha = 1f,
        earRotationL = -115f,
        earRotationR = 115f,
        earTy = 4f,
        earScaleY = 0.92f,
        bodyTy = 3f,
    )

    Expression.Cross -> FacePose(
        eyeAlpha = 0f,
        angryAlpha = 1f,
        angryRotation = 25f,
        earRotationL = -12f,
        earRotationR = 12f,
        earScaleY = 0.88f,
        steamAlpha = 1f,
    )

    Expression.Dead -> FacePose(
        eyeAlpha = 0f,
        deadXAlpha = 1f,
        earRotationL = -125f,
        earRotationR = 125f,
        earTy = 6f,
        topColor = BuddyPalette.greyTop,
        bottomColor = BuddyPalette.greyBottom,
        bodyTy = 4f,
        bodyRotation = -6f,
    )

    // Asymmetric by design: only the left eye closes, and only the left ear tips.
    Expression.Wink -> FacePose(
        arcAlphaL = 1f,
        // The right eye stays open and keeps its capsule; the left is replaced by the arc.
        eyeAlphaL = 0f,
        glintAlpha = 1f,
        earRotationL = -12f,
        sparkleAlpha = 0.8f,
        speedAlpha = 1f,
    )
}
