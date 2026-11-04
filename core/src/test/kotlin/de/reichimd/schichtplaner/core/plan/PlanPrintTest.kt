package de.reichimd.schichtplaner.core.plan

import de.reichimd.schichtplaner.core.Absence
import de.reichimd.schichtplaner.core.AbsenceType
import de.reichimd.schichtplaner.core.Employee
import de.reichimd.schichtplaner.core.ShiftKind
import de.reichimd.schichtplaner.core.TestData
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlanPrintTest {

    private val mo: LocalDate = TestData.MONDAY_START // 02.11.2026, KW 45
    private val di = mo.plusDays(1)

    private val anna = Employee("a", "Anna", 120)
    private val ben = Employee("b", "Ben", 100)
    private val hanna = Employee("h", "Hanna", 40, isBackup = true)

    private fun slot(date: LocalDate, id: String, kind: ShiftKind, fromH: Int, fromM: Int, toH: Int, toM: Int, who: String?) =
        PlanSlot(SlotKey(date, id, 0), id, kind, date.atTime(fromH, fromM), date.atTime(toH, toM), who)

    private val plan = ShiftPlan(
        firstDay = mo,
        days = 14,
        slots = listOf(
            slot(mo, "f1", ShiftKind.EARLY, 4, 30, 11, 30, "a"), // 7 h → 6,5 bezahlt
            slot(mo, "sp", ShiftKind.LATE, 12, 30, 18, 30, "b"), // 6 h, keine Pause
            slot(di, "f1", ShiftKind.EARLY, 4, 30, 11, 30, null), // offen
            slot(di, "sp", ShiftKind.LATE, 12, 30, 18, 30, "weg"), // Person nicht mehr im Team → offen
            slot(mo.plusDays(7), "f1", ShiftKind.EARLY, 5, 30, 12, 30, "h"),
        ),
    )

    private val abwesend = listOf(
        Absence("b", di, AbsenceType.VACATION),
        Absence("a", di, AbsenceType.DAY_OFF_WISH),
        Absence("a", mo.plusDays(2), AbsenceType.SICK),
    )

    private val team = listOf(anna, ben, hanna)

    @Test
    fun einBlattProWoche() {
        val wochen = PlanPrint.weeks(plan, team, abwesend)
        assertEquals(2, wochen.size)
        assertEquals("KW 45 · 02.11. – 08.11.2026", wochen[0].title)
        assertEquals("KW 46 · 09.11. – 15.11.2026", wochen[1].title)
        assertEquals("Mo 02.11.", wochen[0].days.first())
        assertEquals("So 08.11.", wochen[0].days.last())
    }

    @Test
    fun zellenWieAufDemPapier() {
        val w = PlanPrint.week(plan, team, abwesend, 0)
        val zeilen = w.rows.filterIsInstance<PrintRow.Person>().associateBy { it.name }
        val a = zeilen.getValue("Anna")
        assertEquals(PrintCell("4:30–11:30", "P 0,5 · 6,5", ShiftKind.EARLY), a.cells[0])
        assertEquals(PrintCell("Fr", wish = true), a.cells[1]) // Frei-Wunsch erfüllt: farbig
        assertEquals(PrintCell("K"), a.cells[2])
        assertEquals(PrintCell("Fr"), a.cells[3]) // frei ohne Eintrag: normales Fr
        assertEquals("6,5", a.hours)

        val b = zeilen.getValue("Ben")
        assertEquals(PrintCell("12:30–18:30", "6", ShiftKind.LATE), b.cells[0])
        assertEquals(PrintCell("U"), b.cells[1])

        // Aushilfe aus anderer Filiale ohne Eintrag: leer statt "Fr".
        assertEquals(PrintCell(""), zeilen.getValue("Hanna").cells[0])
        assertEquals("6", b.hours)
    }

    @Test
    fun aushilfenUnterEigenerUeberschriftUndSummenUnten() {
        val w = PlanPrint.week(plan, team, abwesend, 0)
        assertEquals(listOf("Anna", "Ben"), w.rows.take(2).map { (it as PrintRow.Person).name })
        assertEquals(PrintRow.Section(PlanPrint.BACKUP_SECTION), w.rows[2])
        assertEquals("Hanna", (w.rows[3] as PrintRow.Person).name)

        val offen = assertIs<PrintRow.Open>(w.rows[4])
        assertEquals(PrintCell(""), offen.cells[0])
        assertEquals(PrintCell("2 offen", "4:30, 12:30"), offen.cells[1])
        assertEquals("2", offen.total)

        val summe = assertIs<PrintRow.Totals>(w.rows.last())
        assertEquals("12,5", summe.cells[0])
        assertEquals("0", summe.cells[1]) // offene und fremde Schichten zählen nicht
        assertEquals("12,5", summe.total)
    }

    @Test
    fun ohneOffeneUndOhneAushilfenKeineExtraZeilen() {
        val w = PlanPrint.week(plan, listOf(anna, ben, hanna), abwesend, 1)
        assertTrue(w.rows.none { it is PrintRow.Open })
        val ohneAushilfe = PlanPrint.week(plan, listOf(anna, ben), abwesend, 1)
        assertTrue(ohneAushilfe.rows.none { it is PrintRow.Section })
        // Hanna arbeitet in Woche 2, ist aber nicht im Team: zählt als offen.
        assertIs<PrintRow.Open>(ohneAushilfe.rows[2])
    }

    @Test
    fun tageAusserhalbDesPlansSindGrau() {
        val kurz = plan.copy(days = 10, slots = plan.slots.filter { it.date.isBefore(mo.plusDays(10)) })
        val w = PlanPrint.week(kurz, team, emptyList(), 1)
        val a = w.rows.first() as PrintRow.Person
        assertTrue(a.cells.take(3).all { it.inPlan })
        assertTrue(a.cells.drop(3).none { it.inPlan })
        val summe = w.rows.last() as PrintRow.Totals
        assertEquals("", summe.cells[6])
    }

    @Test
    fun dateiname() {
        assertEquals("schichtplan-2026-11-02.pdf", PlanPrint.fileName(plan))
    }
}
