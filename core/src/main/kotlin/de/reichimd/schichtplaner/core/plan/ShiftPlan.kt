package de.reichimd.schichtplaner.core.plan

import de.reichimd.schichtplaner.core.Plan
import de.reichimd.schichtplaner.core.PlanningRequest
import de.reichimd.schichtplaner.core.Shift
import de.reichimd.schichtplaner.core.ShiftKind
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Feste Stelle im Plan: Tag, Schichtvorlage und laufende Nummer.
 * Sie bleibt gleich, auch wenn Uhrzeit oder Person von Hand geändert werden.
 */
data class SlotKey(val date: LocalDate, val templateId: String, val slot: Int)

/** Eine Schicht im gespeicherten Plan, mit Person oder offen ([employeeId] = null). */
data class PlanSlot(
    val key: SlotKey,
    /** Name der Vorlage beim Erstellen, z. B. "Früh 1". */
    val name: String,
    val kind: ShiftKind,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val employeeId: String?,
) {
    val date: LocalDate get() = key.date
    val presenceMinutes: Long get() = Duration.between(start, end).toMinutes()

    /** Bezahlte Minuten, Pause wie beim Planer (über 6 h: 30 min). */
    val paidMinutes: Long get() = Shift.paidMinutes(presenceMinutes)
    val pauseMinutes: Long get() = presenceMinutes - paidMinutes
}

/**
 * Der fertige Plan, so wie er gespeichert und von Hand geändert wird.
 * Unabhängig vom Planer: Ändert sich später Team, Laden oder Kalender, bleibt der Plan stehen
 * und die App zeigt an, wo er nicht mehr passt (siehe [PlanCheck]).
 */
data class ShiftPlan(
    val firstDay: LocalDate,
    val days: Int,
    val slots: List<PlanSlot>,
) {
    init {
        require(days in 1..PlanningRequest.MAX_DAYS) { "Planungszeitraum muss 1 bis ${PlanningRequest.MAX_DAYS} Tage lang sein" }
        require(slots.map { it.key }.toSet().size == slots.size) { "Jede Schicht darf nur einmal vorkommen" }
        require(slots.all { it.end.isAfter(it.start) }) { "Jede Schicht muss nach ihrem Beginn enden" }
        require(slots.all { !it.date.isBefore(firstDay) && !it.date.isAfter(lastDay) }) { "Schicht außerhalb des Zeitraums" }
    }

    val lastDay: LocalDate get() = firstDay.plusDays(days.toLong() - 1)
    val weeks: Int get() = (days + 6) / 7

    fun weekStart(week: Int): LocalDate = firstDay.plusWeeks(week.toLong())

    fun slot(key: SlotKey): PlanSlot? = slots.firstOrNull { it.key == key }

    /** Schichten eines Tages in fester Reihenfolge (Beginn, Vorlage, Nummer). */
    fun slotsOn(date: LocalDate): List<PlanSlot> = slots.filter { it.date == date }.sortedWith(ORDER)

    fun slotsOf(employeeId: String, date: LocalDate): List<PlanSlot> =
        slots.filter { it.employeeId == employeeId && it.date == date }.sortedWith(ORDER)

    companion object {
        val ORDER: Comparator<PlanSlot> = compareBy<PlanSlot>({ it.start }, { it.key.templateId }, { it.key.slot })

        /** Aus dem Ergebnis des Planers. Unbesetzte Schichten stehen als offen drin. */
        fun from(request: PlanningRequest, plan: Plan): ShiftPlan {
            fun slot(s: Shift, employeeId: String?) = PlanSlot(
                key = SlotKey(s.date, s.template.id, s.slot),
                name = s.template.name,
                kind = s.kind,
                start = s.start,
                end = s.end,
                employeeId = employeeId,
            )
            val slots = plan.assignments.map { slot(it.shift, it.employeeId) } + plan.unfilled.map { slot(it, null) }
            return ShiftPlan(request.firstDay, request.days, slots.sortedWith(ORDER))
        }
    }
}
