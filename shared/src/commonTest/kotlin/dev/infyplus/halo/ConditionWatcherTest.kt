package dev.infyplus.halo

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Condition evaluation moved from the Worker to the device when push was retired, so the
 * threshold logic is asserted here rather than in the server's smoke suite.
 */
class ConditionWatcherTest {

    private val raining = Readings(temperature = 18.0, precipitation = 2.4)
    private val dry = Readings(temperature = 31.0, precipitation = 0.0)

    @Test
    fun greaterThanFiresOnlyAboveTheThreshold() {
        val wet = WeatherCondition("precipitation", "gt", 0.0)
        assertTrue(ConditionWatcher.isMet(wet, raining))
        assertFalse(ConditionWatcher.isMet(wet, dry))
    }

    @Test
    fun lessThanFiresOnlyBelowTheThreshold() {
        val cold = WeatherCondition("temperature", "lt", 20.0)
        assertTrue(ConditionWatcher.isMet(cold, raining))
        assertFalse(ConditionWatcher.isMet(cold, dry))
    }

    /** Exactly at the threshold is not "above" it — an off-by-one here fires a day early. */
    @Test
    fun thresholdItselfDoesNotFire() {
        assertFalse(ConditionWatcher.isMet(WeatherCondition("precipitation", "gt", 2.4), raining))
        assertFalse(ConditionWatcher.isMet(WeatherCondition("temperature", "lt", 18.0), raining))
    }

    /**
     * A metric we have no reading for must not fire. Returning "true" on missing data would
     * mean a weather reminder going off because the reading was absent.
     */
    @Test
    fun missingReadingNeverFires() {
        val noData = Readings(temperature = null, precipitation = null)
        assertFalse(ConditionWatcher.isMet(WeatherCondition("precipitation", "gt", 0.0), noData))
        assertFalse(ConditionWatcher.isMet(WeatherCondition("humidity", "gt", 50.0), raining))
    }
}
