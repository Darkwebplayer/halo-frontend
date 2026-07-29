package dev.infyplus.halo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.infyplus.halo.ui.HaloState

/**
 * Whether this device has a network at all.
 *
 * Deliberately a *different question* from [HaloState.offline], which is "are our calls working".
 * A device can be on wifi with the server down, and the app must keep saying so. What this adds is
 * the other half, which the app could only ever learn the slow way: when there is no network, no
 * call can possibly succeed, and waiting for one to time out to find that out is why going offline
 * used to take fifteen seconds and coming back took up to a minute.
 *
 * The two signals are used asymmetrically, on purpose:
 *
 * - **Losing the network is conclusive.** Nothing can reach anything, so [HaloState.offline] is set
 *   immediately, with no round-trip.
 * - **Getting it back is not.** A joined wifi says nothing about whether *our* server answers, so
 *   this never clears [HaloState.offline] by itself. It re-triggers the poll, and the reply decides.
 *   Claiming recovery here is how you get a cheerful cat pointed at a dead backend.
 *
 * Starts optimistic and stays that way if no watcher is running, so a platform where this cannot be
 * read behaves exactly as the app did before it existed rather than falsely reporting no network.
 */
object DeviceNetwork {

    var available by mutableStateOf(true)
        private set

    /** Told by the platform watcher. Idempotent — watchers repeat themselves freely. */
    fun report(value: Boolean) {
        if (value == available) return
        available = value
        Sync.log(if (value) "device network back — re-checking the server" else "device network lost")
        // Only the losing edge is acted on here. See the class comment for why the other one isn't.
        if (!value) HaloState.shared.markOffline(true)
    }
}

/**
 * Begin watching, if the platform can.
 *
 * Idempotent, and silent about failure: this is an optimisation on how *fast* the app notices
 * something it would find out anyway, so nothing here is worth an error path of its own.
 *
 * Only worth calling from a long-lived host — the desktop tray app, the Android activity and
 * overlay service. A broadcast receiver's process dies within seconds, and there is nothing for a
 * watcher to observe in that time.
 */
expect fun startNetworkWatch()
