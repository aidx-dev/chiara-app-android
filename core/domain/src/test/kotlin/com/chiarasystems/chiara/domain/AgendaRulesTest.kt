package com.chiarasystems.chiara.domain

import com.chiarasystems.chiara.domain.AgendaRules.Violation
import com.chiarasystems.chiara.model.AppointmentStatus
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgendaRulesTest {
    // Same fixture as iOS tests: Friday 2026-10-30, 09:00 now.
    private val today = LocalDate.of(2026, 10, 30)
    private val now = 540
    private val monday = LocalDate.of(2026, 11, 2)
    private val sunday = LocalDate.of(2026, 11, 1)

    @Test fun slotsSkipLunchButKeepFifteenThirty() {
        assertEquals(510, AgendaRules.slots.first())
        assertEquals(1110, AgendaRules.slots.last())
        assertFalse(AgendaRules.slots.contains(840))
        assertFalse(AgendaRules.slots.contains(900))
        assertTrue(AgendaRules.slots.contains(930))
    }

    @Test fun violationsFollowIosOrder() {
        fun v(day: LocalDate, start: Int, dur: Int = 30, busy: List<AgendaRules.Busy> = emptyList()) =
            AgendaRules.violation(day, start, dur, busy, today, now)
        assertEquals(Violation.Past, v(today.minusDays(1), 600))
        assertEquals(Violation.Sunday, v(sunday, 600))
        assertEquals(Violation.InvalidDuration, v(monday, 600, 20))
        assertEquals(Violation.OutsideHours, v(monday, 480))
        assertEquals(Violation.OutsideHours, v(monday, 1140))
        assertEquals(Violation.LunchBreak, v(monday, 870))
        assertEquals(Violation.Past, v(today, 510))
        assertEquals(Violation.Overlap, v(monday, 600, 30, listOf(AgendaRules.Busy(590, 620))))
        assertNull(v(monday, 600))
        // Only the start is checked: a 60 min visit at 18:30 or through lunch is valid.
        assertNull(v(monday, 1110, 60))
        assertNull(v(monday, 810, 60))
    }

    @Test fun freeStartsAreClosestThenChronological() {
        val free = AgendaRules.freeStarts(monday, 30, emptyList(), today, now, near = 600, limit = 4)
        // 600 (0), 570/630 (30), then 540 vs 660 tie (60) -> earlier slot wins.
        assertEquals(listOf(540, 570, 600, 630), free)
    }

    @Test fun statusNormalization() {
        assertEquals(AppointmentStatus.NoShow, AppointmentStatus.normalize("no_show"))
        assertEquals(AppointmentStatus.Canceled, AppointmentStatus.normalize("Cancelled"))
        assertEquals(AppointmentStatus.Confirmed, AppointmentStatus.normalize("CONFIRMED"))
        assertEquals(AppointmentStatus.Scheduled, AppointmentStatus.normalize(null))
        assertEquals(AppointmentStatus.Scheduled, AppointmentStatus.normalize("whatever"))
        assertFalse(AppointmentStatus.Canceled.occupiesSlot)
        assertTrue(AppointmentStatus.NoShow.occupiesSlot)
    }

    @Test fun uuidV7HasVersionAndVariant() {
        val u = UuidV7.generate(1_700_000_000_000L)
        assertEquals(7, u.version())
        assertEquals(2, u.variant())
        assertEquals(1_700_000_000_000L, u.mostSignificantBits ushr 16)
    }
}
