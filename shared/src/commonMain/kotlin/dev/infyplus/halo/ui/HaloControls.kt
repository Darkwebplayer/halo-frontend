package dev.infyplus.halo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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

/** A selected-state capsule. Filled navy when on, a wash of the character's blue when off. */
@Composable
fun HaloTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(HaloShapes.Pill)
            .background(if (selected) HaloPalette.navy else HaloPalette.body.copy(alpha = 0.25f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Mono(label, color = if (selected) HaloPalette.cream else HaloPalette.navy, weight = FontWeight.Bold)
    }
}

/** An outlined capsule for a one-shot action — "Snooze 30m", "Mark done". */
@Composable
fun HaloChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(HaloShapes.Pill)
            .background(HaloPalette.cream)
            .border(HaloBorder, HaloPalette.navy.copy(alpha = 0.32f), HaloShapes.Pill)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = HaloPalette.ink)
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
            modifier = Modifier.fillMaxWidth(),
        )
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
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) HaloPalette.ink else HaloPalette.navy.copy(alpha = 0.45f),
        )
    }
}
