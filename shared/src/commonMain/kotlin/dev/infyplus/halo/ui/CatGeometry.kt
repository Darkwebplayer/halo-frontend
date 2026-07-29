package dev.infyplus.halo.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp

/**
 * The cat's shapes, in the SVG user units the reference draws in.
 *
 * Paths are built once and reused — allocating a `Path` inside a draw pass would allocate on
 * every frame of a 60fps animation, which is exactly the kind of thing that makes an always-on
 * overlay expensive.
 */
object CatGeometry {

    /**
     * The cat and the dial live in **different** coordinate spaces, and conflating them is the
     * one mistake the reference explicitly warns about (line 187-189: it would "leave the head
     * marooned inside the dial"). The cat's viewBox is `-6 -12 112 116`; the dial's is `0 0 92 92`.
     */
    const val CAT_VIEW_W = 112f
    const val CAT_VIEW_H = 116f

    /** Centre of the cat's viewBox in user units: (-6 + 112/2, -12 + 116/2). */
    val CAT_CENTER = Offset(50f, 46f)

    /** The dial's own viewBox is square, so one number covers it. */
    const val DIAL_VIEW = 92f

    /** The tappable orb: the dial ring and everything inside it. */
    val ORB_DP = 92.dp

    /**
     * Extra canvas around the orb, so nothing is clipped.
     *
     * Nothing *static* escapes the viewBox — I checked every extremity, and the reference's own
     * comment says the viewBox already reserves the margin the ears need. What escapes is the
     * animation: the excited hop peaks at `translateY(-9) scaleY(1.07)` about origin (50,92),
     * which lifts the ear tips about 4 units above the top edge. The rest of this budget is the
     * ground shadow, which sits below the chin.
     *
     * Composables do not clip their own drawing, so this costs nothing here — but the hosting
     * *window* does clip, which is why the collapsed overlay window has to be bigger than the orb.
     */
    val BLEED_DP = 16.dp

    /** The full drawing surface: orb plus bleed on every side. */
    val CANVAS_DP = ORB_DP + BLEED_DP * 2

    /**
     * What fraction of the canvas the cat itself occupies.
     *
     * The drawing scales off its actual layout size rather than [ORB_DP], so the same composable
     * works at any size — the notification banner draws a 38dp version of this exact cat — while
     * keeping the bleed proportional so the hop still never clips.
     */
    const val ORB_TO_CANVAS = 92f / (92f + 16f * 2f)

    /** Head. */
    val HEAD_CENTER = Offset(50f, 52f)
    const val HEAD_RADIUS = 40f

    /** The single line weight used for every contour, interior details included. */
    const val LINE = 3.4f

    /** Ear transform origins (reference lines 236-237). */
    val EAR_ORIGIN_L = Offset(22f, 27f)
    val EAR_ORIGIN_R = Offset(78f, 27f)

    /** Eye group origins (lines 238-239), and the pupil/glow centres. */
    val EYE_L = Offset(37f, 57f)
    val EYE_R = Offset(63f, 57f)
    const val PUPIL_RX = 8.6f
    const val PUPIL_RY = 11f
    const val GLOW_RX = 12.4f
    const val GLOW_RY = 15f

    /** Brow rotation origins (lines 262-263). */
    val BROW_ORIGIN_L = Offset(37f, 38f)
    val BROW_ORIGIN_R = Offset(63f, 38f)

    /** The whole-character transform origin (line 240). */
    val BODY_ORIGIN = Offset(50f, 92f)

    val BLUSH_L = Offset(24f, 77f)
    val BLUSH_R = Offset(76f, 77f)
    const val BLUSH_RADIUS = 5.5f

    /** A soft ellipse under the chin, standing in for the CSS drop-shadow. */
    val SHADOW_CENTER = Offset(50f, 96f)
    const val SHADOW_RX = 34f
    const val SHADOW_RY = 9f

    // ── paths ────────────────────────────────────────────────────────────────

    /** `M19 28 L21 1 L41 15 Z` — the outer ear, one triangle. */
    val earOuterL = tri(19f, 28f, 21f, 1f, 41f, 15f)
    val earInnerL = tri(25f, 24f, 26f, 9f, 35f, 16f)
    val earOuterR = tri(81f, 28f, 79f, 1f, 59f, 15f)
    val earInnerR = tri(75f, 24f, 74f, 9f, 65f, 16f)

    /** Brows: two short lines, drawn at the same weight as everything else. */
    val browLStart = Offset(29f, 38f); val browLEnd = Offset(45f, 35f)
    val browRStart = Offset(71f, 38f); val browREnd = Offset(55f, 35f)

    /** Happy/wink lid arcs: `M27 60 Q37 46 47 60`. */
    val lidArcL = quad(27f, 60f, 37f, 46f, 47f, 60f)
    val lidArcR = quad(53f, 60f, 63f, 46f, 73f, 60f)

    /** The dead eyes, two crossed strokes each. */
    val deadXL = Path().apply {
        moveTo(29f, 49f); lineTo(45f, 65f)
        moveTo(45f, 49f); lineTo(29f, 65f)
    }
    val deadXR = Path().apply {
        moveTo(55f, 49f); lineTo(71f, 65f)
        moveTo(71f, 49f); lineTo(55f, 65f)
    }

    /**
     * The dead mouth: a closed lens of two arcs. The only mouth that does not share the single
     * quadratic topology, which is why [CatPose.mouthLensAlpha] crossfades it instead.
     */
    val mouthLens = Path().apply {
        moveTo(46f, 82f)
        quadraticTo(50f, 87f, 54f, 82f)
        quadraticTo(50f, 77f, 46f, 82f)
        close()
    }

    /** Three four-pointed stars for the excited state. */
    val sparkles: List<Path> = listOf(
        star(4f, 30f, 2.2f, 5f),
        star(96f, 36f, 1.7f, 3.8f),
        star(88f, 8f, 1.4f, 3.2f),
    )

    /** Two curls of steam for the grumpy huff. */
    val steam: List<Path> = listOf(
        quad(84f, 16f, 89f, 11f, 85f, 6f),
        quad(93f, 22f, 97f, 18f, 94f, 14f),
    )

    private fun tri(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) = Path().apply {
        moveTo(x1, y1); lineTo(x2, y2); lineTo(x3, y3); close()
    }

    private fun quad(x1: Float, y1: Float, cx: Float, cy: Float, x2: Float, y2: Float) = Path().apply {
        moveTo(x1, y1); quadraticTo(cx, cy, x2, y2)
    }

    /**
     * A four-pointed star from the reference's relative path data, e.g.
     * `M4 30 l2.2 5 5 2.2 -5 2.2 -2.2 5 -2.2 -5 -5 -2.2 5 -2.2 Z` — where `a` is the short arm
     * and `b` the long one.
     */
    private fun star(x: Float, y: Float, a: Float, b: Float) = Path().apply {
        moveTo(x, y)
        relativeLineTo(a, b); relativeLineTo(b, a)
        relativeLineTo(-b, a); relativeLineTo(-a, b)
        relativeLineTo(-a, -b); relativeLineTo(-b, -a)
        relativeLineTo(b, -a)
        close()
    }
}
