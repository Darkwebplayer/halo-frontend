package dev.infyplus.halo.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path

/**
 * Halo's own shapes, in SVG user units.
 *
 * The viewBox is the cat's — 112x116 with the character centred on (50,46) and pivoting about
 * (50,92) — so both faces drop into the same orb, the same 46dp banner, and the same dial without
 * anyone having to know which one is showing. The numbers are restated here rather than imported
 * from [CatGeometry]: they are this drawing's own frame, and a shared constant would imply the two
 * characters have to keep agreeing forever.
 *
 * Paths are built once and reused — allocating a `Path` inside a draw pass would allocate on every
 * frame of a 60fps animation, which is exactly what makes an always-on overlay expensive.
 */
object HaloBuddyGeometry {

    const val VIEW_H = 116f
    val CENTER = Offset(50f, 46f)
    val BODY_ORIGIN = Offset(50f, 92f)

    /** The head: a dome. Fully round across the top, softly squared off at the chin. */
    val HEAD = Rect(12f, 30f, 88f, 92f)
    val head = Path().apply {
        addRoundRect(
            RoundRect(
                HEAD,
                topLeft = CornerRadius(38f, 36f),
                topRight = CornerRadius(38f, 36f),
                bottomRight = CornerRadius(24f, 24f),
                bottomLeft = CornerRadius(24f, 24f),
            ),
        )
    }

    /**
     * Ears, drawn as leaves with a rounded tip and no outline.
     *
     * The rotation origins sit at the base *inside* the head, so a droop swings the tip out and
     * down while the join stays buried — an origin at the tip would tear the ear off the skull.
     */
    val EAR_ORIGIN_L = Offset(29f, 40f)
    val EAR_ORIGIN_R = Offset(71f, 40f)

    val earL = Path().apply {
        moveTo(19f, 45f)
        quadraticTo(15f, 23f, 22f, 14f)
        quadraticTo(27f, 8f, 32f, 17f)
        quadraticTo(36f, 29f, 37f, 46f)
        close()
    }
    val earR = Path().apply {
        moveTo(81f, 45f)
        quadraticTo(85f, 23f, 78f, 14f)
        quadraticTo(73f, 8f, 68f, 17f)
        quadraticTo(64f, 29f, 63f, 46f)
        close()
    }

    /** Eye centres and the capsule they are drawn from. No pupil inside — the eye *is* the shape. */
    val EYE_L = Offset(37f, 62f)
    val EYE_R = Offset(63f, 62f)
    const val EYE_RX = 7f
    const val EYE_RY = 10.6f

    private fun capsule(c: Offset) = Path().apply {
        addRoundRect(
            RoundRect(
                Rect(c.x - EYE_RX, c.y - EYE_RY, c.x + EYE_RX, c.y + EYE_RY),
                CornerRadius(EYE_RX, EYE_RX),
            ),
        )
    }

    val eyeL = capsule(EYE_L)
    val eyeR = capsule(EYE_R)

    /** The happy/wink lid: one arc per eye, stroked at [ARC_W]. */
    const val ARC_W = 5.4f
    val arcL = Path().apply { moveTo(27f, 67f); quadraticTo(37f, 50f, 47f, 67f) }
    val arcR = Path().apply { moveTo(53f, 67f); quadraticTo(63f, 50f, 73f, 67f) }

    /** Dead eyes: two crossed strokes each, centred on the eye. */
    const val CROSS_W = 5.4f
    val deadXL = Path().apply {
        moveTo(29f, 54f); lineTo(45f, 70f)
        moveTo(45f, 54f); lineTo(29f, 70f)
    }
    val deadXR = Path().apply {
        moveTo(55f, 54f); lineTo(71f, 70f)
        moveTo(71f, 54f); lineTo(55f, 70f)
    }

    /**
     * The determined/cross eye: a thick bar with round ends, rotated about the eye centre.
     *
     * A bar rather than a tapered wedge because the taper is invisible at 92dp and the rotation is
     * what carries the whole read — Work slants it, Cross slants it further.
     */
    const val BAR_HALF = 8.5f
    const val BAR_W = 9.5f

    /** A tear at the inner-lower corner of each eye. */
    val tearL = Path().apply {
        moveTo(43f, 72f)
        quadraticTo(39.5f, 77f, 43f, 79f)
        quadraticTo(46.5f, 77f, 43f, 72f)
        close()
    }
    val tearR = Path().apply {
        moveTo(57f, 72f)
        quadraticTo(53.5f, 77f, 57f, 79f)
        quadraticTo(60.5f, 77f, 57f, 72f)
        close()
    }

    /** The huff: a cloud each side, as three overlapping circles (centre to radius). */
    val cloudL: List<Pair<Offset, Float>> = listOf(
        Offset(6f, 16f) to 6.4f,
        Offset(13f, 11f) to 4.6f,
        Offset(-1f, 10f) to 4.2f,
    )
    val cloudR: List<Pair<Offset, Float>> = listOf(
        Offset(94f, 16f) to 6.4f,
        Offset(87f, 11f) to 4.6f,
        Offset(101f, 10f) to 4.2f,
    )

    /**
     * Speed lines under a hop. Drawn *outside* the body transform on purpose — they are the ground
     * the character left behind, so they must not rise with it.
     */
    val speedLines: List<Pair<Offset, Offset>> = listOf(
        Offset(34f, 96f) to Offset(33f, 106f),
        Offset(42f, 98f) to Offset(41f, 109f),
        Offset(58f, 98f) to Offset(59f, 109f),
        Offset(66f, 96f) to Offset(67f, 106f),
    )
    const val SPEED_W = 1.8f

    /** A soft ellipse under the chin, standing in for the CSS drop-shadow. */
    val SHADOW_CENTER = Offset(50f, 97f)
    const val SHADOW_RX = 30f
    const val SHADOW_RY = 7f
}
