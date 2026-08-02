package dev.infyplus.halo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform

/**
 * Waterloo — a black disc with two white eyes, and nothing else.
 *
 * The whole character is the eyes, so it reuses [FacePose] and `motionFor` unchanged: same eight
 * expressions, same timing, same lid-and-arc vocabulary as [HaloBuddyFace]. It ignores the fields
 * it has no anatomy for (ears, gradient stops, the tear's colour) and inverts the one that matters
 * — here the *eye* is the pale shape and the lid is the face itself closing over it.
 *
 * Deliberately simplified from the reference sheet: the sad eyes are lidded rather than getting
 * their own slanted wedge, and the wink borrows the happy arc. Both differences vanish at 92dp.
 */
object WaterlooPalette {
    /**
     * The face: a cool charcoal, not the reference's black.
     *
     * Black on the cream ground reads as a hole punched in the panel. Lifting it a few steps and
     * cooling it keeps the character solid while letting the disc's edge be seen, and sets it
     * apart from [HaloPalette.ink], which is warm and belongs to the chrome around it.
     */
    val body = Color(0xFF323644)

    val eye = Color(0xFFFFFFFF)
}

/**
 * Waterloo's shapes, in the same SVG user units as the other faces — 112x116 about (50,46) — so it
 * drops into the orb, the banner and the dial with nothing to configure.
 */
object WaterlooGeometry {

    const val VIEW_H = 116f
    val CENTER = Offset(50f, 46f)
    val BODY_ORIGIN = Offset(50f, 92f)

    val DISC_CENTER = Offset(50f, 52f)
    const val DISC_RADIUS = 40f

    val EYE_L = Offset(39f, 50f)
    val EYE_R = Offset(61f, 50f)
    const val EYE_RX = 8.5f
    const val EYE_RY = 9.5f

    /** The iris, in the face's own colour, riding inside the white of the eye. */
    const val IRIS_R = 4.2f

    private fun eye(c: Offset) = Path().apply {
        addRoundRect(
            RoundRect(
                Rect(c.x - EYE_RX, c.y - EYE_RY, c.x + EYE_RX, c.y + EYE_RY),
                CornerRadius(EYE_RX, EYE_RX),
            ),
        )
    }

    val eyeL = eye(EYE_L)
    val eyeR = eye(EYE_R)

    /** The happy (and winking) arc, one per eye. */
    const val ARC_W = 5f
    val arcL = Path().apply { moveTo(32f, 54f); quadraticTo(39f, 42f, 46f, 54f) }
    val arcR = Path().apply { moveTo(54f, 54f); quadraticTo(61f, 42f, 68f, 54f) }

    /** Dead eyes: two crossed strokes each. */
    const val CROSS_W = 5f
    val deadXL = Path().apply {
        moveTo(32f, 43f); lineTo(46f, 57f)
        moveTo(46f, 43f); lineTo(32f, 57f)
    }
    val deadXR = Path().apply {
        moveTo(54f, 43f); lineTo(68f, 57f)
        moveTo(68f, 43f); lineTo(54f, 57f)
    }

    /**
     * The determined eye: a brow and, below it, a teardrop.
     *
     * Two marks with clear air between them, not one fused shape — that gap is what the scowl is
     * made of. Both are drawn level and let [FacePose.angryRotation] do the slanting, so Work and
     * Cross are the same drawing at two angles rather than two hand-placed shapes.
     */
    const val BROW_W = 4.6f
    val browL = Path().apply { moveTo(30f, 44f); lineTo(44f, 44f) }
    val browR = Path().apply { moveTo(70f, 44f); lineTo(56f, 44f) }

    /** The drop: a point at the top over a round belly. */
    private fun drop(x: Float) = Path().apply {
        moveTo(x, 51f)
        quadraticTo(x - 4.6f, 56f, x - 4.6f, 59.5f)
        quadraticTo(x - 4.6f, 64f, x, 64f)
        quadraticTo(x + 4.6f, 64f, x + 4.6f, 59.5f)
        quadraticTo(x + 4.6f, 56f, x, 51f)
        close()
    }

    // Under the *inner* half of the brow, not the middle of the eye — that is where the reference
    // hangs it, and it is what keeps the two marks reading as one scowl rather than two.
    val dropL = drop(40.5f)
    val dropR = drop(59.5f)

    /** A tear below each eye, in the same white as the eyes — there is no second colour here. */
    val tearL = Path().apply {
        moveTo(44f, 60f)
        quadraticTo(40.5f, 65f, 44f, 67f)
        quadraticTo(47.5f, 65f, 44f, 60f)
        close()
    }
    val tearR = Path().apply {
        moveTo(56f, 60f)
        quadraticTo(52.5f, 65f, 56f, 67f)
        quadraticTo(59.5f, 65f, 56f, 60f)
        close()
    }

    /** The huff: solid puffs, drawn clear of the disc in the face's own colour. */
    val clouds: List<Pair<Offset, Float>> = listOf(
        Offset(6f, 16f) to 7f,
        Offset(14f, 10f) to 5f,
        Offset(94f, 16f) to 7f,
        Offset(86f, 10f) to 5f,
    )

    /** Speed lines under a hop, drawn outside the body group so they stay on the ground. */
    val speedLines: List<Pair<Offset, Offset>> = listOf(
        Offset(34f, 96f) to Offset(33f, 106f),
        Offset(42f, 98f) to Offset(41f, 109f),
        Offset(58f, 98f) to Offset(59f, 109f),
        Offset(66f, 96f) to Offset(67f, 106f),
    )
    const val SPEED_W = 1.8f
}

@Composable
fun WaterlooFace(
    expression: Expression,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
) {
    val motion = motionFor(expression)
    val pose = rememberMorph(facePoseFor(expression), expression, reducedMotion, ::lerp)
    val phases = rememberPhases(motion, expression, reducedMotion)

    val frame = remember(pose, phases, motion) { resolve(pose, motion, phases) }

    Canvas(modifier) { drawWaterloo(frame) }
}

/** A pose with every active loop's contribution already folded in — what actually gets drawn. */
private data class WaterlooFrame(
    val pose: FacePose,
    val body: BodyMotion,
    val eyeScaleY: Float,
    val eyeTx: Float,
    val sparkleAlpha: Float,
    val sparkleScale: Float,
    val steamAlpha: Float,
    val steamTy: Float,
    val steamScale: Float,
    val speedAlpha: Float,
)

private fun resolve(pose: FacePose, motion: CatMotion, phases: Phases): WaterlooFrame {
    val body = phases.body(motion, pose.bodyTy, pose.bodyRotation)
    val puff = if (motion.puffMs != null) CatCurves.puff(phases.puff) else null
    val twinkle = if (motion.twinkleMs != null) CatCurves.twinkle(phases.twinkle) else null

    return WaterlooFrame(
        pose = pose,
        body = body,
        eyeScaleY = pose.eyeScaleY * if (motion.blinkMs != null) CatCurves.blinkScaleY(phases.blink) else 1f,
        eyeTx = pose.pupilTx +
            (if (motion.driftMs != null) CatCurves.driftTx(phases.drift) else 0f) +
            (if (motion.lazyMs != null) CatCurves.lazyTx(phases.lazy) else 0f),
        sparkleAlpha = pose.sparkleAlpha * (twinkle?.alpha ?: 1f),
        sparkleScale = twinkle?.scale ?: 1f,
        steamAlpha = pose.steamAlpha * (puff?.alpha ?: 1f),
        steamTy = puff?.ty ?: 0f,
        steamScale = puff?.scale ?: 1f,
        // Only at the top of a jump — speed lines under a character standing still read as rain.
        speedAlpha = pose.speedAlpha * ((-body.ty - 3f) / 5f).coerceIn(0f, 1f),
    )
}

private fun DrawScope.drawWaterloo(frame: WaterlooFrame) {
    val g = WaterlooGeometry

    // Meet-fit into the orb, exactly as SVG's default preserveAspectRatio would — see CatFace.
    val orbPx = size.minDimension * CatGeometry.ORB_TO_CANVAS
    val s = orbPx / g.VIEW_H

    withTransform({
        translate(center.x - g.CENTER.x * s, center.y - g.CENTER.y * s)
        scale(s, s, pivot = Offset.Zero)
    }) {
        // Outside the body group: the ground the character left behind must not jump with it.
        drawSpeedLines(frame)

        withTransform({
            translate(frame.body.tx, frame.body.ty)
            translate(g.BODY_ORIGIN.x, g.BODY_ORIGIN.y)
            rotate(frame.body.rotation, Offset.Zero)
            scale(frame.body.sx, frame.body.sy, pivot = Offset.Zero)
            translate(-g.BODY_ORIGIN.x, -g.BODY_ORIGIN.y)
        }) {
            drawCircle(WaterlooPalette.body, g.DISC_RADIUS, g.DISC_CENTER)
            drawEyes(frame)
            drawTears(frame.pose)
            drawSparkles(frame)
            drawClouds(frame)
        }
    }
}

/**
 * One eye — the inverse of the buddy's.
 *
 * There the eye is the dark shape and a pale sclera shows beneath it; here the eye is the pale
 * shape and the lid is the face's own colour closing over the top, so [FacePose.lidCover] reads as
 * "how much of the eye is still showing".
 */
private fun DrawScope.drawEyes(frame: WaterlooFrame) {
    val g = WaterlooGeometry
    val pose = frame.pose

    fun eye(
        centre: Offset,
        shape: Path,
        arc: Path,
        cross: Path,
        brow: Path,
        drop: Path,
        alpha: Float,
        arcAlpha: Float,
        sign: Float,
    ) {
        withTransform({
            translate(0f, pose.eyeTy)
            translate(centre.x, centre.y)
            scale(1f, frame.eyeScaleY.coerceAtLeast(0.001f), pivot = Offset.Zero)
            translate(-centre.x, -centre.y)
        }) {
            if (alpha > 0f) {
                drawPath(shape, WaterlooPalette.eye, alpha = alpha)
                clipPath(shape) {
                    // The iris moves, the eye does not — a glance is the dark dot crossing the
                    // white, and clipping it to the eye is what gives the crescent at the edges.
                    drawCircle(
                        color = WaterlooPalette.body,
                        radius = g.IRIS_R,
                        center = Offset(centre.x + frame.eyeTx, centre.y + pose.pupilTy),
                        alpha = alpha,
                    )
                    if (pose.lidCover < 1f) {
                        drawRect(
                            color = WaterlooPalette.body,
                            topLeft = Offset(centre.x - g.EYE_RX, centre.y - g.EYE_RY),
                            size = Size(g.EYE_RX * 2, g.EYE_RY * 2 * (1f - pose.lidCover)),
                            alpha = alpha,
                        )
                    }
                }
            }
            if (pose.angryAlpha > 0f) {
                withTransform({
                    translate(centre.x, centre.y)
                    // Steeper than the pose asks for: this brow is a thin bar where Halo's is a
                    // fat one, and a thin bar needs more angle to read as cross at 92dp.
                    rotate(pose.angryRotation * 1.3f * sign, Offset.Zero)
                    translate(-centre.x, -centre.y)
                }) {
                    drawPath(
                        brow,
                        WaterlooPalette.eye,
                        alpha = pose.angryAlpha,
                        style = Stroke(g.BROW_W, cap = StrokeCap.Round),
                    )
                    drawPath(drop, WaterlooPalette.eye, alpha = pose.angryAlpha)
                }
            }
        }
        if (arcAlpha > 0f) {
            drawPath(arc, WaterlooPalette.eye, alpha = arcAlpha, style = Stroke(g.ARC_W, cap = StrokeCap.Round))
        }
        if (pose.deadXAlpha > 0f) {
            drawPath(cross, WaterlooPalette.eye, alpha = pose.deadXAlpha, style = Stroke(g.CROSS_W, cap = StrokeCap.Round))
        }
    }

    eye(g.EYE_L, g.eyeL, g.arcL, g.deadXL, g.browL, g.dropL, pose.eyeAlpha * pose.eyeAlphaL, pose.arcAlphaL, 1f)
    eye(g.EYE_R, g.eyeR, g.arcR, g.deadXR, g.browR, g.dropR, pose.eyeAlpha, pose.arcAlphaR, -1f)
}

private fun DrawScope.drawTears(pose: FacePose) {
    if (pose.tearAlpha <= 0f) return
    val g = WaterlooGeometry
    drawPath(g.tearL, WaterlooPalette.eye, alpha = pose.tearAlpha)
    drawPath(g.tearR, WaterlooPalette.eye, alpha = pose.tearAlpha)
}

private fun DrawScope.drawSparkles(frame: WaterlooFrame) {
    if (frame.sparkleAlpha <= 0f) return
    val g = WaterlooGeometry
    // The cat's three stars, in this character's only other colour: itself.
    withTransform({
        translate(g.CENTER.x, g.CENTER.y)
        scale(frame.sparkleScale * 1.4f, frame.sparkleScale * 1.4f, pivot = Offset.Zero)
        translate(-g.CENTER.x, -g.CENTER.y)
    }) {
        CatGeometry.sparkles.forEach { drawPath(it, WaterlooPalette.body, alpha = frame.sparkleAlpha) }
    }
}

private fun DrawScope.drawClouds(frame: WaterlooFrame) {
    if (frame.steamAlpha <= 0f) return
    val g = WaterlooGeometry
    withTransform({ translate(0f, frame.steamTy) }) {
        g.clouds.forEach { (centre, radius) ->
            drawCircle(
                color = WaterlooPalette.body,
                radius = radius * frame.steamScale,
                center = centre,
                alpha = frame.steamAlpha,
            )
        }
    }
}

private fun DrawScope.drawSpeedLines(frame: WaterlooFrame) {
    if (frame.speedAlpha <= 0f) return
    val g = WaterlooGeometry
    g.speedLines.forEach { (start, end) ->
        drawLine(
            color = WaterlooPalette.body,
            start = start,
            end = end,
            strokeWidth = g.SPEED_W,
            cap = StrokeCap.Round,
            alpha = frame.speedAlpha * 0.7f,
        )
    }
}
