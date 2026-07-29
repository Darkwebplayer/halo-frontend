package dev.infyplus.halo.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp as lerpColor

/**
 * The cat's expressions, as pure data.
 *
 * Nothing here touches Compose UI — these are numbers and interpolation, so the whole expression
 * sheet is unit-testable without a renderer. `CatFace` turns a [CatPose] into draw calls.
 *
 * ## The one idea this file is built around
 *
 * **The pose interpolates; the motion does not.** You can lerp "eyes at 74% height" into "eyes at
 * 55% height", but you cannot lerp a *hop* into a *wobble* — they are different loops with
 * different periods. So the state is split in two:
 *
 *  - [CatPose] is every continuously-varying property. One driver interpolates the whole thing,
 *    matching the reference's single shared `transition: .3s cubic-bezier(.3,1.2,.5,1)` on
 *    `.ears, .eyes-open, .eye, .cat-body-g` (reference line 235).
 *  - [CatMotion] names the loops. Each is a pure phase-to-delta function in [CatCurves], applied
 *    on top of the pose and faded in and out by its own weight, so switching idle to excited
 *    cross-fades breathing into hopping instead of snapping.
 *
 * Values are transcribed from `reference/floating-assistant.html` lines 200-345 and are in the
 * cat's own SVG user units (viewBox `-6 -12 112 116`), not pixels or dp.
 */
enum class Expression {
    /** Default. Breathing, slow blink, occasional pupil drift. */
    Idle,

    /** Awaiting a reply. Heavy lids, lazy side-to-side. */
    Wait,

    /** A timer is running. Concentrating: brows angle in and down, not angry-steep. */
    Work,

    /** Something good happened. Arc eyes, ears up, hops with anticipation. */
    Happy,

    /** Lids droop from above, pupils look up, ears fall. */
    Sad,

    /** A mild huff. Brief by design, never sticky. */
    Cross,

    /** No connection. The lights are out. */
    Dead,

    /** The payoff — a wink on any success. Asymmetric on purpose. */
    Wink,
}

/**
 * One mouth, as a single quadratic.
 *
 * Five of the reference's six mouths (lines 718-723) share this topology, so they morph into one
 * another by interpolating six numbers — smile to frown is literally the control point crossing
 * the chord. The sixth (`m-o`, the dead lens) is two arcs and does not fit; it gets its own
 * crossfade channel on [CatPose.mouthLensAlpha] instead, which is acceptable because `dead` is a
 * hard state change anyway — the body greys out at the same moment.
 */
@Immutable
data class MouthCurve(
    val start: Offset,
    val control: Offset,
    val end: Offset,
    /** 1 for the open mouth, which is filled rather than outlined (reference line 229). */
    val fill: Float = 0f,
) {
    companion object {
        val Smile = MouthCurve(Offset(42f, 80f), Offset(50f, 87f), Offset(58f, 80f))
        /** The straight line as a degenerate quadratic, control at the chord midpoint. */
        val Flat = MouthCurve(Offset(43f, 82f), Offset(50f, 82f), Offset(57f, 82f))
        val Frown = MouthCurve(Offset(42f, 85f), Offset(50f, 79f), Offset(58f, 85f))
        val Pout = MouthCurve(Offset(44f, 83f), Offset(50f, 80f), Offset(56f, 83f))
        val Open = MouthCurve(Offset(40f, 78f), Offset(50f, 92f), Offset(60f, 78f), fill = 1f)
    }
}

fun lerp(a: MouthCurve, b: MouthCurve, t: Float) = MouthCurve(
    start = androidx.compose.ui.geometry.lerp(a.start, b.start, t),
    control = androidx.compose.ui.geometry.lerp(a.control, b.control, t),
    end = androidx.compose.ui.geometry.lerp(a.end, b.end, t),
    fill = lerpF(a.fill, b.fill, t),
)

/**
 * A complete, static pose. Every field interpolates.
 *
 * Left/right pairs are one field negated for the right side wherever the reference is
 * antisymmetric (brows at lines 262-263, 293-296, 304-307). The ears and the left eye get
 * explicit per-side fields because [Expression.Wink] deliberately breaks the symmetry.
 */
@Immutable
data class CatPose(
    // ── eyes (group origin 37,57 and 63,57) ──────────────────────────────────
    val eyeScaleY: Float = 1f,
    val eyeTy: Float = 0f,
    /** Both open eyes at once — `happy` and `dead` replace them with arcs and crosses. */
    val eyesOpenAlpha: Float = 1f,
    val pupilTx: Float = 0f,
    val pupilTy: Float = 0f,
    /** The wink closes only the left pupil (reference line 330). */
    val pupilAlphaL: Float = 1f,
    val lidArcAlphaL: Float = 0f,
    val lidArcAlphaR: Float = 0f,
    val deadXAlpha: Float = 0f,
    /** The soft pool of light behind each pupil. Never on the pupil itself. */
    val glowAlpha: Float = 0.5f,

    // ── brows (origin 37,38 and 63,38) — symmetric in every state ────────────
    val browAlpha: Float = 0f,
    /** Left brow rotation in degrees; the right brow is always the negation. */
    val browRotation: Float = 0f,
    val browTy: Float = 0f,

    // ── ears (origin 22,27 and 78,27) ────────────────────────────────────────
    val earRotationL: Float = 0f,
    val earRotationR: Float = 0f,
    val earTy: Float = 0f,
    val earScaleY: Float = 1f,

    // ── mouth ────────────────────────────────────────────────────────────────
    val mouth: MouthCurve = MouthCurve.Smile,
    /** The `dead` lens mouth, crossfaded over the morphing one. */
    val mouthLensAlpha: Float = 0f,

    // ── skin ─────────────────────────────────────────────────────────────────
    val blushAlpha: Float = 0.7f,
    val bodyColor: Color = HaloPalette.body,
    val earOutColor: Color = HaloPalette.shade,
    val earInColor: Color = HaloPalette.blush,

    // ── the whole character (origin 50,92) ───────────────────────────────────
    val bodyTy: Float = 0f,
    val bodyRotation: Float = 0f,

    // ── decorations ──────────────────────────────────────────────────────────
    val sparkleAlpha: Float = 0f,
    val steamAlpha: Float = 0f,
)

private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * Interpolate two poses.
 *
 * The endpoints short-circuit rather than falling through the arithmetic. Compose's colour lerp
 * round-trips through Oklab, so at t=0 it returns a value a rounding step away from the input —
 * which would leave a settled expression very slightly off its own palette entry, forever. It
 * also saves the whole struct copy on the overwhelmingly common resting case.
 */
fun lerp(a: CatPose, b: CatPose, t: Float): CatPose = when {
    t <= 0f -> a
    t >= 1f -> b
    else -> lerpBetween(a, b, t)
}

private fun lerpBetween(a: CatPose, b: CatPose, t: Float) = CatPose(
    eyeScaleY = lerpF(a.eyeScaleY, b.eyeScaleY, t),
    eyeTy = lerpF(a.eyeTy, b.eyeTy, t),
    eyesOpenAlpha = lerpF(a.eyesOpenAlpha, b.eyesOpenAlpha, t),
    pupilTx = lerpF(a.pupilTx, b.pupilTx, t),
    pupilTy = lerpF(a.pupilTy, b.pupilTy, t),
    pupilAlphaL = lerpF(a.pupilAlphaL, b.pupilAlphaL, t),
    lidArcAlphaL = lerpF(a.lidArcAlphaL, b.lidArcAlphaL, t),
    lidArcAlphaR = lerpF(a.lidArcAlphaR, b.lidArcAlphaR, t),
    deadXAlpha = lerpF(a.deadXAlpha, b.deadXAlpha, t),
    glowAlpha = lerpF(a.glowAlpha, b.glowAlpha, t),
    browAlpha = lerpF(a.browAlpha, b.browAlpha, t),
    browRotation = lerpF(a.browRotation, b.browRotation, t),
    browTy = lerpF(a.browTy, b.browTy, t),
    earRotationL = lerpF(a.earRotationL, b.earRotationL, t),
    earRotationR = lerpF(a.earRotationR, b.earRotationR, t),
    earTy = lerpF(a.earTy, b.earTy, t),
    earScaleY = lerpF(a.earScaleY, b.earScaleY, t),
    mouth = lerp(a.mouth, b.mouth, t),
    mouthLensAlpha = lerpF(a.mouthLensAlpha, b.mouthLensAlpha, t),
    blushAlpha = lerpF(a.blushAlpha, b.blushAlpha, t),
    bodyColor = lerpColor(a.bodyColor, b.bodyColor, t),
    earOutColor = lerpColor(a.earOutColor, b.earOutColor, t),
    earInColor = lerpColor(a.earInColor, b.earInColor, t),
    bodyTy = lerpF(a.bodyTy, b.bodyTy, t),
    bodyRotation = lerpF(a.bodyRotation, b.bodyRotation, t),
    sparkleAlpha = lerpF(a.sparkleAlpha, b.sparkleAlpha, t),
    steamAlpha = lerpF(a.steamAlpha, b.steamAlpha, t),
)

/** `color-mix(in oklch, grey 86%, black)` and `… grey 70%, white)` (reference lines 318-319). */
private val DeadEarOut = lerpColor(HaloPalette.grey, Color.Black, 0.14f)
private val DeadEarIn = lerpColor(HaloPalette.grey, Color.White, 0.30f)

/** The resting pose for an expression, before any looping motion is added on top. */
fun poseFor(expression: Expression): CatPose = when (expression) {
    Expression.Idle -> CatPose()

    Expression.Wait -> CatPose(
        eyeScaleY = 0.55f,
        eyeTy = 5f,
        mouth = MouthCurve.Flat,
    )

    Expression.Work -> CatPose(
        eyeScaleY = 0.74f,
        browAlpha = 1f,
        browRotation = 9f,
        browTy = 3f,
        earRotationL = -7f,
        earRotationR = 7f,
        mouth = MouthCurve.Flat,
    )

    Expression.Happy -> CatPose(
        eyesOpenAlpha = 0f,
        lidArcAlphaL = 1f,
        lidArcAlphaR = 1f,
        glowAlpha = 0.85f,
        earRotationL = -14f,
        earRotationR = 14f,
        earTy = -2f,
        mouth = MouthCurve.Open,
        blushAlpha = 0.85f,
        sparkleAlpha = 1f,
    )

    Expression.Sad -> CatPose(
        eyeTy = -2f,
        pupilTy = -3f,
        glowAlpha = 0.28f,
        browAlpha = 1f,
        browRotation = -16f,
        browTy = 2f,
        earRotationL = 26f,
        earRotationR = -26f,
        earTy = 4f,
        mouth = MouthCurve.Frown,
        bodyTy = 3f,
    )

    Expression.Cross -> CatPose(
        eyeScaleY = 0.62f,
        browAlpha = 1f,
        browRotation = 20f,
        browTy = 6f,
        earRotationL = 38f,
        earRotationR = -38f,
        earScaleY = 0.86f,
        mouth = MouthCurve.Pout,
        blushAlpha = 0.95f,
        steamAlpha = 1f,
    )

    Expression.Dead -> CatPose(
        eyesOpenAlpha = 0f,
        deadXAlpha = 1f,
        glowAlpha = 0f,
        earRotationL = 40f,
        earRotationR = -40f,
        earTy = 6f,
        // The morphing mouth is irrelevant here; the lens is crossfaded over it.
        mouthLensAlpha = 1f,
        blushAlpha = 0.12f,
        bodyColor = HaloPalette.grey,
        earOutColor = DeadEarOut,
        earInColor = DeadEarIn,
        bodyTy = 4f,
        bodyRotation = -6f,
    )

    // Asymmetric by design: only the left eye closes, and only the left ear tips.
    Expression.Wink -> CatPose(
        pupilAlphaL = 0f,
        lidArcAlphaL = 1f,
        glowAlpha = 0.85f,
        earRotationL = -12f,
        mouth = MouthCurve.Open,
        blushAlpha = 0.8f,
    )
}

/**
 * Which loops run for an expression.
 *
 * Periods are milliseconds, straight from the CSS `animation` shorthands. A null means the loop
 * is off; its weight fades to zero rather than stopping dead.
 */
@Immutable
data class CatMotion(
    val breatheMs: Int? = null,
    val blinkMs: Int? = null,
    val driftMs: Int? = null,
    val lazyMs: Int? = null,
    val wobbleMs: Int? = null,
    val hopMs: Int? = null,
    /** The wink hops exactly once (reference line 336); excitement hops forever (line 279). */
    val hopRepeats: Int? = null,
    val shakeMs: Int? = null,
    val shakeRepeats: Int? = null,
    val puffMs: Int? = null,
    val gleamMs: Int? = null,
    val twinkleMs: Int? = null,
)

fun motionFor(expression: Expression): CatMotion = when (expression) {
    Expression.Idle -> CatMotion(breatheMs = 3600, blinkMs = 5400, driftMs = 9000, gleamMs = 3600)
    Expression.Wait -> CatMotion(breatheMs = 5000, lazyMs = 2600)
    Expression.Work -> CatMotion(wobbleMs = 1500)
    Expression.Happy -> CatMotion(hopMs = 1100, twinkleMs = 1100)
    Expression.Sad -> CatMotion()
    Expression.Cross -> CatMotion(shakeMs = 340, shakeRepeats = 3, puffMs = 1000)
    Expression.Dead -> CatMotion()
    Expression.Wink -> CatMotion(hopMs = 1100, hopRepeats = 1)
}
