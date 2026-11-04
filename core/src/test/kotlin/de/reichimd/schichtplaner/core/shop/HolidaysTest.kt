package de.reichimd.schichtplaner.core.shop

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HolidaysTest {

    private fun d(y: Int, m: Int, t: Int) = LocalDate.of(y, m, t)

    @Test
    fun ostersonntagBekannteJahre() {
        assertEquals(d(2024, 3, 31), Holidays.easterSunday(2024))
        assertEquals(d(2025, 4, 20), Holidays.easterSunday(2025))
        assertEquals(d(2026, 4, 5), Holidays.easterSunday(2026))
        assertEquals(d(2027, 3, 28), Holidays.easterSunday(2027))
        assertEquals(d(2028, 4, 16), Holidays.easterSunday(2028))
        assertEquals(d(2038, 4, 25), Holidays.easterSunday(2038)) // spätestmöglicher Termin
        assertEquals(d(2285, 3, 22), Holidays.easterSunday(2285)) // frühestmöglicher Termin
    }

    @Test
    fun sachsenAnhalt2026() {
        val tage = Holidays.of(2026, FederalState.ST).associate { it.date to it.id }
        assertEquals(
            mapOf(
                d(2026, 1, 1) to HolidayId.NEUJAHR,
                d(2026, 1, 6) to HolidayId.DREI_KOENIGE,
                d(2026, 4, 3) to HolidayId.KARFREITAG,
                d(2026, 4, 6) to HolidayId.OSTERMONTAG,
                d(2026, 5, 1) to HolidayId.TAG_DER_ARBEIT,
                d(2026, 5, 14) to HolidayId.HIMMELFAHRT,
                d(2026, 5, 25) to HolidayId.PFINGSTMONTAG,
                d(2026, 10, 3) to HolidayId.EINHEIT,
                d(2026, 10, 31) to HolidayId.REFORMATION,
                d(2026, 12, 25) to HolidayId.WEIHNACHTEN_1,
                d(2026, 12, 26) to HolidayId.WEIHNACHTEN_2,
            ),
            tage,
        )
    }

    @Test
    fun laenderUnterschiede() {
        fun hat(state: FederalState, id: HolidayId, year: Int = 2026) = Holidays.of(year, state).any { it.id == id }
        assertTrue(hat(FederalState.BY, HolidayId.FRONLEICHNAM))
        assertFalse(hat(FederalState.ST, HolidayId.FRONLEICHNAM))
        assertTrue(hat(FederalState.NW, HolidayId.ALLERHEILIGEN))
        assertFalse(hat(FederalState.NW, HolidayId.REFORMATION))
        assertTrue(hat(FederalState.BE, HolidayId.FRAUENTAG))
        assertTrue(hat(FederalState.TH, HolidayId.WELTKINDERTAG))
        assertTrue(hat(FederalState.SL, HolidayId.MARIAE_HIMMELFAHRT))
        assertTrue(hat(FederalState.BB, HolidayId.OSTERSONNTAG))
        assertFalse(hat(FederalState.ST, HolidayId.OSTERSONNTAG))
        assertTrue(hat(FederalState.HE, HolidayId.PFINGSTSONNTAG))
        // Buß- und Bettag: Mittwoch vor dem 23.11.
        assertEquals(d(2026, 11, 18), Holidays.of(2026, FederalState.SN).first { it.id == HolidayId.BUSS_UND_BETTAG }.date)
        assertEquals(d(2028, 11, 22), Holidays.of(2028, FederalState.SN).first { it.id == HolidayId.BUSS_UND_BETTAG }.date)
        // Bundesweit 9 Feiertage.
        assertEquals(9, Holidays.of(2026, FederalState.HH).size - 1) // Hamburg: + Reformationstag
    }

    @Test
    fun zeitraumUeberJahreswechsel() {
        val tage = Holidays.between(d(2026, 12, 20), d(2027, 1, 10), FederalState.ST)
        assertEquals(listOf(d(2026, 12, 25), d(2026, 12, 26), d(2027, 1, 1), d(2027, 1, 6)), tage.keys.sorted())
        assertEquals(emptyMap(), Holidays.between(d(2027, 1, 10), d(2027, 1, 9), FederalState.ST))
    }

    @Test
    fun kuerzereTage() {
        assertEquals(ShortDay.HEILIGABEND, ShortDay.on(d(2026, 12, 24)))
        assertEquals(ShortDay.SILVESTER, ShortDay.on(d(2027, 12, 31)))
        assertEquals(null, ShortDay.on(d(2026, 12, 25)))
    }

    @Test
    fun kuerzelEindeutig() {
        assertEquals(FederalState.entries.size, FederalState.entries.map { it.code }.toSet().size)
        assertEquals(HolidayId.entries.size, HolidayId.entries.map { it.code }.toSet().size)
        FederalState.entries.forEach { assertEquals(it, FederalState.of(it.code)) }
        HolidayId.entries.forEach { assertEquals(it, HolidayId.of(it.code)) }
    }
}
