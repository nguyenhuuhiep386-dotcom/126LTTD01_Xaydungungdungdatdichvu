package vn.servio.customer.ui.request

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.servio.customer.data.model.PostingRules
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime

class ScheduleSlotsTest {

    private val today = LocalDate.of(2026, 10, 29)

    /** posting of #107 as seeded. */
    private val rules = PostingRules(windowHours = 2, minLeadMinutes = 30, maxLeadDays = 30, expireHours = 24)

    private fun vn(date: LocalDate, hour: Int, minute: Int) = date.atTime(hour, minute).atZone(ScheduleSlots.ZONE)

    // The slot must start at least minLeadMinutes from now.
    @Test
    fun minimumLead() {
        assertTrue(ScheduleSlots.isAvailable(today, 10, vn(today, 9, 30), rules)) // now + 30 min
        assertFalse(ScheduleSlots.isAvailable(today, 10, vn(today, 9, 31), rules)) // now + 29 min
    }

    // ... and at most maxLeadDays from now.
    @Test
    fun maximumAhead() {
        val lastDay = today.plusDays(30)
        assertTrue(ScheduleSlots.isAvailable(lastDay, 8, vn(today, 8, 0), rules)) // exactly now + 30 days
        assertFalse(ScheduleSlots.isAvailable(lastDay, 8, vn(today, 7, 59), rules)) // now + 30 days + 1 min
    }

    @Test
    fun todayOverAfterLastSlot() {
        val now = vn(today, 17, 31) // 18:00 is 29 minutes away
        assertFalse(ScheduleSlots.hasAvailableSlot(today, now, rules))
        assertTrue(ScheduleSlots.hasAvailableSlot(today.plusDays(1), now, rules))
        assertEquals(today.plusDays(1), ScheduleSlots.firstAvailableDay(now, rules))

        val days = ScheduleSlots.days(now, rules)
        assertEquals(31, days.size)
        assertEquals(today, days.first())
        assertEquals(today.plusDays(30), days.last())
    }

    @Test
    fun todayStillOpenBeforeLastSlot() {
        val now = vn(today, 17, 30)
        assertTrue(ScheduleSlots.hasAvailableSlot(today, now, rules))
        assertEquals(today, ScheduleSlots.firstAvailableDay(now, rules))
    }

    // "Now" from a device set to another zone is the same instant: days and slots follow Vietnam time.
    @Test
    fun nowInAnotherZone() {
        val nowUtc = ZonedDateTime.of(2026, 10, 29, 17, 0, 0, 0, ZoneOffset.UTC) // 00:00 on the 30th in Vietnam
        assertEquals(today.plusDays(1), ScheduleSlots.days(nowUtc, rules).first())
        assertTrue(ScheduleSlots.isAvailable(today.plusDays(1), 8, nowUtc, rules))
        assertFalse(ScheduleSlots.hasAvailableSlot(today, nowUtc, rules))
    }

    // The limits are the server's (AW-15 may change them): the app follows them without a new build.
    @Test
    fun limitsFollowTheServer() {
        val strict = PostingRules(windowHours = 3, minLeadMinutes = 90, maxLeadDays = 7, expireHours = 12)
        assertTrue(ScheduleSlots.isAvailable(today, 10, vn(today, 8, 30), strict)) // now + 90 min
        assertFalse(ScheduleSlots.isAvailable(today, 10, vn(today, 8, 31), strict)) // now + 89 min
        val days = ScheduleSlots.days(vn(today, 8, 0), strict)
        assertEquals(8, days.size) // today + 7 days
        assertFalse(ScheduleSlots.isAvailable(today.plusDays(7), 10, vn(today, 8, 0), strict)) // 7 days + 2 h ahead
    }
}
