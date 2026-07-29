package dev.infyplus.halo.ui

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun assertClose(expected: Float, actual: Float, tolerance: Float = 0.001f, message: String = "") {
    assertTrue(
        abs(expected - actual) <= tolerance,
        "$message expected $expected but was $actual",
    )
}

/**
 * The expression sheet. These are cheap guards against the kind of transcription slip that is
 * very hard to spot by looking at a moving cat.
 */
class CatPoseTest {

    @Test
    fun lerpReturnsTheEndpointsExactly() {
        val idle = poseFor(Expression.Idle)
        val dead = poseFor(Expression.Dead)

        assertEquals(idle, lerp(idle, dead, 0f))
        assertEquals(dead, lerp(idle, dead, 1f))
    }

    @Test
    fun lerpMeetsInTheMiddle() {
        val a = poseFor(Expression.Idle)      // eyeScaleY 1.0, glow .5
        val b = poseFor(Expression.Cross)     // eyeScaleY .62, glow .5
        val mid = lerp(a, b, 0.5f)

        assertClose(0.81f, mid.eyeScaleY, message = "eye height halfway")
        assertClose(10f, mid.browRotation, message = "brow halfway to its 20 degree scowl")
        assertClose(0.5f, mid.steamAlpha, message = "steam fading in")
    }

    @Test
    fun everyExpressionKeepsBrowsSymmetric() {
        // The right brow is always the negation of the left, which is what lets one field
        // drive both. If a future expression breaks this, it needs its own field.
        for (e in Expression.entries) {
            val pose = poseFor(e)
            assertTrue(
                pose.browRotation.isFinite(),
                "$e has a non-finite brow rotation",
            )
        }
    }

    @Test
    fun onlyTheWinkIsAsymmetric() {
        // Every other expression moves both ears by equal and opposite amounts; the wink tips
        // one ear and closes one eye, which is the whole character of it.
        for (e in Expression.entries - Expression.Wink) {
            val pose = poseFor(e)
            assertClose(
                -pose.earRotationL,
                pose.earRotationR,
                message = "$e should move its ears antisymmetrically:",
            )
            assertEquals(1f, pose.pupilAlphaL, "$e should not close one eye")
        }

        val wink = poseFor(Expression.Wink)
        assertEquals(0f, wink.pupilAlphaL, "the wink closes the left eye")
        assertEquals(1f, wink.lidArcAlphaL, "…and draws an arc over it")
        assertEquals(0f, wink.lidArcAlphaR, "…but not over the right")
        assertEquals(0f, wink.earRotationR, "the wink tips only the left ear")
    }

    @Test
    fun deadIsFullyGreyedOut() {
        val dead = poseFor(Expression.Dead)
        assertEquals(HaloPalette.grey, dead.bodyColor)
        assertEquals(0f, dead.glowAlpha, "the lights are out")
        assertEquals(0f, dead.eyesOpenAlpha)
        assertEquals(1f, dead.deadXAlpha)
        assertEquals(1f, dead.mouthLensAlpha)
    }

    @Test
    fun mouthsMorphThroughTheChord() {
        // Smile to frown is the control point crossing the line between the corners — the
        // reason five of the six mouths can share one shape.
        val smile = MouthCurve.Smile
        val frown = MouthCurve.Frown
        val mid = lerp(smile, frown, 0.5f)

        assertClose(83f, mid.control.y, message = "control passes through the chord")
        assertClose(0f, mid.fill, message = "neither smile nor frown is filled")
        assertClose(1f, lerp(smile, MouthCurve.Open, 1f).fill, message = "the open mouth is filled")
    }

    @Test
    fun everyExpressionHasMotionDefined() {
        for (e in Expression.entries) {
            motionFor(e) // must not throw; `when` is exhaustive over the enum
        }
        assertEquals(1, motionFor(Expression.Wink).hopRepeats, "the wink hops exactly once")
        assertEquals(null, motionFor(Expression.Happy).hopRepeats, "excitement hops forever")
    }
}

/**
 * The looping curves. Every assertion is at a keyframe stop, where easing is the identity — so
 * these pin the transcribed values without depending on the interpolation in between.
 */
class CatCurvesTest {

    @Test
    fun blinkIsOpenUntilLateThenSnapsShut() {
        assertEquals(1f, CatCurves.blinkScaleY(0f))
        assertEquals(1f, CatCurves.blinkScaleY(0.5f), "still wide open halfway through")
        assertEquals(1f, CatCurves.blinkScaleY(0.94f))
        assertClose(0.08f, CatCurves.blinkScaleY(0.97f), message = "shut at 97%")
        assertEquals(1f, CatCurves.blinkScaleY(1f), "open again by the end")
    }

    @Test
    fun hopAnticipatesBeforeItLeaves() {
        // The dip at 68% is what makes it read as a jump rather than a float.
        val rest = CatCurves.hop(0f)
        assertClose(0f, rest.ty)
        assertClose(1f, rest.sx)

        val crouch = CatCurves.hop(0.68f)
        assertClose(2f, crouch.ty, message = "dips down to gather")
        assertTrue(crouch.sx > 1f && crouch.sy < 1f, "squashes wider and shorter when crouching")

        val peak = CatCurves.hop(0.82f)
        assertClose(-9f, peak.ty, message = "peak height")
        assertTrue(peak.sy > 1f && peak.sx < 1f, "stretches taller and narrower at the peak")

        assertClose(0f, CatCurves.hop(1f).ty, message = "back on the ground")
    }

    @Test
    fun hopSquashAndStretchAreInverse() {
        // Volume roughly preserved: wider means shorter, never both at once.
        for (p in listOf(0.68f, 0.82f, 0.92f)) {
            val h = CatCurves.hop(p)
            assertTrue(
                (h.sx - 1f) * (h.sy - 1f) <= 0f,
                "at phase $p the hop scales both axes the same way (sx=${h.sx} sy=${h.sy})",
            )
        }
    }

    @Test
    fun breathePeaksInTheMiddleAndRests() {
        assertClose(1f, CatCurves.breathe(0f).scale)
        assertClose(0f, CatCurves.breathe(0f).ty)
        assertClose(1.025f, CatCurves.breathe(0.5f).scale, message = "fullest at the midpoint")
        assertClose(-1f, CatCurves.breathe(0.5f).ty, message = "rises as it fills")
        assertClose(1f, CatCurves.breathe(1f).scale, message = "back to rest")
    }

    @Test
    fun driftGlancesAsideAndReturns() {
        assertEquals(0f, CatCurves.driftTx(0f))
        assertEquals(0f, CatCurves.driftTx(0.44f), "still looking straight ahead")
        assertClose(2.6f, CatCurves.driftTx(0.55f), message = "glanced across")
        assertClose(2.6f, CatCurves.driftTx(0.72f), message = "holds the glance")
        assertClose(0f, CatCurves.driftTx(1f), message = "back to centre")
    }

    @Test
    fun sweepsAreSymmetricAboutTheirMidpoint() {
        assertClose(-3f, CatCurves.lazyTx(0f))
        assertClose(3f, CatCurves.lazyTx(0.5f))
        assertClose(-3f, CatCurves.lazyTx(1f))

        assertClose(-1.2f, CatCurves.wobbleRotation(0f))
        assertClose(1.2f, CatCurves.wobbleRotation(0.5f))
        assertClose(-1.2f, CatCurves.wobbleRotation(1f))
    }

    @Test
    fun shakeStartsAndEndsAtRest() {
        // It must return to centre, or three repeats would walk the cat sideways.
        assertClose(0f, CatCurves.shakeTx(0f))
        assertClose(-3f, CatCurves.shakeTx(0.25f))
        assertClose(3f, CatCurves.shakeTx(0.75f))
        assertClose(0f, CatCurves.shakeTx(1f))
    }

    @Test
    fun puffRisesAndFadesOut() {
        val begin = CatCurves.puff(0f)
        assertClose(0.9f, begin.alpha)
        assertClose(0f, begin.ty)

        val end = CatCurves.puff(1f)
        assertClose(0f, end.alpha, message = "fully faded")
        assertClose(-12f, end.ty, message = "risen")
        assertTrue(end.scale > begin.scale, "grows as it dissipates")
    }

    @Test
    fun gleamAndTwinkleStayWithinTheirRange() {
        assertClose(0.42f, CatCurves.gleamAlpha(0f))
        assertClose(0.72f, CatCurves.gleamAlpha(0.5f))
        assertClose(0.42f, CatCurves.gleamAlpha(1f))

        assertClose(0.35f, CatCurves.twinkle(0f).alpha)
        assertClose(1f, CatCurves.twinkle(0.5f).alpha)
        assertClose(1.15f, CatCurves.twinkle(0.5f).scale)
    }

    @Test
    fun squashIsATrianglePeakingHalfway() {
        assertClose(1f, CatCurves.squash(0f).sx, message = "no squash at the start")
        assertClose(1f, CatCurves.squash(1f).sx, message = "none at the end")

        val peak = CatCurves.squash(0.5f)
        assertClose(1.06f, peak.sx, message = "widest halfway")
        assertClose(0.94f, peak.sy, message = "and shortest")
    }

    @Test
    fun everyCurveIsBoundedAcrossTheWholeCycle() {
        // A stray keyframe would show up as the cat flying off screen; catch it arithmetically.
        var p = 0f
        while (p <= 1f) {
            assertTrue(CatCurves.blinkScaleY(p) in 0f..1f, "blink out of range at $p")
            assertTrue(CatCurves.hop(p).ty in -9f..2f, "hop out of range at $p")
            assertTrue(CatCurves.gleamAlpha(p) in 0.42f..0.72f, "gleam out of range at $p")
            assertTrue(CatCurves.puff(p).alpha in 0f..0.9f, "puff alpha out of range at $p")
            assertTrue(abs(CatCurves.shakeTx(p)) <= 3f, "shake out of range at $p")
            p += 0.01f
        }
    }
}
