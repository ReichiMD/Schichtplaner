package de.reichimd.schichtplaner.core

import de.reichimd.schichtplaner.core.TestData.MONDAY_START
import de.reichimd.schichtplaner.core.TestData.plan
import de.reichimd.schichtplaner.core.TestData.request
import de.reichimd.schichtplaner.core.TestData.team
import java.time.DayOfWeek
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlannerTest {

    @Test
    fun `mit genug Personal ist jede Schicht besetzt`() {
        val p = plan(request())
        assertEquals(emptyList(), p.unfilled)
    }

    @Test
    fun `Urlaub und Krankheit werden nie verplant`() {
        val absences = (0L until 7L).map { Absence("a", MONDAY_START.plusDays(it), AbsenceType.VACATION) } +
            Absence("b", MONDAY_START.plusDays(2), AbsenceType.SICK)
        val p = plan(request(absences = absences))
        assertTrue(p.shiftsOf("a").none { it.date < MONDAY_START.plusDays(7) })
        assertTrue(p.shiftsOf("b").none { it.date == MONDAY_START.plusDays(2) })
        assertEquals(emptyList(), p.unfilled)
    }

    @Test
    fun `gesperrte Wochentage werden eingehalten`() {
        val employees = team().map {
            if (it.id == "e") it.copy(blockedWeekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)) else it
        }
        val p = plan(request(employees = employees))
        assertTrue(p.shiftsOf("e").none { it.date.dayOfWeek in setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY) })
    }

    @Test
    fun `wer nicht frueh kann bekommt keine Fruehschicht`() {
        val employees = team().map { if (it.id == "e") it.copy(canEarly = false) else it }
        val p = plan(request(employees = employees))
        assertTrue(p.shiftsOf("e").all { it.kind == ShiftKind.LATE })
    }

    @Test
    fun `nach der Spaetschicht keine Fruehschicht am naechsten Morgen`() {
        // Spät endet 19:30, Früh beginnt 05:00 oder 06:00: weniger als 11 h Ruhe.
        val p = plan(request())
        for (e in team()) {
            val shifts = p.shiftsOf(e.id).associateBy { it.date }
            for ((date, s) in shifts) {
                if (s.kind != ShiftKind.LATE || date.dayOfWeek == DayOfWeek.SATURDAY) continue
                val next = shifts[date.plusDays(1)]
                assertTrue(next == null || next.kind != ShiftKind.EARLY, "${e.name} am $date")
            }
        }
    }

    @Test
    fun `hoechstens sechs Tage am Stueck auch ueber den Zeitraum hinweg`() {
        // Nur drei Personen: Druck auf viele Tage am Stück.
        val employees = listOf(
            Employee("a", "Anna", 200),
            Employee("b", "Ben", 200),
            Employee("c", "Clara", 200),
        )
        val p = plan(request(employees = employees, workedDaysBefore = mapOf("a" to 4)))
        // assertHardRules hat die Läufe schon geprüft; Anna darf höchstens die ersten zwei Tage arbeiten.
        val annaFirstThree = p.shiftsOf("a").map { it.date }.filter { it < MONDAY_START.plusDays(3) }
        assertTrue(annaFirstThree.size <= 2)
    }

    @Test
    fun `zu wenig Personal ergibt unbesetzte Schichten statt Fehler`() {
        val p = plan(request(employees = listOf(Employee("a", "Anna", 120))))
        assertTrue(p.unfilled.isNotEmpty())
        assertTrue(p.assignments.isNotEmpty())
    }

    @Test
    fun `Frei-Wuensche werden erfuellt wenn es geht`() {
        val wishes = listOf(
            Absence("a", MONDAY_START.plusDays(5), AbsenceType.DAY_OFF_WISH),
            Absence("a", MONDAY_START.plusDays(6), AbsenceType.DAY_OFF_WISH),
            Absence("c", MONDAY_START.plusDays(1), AbsenceType.DAY_OFF_WISH),
        )
        val p = plan(request(absences = wishes))
        for (w in wishes) {
            assertTrue(p.shiftsOf(w.employeeId).none { it.date == w.date }, "Wunsch ${w.employeeId} am ${w.date}")
        }
    }

    @Test
    fun `Aushilfen nur wenn es ohne sie nicht geht`() {
        val withBackup = team() + Employee("x", "Xenia", 40, isBackup = true)
        assertTrue(plan(request(employees = withBackup)).shiftsOf("x").isEmpty())

        // Montag sind alle bis auf eine Person im Urlaub: Aushilfe muss ran.
        val monday = MONDAY_START
        val absences = team().drop(1).map { Absence(it.id, monday, AbsenceType.VACATION) }
        val p = plan(request(employees = withBackup, absences = absences))
        assertTrue(p.shiftsOf("x").any { it.date == monday })
        assertEquals(emptyList(), p.unfilled.filter { it.date == monday }.drop(1))
    }

    @Test
    fun `Monatsstunden werden ungefaehr getroffen`() {
        val days = 28
        val r = request(days = days)
        val p = plan(r)
        for (e in r.employees) {
            val target = e.monthlyHours * 60.0 * days / 30.4375
            val actual = p.paidMinutesOf(e.id)
            // Toleranz: eine Schicht (rund 6,5 h) nach oben oder unten.
            assertTrue(abs(actual - target) <= 7 * 60, "${e.name}: ${actual / 60.0} h statt ${target / 60.0} h")
        }
    }

    @Test
    fun `Wochenenden werden gleichmaessig verteilt`() {
        val equalTeam = listOf("a", "b", "c", "d", "e", "f").map { Employee(it, it.uppercase(), 95) }
        val r = request(employees = equalTeam, days = 28)
        val p = plan(r)
        val counts = equalTeam.map { e -> p.shiftsOf(e.id).count { it.isWeekend } }
        assertTrue(counts.max() - counts.min() <= 1, "Wochenendschichten: $counts")
    }

    @Test
    fun `Ruhezeit gilt auch zur letzten Schicht vor dem Zeitraum`() {
        // Alle hatten Sonntag Spät bis 19:30: Montag früh (05:00/06:00) ist für niemanden erlaubt.
        val lastEnd = MONDAY_START.minusDays(1).atTime(19, 30)
        val r = request(lastShiftEndBefore = team().associate { it.id to lastEnd })
        val p = plan(r)
        assertTrue(p.assignments.none { it.shift.date == MONDAY_START && it.shift.kind == ShiftKind.EARLY })
        assertEquals(2, p.unfilled.count { it.date == MONDAY_START && it.kind == ShiftKind.EARLY })
    }

    @Test
    fun `Urlaub verringert das Stunden-Soll anteilig`() {
        val days = 28
        val absences = (0L until 14L).map { Absence("a", MONDAY_START.plusDays(it), AbsenceType.VACATION) }
        val r = request(days = days, absences = absences)
        val p = plan(r)
        val target = 120 * 60.0 * (days - 14) / 30.4375
        val actual = p.paidMinutesOf("a")
        assertTrue(abs(actual - target) <= 7 * 60, "Anna: ${actual / 60.0} h statt ${target / 60.0} h")
    }

    @Test
    fun `Aushilfen bleiben unter ihren Monatsstunden`() {
        // Viel zu wenig Stammpersonal: die Aushilfe dürfte sonst fast alles übernehmen.
        val employees = listOf(Employee("a", "Anna", 120), Employee("x", "Xenia", 40, isBackup = true))
        val days = 14
        val p = plan(request(employees = employees, days = days))
        val cap = 40 * 60.0 * days / 30.4375
        assertTrue(p.paidMinutesOf("x") <= cap + 7.5, "Xenia: ${p.paidMinutesOf("x") / 60.0} h")
        assertTrue(p.shiftsOf("x").isNotEmpty())
    }

    @Test
    fun `gleiche Eingabe ergibt gleichen Plan`() {
        val r = request()
        assertEquals(plan(r).assignments, plan(r).assignments)
    }

    @Test
    fun `ungueltige Eingaben werden abgelehnt`() {
        assertFailsWith<IllegalArgumentException> { request(days = 0) }
        assertFailsWith<IllegalArgumentException> { request(days = 400) }
        assertFailsWith<IllegalArgumentException> {
            request(employees = listOf(Employee("a", "Anna", 80), Employee("a", "Anton", 80)))
        }
        assertFailsWith<IllegalArgumentException> { request(employees = listOf(Employee("a", "Anna", -5))) }
        assertFailsWith<IllegalArgumentException> { Weights(unfilledShift = -1) }
    }
}
