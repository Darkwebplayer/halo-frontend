package dev.infyplus.halo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The tab glyphs, drawn rather than imported.
 *
 * Material's icon set is not on this classpath, and putting it there to label six tabs would drop
 * a second visual language into a design whose whole rule is one line weight and no gloss — a
 * filled Material gear next to the hand-drawn cat reads as two apps. These are six paths in a
 * 24-unit box, stroked at the same relative weight as everything else, and they cost nothing.
 */
enum class HaloIcon { Today, Focus, Projects, Chat, Bell, Settings }

/** Everything is drawn in this box and scaled to whatever the caller asks for. */
private const val VIEW = 24f

/** The one line weight, in view units — 2.1 of 24 matches the character's 3.4 of 116 by eye. */
private const val LINE = 2.1f

/** A glyph is one stroked path, plus any solid dots — the design has no other mark. */
private class Glyph(val stroke: Path, val dots: List<Pair<Offset, Float>> = emptyList())

private fun rounded(l: Float, t: Float, r: Float, b: Float, radius: Float) = Path().apply {
    addRoundRect(RoundRect(Rect(l, t, r, b), CornerRadius(radius, radius)))
}

private val glyphs: Map<HaloIcon, Glyph> = mapOf(
    // A calendar: the page, the head rule, and the two hangers above it.
    HaloIcon.Today to Glyph(
        rounded(4f, 6f, 20f, 20f, 3f).apply {
            moveTo(4f, 11f); lineTo(20f, 11f)
            moveTo(9f, 3.5f); lineTo(9f, 7.5f)
            moveTo(15f, 3.5f); lineTo(15f, 7.5f)
        },
        dots = listOf(Offset(12f, 15.6f) to 1.5f),
    ),

    // A target, which is what the focus dial already is.
    HaloIcon.Focus to Glyph(
        Path().apply { addOval(Rect(4f, 4f, 20f, 20f)) },
        dots = listOf(Offset(12f, 12f) to 2.6f),
    ),

    // A folder, tab and all.
    HaloIcon.Projects to Glyph(
        Path().apply {
            moveTo(4.5f, 19f)
            lineTo(4.5f, 6.5f)
            lineTo(10f, 6.5f)
            lineTo(12.2f, 9.5f)
            lineTo(19.5f, 9.5f)
            lineTo(19.5f, 19f)
            close()
        },
    ),

    // A speech bubble — the same shape the orb says things in.
    HaloIcon.Chat to Glyph(
        rounded(4f, 5.5f, 20f, 16.5f, 4f).apply {
            moveTo(9.5f, 16.5f); lineTo(8.5f, 20.5f); lineTo(13.5f, 16.5f)
        },
    ),

    // A bell, for the things that went off while you were not looking.
    HaloIcon.Bell to Glyph(
        Path().apply {
            moveTo(6f, 16.5f); lineTo(18f, 16.5f)
            moveTo(8f, 16.5f); lineTo(8f, 11.5f)
            quadraticTo(8f, 6f, 12f, 6f)
            quadraticTo(16f, 6f, 16f, 11.5f)
            lineTo(16f, 16.5f)
            moveTo(10.2f, 18.8f); quadraticTo(12f, 20.8f, 13.8f, 18.8f)
        },
    ),

    // Sliders rather than a gear: a gear's teeth turn to mush at 14dp, two rules and two knobs
    // do not.
    HaloIcon.Settings to Glyph(
        Path().apply {
            moveTo(4.5f, 9f); lineTo(19.5f, 9f)
            moveTo(4.5f, 15f); lineTo(19.5f, 15f)
        },
        dots = listOf(Offset(15f, 9f) to 2.4f, Offset(9f, 15f) to 2.4f),
    ),
)

/** One glyph, at [size], in whatever colour the thing containing it is using for its text. */
@Composable
fun HaloGlyph(icon: HaloIcon, color: Color, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    val glyph = glyphs.getValue(icon)
    Canvas(modifier.size(size)) {
        val scale = this.size.minDimension / VIEW
        withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
            drawPath(
                glyph.stroke,
                color,
                style = Stroke(LINE, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            glyph.dots.forEach { (centre, radius) -> drawCircle(color, radius, centre) }
        }
    }
}
