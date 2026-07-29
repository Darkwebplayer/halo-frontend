package dev.infyplus.halo

import dev.infyplus.halo.ui.HaloState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The asymmetry between the two edges is the whole design, so it is what gets pinned.
 *
 * Getting this backwards is not a crash — it is the app telling the user it is connected while
 * every call fails, which is worse than the slow detection it replaced.
 */
class DeviceNetworkTest {

    @AfterTest
    fun restore() {
        DeviceNetwork.report(true)
        HaloState.shared.markOffline(false)
    }

    /** Losing the network is conclusive: nothing can reach anything, so say so at once. */
    @Test
    fun losingTheNetworkMarksOfflineImmediately() {
        HaloState.shared.markOffline(false)
        DeviceNetwork.report(false)

        assertFalse(DeviceNetwork.available)
        assertTrue(HaloState.shared.offline)
    }

    /**
     * Regaining it is not. A joined access point says nothing about whether our server answers, so
     * this must leave [HaloState.offline] alone and let the re-triggered poll decide.
     */
    @Test
    fun regainingTheNetworkDoesNotClaimTheServerIsBack() {
        DeviceNetwork.report(false)
        DeviceNetwork.report(true)

        assertTrue(DeviceNetwork.available)
        assertTrue(HaloState.shared.offline, "only a successful call may clear offline")
    }

    /** Watchers repeat themselves; a repeat must not re-trigger anything downstream. */
    @Test
    fun repeatedReportsAreIdempotent() {
        DeviceNetwork.report(false)
        HaloState.shared.markOffline(false) // as if a call had somehow just succeeded
        DeviceNetwork.report(false)

        assertFalse(HaloState.shared.offline, "an unchanged report should not have acted again")
    }
}
