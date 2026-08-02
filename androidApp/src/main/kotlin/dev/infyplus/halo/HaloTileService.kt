package dev.infyplus.halo

import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Ask Android to offer the tile, the way Shazam and friends do on first run.
 *
 * API 33 and up only — before that a tile could only be found by editing the Quick Settings panel
 * by hand, and there is no prompt to fall back to. The caller checks [DeviceOptions.quickTile]
 * before offering this anywhere.
 *
 * Two rules the platform imposes, both of which shape where this is called from:
 * the app must be in the **foreground** when it asks (so: a screen the user is looking at, never a
 * receiver or a service), and the system starts auto-denying once the same request has been refused
 * enough times — so it is asked once at setup, and thereafter only when the user goes looking for it
 * in Settings.
 *
 * Wrapped whole because it is offered from the setup checklist, and a screen whose entire job is
 * explaining how to make the app work must not be the screen that crashes.
 */
fun requestQuickSettingsTile(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    runCatching {
        context.getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(context, HaloTileService::class.java),
            context.getString(R.string.tile_label),
            Icon.createWithResource(context, R.drawable.ic_stat_halo),
            { it.run() },
            { result -> Sync.log("quick settings tile request: $result") },
        )
    }.onFailure { Sync.log("could not offer the tile: ${it.message}") }
}

/**
 * Halo in the pull-down Quick Settings panel: one tap to put the floating button on screen, one to
 * take it off.
 *
 * Chosen to be a *toggle* rather than a shortcut because of what it has to undo. Long-pressing the
 * bubble hides it for good — deliberately, that is what dismissing means — and a way back that
 * requires opening the app to find a setting is not much of a way back. From here it is one swipe.
 *
 * The tile runs in the same process as [OverlayService], so writing [Config.orbHidden] here *is*
 * writing the flag the service's visibility collector is watching. No broadcast, no binder, no
 * chance of the two disagreeing about what the state is.
 */
class HaloTileService : TileService() {

    /**
     * Called every time the panel is opened, and again whenever the system rebinds us.
     *
     * The attach/load pair is here rather than in `onCreate` because Quick Settings can start this
     * process cold — the tile is a component like any other, and it may well be the first thing the
     * user touches after a reboot. Both calls are idempotent.
     */
    override fun onStartListening() {
        super.onStartListening()
        attachSettings(this)
        Config.load()
        render()
    }

    override fun onClick() {
        super.onClick()
        attachSettings(this)
        Config.load()
        val bringingBack = Config.orbHidden
        Config.saveOrbHidden(!Config.orbHidden)
        render()

        if (!bringingBack) return
        // Asking for it back is only meaningful if something is there to draw it. The service is
        // normally already running — it hosts the sync loop as well as the window — but after a
        // force-stop or a process death nothing is, and Android 15+ will not let a background
        // component start a foreground service without an already-visible overlay window. Going via
        // the activity starts it from the foreground, where it is allowed.
        if (isServiceLikelyRunning()) {
            OverlayService.start(this)
        } else {
            openApp()
        }
    }

    /** Reflect [Config.orbHidden] on the tile itself, so the panel is not lying about the state. */
    private fun render() {
        val tile = qsTile ?: return
        val visible = !Config.orbHidden
        tile.state = if (visible) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_halo)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (visible) "On screen" else "Hidden"
        }
        runCatching { tile.updateTile() }
    }

    /**
     * Whether starting the service in place is worth trying.
     *
     * There is no supported way to ask "is my service running" — `getRunningServices` has been
     * useless to third-party apps since API 26. The overlay permission is the thing the service
     * cannot run without and the thing most likely to have been revoked, so it stands in: with it,
     * a plain start is safe to attempt (and [OverlayService.start] is itself a no-op without it);
     * without it, sending the user to the app is the only useful answer anyway.
     */
    private fun isServiceLikelyRunning(): Boolean =
        android.provider.Settings.canDrawOverlays(this)

    /**
     * Open Halo and collapse the shade.
     *
     * `startActivityAndCollapse` takes a `PendingIntent` from API 34; the `Intent` overload throws
     * `UnsupportedOperationException` there rather than merely being deprecated.
     */
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }.onFailure { Sync.log("tile could not open the app: ${it.message}") }
    }
}
