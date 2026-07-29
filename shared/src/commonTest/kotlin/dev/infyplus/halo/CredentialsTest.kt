package dev.infyplus.halo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pure half of credential handling.
 *
 * Deliberately never calls [Config.load]/[Config.save] — those go through `loadSetting`/
 * `saveSetting`, which on the JVM write to the developer's real user preferences. Only the
 * arithmetic of what a valid pair looks like is pinned here; the platform actuals are three lines.
 */
class CredentialsTest {

    /**
     * A hand-typed address is the most likely thing to be wrong, and a trailing slash is the
     * failure that looks least like itself: it produces "https://host//plan" and a 404 that reads
     * as the server being broken rather than the address having one character too many.
     */
    @Test
    fun normalizeFixesTheThreeMistakesPeopleActuallyMake() {
        assertEquals("https://halo.example.dev", Config.normalize("  halo.example.dev  "))
        assertEquals("https://halo.example.dev", Config.normalize("halo.example.dev/"))
        assertEquals("https://halo.example.dev", Config.normalize("https://halo.example.dev"))
    }

    /** A local backend is served over http, so the scheme is only added when one is missing. */
    @Test
    fun normalizeLeavesAnExplicitSchemeAlone() {
        assertEquals("http://127.0.0.1:8787", Config.normalize("http://127.0.0.1:8787/"))
    }

    @Test
    fun normalizeOfNothingIsNothing() {
        assertEquals("", Config.normalize(""))
        assertEquals("", Config.normalize("   "))
    }

    /**
     * The three failures need three different actions from the user — fix the address, fix the
     * token, or look at the server. Collapsing them into one message sends people to the wrong
     * field, which is exactly what the setup screen exists to prevent.
     */
    @Test
    fun anUnreachableHostIsNotTheSameAsARejectedToken() {
        val unreachable = credentialError(RuntimeException("Connection refused"), "https://h")
        assertTrue(unreachable.contains("Couldn't reach"), unreachable)

        val rejected = credentialError(ApiException("unauthorized"), "https://h")
        assertTrue(rejected.contains("rejected that token"), rejected)

        // Reached a real server that said something else — show what it said, not our guess.
        val other = credentialError(ApiException("no such route"), "https://h")
        assertEquals("no such route", other)
    }

    /** An ApiException with no text must still say something, rather than rendering blank. */
    @Test
    fun aSilentRefusalStillGetsAMessage() {
        assertTrue(credentialError(ApiException(""), "https://h").isNotBlank())
    }
}
