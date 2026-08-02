package dev.infyplus.halo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
    val motion = motionFor(expression)
    val pose = rememberMorph(poseFor(expression), expression, reducedMotion, ::lerp)
    val phases = rememberPhases(motion, expression, reducedMotion)

    val frame = remember(pose, phases, motion) { resolve(pose, motion, phases) }

    Canvas(modifier) { drawCat(frame) }
}

/** A pose with every active loop's contribution already folded in — what actually gets drawn. */
private data class CatFrame(
    val pose: CatPose,
    val body: BodyMotion,
    val eyeScaleY: Float,
    val pupilTx: Float,
    val glowAlpha: Float,
    val sparkleAlpha: Float,
    val sparkleScale: Float,
    val steamAlpha: Float,
    val steamTy: Float,
    val steamScale: Float,
)

private fun resolve(pose: CatPose, motion: CatMotion, phases: Phases): CatFrame {
    val puff = if (motion.puffMs != null) CatCurves.puff(phases.puff) else null
    val twinkle = if (motion.twinkleMs != null) CatCurves.twinkle(phases.twinkle) else null

    return CatFrame(
        pose = pose,
        body = phases.body(motion, pose.bodyTy, pose.bodyRotation),
        // The blink multiplies the pose's eye height rather than replacing it, so a waiting cat
        // blinks from its already-heavy lids instead of snapping wide open first.
        eyeScaleY = pose.eyeScaleY * if (motion.blinkMs != null) CatCurves.blinkScaleY(phases.blink) else 1f,
        pupilTx = pose.pupilTx +
            (if (motion.driftMs != null) CatCurves.driftTx(phases.drift) else 0f) +
            (if (motion.lazyMs != null) CatCurves.lazyTx(phases.lazy) else 0f),
        glowAlpha = if (motion.gleamMs != null) CatCurves.gleamAlpha(phases.gleam) else pose.glowAlpha,
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
            translate(frame.body.tx, frame.body.ty)
            translate(g.BODY_ORIGIN.x, g.BODY_ORIGIN.y)
            rotate(frame.body.rotation, Offset.Zero)
            scale(frame.body.sx, frame.body.sy, pivot = Offset.Zero)
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
    drawCircle(CatPalette.blush, g.BLUSH_RADIUS, g.BLUSH_L, alpha = pose.blushAlpha)
    drawCircle(CatPalette.blush, g.BLUSH_RADIUS, g.BLUSH_R, alpha = pose.blushAlpha)
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
