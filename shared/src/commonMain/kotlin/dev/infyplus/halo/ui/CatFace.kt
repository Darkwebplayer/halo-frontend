package dev.infyplus.halo.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.Path

/**
 * The reference's shared transition, applied to every animated property at once
 * (`transition: .3s cubic-bezier(.3,1.2,.5,1)`, line 235). The slight overshoot is what gives
 * expression changes their bounce.
 */
private val PoseEasing = CubicBezierEasing(0.3f, 1.2f, 0.5f, 1f)
private const val POSE_MS = 300
private const val SQUASH_MS = 180

/**
 * Draws the cat.
 *
 * Stateless about *what* it should feel — hand it an [Expression] and it interpolates from
 * whatever it was showing. See [Cat.kt] for why pose and motion are modelled separately.
 *
 * When [reducedMotion] is set, the looping animations never start and pose changes snap. Every
 * expression stays readable because each one changes eye and mouth *shape*, not just movement —
 * which is the reference's own reasoning (line 538) and the reason this degrades cleanly.
 */
@Composable
fun CatFace(
    expression: Expression,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
) {
    val target = poseFor(expression)
    val motion = motionFor(expression)

    // One driver for the whole pose, mirroring the CSS. Interrupting mid-flight is
    // position-continuous because `from` captures what is currently on screen, not the previous
    // target — otherwise a fast idle→work→idle would visibly jump backwards.
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

    // Every state change squashes on the way in (line 343).
    val squash = remember { Animatable(1f) }
    LaunchedEffect(expression, reducedMotion) {
        if (!reducedMotion) {
            squash.snapTo(0f)
            squash.animateTo(1f, tween(SQUASH_MS, easing = LinearEasing))
        }
    }

    val pose = lerp(from, target, morph.value).also { shown = it }

    // One infinite transition for every loop. Each `animateFloat` yields a normalised *phase*,
    // never a value — the shaping happens in CatCurves, so a single clock can drive several
    // channels at once (the hop moves the body on three axes and they must stay in step).
    val loops = rememberInfiniteTransition(label = "cat-loops")

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

    val breathePhase = phase(motion.breatheMs, "breathe")
    val blinkPhase = phase(motion.blinkMs, "blink")
    val driftPhase = phase(motion.driftMs, "drift")
    val lazyPhase = phase(motion.lazyMs, "lazy")
    val wobblePhase = phase(motion.wobbleMs, "wobble")
    val hopPhase = phase(motion.hopMs, "hop")
    val puffPhase = phase(motion.puffMs, "puff")
    val gleamPhase = phase(motion.gleamMs, "gleam")
    val twinklePhase = phase(motion.twinkleMs, "twinkle")

    // Shake is finite — three repeats then still (line 312), so it cannot ride the infinite clock.
    val shake = remember { Animatable(0f) }
    LaunchedEffect(expression, reducedMotion) {
        shake.snapTo(0f)
        val ms = motion.shakeMs
        val repeats = motion.shakeRepeats
        if (!reducedMotion && ms != null && repeats != null) {
            repeat(repeats) { shake.animateTo(1f, tween(ms, easing = LinearEasing)); shake.snapTo(0f) }
        }
    }

    val frame = remember(pose, breathePhase, blinkPhase, driftPhase, lazyPhase, wobblePhase,
        hopPhase, puffPhase, gleamPhase, twinklePhase, shake.value, squash.value, motion) {
        resolve(
            pose = pose,
            motion = motion,
            breathePhase = breathePhase,
            blinkPhase = blinkPhase,
            driftPhase = driftPhase,
            lazyPhase = lazyPhase,
            wobblePhase = wobblePhase,
            hopPhase = hopPhase,
            puffPhase = puffPhase,
            gleamPhase = gleamPhase,
            twinklePhase = twinklePhase,
            shakeProgress = shake.value,
            squashProgress = squash.value,
        )
    }

    Canvas(modifier) { drawCat(frame) }
}

/** A pose with every active loop's contribution already folded in — what actually gets drawn. */
private data class CatFrame(
    val pose: CatPose,
    val bodyTx: Float,
    val bodyTy: Float,
    val bodyScaleX: Float,
    val bodyScaleY: Float,
    val bodyRotation: Float,
    val eyeScaleY: Float,
    val pupilTx: Float,
    val glowAlpha: Float,
    val sparkleAlpha: Float,
    val sparkleScale: Float,
    val steamAlpha: Float,
    val steamTy: Float,
    val steamScale: Float,
)

private fun resolve(
    pose: CatPose,
    motion: CatMotion,
    breathePhase: Float,
    blinkPhase: Float,
    driftPhase: Float,
    lazyPhase: Float,
    wobblePhase: Float,
    hopPhase: Float,
    puffPhase: Float,
    gleamPhase: Float,
    twinklePhase: Float,
    shakeProgress: Float,
    squashProgress: Float,
): CatFrame {
    var tx = 0f
    var ty = pose.bodyTy
    var sx = 1f
    var sy = 1f
    var rotation = pose.bodyRotation

    if (motion.breatheMs != null) {
        val b = CatCurves.breathe(breathePhase)
        sx *= b.scale; sy *= b.scale; ty += b.ty
    }
    if (motion.hopMs != null) {
        val h = CatCurves.hop(hopPhase)
        ty += h.ty; sx *= h.sx; sy *= h.sy
    }
    if (motion.wobbleMs != null) rotation += CatCurves.wobbleRotation(wobblePhase)
    if (motion.shakeMs != null) tx += CatCurves.shakeTx(shakeProgress)

    // The squash rides on top of everything, so it reads on any expression.
    if (squashProgress < 1f) {
        val s = CatCurves.squash(squashProgress)
        sx *= s.sx; sy *= s.sy
    }

    val puff = if (motion.puffMs != null) CatCurves.puff(puffPhase) else null
    val twinkle = if (motion.twinkleMs != null) CatCurves.twinkle(twinklePhase) else null

    return CatFrame(
        pose = pose,
        bodyTx = tx,
        bodyTy = ty,
        bodyScaleX = sx,
        bodyScaleY = sy,
        bodyRotation = rotation,
        // The blink multiplies the pose's eye height rather than replacing it, so a waiting cat
        // blinks from its already-heavy lids instead of snapping wide open first.
        eyeScaleY = pose.eyeScaleY * if (motion.blinkMs != null) CatCurves.blinkScaleY(blinkPhase) else 1f,
        pupilTx = pose.pupilTx +
            (if (motion.driftMs != null) CatCurves.driftTx(driftPhase) else 0f) +
            (if (motion.lazyMs != null) CatCurves.lazyTx(lazyPhase) else 0f),
        glowAlpha = if (motion.gleamMs != null) CatCurves.gleamAlpha(gleamPhase) else pose.glowAlpha,
        sparkleAlpha = pose.sparkleAlpha * (twinkle?.alpha ?: 1f),
        sparkleScale = twinkle?.scale ?: 1f,
        steamAlpha = pose.steamAlpha * (puff?.alpha ?: 1f),
        steamTy = puff?.ty ?: 0f,
        steamScale = puff?.scale ?: 1f,
    )
}

private fun DrawScope.drawCat(frame: CatFrame) {
    val g = CatGeometry
    val pose = frame.pose

    // Meet-fit into the orb, exactly as SVG's default preserveAspectRatio would: the taller
    // dimension binds, and the viewBox centre lands on the canvas centre. Derived from the actual
    // layout size, not from ORB_DP, so the same drawing serves the 92dp orb and the 38dp mini
    // cat in the notification banner.
    val orbPx = size.minDimension * g.ORB_TO_CANVAS
    val s = orbPx / g.CAT_VIEW_H

    withTransform({
        translate(center.x - g.CAT_CENTER.x * s, center.y - g.CAT_CENTER.y * s)
        // pivot defaults to the canvas centre, which would undo the translate above.
        scale(s, s, pivot = Offset.Zero)
    }) {
        // The whole-character transform, about origin (50,92).
        withTransform({
            translate(frame.bodyTx, frame.bodyTy)
            translate(g.BODY_ORIGIN.x, g.BODY_ORIGIN.y)
            rotate(frame.bodyRotation, Offset.Zero)
            scale(frame.bodyScaleX, frame.bodyScaleY, pivot = Offset.Zero)
            translate(-g.BODY_ORIGIN.x, -g.BODY_ORIGIN.y)
        }) {
            drawGroundShadow()
            drawEars(pose)
            drawHead(pose)
            drawBrows(pose)
            drawBlush(pose)
            drawEyes(frame)
            drawMouth(pose)
            drawSparkles(frame)
            drawSteam(frame)
        }
    }
}

/**
 * Stands in for the CSS `drop-shadow`, which has no path-shaped equivalent in commonMain.
 *
 * Drawn inside the body group on purpose: it squashes and lifts with the hop, which reads better
 * than a fixed shadow the character detaches from mid-jump.
 */
private fun DrawScope.drawGroundShadow() {
    val g = CatGeometry
    withTransform({
        translate(g.SHADOW_CENTER.x, g.SHADOW_CENTER.y)
        scale(1f, g.SHADOW_RY / g.SHADOW_RX, pivot = Offset.Zero)
    }) {
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color.Black.copy(alpha = 0.30f),
                0.6f to Color.Black.copy(alpha = 0.16f),
                1f to Color.Transparent,
                center = Offset.Zero,
                radius = g.SHADOW_RX,
            ),
            radius = g.SHADOW_RX,
            center = Offset.Zero,
        )
    }
}

private fun DrawScope.drawEars(pose: CatPose) {
    val g = CatGeometry
    fun ear(origin: Offset, rotation: Float, outer: Path, inner: Path) {
        withTransform({
            // CSS reads right-to-left: `rotate(r) translateY(t)` translates *inside* the rotated
            // frame, so the drop follows wherever the ear is pointing. Applying the drop outside
            // the rotation instead folds a drooping ear into the head and it disappears.
            translate(origin.x, origin.y)
            rotate(rotation, Offset.Zero)
            scale(1f, pose.earScaleY, pivot = Offset.Zero)
            translate(-origin.x, -origin.y + pose.earTy)
        }) {
            drawPath(outer, pose.earOutColor)
            drawPath(outer, HaloPalette.ink, style = Stroke(g.LINE, join = StrokeJoin.Round))
            drawPath(inner, pose.earInColor)
        }
    }
    ear(g.EAR_ORIGIN_L, pose.earRotationL, g.earOuterL, g.earInnerL)
    ear(g.EAR_ORIGIN_R, pose.earRotationR, g.earOuterR, g.earInnerR)
}

private fun DrawScope.drawHead(pose: CatPose) {
    val g = CatGeometry
    drawCircle(pose.bodyColor, g.HEAD_RADIUS, g.HEAD_CENTER)
    drawCircle(HaloPalette.ink, g.HEAD_RADIUS, g.HEAD_CENTER, style = Stroke(g.LINE))
}

private fun DrawScope.drawBrows(pose: CatPose) {
    if (pose.browAlpha <= 0f) return
    val g = CatGeometry
    fun brow(origin: Offset, rotation: Float, a: Offset, b: Offset) {
        withTransform({
            translate(origin.x, origin.y)
            rotate(rotation, Offset.Zero)
            translate(-origin.x, -origin.y + pose.browTy)
        }) {
            drawLine(HaloPalette.ink, a, b, g.LINE, StrokeCap.Round, alpha = pose.browAlpha)
        }
    }
    // Antisymmetric: one field drives both, negated for the right.
    brow(g.BROW_ORIGIN_L, pose.browRotation, g.browLStart, g.browLEnd)
    brow(g.BROW_ORIGIN_R, -pose.browRotation, g.browRStart, g.browREnd)
}

private fun DrawScope.drawBlush(pose: CatPose) {
    if (pose.blushAlpha <= 0f) return
    val g = CatGeometry
    drawCircle(HaloPalette.blush, g.BLUSH_RADIUS, g.BLUSH_L, alpha = pose.blushAlpha)
    drawCircle(HaloPalette.blush, g.BLUSH_RADIUS, g.BLUSH_R, alpha = pose.blushAlpha)
}

private fun DrawScope.drawEyes(frame: CatFrame) {
    val g = CatGeometry
    val pose = frame.pose

    fun eye(origin: Offset, lidArc: Path, deadX: Path, pupilAlpha: Float, lidAlpha: Float) {
        withTransform({
            translate(0f, pose.eyeTy)
            translate(origin.x, origin.y)
            scale(1f, frame.eyeScaleY.coerceAtLeast(0.001f), pivot = Offset.Zero)
            translate(-origin.x, -origin.y)
        }) {
            if (pose.eyesOpenAlpha > 0f) {
                withTransform({ translate(frame.pupilTx, pose.pupilTy) }) {
                    // A soft pool of light *behind* the dot. The dot itself stays a flat shape,
                    // so the abstraction survives. CSS blurs this; BlurEffect is API 31+ and
                    // minSdk is 24, so it is a radial gradient instead — same look, cheaper.
                    val glow = frame.glowAlpha * pose.eyesOpenAlpha * pupilAlpha
                    if (glow > 0f) {
                        withTransform({
                            translate(origin.x, origin.y)
                            scale(1f, g.GLOW_RY / g.GLOW_RX, pivot = Offset.Zero)
                        }) {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    0f to HaloPalette.pale.copy(alpha = glow),
                                    0.55f to HaloPalette.pale.copy(alpha = glow * 0.75f),
                                    1f to Color.Transparent,
                                    center = Offset.Zero,
                                    radius = g.GLOW_RX,
                                ),
                                radius = g.GLOW_RX,
                                center = Offset.Zero,
                            )
                        }
                    }
                    drawOval(
                        color = HaloPalette.ink,
                        topLeft = Offset(origin.x - g.PUPIL_RX, origin.y - g.PUPIL_RY),
                        size = Size(g.PUPIL_RX * 2, g.PUPIL_RY * 2),
                        alpha = pose.eyesOpenAlpha * pupilAlpha,
                    )
                }
            }
        }
        if (lidAlpha > 0f) {
            drawPath(
                lidArc,
                HaloPalette.ink,
                alpha = lidAlpha,
                style = Stroke(g.LINE, cap = StrokeCap.Round),
            )
        }
        if (pose.deadXAlpha > 0f) {
            drawPath(
                deadX,
                HaloPalette.ink,
                alpha = pose.deadXAlpha,
                style = Stroke(g.LINE, cap = StrokeCap.Round),
            )
        }
    }

    eye(g.EYE_L, g.lidArcL, g.deadXL, pose.pupilAlphaL, pose.lidArcAlphaL)
    eye(g.EYE_R, g.lidArcR, g.deadXR, 1f, pose.lidArcAlphaR)
}

private fun DrawScope.drawMouth(pose: CatPose) {
    val g = CatGeometry
    val m = pose.mouth

    val morphing = Path().apply {
        moveTo(m.start.x, m.start.y)
        quadraticTo(m.control.x, m.control.y, m.end.x, m.end.y)
    }
    val alpha = 1f - pose.mouthLensAlpha
    if (alpha > 0f) {
        // An open mouth is filled, not outlined (line 229) — so the fill fades in with the morph.
        if (m.fill > 0f) {
            drawPath(Path().apply { addPath(morphing); close() }, HaloPalette.ink, alpha = alpha * m.fill)
        }
        drawPath(morphing, HaloPalette.ink, alpha = alpha, style = Stroke(g.LINE, cap = StrokeCap.Round))
    }
    if (pose.mouthLensAlpha > 0f) {
        drawPath(
            g.mouthLens,
            HaloPalette.ink,
            alpha = pose.mouthLensAlpha,
            style = Stroke(g.LINE, cap = StrokeCap.Round),
        )
    }
}

private fun DrawScope.drawSparkles(frame: CatFrame) {
    if (frame.sparkleAlpha <= 0f) return
    val g = CatGeometry
    // SVG's default transform-box puts the scale origin at the viewBox centre, not each star's
    // own centroid — so they drift slightly outward as they twinkle, which is what Chrome shows.
    withTransform({
        translate(g.CAT_CENTER.x, g.CAT_CENTER.y)
        scale(frame.sparkleScale, frame.sparkleScale, pivot = Offset.Zero)
        translate(-g.CAT_CENTER.x, -g.CAT_CENTER.y)
    }) {
        g.sparkles.forEach { drawPath(it, HaloPalette.sun, alpha = frame.sparkleAlpha) }
    }
}

private fun DrawScope.drawSteam(frame: CatFrame) {
    if (frame.steamAlpha <= 0f) return
    val g = CatGeometry
    withTransform({ translate(0f, frame.steamTy) }) {
        g.steam.forEach {
            drawPath(
                it,
                HaloPalette.pale,
                alpha = frame.steamAlpha,
                style = Stroke(2.4f * frame.steamScale, cap = StrokeCap.Round),
            )
        }
    }
}
