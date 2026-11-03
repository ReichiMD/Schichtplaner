package de.reichimd.schichtplaner.core.wishes

import de.reichimd.schichtplaner.core.Absence
import de.reichimd.schichtplaner.core.AbsenceType.DAY_OFF_WISH
import de.reichimd.schichtplaner.core.AbsenceType.SICK
import de.reichimd.schichtplaner.core.AbsenceType.VACATION
import de.reichimd.schichtplaner.core.Employee
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WishesTest {

    private val d9 = LocalDate.of(2026, 11, 9)
    private val d10 = LocalDate.of(2026, 11, 10)

    @Test
    fun antippenTraegtEinUndNochmalAntippenEntfernt() {
        val eins = Wishes.toggle(emptyList(), "a", d9, VACATION)
        assertEquals(listOf(Absence("a", d9, VACATION)), eins)
        assertEquals(emptyList(), Wishes.toggle(eins, "a", d9, VACATION))
    }

    @Test
    fun andereArtErsetztDenEintrag() {
        val urlaub = Wishes.toggle(emptyList(), "a", d9, VACATION)
        val frei = Wishes.toggle(urlaub, "a", d9, DAY_OFF_WISH)
        assertEquals(listOf(Absence("a", d9, DAY_OFF_WISH)), frei)
    }

    @Test
    fun anderePersonenBleibenUnberuehrt() {
        val start = listOf(Absence("b", d9, SICK))
        val danach = Wishes.toggle(start, "a", d9, VACATION)
        assertEquals(SICK, Wishes.on(danach, "b", d9))
        assertEquals(VACATION, Wishes.on(danach, "a", d9))
        assertNull(Wishes.on(danach, "a", d10))
    }

    @Test
    fun normalisierenEntferntDoppelteUndSortiert() {
        val roh = listOf(Absence("b", d10, VACATION), Absence("a", d9, VACATION), Absence("a", d9, SICK))
        assertEquals(listOf(Absence("a", d9, SICK), Absence("b", d10, VACATION)), Wishes.normalize(roh))
    }

    @Test
    fun personLoeschenNimmtIhreEintraegeMit() {
        val e = listOf(Absence("a", d9, VACATION), Absence("b", d9, VACATION), Absence("a", d10, SICK))
        assertEquals(listOf(Absence("b", d9, VACATION)), Wishes.removeEmployee(e, "a"))
        assertEquals(2, Wishes.ofEmployee(e, "a").size)
    }

    @Test
    fun zaehlenProMonat() {
        val e = listOf(
            Absence("a", d9, VACATION),
            Absence("a", d10, VACATION),
            Absence("a", LocalDate.of(2026, 11, 20), DAY_OFF_WISH),
            Absence("a", LocalDate.of(2026, 12, 1), VACATION),
            Absence("b", d9, SICK),
        )
        val n = Wishes.countInMonth(e, "a", YearMonth.of(2026, 11))
        assertEquals(mapOf(VACATION to 2, DAY_OFF_WISH to 1, SICK to 0), n)
        assertEquals("2 Tage Urlaub, 1 Frei-Wunsch", Wishes.describeCounts(n))
        assertEquals("Nichts eingetragen", Wishes.describeCounts(Wishes.countInMonth(e, "c", YearMonth.of(2026, 11))))
        assertEquals("1 Tag krank", Wishes.describeCounts(Wishes.countInMonth(e, "b", YearMonth.of(2026, 11))))
    }

    @Test
    fun monatsrasterBeginntAmMontag() {
        // 1. November 2026 ist ein Sonntag: sechs leere Felder davor.
        val nov = Wishes.monthGrid(YearMonth.of(2026, 11))
        assertEquals(6, nov.takeWhile { it == null }.size)
        assertEquals(LocalDate.of(2026, 11, 1), nov[6])
        assertEquals(0, nov.size % 7)
        assertEquals(30, nov.count { it != null })

        // Februar 2027 beginnt am Montag und hat genau vier Wochen.
        val feb = Wishes.monthGrid(YearMonth.of(2027, 2))
        assertEquals(28, feb.size)
        assertEquals(LocalDate.of(2027, 2, 1), feb[0])
    }

    @Test
    fun blaetternIstBegrenzt() {
        val heute = LocalDate.of(2026, 11, 3)
        assertTrue(Wishes.canGoBack(YearMonth.of(2026, 11), heute))
        assertFalse(Wishes.canGoBack(YearMonth.of(2026, 10), heute))
        assertTrue(Wishes.canGoForward(YearMonth.of(2027, 10), heute))
        assertFalse(Wishes.canGoForward(YearMonth.of(2027, 11), heute))
    }

    @Test
    fun startMonatIstDerMonatDesPlanbeginns() {
        assertEquals(YearMonth.of(2026, 12), Wishes.startMonth(LocalDate.of(2026, 12, 1)))
    }

    @Test
    fun fuerDenPlanNurZeitraumUndTeam() {
        val team = listOf(Employee("a", "Anna", 120))
        val e = listOf(
            Absence("a", d9, VACATION),
            Absence("a", d10, VACATION),
            Absence("a", LocalDate.of(2026, 11, 8), VACATION),
            Absence("weg", d9, VACATION),
        )
        assertEquals(listOf(Absence("a", d9, VACATION), Absence("a", d10, VACATION)), Wishes.forPlan(e, team, d9, d10))
    }

    @Test
    fun kuerzelWieAufDemPapier() {
        assertEquals(listOf("U", "Fr", "K"), Wishes.TYPES.map(Wishes::code))
    }
}
