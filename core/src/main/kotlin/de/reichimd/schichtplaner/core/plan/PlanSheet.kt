package de.reichimd.schichtplaner.core.plan

import de.reichimd.schichtplaner.core.Absence
import de.reichimd.schichtplaner.core.AbsenceType
import de.reichimd.schichtplaner.core.Employee
import de.reichimd.schichtplaner.core.Planner
import de.reichimd.schichtplaner.core.shop.ShopSettings
import de.reichimd.schichtplaner.core.shop.ShortDay
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.roundToLong

/** Besonderer Tag im Plan: Feiertag (freie Tage heißen dann "FF") oder kürzerer Tag wie Heiligabend. */
data class DayNote(
    /** z. B. "Reformationstag" oder "Heiligabend". */
    val label: String,
    val holiday: Boolean,
    /** Der Laden ist an diesem Tag zu (nur bei Feiertagen). */
    val closed: Boolean = false,
)

/**
 * Zahlen und Texte für das Wochenblatt wie auf dem Papier:
 * je Person Zeit, Kürzel/Pause und Stunden, unten "Verk. aus and. Fil." und "ges. Stunden".
 */
object PlanSheet {

    /** Minuten als Stunden mit Komma, ohne unnötige Nullen: 390 → "6,5", 405 → "6,75", 420 → "7". */
    fun hours(minutes: Long): String {
        val sign = if (minutes < 0) "-" else ""
        val m = kotlin.math.abs(minutes)
        // Auf Hundertstel Stunden gerundet, damit z. B. 50 min als 0,83 erscheint.
        val hundredths = (m * 100 + 30) / 60
        val whole = hundredths / 100
        val rest = hundredths % 100
        if (rest == 0L) return "$sign$whole"
        val frac = rest.toString().padStart(2, '0').trimEnd('0')
        return "$sign$whole,$frac"
    }

    /** Uhrzeit ohne führende Null, wie handgeschrieben: "4:30", "12:30". */
    fun time(t: LocalDateTime): String = "${t.hour}:${t.minute.toString().padStart(2, '0')}"

    /** Zweite Zeile einer Zelle: Pause, z. B. "P 0,5", oder leer. */
    fun pause(slot: PlanSlot): String = if (slot.pauseMinutes > 0) "P ${hours(slot.pauseMinutes)}" else ""

    fun weekDays(plan: ShiftPlan, week: Int): List<LocalDate> =
        (0L until 7L).map { plan.weekStart(week).plusDays(it) }

    fun inPlan(plan: ShiftPlan, date: LocalDate): Boolean = !date.isBefore(plan.firstDay) && !date.isAfter(plan.lastDay)

    /** Bezahlte Minuten einer Person in einer Woche. */
    fun paidMinutes(plan: ShiftPlan, employeeId: String, week: Int): Long {
        val days = weekDays(plan, week).toSet()
        return plan.slots.filter { it.employeeId == employeeId && it.date in days }.sumOf { it.paidMinutes }
    }

    /**
     * Soll einer Person in einer Woche in Minuten: Monatsstunden anteilig, Urlaubs- und Krankheitstage
     * verringern es (wie beim Planer). Tage außerhalb des Plans zählen nicht.
     */
    fun targetMinutes(plan: ShiftPlan, employee: Employee, absences: List<Absence>, week: Int): Long {
        val days = weekDays(plan, week).filter { inPlan(plan, it) }
        val absent = absences.count {
            it.employeeId == employee.id && it.date in days &&
                (it.type == AbsenceType.VACATION || it.type == AbsenceType.SICK)
        }
        val present = (days.size - absent).coerceAtLeast(0)
        return (employee.monthlyHours * 60.0 * present / Planner.AVG_DAYS_PER_MONTH).roundToLong()
    }

    /** "ges. Stunden" eines Tages: alle eingeteilten Personen aus dem Team, auch Aushilfen. */
    fun dayTotalMinutes(plan: ShiftPlan, date: LocalDate, teamIds: Set<String>): Long =
        plan.slots.filter { it.date == date && it.employeeId in teamIds }.sumOf { it.paidMinutes }

    /** Offene Schichten eines Tages: niemand eingeteilt, oder die Person ist nicht mehr im Team. */
    fun openOn(plan: ShiftPlan, date: LocalDate, teamIds: Set<String>): List<PlanSlot> =
        plan.slotsOn(date).filter { it.employeeId == null || it.employeeId !in teamIds }

    /** Feiertage und kürzere Tage von [first] bis [last]. Kürzere Tage nur, wenn sie eingestellt sind. */
    fun dayNotes(shop: ShopSettings, first: LocalDate, last: LocalDate): Map<LocalDate, DayNote> {
        val notes = mutableMapOf<LocalDate, DayNote>()
        for ((date, h) in shop.holidays(first, last)) {
            notes[date] = DayNote(h.name, holiday = true, closed = shop.isClosedHoliday(h))
        }
        var d = first
        while (!d.isAfter(last)) {
            val short = ShortDay.on(d)
            if (short != null && short in shop.shortDays && d !in notes) notes[d] = DayNote(short.label, holiday = false)
            d = d.plusDays(1)
        }
        return notes
    }

    fun openCount(plan: ShiftPlan, teamIds: Set<String>): Int =
        plan.slots.count { it.employeeId == null || it.employeeId !in teamIds }
}
