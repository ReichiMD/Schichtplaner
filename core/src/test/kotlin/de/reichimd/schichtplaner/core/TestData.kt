package de.reichimd.schichtplaner.core

import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertTrue
import kotlin.test.fail

/** Erfundener Beispiel-Laden für die Tests. Keine echten Daten. */
object TestData {

    /** Montag. */
    val MONDAY_START: LocalDate = LocalDate.of(2026, 11, 2)

    fun t(h: Int, m: Int = 0): LocalTime = LocalTime.of(h, m)

    val earlyA = ShiftTemplate("fa", "Früh A", ShiftKind.EARLY, ShiftStart.At(t(5)), ShiftEnd.At(t(12)))
    val earlyB = ShiftTemplate("fb", "Früh B", ShiftKind.EARLY, ShiftStart.At(t(6)), ShiftEnd.At(t(13)))
    val late = ShiftTemplate(
        "sp", "Spät", ShiftKind.LATE,
        ShiftStart.At(t(13)), ShiftEnd.AfterClose(30),
        weekdays = setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY),
        onHolidays = false,
    )

    fun shop(holidays: Set<LocalDate> = emptySet()) = ShopConfig(
        openingHours = DayOfWeek.entries.associateWith {
            when (it) {
                SATURDAY -> OpeningHours(t(6), t(16))
                SUNDAY -> OpeningHours(t(7), t(13))
                else -> OpeningHours(t(6), t(19))
            }
        },
        holidayHours = OpeningHours(t(7), t(13)),
        holidays = holidays,
        templates = listOf(earlyA, earlyB, late),
    )

    fun team() = listOf(
        Employee("a", "Anna", 120),
        Employee("b", "Ben", 120),
        Employee("c", "Clara", 100),
        Employee("d", "David", 100),
        Employee("e", "Ella", 80),
        Employee("f", "Finn", 40),
    )

    fun request(
        employees: List<Employee> = team(),
        absences: List<Absence> = emptyList(),
        days: Int = 14,
        holidays: Set<LocalDate> = emptySet(),
        rules: PlanningRules = PlanningRules(),
        workedDaysBefore: Map<String, Int> = emptyMap(),
    ) = PlanningRequest(MONDAY_START, days, shop(holidays), employees, absences, rules, workedDaysBefore)

    fun plan(request: PlanningRequest, seconds: Long = 3): Plan =
        Planner.plan(request, timeLimitMillis = seconds * 1000).also { assertHardRules(request, it) }

    /** Prüft alle harten Regeln. Wird nach jedem Planerlauf aufgerufen. */
    fun assertHardRules(request: PlanningRequest, plan: Plan) {
        val byId = request.employees.associateBy { it.id }
        val blocked = request.absences
            .filter { it.type != AbsenceType.DAY_OFF_WISH }
            .map { it.employeeId to it.date }
            .toSet()

        // Jede Schicht genau einmal: besetzt oder unbesetzt.
        val all = ShiftCalendar.shifts(request.firstDay, request.days, request.shop)
        val covered = plan.assignments.map { it.shift } + plan.unfilled
        assertTrue(covered.size == all.size && covered.toSet() == all.toSet(), "Jede Schicht muss genau einmal vorkommen")

        for (a in plan.assignments) {
            val e = byId[a.employeeId] ?: fail("Unbekannte Person ${a.employeeId}")
            assertTrue(e.canWork(a.shift.kind), "${e.name} kann ${a.shift.kind} nicht")
            assertTrue(a.shift.date.dayOfWeek !in e.blockedWeekdays, "${e.name} ist am ${a.shift.date.dayOfWeek} gesperrt")
            assertTrue((e.id to a.shift.date) !in blocked, "${e.name} hat am ${a.shift.date} Urlaub oder ist krank")
        }

        for (e in request.employees) {
            val shifts = plan.shiftsOf(e.id).sortedBy { it.start }
            val days = shifts.map { it.date }
            assertTrue(days.size == days.toSet().size, "${e.name} hat zwei Schichten an einem Tag")

            for (i in 1 until shifts.size) {
                val rest = Duration.between(shifts[i - 1].end, shifts[i].start)
                assertTrue(
                    rest >= Duration.ofHours(request.rules.minRestHours),
                    "${e.name}: nur ${rest.toMinutes()} min Ruhe vor ${shifts[i].date}",
                )
            }

            val worked = days.toSet()
            var run = (request.workedDaysBefore[e.id] ?: 0)
            for (d in 0 until request.days) {
                val date = request.firstDay.plusDays(d.toLong())
                run = if (date in worked) run + 1 else 0
                assertTrue(run <= request.rules.maxConsecutiveDays, "${e.name}: $run Tage am Stück bis $date")
            }
        }
    }
}
