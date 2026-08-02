package dev.infyplus.halo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.border
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.infyplus.halo.Item
import dev.infyplus.halo.nowMillis

/**
 * One task, everywhere.
 *
 * There used to be four different item renderers — a Material `Card` on Today, a Halo card in the
 * chat panel, a bare `Text` in the focus picker — which is why the same field appeared as `task`
 * on one screen and `TASK` on another, and why `due_at` was printed as a raw ISO instant on two of
 * them. One row means one answer to what an item looks like.
 *
 * What it says, in order of what a person actually needs: when it is due, then whether it repeats,
 * then anything unusual about it. Priority appears only when it is *not* normal and the kind only
 * when it is not a plain task, because a line that says the same thing on every row says nothing.
 */
@Composable
fun ItemRow(
    item: Item,
    modifier: Modifier = Modifier,
    now: Long = nowMillis(),
    /** How often it comes back, when the rule is known. Shown as-is; see [describeRecurrence]. */
    cadence: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val done = !item.doneAt.isNullOrBlank()

    Column(
        modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .clip(HaloShapes.Card)
            .background(
                if (done) HaloPalette.body.copy(alpha = 0.12f)
                else HaloPalette.cream,
            )
            .border(HaloBorder, HaloPalette.navy.copy(alpha = 0.22f), HaloShapes.Card)
            .let {
                if (onClick == null) it
                else it.clickable(interactionSource = interaction, indication = null, onClick = onClick)
            }
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            item.title,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            // Struck through rather than hidden: a completed thing is still part of the day.
            textDecoration = if (done) TextDecoration.LineThrough else null,
            color = if (done) HaloPalette.navy.copy(alpha = 0.6f) else HaloPalette.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        val meta = buildList {
            whenLabel(item.dueAt, now)?.let(::add)
            cadence?.let { add("↻ $it") }
            if (cadence == null && item.repeats) add("↻ repeats")
            if (item.kind != "task") add(item.kind.replaceFirstChar { it.uppercase() })
            when (item.priority) {
                1 -> add("High")
                3 -> add("Low")
            }
        }
        if (meta.isNotEmpty()) {
            Mono(meta.joinToString("  ·  "), color = HaloPalette.navy.copy(alpha = 0.8f))
        }

        if (item.tagList.isNotEmpty()) {
            Row(
                Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item.tagList.take(4).forEach { tag ->
                    Mono(
                        "#$tag",
                        Modifier
                            .clip(HaloShapes.Pill)
                            .background(HaloPalette.body.copy(alpha = 0.3f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        color = HaloPalette.navy,
                    )
                }
            }
        }
    }
}
