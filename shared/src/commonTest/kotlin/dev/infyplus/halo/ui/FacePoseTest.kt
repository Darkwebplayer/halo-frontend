package dev.infyplus.halo.ui

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Halo's expression sheet. Same guards as [CatPoseTest] — cheap protection against the kind of
 * slip that is very hard to spot on a moving face.
 */
class FacePoseTest {

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 0.001f, message: String = "") {
        assertTrue(abs(expected - actual) <= tolerance, "$message expected $expected but was $actual")
    }

    @Test
    fun lerpReturnsTheEndpointsExactly() {
        val idle = facePoseFor(Expression.Idle)
        val dead = facePoseFor(Expression.Dead)

        assertEquals(idle, lerp(idle, dead, 0f))
        assertEquals(dead, lerp(idle, dead, 1f))
    }

    @Test
    fun lerpMeetsInTheMiddle() {
        val work = facePoseFor(Expression.Work)   // bar at 16 degrees
        val cross = facePoseFor(Expression.Cross) // bar at 25
        val mid = lerp(work, cross, 0.5f)

        assertClose(20.5f, mid.angryRotation, message = "the bar steepens continuously")
        assertClose(0.5f, mid.steamAlpha, message = "steam fading in")
    }

    @Test
    fun everyExpressionEitherOpensAnEyeOrReplacesIt() {
        // The open capsule and the shapes that stand in for it are alternatives, never overlays:
        // an expression that leaves both on draws an arc across a wide-open eye.
        for (e in Expression.entries) {
            val pose = facePoseFor(e)
            val replaced = pose.arcAlphaR > 0f || pose.deadXAlpha > 0f || pose.angryAlpha > 0f
            assertTrue(
                !(replaced && pose.eyeAlpha > 0f),
                "$e draws a replacement shape over an open eye",
            )
        }
    }

    @Test
    fun onlyTheWinkIsAsymmetric() {
        for (e in Expression.entries - Expression.Wink) {
            val pose = facePoseFor(e)
            assertClose(
                -pose.earRotationL,
                pose.earRotationR,
                message = "$e should move its ears antisymmetrically:",
            )
            assertEquals(pose.arcAlphaL, pose.arcAlphaR, "$e should treat both eyes the same")
            assertEquals(1f, pose.eyeAlphaL, "$e should not close one eye")
        }

        val wink = facePoseFor(Expression.Wink)
        assertEquals(0f, wink.eyeAlphaL, "the wink closes the left eye")
        assertEquals(1f, wink.arcAlphaL, "…and draws an arc over it")
        assertEquals(0f, wink.arcAlphaR, "…but not over the right")
        assertEquals(0f, wink.earRotationR, "the wink tips only the left ear")
    }

    @Test
    fun deadIsFullyGreyedOut() {
        val dead = facePoseFor(Expression.Dead)
        assertEquals(BuddyPalette.greyTop, dead.topColor)
        assertEquals(BuddyPalette.greyBottom, dead.bottomColor)
        assertEquals(0f, dead.eyeAlpha, "the lights are out")
        assertEquals(1f, dead.deadXAlpha)
    }

    @Test
    fun theLidOnlyEverUncoversTheEye() {
        // lidCover is a fraction of the capsule; anything outside 0..1 draws a lid hanging in
        // space or a dark block over the cheek.
        for (e in Expression.entries) {
            val cover = facePoseFor(e).lidCover
            assertTrue(cover in 0f..1f, "$e has a lid covering $cover of the eye")
        }
        assertEquals(1f, facePoseFor(Expression.Idle).lidCover, "an idle eye is fully dark")
        assertTrue(facePoseFor(Expression.Wait).lidCover < 1f, "waiting is heavy-lidded")
    }
}
