package dev.adminos.api.domain.scheduling

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScheduleTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val nineIst = Schedule.Daily(LocalTime.of(9, 0), ist)

    @Test
    fun `after today's time the slot is today`() {
        // 10:00 IST on 16 Oct = 04:30Z
        assertEquals(Instant.parse("2026-10-16T03:30:00Z"), nineIst.latestSlot(Instant.parse("2026-10-16T04:30:00Z")))
    }

    @Test
    fun `before today's time the slot is yesterday's`() {
        // 08:59 IST on 16 Oct
        assertEquals(Instant.parse("2026-10-15T03:30:00Z"), nineIst.latestSlot(Instant.parse("2026-10-16T03:29:00Z")))
    }

    @Test
    fun `exactly at the time the slot has started`() {
        assertEquals(Instant.parse("2026-10-16T03:30:00Z"), nineIst.latestSlot(Instant.parse("2026-10-16T03:30:00Z")))
    }

    @Test
    fun `the local date decides the day, not UTC`() {
        // 01:00 IST on 17 Oct is still 16 Oct in UTC; the 09:00 slot that has started is 16 Oct's.
        assertEquals(Instant.parse("2026-10-16T03:30:00Z"), nineIst.latestSlot(Instant.parse("2026-10-16T19:30:00Z")))
    }

    @Test
    fun `a time skipped by daylight saving moves to the next valid time`() {
        val ny = Schedule.Daily(LocalTime.of(2, 30), ZoneId.of("America/New_York"))
        // 8 Mar 2026: clocks jump 02:00 → 03:00 EST→EDT, so 02:30 becomes 03:30 EDT = 07:30Z.
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), ny.latestSlot(Instant.parse("2026-03-08T12:00:00Z")))
    }

    @Test
    fun `every-period slots align to the epoch so all instances agree`() {
        val hourly = Schedule.Every(Duration.ofHours(1))
        assertEquals(Instant.parse("2026-10-16T04:00:00Z"), hourly.latestSlot(Instant.parse("2026-10-16T04:59:59Z")))
        assertEquals(Instant.parse("2026-10-16T05:00:00Z"), hourly.latestSlot(Instant.parse("2026-10-16T05:00:00Z")))
    }

    @Test
    fun `a period shorter than a minute is rejected`() {
        assertFailsWith<IllegalArgumentException> { Schedule.Every(Duration.ofSeconds(59)) }
    }

    @Test
    fun `schedules describe themselves for logs`() {
        assertEquals("daily at 09:00 Asia/Kolkata", nineIst.describe())
        assertTrue(Schedule.Every(Duration.ofMinutes(5)).describe().startsWith("every PT5M"))
    }
}
