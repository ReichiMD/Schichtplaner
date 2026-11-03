package de.reichimd.schichtplaner

import de.reichimd.schichtplaner.core.Employee
import de.reichimd.schichtplaner.core.OpeningHours
import de.reichimd.schichtplaner.core.PlanningRequest
import de.reichimd.schichtplaner.core.ShiftEnd
import de.reichimd.schichtplaner.core.ShiftKind
import de.reichimd.schichtplaner.core.ShiftStart
import de.reichimd.schichtplaner.core.ShiftTemplate
import de.reichimd.schichtplaner.core.ShopConfig
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/**
 * Erfundene Beispieldaten für die erste Test-App. Keine echten Namen.
 * Das Team wird inzwischen in der App gepflegt; der Laden folgt in einem späteren Schritt.
 */
object DemoData {

    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)

    val shop = ShopConfig(
        openingHours = DayOfWeek.entries.associateWith {
            when (it) {
                DayOfWeek.SATURDAY -> OpeningHours(t(7), t(16))
                DayOfWeek.SUNDAY -> OpeningHours(t(7), t(17))
                else -> OpeningHours(t(6), t(18))
            }
        },
        holidayHours = OpeningHours(t(7), t(17)),
        templates = listOf(
            ShiftTemplate("f1", "Früh 1", ShiftKind.EARLY, ShiftStart.At(t(4, 30)), ShiftEnd.At(t(11, 30))),
            ShiftTemplate("f2", "Früh 2", ShiftKind.EARLY, ShiftStart.At(t(5, 30)), ShiftEnd.At(t(12, 30))),
            ShiftTemplate("sp", "Spät", ShiftKind.LATE, ShiftStart.BeforeClose(330), ShiftEnd.AfterClose(30)),
        ),
    )

    val team = listOf(
        Employee("1", "Anna", 120),
        Employee("2", "Ben", 120),
        Employee("3", "Clara", 100),
        Employee("4", "David", 100),
        Employee("5", "Ella", 80, canEarly = false),
        Employee("6", "Finn", 80, blockedWeekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)),
        Employee("7", "Greta", 40),
        Employee("8", "Hanna", 40, isBackup = true),
    )

    /** Zwei Wochen ab dem nächsten Montag. */
    fun request(today: LocalDate = LocalDate.now(), employees: List<Employee> = team) = PlanningRequest(
        firstDay = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)),
        days = 14,
        shop = shop,
        employees = employees,
    )
}
