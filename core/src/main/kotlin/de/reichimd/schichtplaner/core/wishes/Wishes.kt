package de.reichimd.schichtplaner.core.wishes

import de.reichimd.schichtplaner.core.Absence
import de.reichimd.schichtplaner.core.AbsenceType
import de.reichimd.schichtplaner.core.Employee
import java.time.LocalDate
import java.time.YearMonth

/**
 * Regeln für den Wünsche-Kalender: Urlaub, Frei-Wünsche und Krankmeldungen je Person und Tag.
 * Reines Kotlin, damit alles ohne Handy getestet werden kann.
 *
 * Pro Person und Tag gibt es höchstens einen Eintrag. Die Liste ist immer sortiert (Datum, Person),
 * damit die gespeicherte Datei bei gleichem Inhalt gleich aussieht.
 */
object Wishes {

    /** Wie weit man im Kalender zurück- und vorblättern kann, ab dem aktuellen Monat. */
    const val MONTHS_BACK = 1
    const val MONTHS_AHEAD = 12

    /** Kürzel wie auf dem Papierplan. */
    fun code(type: AbsenceType): String = when (type) {
        AbsenceType.VACATION -> "U"
        AbsenceType.DAY_OFF_WISH -> "Fr"
        AbsenceType.SICK -> "K"
    }

    fun label(type: AbsenceType): String = when (type) {
        AbsenceType.VACATION -> "Urlaub"
        AbsenceType.DAY_OFF_WISH -> "Frei-Wunsch"
        AbsenceType.SICK -> "Krank"
    }

    /** Reihenfolge der Knöpfe im Kalender. */
    val TYPES = listOf(AbsenceType.VACATION, AbsenceType.DAY_OFF_WISH, AbsenceType.SICK)

    fun normalize(entries: Collection<Absence>): List<Absence> =
        entries
            .associateBy { it.employeeId to it.date } // pro Person und Tag nur einer, der letzte gewinnt
            .values
            .sortedWith(compareBy<Absence> { it.date }.thenBy { it.employeeId })

    /**
     * Antippen eines Tages: Steht dort schon dieselbe Art, wird sie entfernt.
     * Steht dort etwas anderes oder nichts, gilt danach [type].
     */
    fun toggle(entries: List<Absence>, employeeId: String, date: LocalDate, type: AbsenceType): List<Absence> {
        val current = entries.firstOrNull { it.employeeId == employeeId && it.date == date }
        val rest = entries.filterNot { it.employeeId == employeeId && it.date == date }
        return normalize(if (current?.type == type) rest else rest + Absence(employeeId, date, type))
    }

    fun on(entries: List<Absence>, employeeId: String, date: LocalDate): AbsenceType? =
        entries.firstOrNull { it.employeeId == employeeId && it.date == date }?.type

    fun ofEmployee(entries: List<Absence>, employeeId: String): List<Absence> =
        entries.filter { it.employeeId == employeeId }

    fun removeEmployee(entries: List<Absence>, employeeId: String): List<Absence> =
        entries.filterNot { it.employeeId == employeeId }

    /** Einträge einer Person in einem Monat, als Datum → Art. */
    fun inMonth(entries: List<Absence>, employeeId: String, month: YearMonth): Map<LocalDate, AbsenceType> =
        entries
            .filter { it.employeeId == employeeId && YearMonth.from(it.date) == month }
            .associate { it.date to it.type }

    /** Wie viele Tage je Art eine Person in einem Monat eingetragen hat. Fehlende Arten zählen 0. */
    fun countInMonth(entries: List<Absence>, employeeId: String, month: YearMonth): Map<AbsenceType, Int> {
        val found = inMonth(entries, employeeId, month).values.groupingBy { it }.eachCount()
        return TYPES.associateWith { found[it] ?: 0 }
    }

    /** "3 Tage Urlaub, 1 Frei-Wunsch" bzw. "Nichts eingetragen". */
    fun describeCounts(counts: Map<AbsenceType, Int>): String {
        val parts = TYPES.mapNotNull { t ->
            val n = counts[t] ?: 0
            if (n == 0) return@mapNotNull null
            when (t) {
                AbsenceType.VACATION -> if (n == 1) "1 Tag Urlaub" else "$n Tage Urlaub"
                AbsenceType.DAY_OFF_WISH -> if (n == 1) "1 Frei-Wunsch" else "$n Frei-Wünsche"
                AbsenceType.SICK -> if (n == 1) "1 Tag krank" else "$n Tage krank"
            }
        }
        return if (parts.isEmpty()) "Nichts eingetragen" else parts.joinToString(", ")
    }

    /**
     * Felder eines Monatskalenders, Woche beginnt am Montag. Vor dem 1. stehen so viele null,
     * wie Tage der Vorwoche fehlen; hinten wird auf volle Wochen aufgefüllt.
     */
    fun monthGrid(month: YearMonth): List<LocalDate?> {
        val first = month.atDay(1)
        val lead = first.dayOfWeek.value - 1
        val days = (1..month.lengthOfMonth()).map { month.atDay(it) }
        val cells = List(lead) { null } + days
        val trail = (7 - cells.size % 7) % 7
        return cells + List(trail) { null }
    }

    /** Der Monat, den man beim Öffnen sieht: der Monat, in dem der nächste Plan beginnt. */
    fun startMonth(planStart: LocalDate): YearMonth = YearMonth.from(planStart)

    fun canGoBack(month: YearMonth, today: LocalDate): Boolean =
        month > YearMonth.from(today).minusMonths(MONTHS_BACK.toLong())

    fun canGoForward(month: YearMonth, today: LocalDate): Boolean =
        month < YearMonth.from(today).plusMonths(MONTHS_AHEAD.toLong())

    /**
     * Was der Planer bekommt: nur Einträge von Personen im Team und nur im Planungszeitraum.
     * Einträge gelöschter Personen oder anderer Monate dürfen das Soll nicht verfälschen.
     */
    fun forPlan(entries: List<Absence>, employees: List<Employee>, firstDay: LocalDate, lastDay: LocalDate): List<Absence> {
        val ids = employees.map { it.id }.toSet()
        return entries.filter { it.employeeId in ids && !it.date.isBefore(firstDay) && !it.date.isAfter(lastDay) }
    }
}
