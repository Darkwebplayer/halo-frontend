package dev.infyplus.halo.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Halo's palette — "deep pastels floating on crisp white, postmodern 1950s".
 *
 * Converted from the OKLCH tokens in `reference/floating-assistant.html` (lines 23-36) through
 * OKLab to linear sRGB to sRGB, rather than eyeballed, so the character matches the prototype.
 *
 * Two rules the reference is emphatic about, worth keeping:
 *  - There is no black. The darkest value is a warm ink ([ink]), and ONE line weight draws every
 *    contour — no thin whiskers, no hairline eye outlines.
 *  - Fills are flat. No gradients and no gloss anywhere on the character; the only gradient in
 *    the whole drawing is the soft pool of light behind each eye, which sits *underneath* the
 *    pupil so the abstraction survives.
 *
 * The CSS uses `color-mix(in oklch, X n%, transparent)` in a few places, which is just alpha —
 * write those as `HaloPalette.navy.copy(alpha = 0.34f)` at the point of use.
 */
object HaloPalette {
    /** Chalky periwinkle — the head. */
    val body = Color(0xFF91B4E1)

    /** A step darker, for the backs of the ears. */
    val shade = Color(0xFF7A9ACA)

    /** Warm white. Only ever the glow behind the eyes. */
    val pale = Color(0xFFF7F5EF)

    /** Borders and panel chrome. Same family as [ink], deliberately not black. */
    val navy = Color(0xFF2F201A)

    /** The one line. Every contour on the character is this colour at one width. */
    val ink = Color(0xFF2A1B16)

    /** Retro mustard — the progress dial, the badge, the send button. */
    val sun = Color(0xFFE1B767)

    /** Dusty tomato — lateness, errors, the offline strip. */
    val warm = Color(0xFFCC6349)

    /** Flat coral cheek. Printed on, not an airbrushed glow. */
    val blush = Color(0xFFE08F88)

    /** Crisp white — the ground the character floats on, and the panel's surface. */
    val cream = Color(0xFFFBFAF7)

    /** Dead state: no connection, the lights are out. */
    val grey = Color(0xFFA29D99)
}

/**
 * The corner radii the design uses, previously private vals duplicated across the halo files.
 *
 * Values are unchanged from where they were lifted: [Panel] from HaloPanel, [Banner] from
 * HeadsUpBanner, [Card] from the five card-ish containers, [Pill] from every capsule.
 */
object HaloShapes {
    val Panel = RoundedCornerShape(28.dp)
    val Banner = RoundedCornerShape(20.dp)
    val Card = RoundedCornerShape(16.dp)
    val Pill = RoundedCornerShape(999.dp)
}

/**
 * The one line weight. Every border in the design is this — see [HaloPalette]'s "one line" rule.
 */
val HaloBorder = 2.dp

/**
 * Halo's look, expressed as a Material theme so the parts of the app that are built from stock
 * Material components inherit it.
 *
 * The overlay does not need this: everything under `halo/` reads [HaloPalette] directly and never
 * touches `MaterialTheme.colorScheme`, so wrapping or not wrapping it renders identically. This
 * exists for the in-app screens, which are `OutlinedTextField`/`Button`/`Card` and would otherwise
 * keep Material's stock purple.
 */
@Composable
fun HaloTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = HaloPalette.sun,
            onPrimary = HaloPalette.ink,
            secondary = HaloPalette.body,
            onSecondary = HaloPalette.ink,
            background = HaloPalette.cream,
            onBackground = HaloPalette.ink,
            surface = HaloPalette.cream,
            onSurface = HaloPalette.ink,
            // Cards and other tonal surfaces: a wash of the character's own periwinkle rather
            // than Material's grey, which would read as a different product next to the orb.
            surfaceVariant = HaloPalette.body.copy(alpha = 0.16f),
            onSurfaceVariant = HaloPalette.navy,
            outline = HaloPalette.navy,
            outlineVariant = HaloPalette.navy.copy(alpha = 0.32f),
            error = HaloPalette.warm,
            onError = HaloPalette.cream,
        ),
        shapes = Shapes(
            extraSmall = HaloShapes.Card,
            small = HaloShapes.Card,
            medium = HaloShapes.Card,
            large = HaloShapes.Panel,
            extraLarge = HaloShapes.Panel,
        ),
        typography = HaloTypography,
        content = content,
    )
}

/** The mono, letter-spaced micro-type the design uses for every label. */
private val MonoLabel = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 10.sp,
    letterSpacing = 0.9.sp,
)

private val HaloTypography = Typography(
    // Only the label styles are opinionated — those are the ones the design actually specifies.
    // Body and title styles keep Material's metrics; the colour scheme is what restyles them.
    labelSmall = MonoLabel,
    labelMedium = MonoLabel.copy(fontWeight = FontWeight.Bold),
    labelLarge = MonoLabel.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
)
