package dev.infyplus.halo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.infyplus.halo.Config
import dev.infyplus.halo.DEFAULT_AVATAR

/**
 * Which face the orb wears.
 *
 * The whole seam between "the user picked an avatar" and "something draws it". Everything that
 * shows a face calls [AvatarFace] rather than a specific character, so adding a third one is a new
 * renderer and a line in [AVATARS] — not a hunt through every call site.
 */

/** The faces we can actually draw, in picker order. Ids are what the server stores. */
val AVATARS: List<Pair<String, String>> = listOf(
    DEFAULT_AVATAR to "Halo",
    "waterloo" to "Waterloo",
    // The id stays "blue_cat": it is what is already stored against every account that picked
    // the cat, and renaming a storage key to rename a character would silently reset them all.
    "blue_cat" to "Garfield",
)

/**
 * Draw the current avatar.
 *
 * Defaults to [Config.avatar], which is Compose state, so a change in Settings repaints the orb,
 * the banner and the focus screen without any of them subscribing to anything. An unknown id draws
 * the default rather than nothing — a face is not worth failing over.
 */
@Composable
fun AvatarFace(
    expression: Expression,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
    avatar: String = Config.avatar,
) {
    when (avatar) {
        "blue_cat" -> CatFace(expression, modifier, reducedMotion)
        "waterloo" -> WaterlooFace(expression, modifier, reducedMotion)
        else -> HaloBuddyFace(expression, modifier, reducedMotion)
    }
}
