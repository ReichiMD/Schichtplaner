package de.reichimd.schichtplaner.core.plan

import java.time.LocalDate
import java.time.LocalTime

/**
 * Änderungen von Hand am fertigen Plan. Jede Funktion gibt einen neuen Plan zurück.
 * Regeln werden hier nicht durchgesetzt: Die Filialleitung darf alles, die App warnt nur (siehe [PlanCheck]).
 */
object PlanEdit {

    /**
     * Person [employeeId] übernimmt die Schicht [key].
     * Hatte sie an dem Tag schon eine andere Schicht, wird getauscht: Die bisherige Person der Zielschicht
     * bekommt diese Schicht. War die Zielschicht offen, wird die alte Schicht der Person offen.
     * Wer nicht mehr in [teamIds] steht, gilt als niemand und bekommt nichts zurück.
     */
    fun assign(plan: ShiftPlan, key: SlotKey, employeeId: String, teamIds: Set<String>? = null): ShiftPlan {
        val target = plan.slot(key) ?: return plan
        if (target.employeeId == employeeId) return plan
        val previous = target.employeeId?.takeIf { teamIds == null || it in teamIds }
        val own = plan.slotsOf(employeeId, target.date).map { it.key }
        val swapWith = own.firstOrNull()
        return plan.copy(
            slots = plan.slots.map {
                when (it.key) {
                    key -> it.copy(employeeId = employeeId)
                    swapWith -> it.copy(employeeId = previous)
                    in own -> it.copy(employeeId = null)
                    else -> it
                }
            },
        )
    }

    /** Schicht [key] wird offen (niemand eingeteilt). */
    fun clear(plan: ShiftPlan, key: SlotKey): ShiftPlan =
        plan.copy(slots = plan.slots.map { if (it.key == key) it.copy(employeeId = null) else it })

    /** Person hat an diesem Tag frei: Ihre Schichten an dem Tag werden offen. */
    fun free(plan: ShiftPlan, employeeId: String, date: LocalDate): ShiftPlan =
        plan.copy(slots = plan.slots.map { if (it.employeeId == employeeId && it.date == date) it.copy(employeeId = null) else it })

    /** Was gegen neue Uhrzeiten spricht, oder null. */
    fun timeProblem(start: LocalTime, end: LocalTime): String? =
        if (!end.isAfter(start)) "Das Ende muss nach dem Beginn liegen." else null

    /** Neue Uhrzeiten für eine Schicht, nur an diesem Tag. Ungültige Zeiten (siehe [timeProblem]) ändern nichts. */
    fun setTimes(plan: ShiftPlan, key: SlotKey, start: LocalTime, end: LocalTime): ShiftPlan {
        if (timeProblem(start, end) != null) return plan
        return plan.copy(
            slots = plan.slots.map {
                if (it.key == key) it.copy(start = it.date.atTime(start), end = it.date.atTime(end)) else it
            },
        )
    }
}
