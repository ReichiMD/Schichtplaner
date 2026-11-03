package de.reichimd.schichtplaner.core.team

import de.reichimd.schichtplaner.core.Employee
import java.time.DayOfWeek

/**
 * Regeln und Hilfen für die Teamliste, die die Filialleitung in der App pflegt.
 * Reines Kotlin, damit alles ohne Handy getestet werden kann.
 */
object Team {

    const val MAX_NAME_LENGTH = 30
    const val MIN_HOURS = 0
    const val MAX_HOURS = 250
    const val HOURS_STEP = 5
    val HOURS_PRESETS = listOf(40, 80, 100, 120)

    /**
     * Beispiel-Team zum Ausprobieren, per Knopf ladbar solange das Team leer ist.
     * Nur Vornamen; Stunden und Regeln sind ausgedachte Beispielwerte, keine echten Angaben.
     */
    val EXAMPLE: List<Employee> = listOf(
        Employee("bsp-1", "Conny", 120),
        Employee("bsp-2", "Stephie", 120),
        Employee("bsp-3", "Elke", 120),
        Employee("bsp-4", "Isa", 100),
        Employee("bsp-5", "Niclas", 80, canEarly = false, blockedWeekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)),
        Employee("bsp-6", "Adriana", 80),
        Employee("bsp-7", "Anastasia", 40, canEarly = false),
        Employee("bsp-8", "Shirin", 40, isBackup = true),
    )

    /** Vorname bereinigen: Ränder weg, mehrfache Leerzeichen zu einem. */
    fun cleanName(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")

    /** Stunden auf den erlaubten Bereich begrenzen. */
    fun clampHours(hours: Int): Int = hours.coerceIn(MIN_HOURS, MAX_HOURS)

    /**
     * Prüft einen Mitarbeiter vor dem Speichern. Leere Liste = alles in Ordnung.
     * [others] ist das restliche Team (der Mitarbeiter selbst darf darin stehen, er wird über die ID erkannt).
     */
    fun problems(employee: Employee, others: List<Employee>): List<String> {
        val result = mutableListOf<String>()
        val name = cleanName(employee.name)
        when {
            name.isEmpty() -> result += "Bitte einen Vornamen eingeben."
            name.length > MAX_NAME_LENGTH -> result += "Der Name ist zu lang (höchstens $MAX_NAME_LENGTH Zeichen)."
            others.any { it.id != employee.id && cleanName(it.name).equals(name, ignoreCase = true) } ->
                result += "„$name“ gibt es schon. Bitte unterscheidbar machen, z. B. „$name B.“"
        }
        if (employee.monthlyHours !in MIN_HOURS..MAX_HOURS) {
            result += "Die Monatsstunden müssen zwischen $MIN_HOURS und $MAX_HOURS liegen."
        }
        if (!employee.canEarly && !employee.canLate) {
            result += "Mindestens Früh- oder Spätschicht muss gehen, sonst wird die Person nie eingeplant."
        }
        if (employee.blockedWeekdays.size == DayOfWeek.entries.size) {
            result += "An allen sieben Tagen gesperrt: So wird die Person nie eingeplant."
        }
        return result
    }

    /** Fügt einen neuen Mitarbeiter hinten an oder ersetzt den mit gleicher ID an seiner Stelle. */
    fun upsert(team: List<Employee>, employee: Employee): List<Employee> {
        val cleaned = employee.copy(name = cleanName(employee.name))
        val index = team.indexOfFirst { it.id == cleaned.id }
        return if (index < 0) team + cleaned else team.toMutableList().also { it[index] = cleaned }
    }

    fun remove(team: List<Employee>, id: String): List<Employee> = team.filterNot { it.id == id }

    private val SHORT_DAY = mapOf(
        DayOfWeek.MONDAY to "Mo",
        DayOfWeek.TUESDAY to "Di",
        DayOfWeek.WEDNESDAY to "Mi",
        DayOfWeek.THURSDAY to "Do",
        DayOfWeek.FRIDAY to "Fr",
        DayOfWeek.SATURDAY to "Sa",
        DayOfWeek.SUNDAY to "So",
    )

    fun shortDay(day: DayOfWeek): String = SHORT_DAY.getValue(day)

    /** Kurze Merkmale für die Teamliste, z. B. "Nur spät", "Nie Mo, Di", "Aushilfe". */
    fun tags(employee: Employee): List<String> {
        val result = mutableListOf<String>()
        result += when {
            employee.canEarly && employee.canLate -> "Früh und spät"
            employee.canEarly -> "Nur früh"
            employee.canLate -> "Nur spät"
            else -> "Keine Schicht"
        }
        if (employee.blockedWeekdays.isNotEmpty()) {
            result += "Nie " + employee.blockedWeekdays.sorted().joinToString(", ") { shortDay(it) }
        }
        if (employee.isBackup) result += "Aushilfe"
        return result
    }
}
