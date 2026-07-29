package dev.infyplus.halo

import dev.infyplus.halo.ui.apiCatching
import dev.infyplus.halo.ui.isConnectivity
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The two rules that make an offline cat mean something.
 *
 * Every network call in the app is wrapped in [apiCatching] and every failure is classified by
 * [isConnectivity]. Between them they decide whether the user is told "no connection" — so a
 * mistake here is not a crash, it is the app confidently lying about why something did not work.
 */
class ApiCatchingTest {

    @Test
    fun successCarriesTheValueThrough() {
        assertEquals(7, apiCatching { 7 }.getOrNull())
    }

    @Test
    fun ordinaryFailuresAreCaptured() {
        val result = apiCatching { throw IllegalStateException("server said no") }
        assertTrue(result.isFailure)
        assertEquals("server said no", result.exceptionOrNull()?.message)
    }

    /**
     * The bug this whole helper exists for.
     *
     * Every call site runs on a `rememberCoroutineScope`, so closing the panel mid-request cancels
     * it. `runCatching` used to swallow that, turning a routine teardown into a failure that
     * [isConnectivity] then reported as being offline — the cat went grey and "Connection lost"
     * appeared on a perfectly good network. Swallowing it also breaks cancellation itself.
     */
    @Test
    fun cancellationIsRethrownNotCaptured() {
        try {
            apiCatching { throw CancellationException("panel closed") }
            fail("cancellation must propagate, not become a Result.failure")
        } catch (expected: CancellationException) {
            assertEquals("panel closed", expected.message)
        }
    }

    /** Not offline: an OOM or a missing class is a fault in the app, and must reach a crash log. */
    @Test
    fun errorsAreNotCaught() {
        try {
            apiCatching { throw NoClassDefFoundError("dev/infyplus/halo/ui/HaloThreadKt") }
            fail("an Error must not be reported to the user as a network problem")
        } catch (expected: Throwable) {
            assertTrue(expected is Error)
        }
    }

    /**
     * [ApiException] is only thrown for a non-2xx, which means we reached the server. Anything else
     * came out of the socket before we got there.
     */
    @Test
    fun onlyFailuresToReachTheServerCountAsOffline() {
        assertFalse(ApiException("401 unauthorized").isConnectivity())
        assertTrue(IllegalStateException("connection refused").isConnectivity())
    }
}
