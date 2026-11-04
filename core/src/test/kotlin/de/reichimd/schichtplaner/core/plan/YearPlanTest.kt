package de.reichimd.schichtplaner.core.plan

import de.reichimd.schichtplaner.core.Absence
import de.reichimd.schichtplaner.core.AbsenceType
import de.reichimd.schichtplaner.core.Employee
import de.reichimd.schichtplaner.core.Planner
import de.reichimd.schichtplaner.core.ShiftRef
import de.reichimd.schichtplaner.core.shop.Shop
import de.reichimd.schichtplaner.core.storage.DataFileTooNewException
import java.time.Duration
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YearPlanTest {

    private val shop = Shop.EXAMPLE
    private val mo: LocalDate = LocalDate.of(2026, 11, 9)

    private val team = listOf(
        Employee("a", "Anna", 120),
        Employee("b", "Ben", 120),
        Employee("c", "Clara", 120),
        Employee("d", "David", 100),
        Employee("e", "Ella", 100),
        Employee("f", "Finn", 80),
        Employee("g", "Greta", 80, isBackup = true),
    )
    private val ids = team.map { it.id }.toSet()

    @Test
    fun wischbereichVomVorjahrBisEndeNaechstesJahr() {
        val wochen = YearPlans.range(LocalDate.of(2026, 11, 4))
        assertEquals(LocalDate.of(2024, 12, 30), wochen.first()) // Woche mit dem 1.1.2025
        assertEquals(LocalDate.of(2027, 12, 27), wochen.last()) // Woche mit dem 31.12.2027
        assertTrue(wochen.all { it.dayOfWeek == java.time.DayOfWeek.MONDAY })
        assertTrue(wochen.zipWithNext().all { (a, b) -> a.plusWeeks(1) == b })
    }

    @Test
    fun ungeplanteWocheKommtAusDenVorlagen() {
        val w = YearPlans.week(YearPlan.EMPTY, mo, shop)
        assertEquals(mo, w.firstDay)
        assertEquals(7, w.days)
        // Beispiel-Laden: 3 Schichten pro Tag.
        assertEquals(21, w.slots.size)
        assertTrue(w.slots.all { it.employeeId == null })
        assertFalse(YearPlan.EMPTY.hasWeek(mo))
    }

    @Test
    fun handaenderungSpeichertNurDieseWoche() {
        val w = YearPlans.week(YearPlan.EMPTY, mo, shop)
        val key = w.slotsOn(mo).first().key
        val plan = YearPlans.withWeek(YearPlan.EMPTY, PlanEdit.assign(w, key, "a"))
        assertTrue(plan.hasWeek(mo))
        assertFalse(plan.hasWeek(mo.plusWeeks(1)))
        assertEquals("a", YearPlans.week(plan, mo, shop).slot(key)?.employeeId)

        // Später geänderte Vorlagen ändern eine gespeicherte Woche nicht, eine ungeplante schon.
        val anders = shop.copy(templates = shop.templates.take(1))
        assertEquals(21, YearPlans.week(plan, mo, anders).slots.size)
        assertEquals(7, YearPlans.week(plan, mo.plusWeeks(1), anders).slots.size)

        val leer = YearPlans.clearWeek(plan, mo)
        assertTrue(leer.isEmpty())
    }

    @Test
    fun restAusfuellenBehaeltHandeintraegeUndFuelltDenRest() {
        val w = YearPlans.week(YearPlan.EMPTY, mo, shop)
        val f1Mo = w.slotsOn(mo).first { it.key.templateId == "f1" }.key
        val spDi = w.slotsOn(mo.plusDays(1)).first { it.key.templateId == "sp" }.key
        val vonHand = PlanEdit.assign(PlanEdit.assign(w, f1Mo, "f"), spDi, "a")
        val plan = YearPlans.withWeek(YearPlan.EMPTY, vonHand)

        val request = YearPlans.fillRequest(plan, mo, shop, team, emptyList())
        assertEquals(mapOf(ShiftRef(mo, "f1", 0) to "f", ShiftRef(mo.plusDays(1), "sp", 0) to "a"), request.fixed)
        val result = Planner.plan(request, timeLimitMillis = 10_000)
        assertTrue(result.solved)
        val gefuellt = YearPlans.applyFill(YearPlans.week(plan, mo, shop), result, ids)

        assertEquals("f", gefuellt.slot(f1Mo)?.employeeId)
        assertEquals("a", gefuellt.slot(spDi)?.employeeId)
        assertTrue(gefuellt.slots.all { it.employeeId != null }, "alles besetzt")
        // Anna hat Di spät: Mi früh geht wegen der Ruhezeit nicht.
        val annaMi = gefuellt.slotsOf("a", mo.plusDays(2)).firstOrNull()
        if (annaMi != null) assertTrue(Duration.between(mo.plusDays(1).atTime(18, 30), annaMi.start).toHours() >= 11)
        assertEquals(emptyList(), PlanCheck.warnings(gefuellt, team, emptyList()).filter { it.severity == Severity.RULE })
    }

    @Test
    fun restAusfuellenOhneEintraegeFuelltDieGanzeWoche() {
        val request = YearPlans.fillRequest(YearPlan.EMPTY, mo, shop, team, emptyList())
        assertTrue(request.fixed.isEmpty())
        val result = Planner.plan(request, timeLimitMillis = 10_000)
        val gefuellt = YearPlans.applyFill(YearPlans.week(YearPlan.EMPTY, mo, shop), result, ids)
        assertTrue(gefuellt.slots.all { it.employeeId != null })
    }

    @Test
    fun handgeaenderteZeitenWerdenUebernommen() {
        val w = YearPlans.week(YearPlan.EMPTY, mo, shop)
        val key = w.slotsOn(mo).first().key
        val plan = YearPlans.withWeek(YearPlan.EMPTY, PlanEdit.setTimes(w, key, java.time.LocalTime.of(6, 0), java.time.LocalTime.of(10, 0)))
        val request = YearPlans.fillRequest(plan, mo, shop, team, emptyList())
        val s = request.shifts!!.first { it.date == mo && it.template.id == key.templateId && it.slot == key.slot }
        assertEquals(mo.atTime(6, 0), s.start)
        assertEquals(240, s.paidMinutes)
    }

    @Test
    fun vorwocheZaehltFuerRuhezeitUndTageAmStueck() {
        // Ben arbeitet die ganze Vorwoche, Sonntag spät.
        var plan = YearPlan.EMPTY
        val vorMo = mo.minusWeeks(1)
        var vor = YearPlans.week(plan, vorMo, shop)
        for (d in 0L..6L) {
            val tag = vorMo.plusDays(d)
            val spaet = vor.slotsOn(tag).last()
            vor = PlanEdit.assign(vor, spaet.key, "b")
        }
        plan = YearPlans.withWeek(plan, vor)

        val request = YearPlans.fillRequest(plan, mo, shop, team, emptyList())
        assertEquals(6, request.workedDaysBefore["b"]) // höchstens so weit zurück gezählt
        assertEquals(mo.minusDays(1).atTime(17, 30), request.lastShiftEndBefore["b"])

        val result = Planner.plan(request, timeLimitMillis = 10_000)
        // Nach 6+ Tagen am Stück hat Ben Montag frei.
        assertTrue(result.assignments.none { it.employeeId == "b" && it.shift.date == mo })
    }

    @Test
    fun folgewocheZaehltFuerRuhezeit() {
        // Anna fängt nächsten Montag schon um 4:00 an: Sonntag spät (bis 17:30) ließe nur 10,5 h Ruhe.
        val naechsteMo = mo.plusWeeks(1)
        val n = YearPlans.week(YearPlan.EMPTY, naechsteMo, shop)
        val key = n.slotsOn(naechsteMo).first().key
        val vonHand = PlanEdit.setTimes(PlanEdit.assign(n, key, "a"), key, java.time.LocalTime.of(4, 0), java.time.LocalTime.of(11, 0))
        val plan = YearPlans.withWeek(YearPlan.EMPTY, vonHand)
        val request = YearPlans.fillRequest(plan, mo, shop, team, emptyList())
        assertEquals(naechsteMo.atTime(4, 0), request.firstShiftStartAfter["a"])
        val result = Planner.plan(request, timeLimitMillis = 10_000)
        assertTrue(result.assignments.none { it.employeeId == "a" && it.shift.date == mo.plusDays(6) && it.shift.kind == de.reichimd.schichtplaner.core.ShiftKind.LATE })
    }

    @Test
    fun ausgeschiedeneWerdenNichtEingeplantBleibenAberStehen() {
        val alt = team.map { if (it.id == "f") it.copy(archived = true) else it }
        val w = YearPlans.week(YearPlan.EMPTY, mo, shop)
        val key = w.slotsOn(mo).first().key
        val plan = YearPlans.withWeek(YearPlan.EMPTY, PlanEdit.assign(w, key, "f"))

        val result = Planner.plan(YearPlans.fillRequest(plan, mo, shop, alt, emptyList()), timeLimitMillis = 10_000)
        val gefuellt = YearPlans.applyFill(YearPlans.week(plan, mo, shop), result, alt.map { it.id }.toSet())
        // Der Handeintrag bleibt, sonst bekommt Finn nichts.
        assertEquals(listOf(key), gefuellt.slots.filter { it.employeeId == "f" }.map { it.key })

        assertTrue(YearPlans.rows(alt, gefuellt).any { it.id == "f" })
        assertFalse(YearPlans.rows(alt, YearPlans.week(plan, mo.plusWeeks(1), shop)).any { it.id == "f" })
    }

    @Test
    fun handeintraegeGegenDieRegelnBleibenUndDerRestWirdTrotzdemGefuellt() {
        // Ben von Hand an allen 7 Tagen (zu viele am Stück), Mo spät und Di früh (zu wenig Ruhe),
        // dazu Urlaub am Mittwoch. Der Planer muss das so stehen lassen und den Rest füllen.
        var w = YearPlans.week(YearPlan.EMPTY, mo, shop)
        w = PlanEdit.assign(w, w.slotsOn(mo).last().key, "b")
        for (d in 1L..6L) w = PlanEdit.assign(w, w.slotsOn(mo.plusDays(d)).first().key, "b")
        val plan = YearPlans.withWeek(YearPlan.EMPTY, w)
        val urlaub = listOf(Absence("b", mo.plusDays(2), AbsenceType.VACATION))

        val request = YearPlans.fillRequest(plan, mo, shop, team, urlaub)
        val result = Planner.plan(request, timeLimitMillis = 10_000)
        assertTrue(result.solved)
        val gefuellt = YearPlans.applyFill(YearPlans.week(plan, mo, shop), result, ids)
        assertEquals(w.slots.filter { it.employeeId == "b" }.map { it.key }, gefuellt.slots.filter { it.employeeId == "b" }.map { it.key })
        assertTrue(gefuellt.slots.count { it.employeeId == null } < w.slots.count { it.employeeId == null })
    }

    @Test
    fun unbekannteIdZaehltBeimFuellenWieOffen() {
        val w = YearPlans.week(YearPlan.EMPTY, mo, shop)
        val key = w.slotsOn(mo).first().key
        val plan = YearPlans.withWeek(YearPlan.EMPTY, PlanEdit.assign(w, key, "gibt-es-nicht"))
        val request = YearPlans.fillRequest(plan, mo, shop, team, emptyList())
        assertTrue(request.fixed.isEmpty())
        val gefuellt = YearPlans.applyFill(YearPlans.week(plan, mo, shop), Planner.plan(request, timeLimitMillis = 10_000), ids)
        assertTrue(gefuellt.slot(key)?.employeeId in ids)
    }

    @Test
    fun urlaubWirdBeimFuellenBeachtet() {
        val urlaub = (0L..6L).map { Absence("a", mo.plusDays(it), AbsenceType.VACATION) }
        val request = YearPlans.fillRequest(YearPlan.EMPTY, mo, shop, team, urlaub)
        val result = Planner.plan(request, timeLimitMillis = 10_000)
        assertTrue(result.assignments.none { it.employeeId == "a" })
    }

    @Test
    fun hinweiseUeberDenWochenwechsel() {
        // Sonntag von Hand bis 19:00, Montag früh ab 4:30: nur 9,5 h Ruhe über den Wochenwechsel.
        val vorMo = mo.minusWeeks(1)
        var vor = YearPlans.week(YearPlan.EMPTY, vorMo, shop)
        val so = vor.slotsOn(mo.minusDays(1)).last()
        vor = PlanEdit.setTimes(PlanEdit.assign(vor, so.key, "a"), so.key, java.time.LocalTime.of(13, 0), java.time.LocalTime.of(19, 0))
        var plan = YearPlans.withWeek(YearPlan.EMPTY, vor)
        val w = YearPlans.week(plan, mo, shop)
        plan = YearPlans.withWeek(plan, PlanEdit.assign(w, w.slotsOn(mo).first().key, "a"))

        val hinweise = YearPlans.warnings(plan, mo, shop, team, emptyList())
        assertTrue(hinweise.any { it.employeeId == "a" && it.key.date == mo && "Ruhe" in it.message }, hinweise.toString())
        assertTrue(hinweise.all { it.key.date >= mo })
    }

    @Test
    fun speicherformatJahresplanUndAltesFormat() {
        val w = YearPlans.week(YearPlan.EMPTY, mo, shop)
        val plan = YearPlans.withWeek(
            YearPlans.withWeek(YearPlan.EMPTY, PlanEdit.assign(w, w.slots.first().key, "a")),
            YearPlans.week(YearPlan.EMPTY, mo.plusWeeks(20), shop),
        )
        assertEquals(plan, PlanCodec.decodeYear(PlanCodec.encodeYear(plan)))
        assertEquals(YearPlan.EMPTY, PlanCodec.decodeYear(PlanCodec.encodeYear(YearPlan.EMPTY)))

        // plan.json aus 0.1.8 (ein Zeitraum) wird gelesen.
        assertEquals(YearPlan(w.slots), PlanCodec.decodeYear(PlanCodec.encode(w)))
        assertEquals(YearPlan.EMPTY, PlanCodec.decodeYear(PlanCodec.encode(null)))
        assertFailsWith<DataFileTooNewException> { PlanCodec.decodeYear("""{"version":3}""") }
        assertNull(YearPlan.EMPTY.firstDay)
    }
}
