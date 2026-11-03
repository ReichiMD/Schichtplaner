package de.reichimd.schichtplaner.core

import de.reichimd.schichtplaner.core.TestData.MONDAY_START
import de.reichimd.schichtplaner.core.TestData.shop
import de.reichimd.schichtplaner.core.TestData.t
import java.time.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ShiftCalendarTest {

    @Test
    fun `Spaetschicht endet 30 Minuten nach Ladenschluss`() {
        val week = ShiftCalendar.shifts(MONDAY_START, 7, shop())
        val monday = week.single { it.date == MONDAY_START && it.kind == ShiftKind.LATE }
        assertEquals(t(19, 30), monday.end.toLocalTime())
        val saturday = week.single { it.date.dayOfWeek == DayOfWeek.SATURDAY && it.kind == ShiftKind.LATE }
        assertEquals(t(16, 30), saturday.end.toLocalTime())
    }

    @Test
    fun `Vorlagen gelten nur an ihren Wochentagen`() {
        val week = ShiftCalendar.shifts(MONDAY_START, 7, shop())
        assertEquals(3 * 6 + 2, week.size)
        assertTrue(week.none { it.date.dayOfWeek == DayOfWeek.SUNDAY && it.kind == ShiftKind.LATE })
    }

    @Test
    fun `Feiertag nutzt Feiertagszeiten und Feiertagsvorlagen`() {
        val holiday = MONDAY_START.plusDays(2)
        val shifts = ShiftCalendar.shifts(holiday, 1, shop(holidays = setOf(holiday)))
        assertEquals(setOf("fa", "fb"), shifts.map { it.template.id }.toSet())
    }

    @Test
    fun `geschlossene Tage haben keine Schichten`() {
        val closedSunday = shop().let { it.copy(openingHours = it.openingHours - DayOfWeek.SUNDAY) }
        val week = ShiftCalendar.shifts(MONDAY_START, 7, closedSunday)
        assertTrue(week.none { it.date.dayOfWeek == DayOfWeek.SUNDAY })
    }

    @Test
    fun `mehrere Personen pro Vorlage ergeben mehrere Schichten`() {
        val s = shop().let { it.copy(templates = listOf(TestData.earlyA.copy(staff = 2))) }
        assertEquals(2, ShiftCalendar.shifts(MONDAY_START, 1, s).size)
    }

    @Test
    fun `Schicht die vor ihrem Beginn endet wird abgelehnt`() {
        val broken = TestData.late.copy(start = ShiftStart.At(t(18)), end = ShiftEnd.At(t(17)))
        val s = shop().let { it.copy(templates = listOf(broken)) }
        assertFailsWith<IllegalArgumentException> { ShiftCalendar.shifts(MONDAY_START, 1, s) }
    }

    @Test
    fun `ueber sechs Stunden werden 30 Minuten Pause abgezogen`() {
        assertEquals(360, Shift.paidMinutes(360))
        assertEquals(390, Shift.paidMinutes(420))
        assertEquals(480, Shift.paidMinutes(510))
    }
}
