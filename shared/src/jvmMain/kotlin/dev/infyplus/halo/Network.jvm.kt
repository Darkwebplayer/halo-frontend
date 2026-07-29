package dev.infyplus.halo

import java.net.NetworkInterface

/**
 * Desktop has no push equivalent, so this polls. That is not a shortcut — the JDK exposes no
 * network-change notification on any platform, and every library that offers one for the JVM is
 * doing exactly this underneath.
 *
 * The poll is cheap enough not to care about: enumerating interfaces reads kernel state and touches
 * no network, so a two-second tick is far below the noise floor of an app that already redraws an
 * animated cat. Two seconds against the fifteen-second connect timeout it replaces is the whole
 * point.
 *
 * It is also a weaker signal than Android's. "An interface is up with a routable address" is not
 * proof of internet — a VM bridge or a Docker interface satisfies it with the wifi off. That only
 * costs us a missed *fast* detection, never a false one: the call still goes out and still fails,
 * exactly as it did before this existed. The asymmetry in [DeviceNetwork] is what makes that safe.
 */
@Volatile
private var watching = false

actual fun startNetworkWatch() {
    if (watching) return
    watching = true
    Thread(
        {
            while (true) {
                DeviceNetwork.report(hasUsableInterface())
                Thread.sleep(POLL_MILLIS)
            }
        },
        "halo-network",
    ).apply { isDaemon = true }.start()
}

private const val POLL_MILLIS = 2_000L

/**
 * True when something could plausibly carry traffic.
 *
 * Loopback is excluded because it is always up and would make this constantly true. Link-local
 * addresses (169.254.x, fe80::) are excluded because they are what an interface self-assigns when
 * DHCP found nobody — the signature of a cable plugged into nothing.
 *
 * Answers true when it cannot tell. Guessing "offline" from a failed enumeration would grey the cat
 * out and skip polls over a question we never actually managed to ask.
 */
private fun hasUsableInterface(): Boolean = runCatching {
    NetworkInterface.getNetworkInterfaces().asSequence().any { nic ->
        nic.isUp && !nic.isLoopback && nic.inetAddresses.asSequence().any { address ->
            !address.isLoopbackAddress && !address.isLinkLocalAddress && !address.isAnyLocalAddress
        }
    }
}.getOrDefault(true)
