package vn.servio.customer.ui.request

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime

class ScheduleSlotsTest {

    private val today = LocalDate.of(2026, 10, 29)

    private fun vn(date: LocalDate, hour: Int, minute: Int) = date.atTime(hour, minute).atZone(ScheduleSlots.ZONE)

    // The slot must start at least 30 minutes from now.
    @Test
    fun minimumLead() {
        assertTrue(ScheduleSlots.isAvailable(today, 10, vn(today, 9, 30))) // now + 30 min
        assertFalse(ScheduleSlots.isAvailable(today, 10, vn(today, 9, 31))) // now + 29 min
    }

    // ... and at most 30 days from now.
    @Test
    fun maximumAhead() {
        val lastDay = today.plusDays(30)
        assertTrue(ScheduleSlots.isAvailable(lastDay, 8, vn(today, 8, 0))) // exactly now + 30 days
        assertFalse(ScheduleSlots.isAvailable(lastDay, 8, vn(today, 7, 59))) // now + 30 days + 1 min
    }

    @Test
    fun todayOverAfterLastSlot() {
        val now = vn(today, 17, 31) // 18:00 is 29 minutes away
        assertFalse(ScheduleSlots.hasAvailableSlot(today, now))
        assertTrue(ScheduleSlots.hasAvailableSlot(today.plusDays(1), now))
        assertEquals(today.plusDays(1), ScheduleSlots.firstAvailableDay(now))

        val days = ScheduleSlots.days(now)
        assertEquals(31, days.size)
        assertEquals(today, days.first())
        assertEquals(today.plusDays(30), days.last())
    }

    @Test
    fun todayStillOpenBeforeLastSlot() {
        val now = vn(today, 17, 30)
        assertTrue(ScheduleSlots.hasAvailableSlot(today, now))
        assertEquals(today, ScheduleSlots.firstAvailableDay(now))
    }

    // "Now" from a device set to another zone is the same instant: days and slots follow Vietnam time.
    @Test
    fun nowInAnotherZone() {
        val nowUtc = ZonedDateTime.of(2026, 10, 29, 17, 0, 0, 0, ZoneOffset.UTC) // 00:00 on the 30th in Vietnam
        assertEquals(today.plusDays(1), ScheduleSlots.days(nowUtc).first())
        assertTrue(ScheduleSlots.isAvailable(today.plusDays(1), 8, nowUtc))
        assertFalse(ScheduleSlots.hasAvailableSlot(today, nowUtc))
    }
}
