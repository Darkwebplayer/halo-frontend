package dev.infyplus.halo

import dev.infyplus.halo.ui.agoLabel
import dev.infyplus.halo.ui.dayLabel
import dev.infyplus.halo.ui.describeRecurrence
import dev.infyplus.halo.ui.hasPassed
import dev.infyplus.halo.ui.localDateKey
import dev.infyplus.halo.ui.timeLabel
import dev.infyplus.halo.ui.whenLabel
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The date labels, pinned to Asia/Kolkata.
 *
 * +05:30 on purpose: a whole-hour zone hides a dropped or mis-added half hour, and it is the zone
 * the backend's own `time-check.ts` uses for the same reason. These live in jvmTest rather than
 * commonTest because the labels read the device's default zone, and a test that depends on the
 * developer's machine settings is not a test.
 */
class DatesTest {
    private val original = TimeZone.getDefault()

    @BeforeTest
    fun pin() = TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))

    @AfterTest
    fun restore() = TimeZone.setDefault(original)

    /** 2026-08-02T06:00:00Z is 11:30 on the 2nd, IST. */
    private val now = epochMillisOf("2026-08-02T06:00:00Z")!!

    @Test
    fun `the alerts regression - a real time, not a slice of the string`() {
        // The bug this file exists for: `sentAt.takeLast(14).take(5)` printed "T06:3" for exactly
        // this input, because the slice was written for a 25-char +00:00 string and the server
        // sends a 24-char .000Z one.
        assertEquals("6:30 AM", timeLabel("2026-08-02T01:00:00.000Z"))
    }

    @Test
    fun `half-hour offsets survive the conversion`() {
        // 03:00Z + 05:30 = 08:30 local. A whole-hour zone would pass even if the minutes were lost.
        assertEquals("Today 8:30 AM", whenLabel("2026-08-02T03:00:00.000Z", now))
    }

    @Test
    fun `neighbouring days are named, not dated`() {
        assertEquals("Today 8:30 AM", whenLabel("2026-08-02T03:00:00.000Z", now))
        assertEquals("Tomorrow 8:30 AM", whenLabel("2026-08-03T03:00:00.000Z", now))
        assertEquals("Yesterday 8:30 AM", whenLabel("2026-08-01T03:00:00.000Z", now))
        assertEquals("5 Aug 8:30 AM", whenLabel("2026-08-05T03:00:00.000Z", now))
    }

    @Test
    fun `the local day is what counts, not the UTC one`() {
        // 20:00Z on the 2nd is already 01:30 on the 3rd in IST — the case a naive UTC read gets
        // wrong, and the one that decides whether tonight's reminder says Today or Tomorrow.
        assertEquals("Tomorrow 1:30 AM", whenLabel("2026-08-02T20:00:00.000Z", now))
    }

    @Test
    fun `midnight and noon do not collide in twelve-hour time`() {
        assertEquals("12:00 AM", timeLabel("2026-08-01T18:30:00.000Z")) // 00:00 IST
        assertEquals("12:00 PM", timeLabel("2026-08-02T06:30:00.000Z")) // 12:00 IST
    }

    @Test
    fun `no date says nothing rather than something wrong`() {
        assertNull(whenLabel(null, now))
        assertNull(whenLabel("not a date", now))
        assertNull(timeLabel(""))
        assertNull(agoLabel(null, now))
    }

    @Test
    fun `ago is coarse and reads backwards`() {
        assertEquals("just now", agoLabel("2026-08-02T05:59:40.000Z", now))
        assertEquals("18m ago", agoLabel("2026-08-02T05:42:00.000Z", now))
        assertEquals("3h ago", agoLabel("2026-08-02T03:00:00.000Z", now))
        assertEquals("2d ago", agoLabel("2026-07-31T06:00:00.000Z", now))
    }

    @Test
    fun `day headings drop the time`() {
        assertEquals("Today", dayLabel("2026-08-02T03:00:00.000Z", now))
        assertEquals("5 Aug", dayLabel("2026-08-05T03:00:00.000Z", now))
    }

    @Test
    fun `the cache key is the local date, not the UTC one`() {
        // 20:00Z on the 2nd is the 3rd in IST, so a summary fetched then belongs to the 3rd.
        assertEquals("2026-08-02", localDateKey(now))
        assertEquals("2026-08-03", localDateKey(epochMillisOf("2026-08-02T20:00:00Z")!!))
    }

    @Test
    fun `hasPassed decides which summary tab opens`() {
        assertTrue(hasPassed("07:00", now))   // 11:30 local is past 07:00
        assertTrue(!hasPassed("20:00", now))
        assertTrue(hasPassed("11:30", now))   // inclusive at the minute it strikes
        assertTrue(!hasPassed("nonsense", now))
    }

    @Test
    fun `recurrence reads as a sentence`() {
        assertEquals("every Mon, Fri at 8:00 AM", describeRecurrence("fixed", "1,5", null, 8))
        assertEquals("every day at 7:00 AM", describeRecurrence("fixed", "0,1,2,3,4,5,6", null, 7))
        assertEquals("every 3 days at 9:00 AM", describeRecurrence("fixed", null, 3, 9))
        assertEquals("when the last is done, at 6:00 PM", describeRecurrence("relative", null, null, 18))
    }
}
