package dev.infyplus.halo.ui

import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import dev.infyplus.halo.prefersReducedMotion
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The reusable pieces of the halo look.
 *
 * These were private composables inside HaloPanel and HeadsUpBanner, which meant the in-app screens
 * could not use them and grew a second, Material-flavoured vocabulary instead. The bodies here are
 * lifted unchanged — the overlay must render exactly as it did, which the PNGs from
 * `:desktopApp:test` are the check for.
 */

/** The mono, letter-spaced micro-type the design uses for every label. */
@Composable
fun Mono(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = HaloPalette.navy.copy(alpha = 0.78f),
    weight: FontWeight = FontWeight.Normal,
) {
    Text(
        text = text,
        modifier = modifier,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        letterSpacing = 0.9.sp,
        fontWeight = weight,
        color = color,
    )
}

/**
 * A selected-state capsule. Filled navy when on, a wash of the character's blue when off.
 *
 * Given an [icon], only the selected tab spells its name out: five tabs' worth of words is most of
 * a phone's width, and the one you are looking at is the only one that has to be readable. The
 * others keep their glyph, which is enough to find them by, and the name comes back the moment the
 * tab is chosen.
 *
 * @param badge kept visible on an unselected tab, where the label that would have carried it is
 *   hidden. A count nobody can see is the same as no count.
 */
@Composable
fun HaloTab(
    label: String,
    selected: Boolean,
    icon: HaloIcon? = null,
    badge: String? = null,
    onClick: () -> Unit,
) {
    val color = if (selected) HaloPalette.cream else HaloPalette.navy
    val reducedMotion = prefersReducedMotion()
    // Both in sp rather than dp, so the strip grows with the system font setting the way the label
    // inside it does.
    //
    // The height is fixed rather than left to the content. Letting the tallest child decide it
    // meant the pill grew when the name appeared — a text's line box is its font's ascent and
    // descent, which is taller than the glyph and taller again with font padding on Android, so no
    // amount of matching the icon to the text settles it. Nailing the height down means only the
    // width can change.
    val glyphSize = with(LocalDensity.current) { 15.sp.toDp() }
    val pillHeight = with(LocalDensity.current) { 26.sp.toDp() }
    Row(
        Modifier
            .height(pillHeight)
            .clip(HaloShapes.Pill)
            .background(if (selected) HaloPalette.navy else HaloPalette.body.copy(alpha = 0.25f))
            .clickable(onClick = onClick)
            // The name arriving and leaving is a width change, so the pill grows into it rather
            // than snapping a letter at a time.
            .animateContentSize(animationSpec = if (reducedMotion) snap() else spring())
            .padding(horizontal = 10.dp)
            // An icon on its own says nothing to a screen reader; the name it is standing in for
            // has to be said somewhere.
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { HaloGlyph(it, color, size = glyphSize) }
        when {
            icon == null || selected -> Mono(label, color = color, weight = FontWeight.Bold)
            badge != null -> Mono(badge, color = color, weight = FontWeight.Bold)
        }
    }
}

/** An outlined capsule for a one-shot action — "Snooze 30m", "Mark done". */
@Composable
fun HaloChip(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(HaloShapes.Pill)
            .background(HaloPalette.cream)
            .border(HaloBorder, HaloPalette.navy.copy(alpha = if (enabled) 0.32f else 0.16f), HaloShapes.Pill)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            // Faded rather than hidden: a chip that vanishes mid-tap moves everything beside it.
            color = if (enabled) HaloPalette.ink else HaloPalette.ink.copy(alpha = 0.45f),
        )
    }
}

/**
 * The card container the design repeats for every block of content: tinted fill, soft navy border,
 * 16dp corners. [tint] is the only thing that varies between the five places it appears.
 */
@Composable
fun HaloCard(
    modifier: Modifier = Modifier,
    tint: Color = HaloPalette.body.copy(alpha = 0.16f),
    border: Color = HaloPalette.navy.copy(alpha = 0.28f),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(HaloShapes.Card)
            .background(tint)
            .border(HaloBorder, border, HaloShapes.Card)
            .padding(horizontal = 13.dp, vertical = 10.dp),
        content = content,
    )
}

/**
 * The pill-shaped text input from the panel's composer.
 *
 * A [BasicTextField] rather than Material's, because the design has no floating label, no
 * underline and no container — just the capsule border drawn here.
 */
@Composable
fun HaloField(
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    Box(
        modifier
            .clip(HaloShapes.Pill)
            .border(HaloBorder, HaloPalette.navy.copy(alpha = 0.28f), HaloShapes.Pill)
            .padding(horizontal = 15.dp, vertical = 11.dp),
    ) {
        if (value.isEmpty()) {
            Text(placeholder, fontSize = 14.sp, color = HaloPalette.navy.copy(alpha = 0.62f))
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            singleLine = singleLine,
            textStyle = TextStyle(fontSize = 14.sp, color = HaloPalette.ink),
            cursorBrush = SolidColor(HaloPalette.ink),
            visualTransformation = visualTransformation,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Makes a surface answer the finger the moment it lands, not when it lifts.
 *
 * Waiting for the click to acknowledge a press is the single cheapest way to make an interface
 * feel dead — the gap between touching something and seeing it react is read as lag even when the
 * work afterwards is instant. So this is driven by the press interaction, which fires on down.
 *
 * A spring rather than a tween because a press can be released mid-animation and the scale has to
 * turn round from wherever it actually is. Critically damped: a button that bounces reads as a toy.
 */
@Composable
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressed: Float = 0.97f,
): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val reduced = remember { prefersReducedMotion() }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressed else 1f,
        animationSpec = if (reduced) snap() else spring(dampingRatio = Spring.DampingRatioNoBouncy),
        label = "pressScale",
    )
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * Hides all but the last [reveal] characters of a secret.
 *
 * The tail is deliberately left visible. A fully-masked field gives no way to tell a token that
 * was pasted correctly from one that was not, and no way to notice that it has been replaced —
 * so people clear it and paste again, which is worse for the secret than showing four characters.
 *
 * Short values are masked entirely rather than mostly-revealed, which is the case where showing a
 * tail would give away most of the string.
 */
class TailVisible(private val reveal: Int = 4) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val keep = if (text.length > reveal * 2) reveal else 0
        val masked = "•".repeat(text.length - keep) + text.text.takeLast(keep)
        // The mask is character-for-character, so offsets map straight through.
        return TransformedText(AnnotatedString(masked), OffsetMapping.Identity)
    }
}

/**
 * A capsule button. [filled] is the mustard primary from the send button; unfilled is the same
 * shape drawn as an outline, for the secondary actions beside it.
 */
@Composable
fun HaloButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = true,
    onClick: () -> Unit,
) {
    val fill = when {
        !filled -> HaloPalette.cream
        enabled -> HaloPalette.sun
        else -> HaloPalette.sun.copy(alpha = 0.45f)
    }
    Box(
        modifier
            .clip(HaloShapes.Pill)
            .background(fill)
            .border(
                HaloBorder,
                if (enabled) HaloPalette.navy else HaloPalette.navy.copy(alpha = 0.35f),
                HaloShapes.Pill,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        // No-op while the button wraps its label, which is every existing use — it matters only
        // when a caller stretches one with fillMaxWidth, where the label would otherwise sit left.
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) HaloPalette.ink else HaloPalette.navy.copy(alpha = 0.45f),
        )
    }
}
