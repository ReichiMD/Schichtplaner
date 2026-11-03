package de.reichimd.schichtplaner.core.plan

import de.reichimd.schichtplaner.core.Absence
import de.reichimd.schichtplaner.core.AbsenceType
import de.reichimd.schichtplaner.core.Employee
import de.reichimd.schichtplaner.core.Planner
import de.reichimd.schichtplaner.core.ShiftKind
import de.reichimd.schichtplaner.core.TestData
import de.reichimd.schichtplaner.core.storage.DataFileException
import de.reichimd.schichtplaner.core.storage.DataFileTooNewException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanTest {

    private val mo: LocalDate = TestData.MONDAY_START
    private val di: LocalDate = mo.plusDays(1)

    private val anna = Employee("a", "Anna", 120)
    private val ben = Employee("b", "Ben", 120, canEarly = false)
    private val clara = Employee("c", "Clara", 80, blockedWeekdays = setOf(DayOfWeek.TUESDAY))
    private val team = listOf(anna, ben, clara)
    private val ids = team.map { it.id }.toSet()

    private fun slot(date: LocalDate, id: String, kind: ShiftKind, from: LocalTime, to: LocalTime, who: String?) =
        PlanSlot(SlotKey(date, id, 0), id, kind, date.atTime(from), date.atTime(to), who)

    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)

    /** Mo und Di je zwei Frühschichten und eine Spätschicht. */
    private fun plan(
        moF1: String? = "a", moF2: String? = "c", moSp: String? = "b",
        diF1: String? = "a", diF2: String? = null, diSp: String? = "b",
    ) = ShiftPlan(
        firstDay = mo,
        days = 7,
        slots = listOf(
            slot(mo, "f1", ShiftKind.EARLY, t(4, 30), t(11, 30), moF1),
            slot(mo, "f2", ShiftKind.EARLY, t(5, 30), t(12, 30), moF2),
            slot(mo, "sp", ShiftKind.LATE, t(12, 30), t(18, 30), moSp),
            slot(di, "f1", ShiftKind.EARLY, t(4, 30), t(11, 30), diF1),
            slot(di, "f2", ShiftKind.EARLY, t(5, 30), t(12, 30), diF2),
            slot(di, "sp", ShiftKind.LATE, t(12, 30), t(18, 30), diSp),
        ),
    )

    private fun key(date: LocalDate, id: String) = SlotKey(date, id, 0)

    // ---------- Handänderungen ----------

    @Test
    fun offeneSchichtUebernehmen() {
        val p = PlanEdit.assign(plan(), key(di, "f2"), "c")
        assertEquals("c", p.slot(key(di, "f2"))!!.employeeId)
    }

    @Test
    fun tauschenAmSelbenTag() {
        // Anna übernimmt Bens Spätschicht am Mo, Ben bekommt dafür Annas Frühschicht.
        val p = PlanEdit.assign(plan(), key(mo, "sp"), "a")
        assertEquals("a", p.slot(key(mo, "sp"))!!.employeeId)
        assertEquals("b", p.slot(key(mo, "f1"))!!.employeeId)
        assertEquals("c", p.slot(key(mo, "f2"))!!.employeeId)
    }

    @Test
    fun aufOffeneSchichtWechselnMachtAlteSchichtOffen() {
        val p = PlanEdit.assign(plan(), key(di, "f2"), "a")
        assertEquals("a", p.slot(key(di, "f2"))!!.employeeId)
        assertNull(p.slot(key(di, "f1"))!!.employeeId)
    }

    @Test
    fun geloeschtePersonBekommtBeimTauschNichtsZurueck() {
        val p = PlanEdit.assign(plan(moSp = "weg"), key(mo, "sp"), "a", teamIds = ids)
        assertEquals("a", p.slot(key(mo, "sp"))!!.employeeId)
        assertNull(p.slot(key(mo, "f1"))!!.employeeId)
    }

    @Test
    fun freiUndLeeren() {
        val frei = PlanEdit.free(plan(), "a", mo)
        assertNull(frei.slot(key(mo, "f1"))!!.employeeId)
        assertEquals("a", frei.slot(key(di, "f1"))!!.employeeId)
        assertNull(PlanEdit.clear(plan(), key(mo, "sp")).slot(key(mo, "sp"))!!.employeeId)
    }

    @Test
    fun zeitenAendern() {
        val p = PlanEdit.setTimes(plan(), key(mo, "f1"), t(5), t(12, 30))
        val s = p.slot(key(mo, "f1"))!!
        assertEquals(mo.atTime(5, 0), s.start)
        assertEquals(mo.atTime(12, 30), s.end)
        assertEquals(key(mo, "f1"), s.key) // Stelle bleibt gleich
        // Ende vor Beginn ändert nichts.
        assertEquals(plan(), PlanEdit.setTimes(plan(), key(mo, "f1"), t(12), t(11)))
        assertTrue(PlanEdit.timeProblem(t(12), t(12)) != null)
        assertNull(PlanEdit.timeProblem(t(4, 30), t(11, 30)))
    }

    @Test
    fun unbekannteSchichtAendertNichts() {
        val p = plan()
        assertEquals(p, PlanEdit.assign(p, SlotKey(mo, "gibtsnicht", 0), "a"))
    }

    // ---------- Prüfung ----------

    private fun messages(p: ShiftPlan, absences: List<Absence> = emptyList()) =
        PlanCheck.warnings(p, team, absences).map { it.key to it.message }

    @Test
    fun gueltigerPlanHatKeineHinweise() {
        assertEquals(emptyList(), messages(plan()))
    }

    @Test
    fun urlaubKrankUndFreiWunsch() {
        val w = PlanCheck.warnings(
            plan(),
            team,
            listOf(
                Absence("a", mo, AbsenceType.VACATION),
                Absence("c", mo, AbsenceType.SICK),
                Absence("b", di, AbsenceType.DAY_OFF_WISH),
            ),
        )
        assertEquals(3, w.size)
        assertTrue(w.any { it.key == key(mo, "f1") && it.message.contains("Urlaub") && it.severity == Severity.RULE })
        assertTrue(w.any { it.key == key(mo, "f2") && it.message.contains("krank") && it.severity == Severity.RULE })
        assertTrue(w.any { it.key == key(di, "sp") && it.severity == Severity.WISH })
    }

    @Test
    fun schichtartUndSperrtag() {
        // Ben kann nicht früh, Clara nie dienstags.
        val m = messages(plan(moF1 = "b", moSp = "a", diF2 = "c"))
        assertTrue(m.any { it.first == key(mo, "f1") && it.second.contains("keine Frühschicht") })
        assertTrue(m.any { it.first == key(di, "f2") && it.second.contains("Di nie") })
    }

    @Test
    fun ruhezeitZuKurz() {
        // Anna spät am Mo bis 18:30, dann früh am Di ab 4:30: nur 10 h Ruhe.
        val m = messages(plan(moF1 = "c", moF2 = null, moSp = "a"))
        assertEquals(listOf(key(di, "f1")), m.map { it.first })
        assertTrue(m[0].second.startsWith("Nur 10 h Ruhe nach der Schicht am Mo, 02.11."), m.toString())
    }

    @Test
    fun zweiSchichtenAmTag() {
        val m = messages(plan(moF2 = "a"))
        assertTrue(m.any { it.second.contains("zwei Schichten") })
    }

    @Test
    fun zuVieleTageAmStueck() {
        val days = (0L until 7L).map { mo.plusDays(it) }
        val p = ShiftPlan(mo, 7, days.map { slot(it, "f1", ShiftKind.EARLY, t(6), t(12), "a") })
        val m = PlanCheck.warnings(p, team, emptyList())
        assertEquals(1, m.size)
        assertEquals(key(mo.plusDays(6), "f1"), m[0].key)
        assertTrue(m[0].message.startsWith("7. Arbeitstag"))
    }

    @Test
    fun zuLangeSchicht() {
        val p = ShiftPlan(mo, 7, listOf(slot(mo, "f1", ShiftKind.EARLY, t(4), t(14, 30), "a")))
        assertTrue(messages(p).any { it.second.contains("Länger als 10") })
    }

    @Test
    fun personNichtMehrImTeamWirdNichtGeprueft() {
        assertEquals(emptyList(), messages(plan(moF1 = "weg")))
        assertEquals(1, PlanSheet.openCount(plan(moF1 = "weg"), ids) - PlanSheet.openCount(plan(), ids))
    }

    @Test
    fun vorschauZeigtHinweiseNurFuerDieGewaehltePerson() {
        val w = PlanCheck.preview(plan(), key(di, "f2"), "c", team, emptyList())
        assertEquals(1, w.size)
        assertTrue(w[0].message.contains("Di nie"))
        assertEquals(emptyList(), PlanCheck.preview(plan(), key(di, "f2"), "a", team, emptyList()))
    }

    // ---------- Wochenblatt ----------

    @Test
    fun stundenTexte() {
        assertEquals("6,5", PlanSheet.hours(390))
        assertEquals("6,75", PlanSheet.hours(405))
        assertEquals("7", PlanSheet.hours(420))
        assertEquals("0", PlanSheet.hours(0))
        assertEquals("0,25", PlanSheet.hours(15))
        assertEquals("-1,5", PlanSheet.hours(-90))
        assertEquals("0,83", PlanSheet.hours(50))
        assertEquals("0,17", PlanSheet.hours(10))
        assertEquals("4:30", PlanSheet.time(mo.atTime(4, 30)))
        assertEquals("12:05", PlanSheet.time(mo.atTime(12, 5)))
    }

    @Test
    fun pauseUndSummen() {
        val p = plan()
        val f1 = p.slot(key(mo, "f1"))!!
        assertEquals(390, f1.paidMinutes) // 7 h Anwesenheit, 30 min Pause
        assertEquals("P 0,5", PlanSheet.pause(f1))
        assertEquals("", PlanSheet.pause(p.slot(key(mo, "sp"))!!)) // genau 6 h: keine Pause
        assertEquals(390 + 390 + 360, PlanSheet.dayTotalMinutes(p, mo, ids))
        assertEquals(390 + 390, PlanSheet.paidMinutes(p, "a", 0))
        assertEquals(listOf(key(di, "f2")), PlanSheet.openOn(p, di, ids).map { it.key })
    }

    @Test
    fun sollProWocheMitUrlaub() {
        val p = plan()
        // 120 h im Monat, 7 Tage: 120 * 60 * 7 / 30,4375 = 1655,8 min
        assertEquals(1656, PlanSheet.targetMinutes(p, anna, emptyList(), 0))
        assertEquals(
            Math.round(120 * 60.0 * 5 / Planner.AVG_DAYS_PER_MONTH),
            PlanSheet.targetMinutes(p, anna, listOf(Absence("a", mo, AbsenceType.VACATION), Absence("a", di, AbsenceType.SICK)), 0),
        )
    }

    // ---------- Aus dem Planer und Speichern ----------

    @Test
    fun ausDemPlanerUndZurueck() {
        val request = TestData.request(days = 7)
        val result = Planner.plan(request)
        val p = ShiftPlan.from(request, result)
        assertEquals(result.assignments.size + result.unfilled.size, p.slots.size)
        for (a in result.assignments) {
            assertEquals(a.employeeId, p.slot(SlotKey(a.shift.date, a.shift.template.id, a.shift.slot))!!.employeeId)
        }
        assertEquals(p, PlanCodec.decode(PlanCodec.encode(p)))
        // Gleicher Inhalt ergibt gleiche Datei.
        assertEquals(PlanCodec.encode(p), PlanCodec.encode(p.copy(slots = p.slots.reversed())).let { PlanCodec.encode(PlanCodec.decode(it)) })
    }

    @Test
    fun keinPlanGespeichert() {
        assertNull(PlanCodec.decode(PlanCodec.encode(null)))
    }

    @Test
    fun handaenderungenUeberstehenSpeichern() {
        val p = PlanEdit.setTimes(PlanEdit.assign(plan(), key(di, "f2"), "c"), key(mo, "sp"), t(13), t(18))
        assertEquals(p, PlanCodec.decode(PlanCodec.encode(p)))
    }

    @Test
    fun kaputteDateien() {
        assertFailsWith<DataFileException> { PlanCodec.decode("kein json") }
        assertFailsWith<DataFileException> { PlanCodec.decode("""{"version":0}""") }
        assertFailsWith<DataFileTooNewException> { PlanCodec.decode("""{"version":99}""") }
        val good = PlanCodec.encode(plan())
        assertFailsWith<DataFileException> { PlanCodec.decode(good.replace("\"early\"", "\"mittag\"")) }
        assertFailsWith<DataFileException> { PlanCodec.decode(good.replace("2026-11-02T04:30", "2026-11-02T19:30")) }
        assertFailsWith<DataFileException> { PlanCodec.decode(good.replace("\"days\": 7", "\"days\": 1")) }
        assertFailsWith<DataFileException> { PlanCodec.decode(good.replace("\"f2\"", "\"f1\"")) }
        assertFailsWith<DataFileException> { PlanCodec.decode(good.replace("2026-11-03T", "2026-11-04T")) }
    }
}
