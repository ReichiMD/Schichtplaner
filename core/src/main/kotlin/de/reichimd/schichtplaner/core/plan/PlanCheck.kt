package de.reichimd.schichtplaner.core.plan

import de.reichimd.schichtplaner.core.Absence
import de.reichimd.schichtplaner.core.AbsenceType
import de.reichimd.schichtplaner.core.Employee
import de.reichimd.schichtplaner.core.PlanningRules
import de.reichimd.schichtplaner.core.ShiftKind
import de.reichimd.schichtplaner.core.Weekdays
import de.reichimd.schichtplaner.core.shop.Shop
import java.time.Duration
import java.time.format.DateTimeFormatter

/** Wie ernst ein Hinweis ist. */
enum class Severity {
    /** Frei-Wunsch nicht erfüllt: erlaubt, aber sichtbar ("Fr" an der Schicht). */
    WISH,
    /** Verstößt gegen eine feste Regel (Urlaub, Ruhezeit, ...): rot. */
    RULE,
}

data class Warning(val key: SlotKey, val employeeId: String, val message: String, val severity: Severity)

/**
 * Prüft einen (von Hand geänderten) Plan gegen dieselben Regeln wie der Planer.
 * Geprüft wird immer mit dem aktuellen Team und Kalender: Wer nach dem Erstellen krank gemeldet wird,
 * taucht hier sofort auf. Personen, die nicht mehr im Team sind, zählen als offen und werden nicht geprüft.
 */
object PlanCheck {

    private val DAY = DateTimeFormatter.ofPattern("dd.MM.")

    fun warnings(
        plan: ShiftPlan,
        employees: List<Employee>,
        absences: List<Absence>,
        rules: PlanningRules = PlanningRules(),
    ): List<Warning> {
        val byId = employees.associateBy { it.id }
        val absent = absences.associate { (it.employeeId to it.date) to it.type }
        val result = mutableListOf<Warning>()
        val minRest = Duration.ofHours(rules.minRestHours)

        val assigned = plan.slots.filter { it.employeeId != null && it.employeeId in byId }
        for ((id, mine) in assigned.groupBy { it.employeeId!! }) {
            val e = byId.getValue(id)
            val sorted = mine.sortedWith(ShiftPlan.ORDER)
            fun warn(s: PlanSlot, message: String, severity: Severity = Severity.RULE) {
                result += Warning(s.key, id, message, severity)
            }

            for ((i, s) in sorted.withIndex()) {
                when (absent[id to s.date]) {
                    AbsenceType.VACATION -> warn(s, "${e.name} hat Urlaub.")
                    AbsenceType.SICK -> warn(s, "${e.name} ist krank gemeldet.")
                    AbsenceType.DAY_OFF_WISH -> warn(s, "${e.name} wollte frei haben.", Severity.WISH)
                    null -> Unit
                }
                if (s.date.dayOfWeek in e.blockedWeekdays) {
                    warn(s, "${e.name} kann ${Weekdays.short(s.date.dayOfWeek)} nie.")
                }
                if (!e.canWork(s.kind)) {
                    warn(s, "${e.name} macht keine ${if (s.kind == ShiftKind.EARLY) "Frühschicht" else "Spätschicht"}.")
                }
                if (s.presenceMinutes > Shop.MAX_SHIFT_HOURS * 60) {
                    warn(s, "Länger als ${Shop.MAX_SHIFT_HOURS} Stunden.")
                }
                val before = sorted.getOrNull(i - 1) ?: continue
                if (before.date == s.date) {
                    warn(s, "${e.name} hat an diesem Tag zwei Schichten.")
                } else {
                    val rest = Duration.between(before.end, s.start)
                    if (rest < minRest) {
                        val day = "${Weekdays.short(before.date.dayOfWeek)}, ${before.date.format(DAY)}"
                        warn(s, "Nur ${PlanSheet.hours(rest.toMinutes().coerceAtLeast(0))} h Ruhe nach der Schicht am $day (mindestens ${rules.minRestHours} h).")
                    }
                }
            }

            // Tage am Stück, nur innerhalb des Plans.
            val dates = sorted.map { it.date }.distinct()
            var run = 0
            for ((i, d) in dates.withIndex()) {
                run = if (i > 0 && dates[i - 1].plusDays(1) == d) run + 1 else 1
                if (run > rules.maxConsecutiveDays) {
                    sorted.filter { it.date == d }.forEach {
                        warn(it, "$run. Arbeitstag am Stück (höchstens ${rules.maxConsecutiveDays}).")
                    }
                }
            }
        }
        return result
    }

    /** Hinweise für [employeeId], wenn sie die Schicht [key] übernähme (für die Auswahl beim Ändern). */
    fun preview(
        plan: ShiftPlan,
        key: SlotKey,
        employeeId: String,
        employees: List<Employee>,
        absences: List<Absence>,
        rules: PlanningRules = PlanningRules(),
    ): List<Warning> =
        warnings(PlanEdit.assign(plan, key, employeeId), employees, absences, rules)
            .filter { it.employeeId == employeeId && it.key == key }
}
