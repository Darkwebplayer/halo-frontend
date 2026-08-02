package dev.infyplus.halo

import android.app.Activity
import android.os.Bundle
import dev.infyplus.halo.ui.HaloState

/**
 * A door, not a screen: opens the floating panel and gets out of the way.
 *
 * Exists because of two things that cannot both be satisfied by a plain broadcast. Tapping a
 * notification should collapse the shade, and only launching an activity does that — a broadcast
 * leaves the shade hanging open over the panel it just asked for. And starting the overlay service
 * from the background is refused on Android 12+, while starting it from a foreground activity is
 * allowed, which matters precisely when the bubble has been dismissed and there is no visible
 * overlay window to earn the `SYSTEM_ALERT_WINDOW` exemption.
 *
 * Draws nothing and finishes inside `onCreate`, so what the user sees is the shade closing and the
 * panel appearing. `Theme.Translucent.NoTitleBar` rather than `Theme.NoDisplay`, which throws on
 * modern Android unless the activity finishes before it would have resumed.
 */
class OpenPanelActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Can be the first component in a cold process — the shade outlives us.
        attachSettings(this)
        Config.load()

        // Published rather than acted on: the service owns the window, and it may need to put one
        // back before there is anything to open. It reads this as Compose state.
        HaloState.shared.requestOpen()
        OverlayService.start(this)

        finish()
        // No animation, or the panel appears behind a window sliding out of the way.
        overridePendingTransition(0, 0)
    }
}
