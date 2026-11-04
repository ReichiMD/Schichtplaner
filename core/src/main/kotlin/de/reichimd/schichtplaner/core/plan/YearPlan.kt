package de.reichimd.schichtplaner.core.plan

import de.reichimd.schichtplaner.core.Absence
import de.reichimd.schichtplaner.core.Employee
import de.reichimd.schichtplaner.core.Plan
import de.reichimd.schichtplaner.core.PlanningRequest
import de.reichimd.schichtplaner.core.PlanningRules
import de.reichimd.schichtplaner.core.Shift
import de.reichimd.schichtplaner.core.ShiftCalendar
import de.reichimd.schichtplaner.core.ShiftEnd
import de.reichimd.schichtplaner.core.ShiftRef
import de.reichimd.schichtplaner.core.ShiftStart
import de.reichimd.schichtplaner.core.ShiftTemplate
import de.reichimd.schichtplaner.core.shop.ShopSettings
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Der ganze Plan, Woche für Woche, ohne festen Zeitraum. Gespeichert werden nur Wochen, die schon
 * angefasst wurden (von Hand oder per "Rest ausfüllen"). Alle anderen Wochen ergeben sich aus den
 * Schichtvorlagen des Ladens und sind leer (alle Schichten offen).
 *
 * Eine Woche gilt als gespeichert, sobald sie mindestens eine Schicht enthält.
 */
data class YearPlan(val slots: List<PlanSlot>) {
    init {
        require(slots.map { it.key }.toSet().size == slots.size) { "Jede Schicht darf nur einmal vorkommen" }
        require(slots.all { it.end.isAfter(it.start) }) { "Jede Schicht muss nach ihrem Beginn enden" }
    }

    /** Erster Tag mit einer gespeicherten Schicht, oder null. */
    val firstDay: LocalDate? get() = slots.minOfOrNull { it.date }

    fun isEmpty() = slots.isEmpty()

    fun slotsBetween(first: LocalDate, last: LocalDate): List<PlanSlot> =
        slots.filter { !it.date.isBefore(first) && !it.date.isAfter(last) }

    fun hasWeek(monday: LocalDate): Boolean = slots.any { YearPlans.monday(it.date) == monday }

    companion object {
        val EMPTY = YearPlan(emptyList())

        fun of(plan: ShiftPlan?): YearPlan = YearPlan(plan?.slots.orEmpty())
    }
}

/** Woche anzeigen, ändern und füllen. Reines Kotlin, ohne Handy testbar. */
object YearPlans {

    fun monday(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /**
     * Durch welche Wochen man wischen kann: ab der ersten Woche des Vorjahres (im Januar noch an den
     * Dezember kommen) bis Ende des nächsten Jahres.
     */
    fun range(today: LocalDate): List<LocalDate> {
        val first = monday(LocalDate.of(today.year - 1, 1, 1))
        val last = monday(LocalDate.of(today.year + 1, 12, 31))
        return generateSequence(first) { it.plusWeeks(1) }.takeWhile { !it.isAfter(last) }.toList()
    }

    /** Offene Schichten einer Woche aus den Vorlagen des Ladens (Feiertage noch wie normale Tage). */
    fun templateSlots(monday: LocalDate, shop: ShopSettings): List<PlanSlot> =
        ShiftCalendar.shifts(monday, 7, shop.toConfig()).map {
            PlanSlot(SlotKey(it.date, it.template.id, it.slot), it.template.name, it.kind, it.start, it.end, null)
        }

    /** Die Woche ab [monday]: gespeichert, sonst aus den Vorlagen. */
    fun week(plan: YearPlan, monday: LocalDate, shop: ShopSettings): ShiftPlan {
        val stored = plan.slotsBetween(monday, monday.plusDays(6))
        val slots = stored.ifEmpty { templateSlots(monday, shop) }
        return ShiftPlan(monday, 7, slots.sortedWith(ShiftPlan.ORDER))
    }

    /**
     * Mehrere Wochen ab [monday] in einem Plan (für Prüfung über Wochengrenzen und für das PDF).
     * Höchstens [PlanningRequest.MAX_DAYS] Tage.
     */
    fun weeks(plan: YearPlan, monday: LocalDate, count: Int, shop: ShopSettings): ShiftPlan {
        val n = count.coerceIn(1, PlanningRequest.MAX_DAYS / 7)
        val slots = (0 until n).flatMap { week(plan, monday.plusWeeks(it.toLong()), shop).slots }
        return ShiftPlan(monday, n * 7, slots)
    }

    /** Ersetzt die Woche von [week] (beginnt am Montag) durch deren Schichten. */
    fun withWeek(plan: YearPlan, week: ShiftPlan): YearPlan {
        val first = week.firstDay
        val last = week.lastDay
        val rest = plan.slots.filter { it.date.isBefore(first) || it.date.isAfter(last) }
        return YearPlan((rest + week.slots).sortedWith(ShiftPlan.ORDER))
    }

    /** Woche wieder leer: Danach gelten für sie wieder die Vorlagen des Ladens. */
    fun clearWeek(plan: YearPlan, monday: LocalDate): YearPlan {
        val last = monday.plusDays(6)
        return YearPlan(plan.slots.filter { it.date.isBefore(monday) || it.date.isAfter(last) })
    }

    /**
     * Wer in einer Woche als Zeile erscheint: alle aus dem Team, dazu Ausgeschiedene nur, wenn sie
     * in dieser Woche eine Schicht haben. Reihenfolge wie im Team.
     */
    fun rows(employees: List<Employee>, week: ShiftPlan): List<Employee> {
        val working = week.slots.mapNotNull { it.employeeId }.toSet()
        return employees.filter { !it.archived || it.id in working }
    }

    /**
     * Hinweise zu einer Woche. Geprüft wird mit der Woche davor und danach, damit Ruhezeit und Tage am Stück
     * auch über den Wochenwechsel stimmen. Zurück kommen nur Hinweise zur Woche selbst.
     */
    fun warnings(
        plan: YearPlan,
        monday: LocalDate,
        shop: ShopSettings,
        employees: List<Employee>,
        absences: List<Absence>,
        rules: PlanningRules = PlanningRules(),
    ): List<Warning> {
        val window = weeks(plan, monday.minusWeeks(1), 3, shop)
        val last = monday.plusDays(6)
        return PlanCheck.warnings(window, employees, absences, rules)
            .filter { !it.key.date.isBefore(monday) && !it.key.date.isAfter(last) }
    }

    /**
     * Auftrag an den Planer für "Rest ausfüllen": Alle schon eingetragenen Schichten der Woche bleiben fest,
     * nur offene werden besetzt. Die Woche davor zählt für Ruhezeit und Tage am Stück, die Woche danach
     * (soweit geplant) nur für die Ruhezeit.
     * [employees] sind alle, Ausgeschiedene werden nicht neu eingeplant.
     */
    fun fillRequest(
        plan: YearPlan,
        monday: LocalDate,
        shop: ShopSettings,
        employees: List<Employee>,
        absences: List<Absence>,
        rules: PlanningRules = PlanningRules(),
    ): PlanningRequest {
        val week = week(plan, monday, shop)
        val ids = employees.map { it.id }.toSet()
        val before = plan.slotsBetween(monday.minusDays(rules.maxConsecutiveDays.toLong()), monday.minusDays(1))
            .filter { it.employeeId in ids }
        val after = plan.slotsBetween(monday.plusDays(7), monday.plusDays(8)).filter { it.employeeId in ids }

        val workedDaysBefore = ids.associateWith { id ->
            val days = before.filter { it.employeeId == id }.map { it.date }.toSet()
            generateSequence(monday.minusDays(1)) { it.minusDays(1) }.takeWhile { it in days }.count()
        }.filterValues { it > 0 }
        val lastEnd = before.groupBy { it.employeeId!! }.mapValues { (_, s) -> s.maxOf { it.end } }
        val firstStart = after.groupBy { it.employeeId!! }.mapValues { (_, s) -> s.minOf { it.start } }

        return PlanningRequest(
            firstDay = monday,
            days = 7,
            shop = shop.toConfig(),
            employees = employees,
            absences = absences.filter { !it.date.isBefore(monday) && !it.date.isAfter(monday.plusDays(6)) },
            rules = rules,
            workedDaysBefore = workedDaysBefore,
            lastShiftEndBefore = lastEnd,
            firstShiftStartAfter = firstStart,
            shifts = week.slots.map { shift(it) },
            // Wer nicht (mehr) bekannt ist, zählt wie offen (so zeigt es auch das Wochenblatt).
            fixed = week.slots.filter { it.employeeId != null && it.employeeId in ids }
                .associate { ShiftRef(it.date, it.key.templateId, it.key.slot) to it.employeeId!! },
        )
    }

    /** Ergebnis von "Rest ausfüllen" eintragen: nur in Schichten, die noch offen sind (niemand aus [teamIds]). */
    fun applyFill(week: ShiftPlan, result: Plan, teamIds: Set<String>): ShiftPlan {
        val assigned = result.assignments.associate { SlotKey(it.shift.date, it.shift.template.id, it.shift.slot) to it.employeeId }
        return week.copy(
            slots = week.slots.map { s ->
                if (s.employeeId == null || s.employeeId !in teamIds) assigned[s.key]?.let { s.copy(employeeId = it) } ?: s else s
            },
        )
    }

    /** Eine Schicht des Plans als Schicht für den Planer (mit ihren tatsächlichen Zeiten). */
    fun shift(slot: PlanSlot): Shift = Shift(
        date = slot.date,
        template = ShiftTemplate(
            id = slot.key.templateId,
            name = slot.name,
            kind = slot.kind,
            start = ShiftStart.At(slot.start.toLocalTime()),
            end = ShiftEnd.At(slot.end.toLocalTime()),
        ),
        slot = slot.key.slot,
        start = slot.start,
        end = slot.end,
    )
}
