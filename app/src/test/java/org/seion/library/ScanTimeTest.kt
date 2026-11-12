package org.seion.library

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/** Settings › Rescan now says "Last scan today 09:12", "yesterday 21:40" or the date, by the local calendar day. */
class ScanTimeTest {
    private val zone = ZoneId.of("America/Sao_Paulo")

    private fun at(d: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 11, d, h, m, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun `today, yesterday and earlier follow the local day, not 24 hours`() {
        val now = at(10, 0, 30)
        assertEquals(ScanDay.TODAY, ScanTime.day(at(10, 0, 5), now, zone))
        assertEquals(ScanDay.YESTERDAY, ScanTime.day(at(9, 23, 59), now, zone))
        assertEquals(ScanDay.YESTERDAY, ScanTime.day(at(9, 0, 1), now, zone))
        assertEquals(ScanDay.EARLIER, ScanTime.day(at(8, 23, 59), now, zone))
    }
}
