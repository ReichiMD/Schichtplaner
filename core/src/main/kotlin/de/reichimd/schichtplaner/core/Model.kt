package de.reichimd.schichtplaner.core

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Öffnungszeit eines Tages. */
data class OpeningHours(val open: LocalTime, val close: LocalTime) {
    init {
        require(close.isAfter(open)) { "Ladenschluss muss nach der Öffnung liegen" }
    }
}

/** Art einer Schicht. Mitarbeiter können für einzelne Arten gesperrt sein. */
enum class ShiftKind { EARLY, LATE }

/** Ende einer Schichtvorlage: feste Uhrzeit oder relativ zum Ladenschluss des Tages. */
sealed interface ShiftEnd {
    data class At(val time: LocalTime) : ShiftEnd
    data class AfterClose(val minutes: Long) : ShiftEnd
}

/** Beginn einer Schichtvorlage: feste Uhrzeit oder relativ zum Ladenschluss des Tages. */
sealed interface ShiftStart {
    data class At(val time: LocalTime) : ShiftStart
    data class BeforeClose(val minutes: Long) : ShiftStart
}

/**
 * Schichtvorlage, z. B. "Früh 1" 04:30–11:30 oder "Spät" 12:30 bis 30 min nach Ladenschluss.
 * [weekdays] legt fest, an welchen Tagen die Vorlage gebraucht wird (Feiertage zählen wie [holidayLike]).
 */
data class ShiftTemplate(
    val id: String,
    val name: String,
    val kind: ShiftKind,
    val start: ShiftStart,
    val end: ShiftEnd,
    val weekdays: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val onHolidays: Boolean = true,
    val staff: Int = 1,
)

/** Einstellungen des Ladens. Alles davon stellt die Filialleitung in der App ein. */
data class ShopConfig(
    val openingHours: Map<DayOfWeek, OpeningHours>,
    val holidayHours: OpeningHours?,
    val holidays: Set<LocalDate> = emptySet(),
    val templates: List<ShiftTemplate>,
) {
    /** Öffnungszeit an einem Datum, oder null wenn geschlossen. */
    fun hoursOn(date: LocalDate): OpeningHours? =
        if (date in holidays) holidayHours else openingHours[date.dayOfWeek]
}

data class Employee(
    val id: String,
    val name: String,
    val monthlyHours: Int,
    val canEarly: Boolean = true,
    val canLate: Boolean = true,
    /** Wochentage, an denen die Person nie kann, z. B. wegen Schule. */
    val blockedWeekdays: Set<DayOfWeek> = emptySet(),
    /** Aushilfen werden nur eingeplant, wenn es ohne sie nicht geht. */
    val isBackup: Boolean = false,
) {
    fun canWork(kind: ShiftKind) = when (kind) {
        ShiftKind.EARLY -> canEarly
        ShiftKind.LATE -> canLate
    }
}

enum class AbsenceType {
    /** U: harte Sperre. */
    VACATION,
    /** K: harte Sperre. */
    SICK,
    /** Frei-Wunsch: wird möglichst erfüllt. */
    DAY_OFF_WISH,
}

data class Absence(val employeeId: String, val date: LocalDate, val type: AbsenceType)

/** Eine konkrete Schicht an einem Datum. */
data class Shift(
    val date: LocalDate,
    val template: ShiftTemplate,
    /** Laufende Nummer, wenn die Vorlage mehrere Personen braucht. */
    val slot: Int,
    val start: LocalDateTime,
    val end: LocalDateTime,
) {
    val kind: ShiftKind get() = template.kind
    val isWeekend: Boolean get() = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY

    /** Anwesenheit in Minuten. */
    val presenceMinutes: Long get() = Duration.between(start, end).toMinutes()

    /** Bezahlte Minuten: über 6 h Anwesenheit werden 30 min Pause abgezogen (ArbZG §4). */
    val paidMinutes: Long get() = paidMinutes(presenceMinutes)

    companion object {
        fun paidMinutes(presenceMinutes: Long): Long =
            if (presenceMinutes > 6 * 60) presenceMinutes - 30 else presenceMinutes
    }
}

/** Gewichte der weichen Regeln. Höher = wichtiger. */
data class Weights(
    val unfilledShift: Int = 10_000,
    val backupShift: Int = 200,
    val dayOffWishBroken: Int = 100,
    val hoursDeviationPerQuarterHour: Int = 2,
    val weekendImbalance: Int = 60,
)

data class PlanningRules(
    val minRestHours: Long = 11,
    val maxConsecutiveDays: Int = 6,
    val weights: Weights = Weights(),
)

data class PlanningRequest(
    val firstDay: LocalDate,
    val days: Int,
    val shop: ShopConfig,
    val employees: List<Employee>,
    val absences: List<Absence> = emptyList(),
    val rules: PlanningRules = PlanningRules(),
    /** Schon gearbeitete Tage direkt vor dem Zeitraum (für "max. Tage am Stück"), je Mitarbeiter-ID. */
    val workedDaysBefore: Map<String, Int> = emptyMap(),
) {
    val lastDay: LocalDate get() = firstDay.plusDays(days.toLong() - 1)
}

data class Assignment(val shift: Shift, val employeeId: String)

data class Plan(
    val assignments: List<Assignment>,
    val unfilled: List<Shift>,
    /** true, wenn der Solver beweisen konnte, dass es keinen besseren Plan gibt. */
    val optimal: Boolean,
) {
    fun shiftsOf(employeeId: String): List<Shift> =
        assignments.filter { it.employeeId == employeeId }.map { it.shift }

    fun paidMinutesOf(employeeId: String): Long = shiftsOf(employeeId).sumOf { it.paidMinutes }
}
