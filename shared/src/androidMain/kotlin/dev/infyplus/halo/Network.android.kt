package dev.infyplus.halo

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Android pushes network changes at us, so this is genuinely instant — no polling anywhere.
 *
 * [ConnectivityManager.registerDefaultNetworkCallback] (API 24, which is our minimum) reports the
 * network the system would actually route our traffic over, which is the only one worth watching.
 *
 * The capability read is [NetworkCapabilities.NET_CAPABILITY_VALIDATED] rather than "is connected":
 * Android probes the link itself and only sets VALIDATED once something out on the internet has
 * answered. That is what distinguishes real connectivity from a joined access point sitting behind
 * a hotel captive portal — the case where "connected" is true and nothing works.
 */
@Volatile
private var watching = false

actual fun startNetworkWatch() {
    if (watching) return
    val context = androidAppContext ?: return
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
    watching = true

    runCatching {
        // The callback only fires on *changes*, and if the device is offline right now there is no
        // default network to report one for — so the current state is read once up front. Without
        // this, starting up with no network looks like having one until it next changes.
        DeviceNetwork.report(
            manager.activeNetwork
                ?.let { manager.getNetworkCapabilities(it) }
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        )

        manager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) = DeviceNetwork.report(false)

                override fun onUnavailable() = DeviceNetwork.report(false)

                // Not onAvailable: that fires as soon as the link is up, before Android has
                // checked whether anything is reachable through it. Waiting for the validated
                // capability is the difference between "wifi joined" and "internet works".
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                    DeviceNetwork.report(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            },
        )
    }.onFailure {
        // Left as "available", so the app falls back to finding out the slow way rather than
        // claiming there is no network when we simply could not ask.
        watching = false
        Sync.log("network watch unavailable: ${it.message}")
    }
}
