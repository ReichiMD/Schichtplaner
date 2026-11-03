package de.reichimd.schichtplaner

import de.reichimd.schichtplaner.core.Employee
import de.reichimd.schichtplaner.core.PlanningRequest
import de.reichimd.schichtplaner.core.shop.Shop
import de.reichimd.schichtplaner.core.shop.ShopSettings
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Erfundene Beispieldaten für die Test-App. Keine echten Namen.
 * Team und Laden werden inzwischen in der App gepflegt; das hier gilt nur, solange dort nichts steht.
 */
object DemoData {

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

    /** Ab dem nächsten Montag, so viele Wochen wie im Laden eingestellt. */
    fun request(
        today: LocalDate = LocalDate.now(),
        employees: List<Employee> = team,
        laden: ShopSettings = Shop.EXAMPLE,
    ) = PlanningRequest(
        firstDay = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)),
        days = laden.planDays,
        shop = laden.toConfig(),
        employees = employees,
    )
}
