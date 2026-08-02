package dev.infyplus.halo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp as lerpColor

/**
 * Halo's colours.
 *
 * Kept out of [HaloPalette], whose stated rules are "no black, one line weight, flat fills". This
 * character breaks all three on purpose: it is a soft gradient volume with no contour at all.
 */
object BuddyPalette {
    /** Head gradient, top to chin. */
    val top = Color(0xFFB7C6FB)
    val bottom = Color(0xFF7E93F2)

    /** Eyes. Near-black, faintly cool — never pure black, same instinct as [HaloPalette.ink]. */
    val ink = Color(0xFF1D1D26)

    /** What shows below a heavy lid. */
    val sclera = Color(0xFFF4F6FF)

    /** The glint that rides on the eye when idle. */
    val gleam = Color(0xFFFFFFFF)

    val tear = Color(0xFF7FD7E8)
    val gold = Color(0xFFFBC63C)
    val puff = Color(0xFFC9CBD6)

    /** Dead: the lights are out. */
    val greyTop = Color(0xFFB9BAC2)
    val greyBottom = Color(0xFF94959E)
}

/**
 * Draws Halo — the default face.
 *
 * Same contract as [CatFace]: hand it an [Expression] and it interpolates from whatever it is
 * showing, scaling off its own layout size so the 92dp orb and the 46dp banner face are one
 * drawing. The pose maths lives in [FacePose]; the timing is shared with the cat through
 * [rememberMorph] / [rememberPhases].
 *
 * With [reducedMotion] the loops never start and pose changes snap. Every expression stays
 * readable because each one changes eye *shape*, not just movement.
 */
@Composable
fun HaloBuddyFace(
    expression: Expression,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
) {
    val motion = motionFor(expression)
    val pose = rememberMorph(facePoseFor(expression), expression, reducedMotion, ::lerp)
    val phases = rememberPhases(motion, expression, reducedMotion)

    val frame = remember(pose, phases, motion) { resolve(pose, motion, phases) }

    Canvas(modifier) { drawBuddy(frame) }
}

/** A pose with every active loop's contribution already folded in — what actually gets drawn. */
private data class BuddyFrame(
    val pose: FacePose,
    val body: BodyMotion,
    val eyeScaleY: Float,
    val eyeTx: Float,
    val glintAlpha: Float,
    val sparkleAlpha: Float,
    val sparkleScale: Float,
    val steamAlpha: Float,
    val steamTy: Float,
    val steamScale: Float,
    val speedAlpha: Float,
)

private fun resolve(pose: FacePose, motion: CatMotion, phases: Phases): BuddyFrame {
    val body = phases.body(motion, pose.bodyTy, pose.bodyRotation)
    val puff = if (motion.puffMs != null) CatCurves.puff(phases.puff) else null
    val twinkle = if (motion.twinkleMs != null) CatCurves.twinkle(phases.twinkle) else null

    return BuddyFrame(
        pose = pose,
        body = body,
        // The blink multiplies the pose's eye height rather than replacing it, so a heavy-lidded
        // wait blinks from where it already is instead of snapping wide open first.
        eyeScaleY = pose.eyeScaleY * if (motion.blinkMs != null) CatCurves.blinkScaleY(phases.blink) else 1f,
        eyeTx = pose.pupilTx +
            (if (motion.driftMs != null) CatCurves.driftTx(phases.drift) else 0f) +
            (if (motion.lazyMs != null) CatCurves.lazyTx(phases.lazy) else 0f),
        // The gleam only ever modulates a glint the pose asked for; it never conjures one.
        glintAlpha = pose.glintAlpha *
            (if (motion.gleamMs != null) CatCurves.gleamAlpha(phases.gleam) / 0.72f else 1f),
        sparkleAlpha = pose.sparkleAlpha * (twinkle?.alpha ?: 1f),
        sparkleScale = twinkle?.scale ?: 1f,
        steamAlpha = pose.steamAlpha * (puff?.alpha ?: 1f),
        steamTy = puff?.ty ?: 0f,
        steamScale = puff?.scale ?: 1f,
        // Only at the top of a jump — speed lines under a character standing still read as rain.
        speedAlpha = pose.speedAlpha * ((-body.ty - 3f) / 5f).coerceIn(0f, 1f),
    )
}

private fun DrawScope.drawBuddy(frame: BuddyFrame) {
    val g = HaloBuddyGeometry

    // Meet-fit into the orb, exactly as SVG's default preserveAspectRatio would — see CatFace.
    val orbPx = size.minDimension * CatGeometry.ORB_TO_CANVAS
    val s = orbPx / g.VIEW_H

    withTransform({
        translate(center.x - g.CENTER.x * s, center.y - g.CENTER.y * s)
        // pivot defaults to the canvas centre, which would undo the translate above.
        scale(s, s, pivot = Offset.Zero)
    }) {
        drawGroundShadow()
        // Outside the body group: the ground the character left behind must not jump with it.
        drawSpeedLines(frame)

        withTransform({
            translate(frame.body.tx, frame.body.ty)
            translate(g.BODY_ORIGIN.x, g.BODY_ORIGIN.y)
            rotate(frame.body.rotation, Offset.Zero)
            scale(frame.body.sx, frame.body.sy, pivot = Offset.Zero)
            translate(-g.BODY_ORIGIN.x, -g.BODY_ORIGIN.y)
        }) {
            drawEars(frame.pose)
            drawHead(frame.pose)
            drawEyes(frame)
            drawTears(frame.pose)
            drawSparkles(frame)
            drawClouds(frame)
        }
    }
}

/** Stands in for the CSS `drop-shadow`, which has no path-shaped equivalent in commonMain. */
private fun DrawScope.drawGroundShadow() {
    val g = HaloBuddyGeometry
    withTransform({
        translate(g.SHADOW_CENTER.x, g.SHADOW_CENTER.y)
        scale(1f, g.SHADOW_RY / g.SHADOW_RX, pivot = Offset.Zero)
    }) {
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color.Black.copy(alpha = 0.20f),
                0.6f to Color.Black.copy(alpha = 0.10f),
                1f to Color.Transparent,
                center = Offset.Zero,
                radius = g.SHADOW_RX,
            ),
            radius = g.SHADOW_RX,
            center = Offset.Zero,
        )
    }
}

/** The head's own gradient, in user units, so it does not slide when the body scales. */
private fun headBrush(pose: FacePose) = Brush.verticalGradient(
    colors = listOf(pose.topColor, pose.bottomColor),
    startY = HaloBuddyGeometry.HEAD.top,
    endY = HaloBuddyGeometry.HEAD.bottom,
)

private fun DrawScope.drawEars(pose: FacePose) {
    val g = HaloBuddyGeometry
    // A step deeper than the head so they read as behind it — there is no outline to separate them.
    val brush = Brush.verticalGradient(
        colors = listOf(
            lerpColor(pose.topColor, Color.Black, 0.10f),
            lerpColor(pose.bottomColor, Color.Black, 0.06f),
        ),
        startY = 4f,
        endY = 44f,
    )

    fun ear(origin: Offset, rotation: Float, path: Path) {
        withTransform({
            // CSS reads right-to-left: `rotate(r) translateY(t)` translates *inside* the rotated
            // frame, so the drop follows wherever the ear is pointing.
            translate(origin.x, origin.y)
            rotate(rotation, Offset.Zero)
            scale(1f, pose.earScaleY, pivot = Offset.Zero)
            translate(-origin.x, -origin.y + pose.earTy)
        }) {
            drawPath(path, brush)
        }
    }
    ear(g.EAR_ORIGIN_L, pose.earRotationL, g.earL)
    ear(g.EAR_ORIGIN_R, pose.earRotationR, g.earR)
}

private fun DrawScope.drawHead(pose: FacePose) {
    drawPath(HaloBuddyGeometry.head, headBrush(pose))
}

/**
 * One eye.
 *
 * The open eye is a pale capsule with the dark filled in from the top down to [FacePose.lidCover]
 * — at 1 that is the plain dark capsule of every ordinary expression, and anything less is a lid.
 * The arc, the cross and the determined bar are alternatives to it, not overlays, which is why
 * each expression zeroes `eyeAlpha` when it uses one.
 */
private fun DrawScope.drawEyes(frame: BuddyFrame) {
    val g = HaloBuddyGeometry
    val pose = frame.pose

    fun eye(
        centre: Offset,
        capsule: Path,
        arc: Path,
        cross: Path,
        alpha: Float,
        arcAlpha: Float,
        /** +1 on the left, -1 on the right: the determined bar is antisymmetric. */
        sign: Float,
    ) {
        withTransform({
            translate(frame.eyeTx, pose.eyeTy + pose.pupilTy)
            translate(centre.x, centre.y)
            scale(1f, frame.eyeScaleY.coerceAtLeast(0.001f), pivot = Offset.Zero)
            translate(-centre.x, -centre.y)
        }) {
            if (alpha > 0f) {
                drawPath(capsule, BuddyPalette.sclera, alpha = alpha)
                clipPath(capsule) {
                    drawRect(
                        color = BuddyPalette.ink,
                        topLeft = Offset(centre.x - g.EYE_RX, centre.y - g.EYE_RY),
                        size = Size(g.EYE_RX * 2, g.EYE_RY * 2 * pose.lidCover),
                        alpha = alpha,
                    )
                }
                if (frame.glintAlpha > 0f) {
                    drawOval(
                        color = BuddyPalette.gleam,
                        topLeft = Offset(centre.x - 4.6f, centre.y - 8f),
                        size = Size(4.2f, 5.4f),
                        alpha = frame.glintAlpha * alpha * pose.lidCover,
                    )
                }
            }
            if (pose.angryAlpha > 0f) {
                // A bar through the eye centre; the rotation is what makes it Work or Cross.
                withTransform({
                    translate(centre.x, centre.y)
                    rotate(pose.angryRotation * sign, Offset.Zero)
                    translate(-centre.x, -centre.y)
                }) {
                    drawLine(
                        color = BuddyPalette.ink,
                        start = Offset(centre.x - g.BAR_HALF, centre.y),
                        end = Offset(centre.x + g.BAR_HALF, centre.y),
                        strokeWidth = g.BAR_W,
                        cap = StrokeCap.Round,
                        alpha = pose.angryAlpha,
                    )
                }
            }
        }
        if (arcAlpha > 0f) {
            drawPath(arc, BuddyPalette.ink, alpha = arcAlpha, style = Stroke(g.ARC_W, cap = StrokeCap.Round))
        }
        if (pose.deadXAlpha > 0f) {
            drawPath(cross, BuddyPalette.ink, alpha = pose.deadXAlpha, style = Stroke(g.CROSS_W, cap = StrokeCap.Round))
        }
    }

    eye(g.EYE_L, g.eyeL, g.arcL, g.deadXL, pose.eyeAlpha * pose.eyeAlphaL, pose.arcAlphaL, 1f)
    eye(g.EYE_R, g.eyeR, g.arcR, g.deadXR, pose.eyeAlpha, pose.arcAlphaR, -1f)
}

private fun DrawScope.drawTears(pose: FacePose) {
    if (pose.tearAlpha <= 0f) return
    val g = HaloBuddyGeometry
    drawPath(g.tearL, BuddyPalette.tear, alpha = pose.tearAlpha)
    drawPath(g.tearR, BuddyPalette.tear, alpha = pose.tearAlpha)
}

private fun DrawScope.drawSparkles(frame: BuddyFrame) {
    if (frame.sparkleAlpha <= 0f) return
    val g = HaloBuddyGeometry
    // Shared with the cat: three four-pointed stars in the same places, in this character's gold —
    // drawn half again as large, since there is no outline anywhere else to hold the eye.
    withTransform({
        translate(g.CENTER.x, g.CENTER.y)
        scale(frame.sparkleScale * 1.5f, frame.sparkleScale * 1.5f, pivot = Offset.Zero)
        translate(-g.CENTER.x, -g.CENTER.y)
    }) {
        CatGeometry.sparkles.forEach { drawPath(it, BuddyPalette.gold, alpha = frame.sparkleAlpha) }
    }
}

private fun DrawScope.drawClouds(frame: BuddyFrame) {
    if (frame.steamAlpha <= 0f) return
    val g = HaloBuddyGeometry
    withTransform({ translate(0f, frame.steamTy) }) {
        (g.cloudL + g.cloudR).forEach { (centre, radius) ->
            drawCircle(
                color = BuddyPalette.puff,
                radius = radius * frame.steamScale,
                center = centre,
                alpha = frame.steamAlpha,
            )
        }
    }
}

private fun DrawScope.drawSpeedLines(frame: BuddyFrame) {
    if (frame.speedAlpha <= 0f) return
    val g = HaloBuddyGeometry
    g.speedLines.forEach { (start, end) ->
        drawLine(
            color = BuddyPalette.bottom,
            start = start,
            end = end,
            strokeWidth = g.SPEED_W,
            cap = StrokeCap.Round,
            alpha = frame.speedAlpha * 0.7f,
        )
    }
}
