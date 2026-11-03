package de.reichimd.schichtplaner.core

import de.reichimd.schichtplaner.core.wishes.Wishes
import org.chocosolver.solver.Model
import org.chocosolver.solver.search.limits.FailCounter
import org.chocosolver.solver.search.loop.lns.INeighborFactory
import org.chocosolver.solver.search.strategy.Search
import org.chocosolver.solver.search.strategy.selectors.values.IntDomainMax
import org.chocosolver.solver.search.strategy.selectors.variables.InputOrder
import org.chocosolver.solver.variables.BoolVar
import org.chocosolver.solver.variables.IntVar
import java.time.Duration
import kotlin.math.roundToInt

/**
 * Erstellt einen Schichtplan mit dem Constraint-Solver Choco.
 *
 * Harte Regeln (werden nie verletzt):
 * - höchstens eine Schicht pro Person und Tag
 * - kein Einsatz bei Urlaub, Krankheit oder an gesperrten Wochentagen
 * - nur Schichtarten, die die Person kann
 * - Mindest-Ruhezeit zwischen zwei Schichten, auch zur letzten Schicht vor dem Zeitraum
 * - höchstens [PlanningRules.maxConsecutiveDays] Arbeitstage am Stück
 * - Aushilfen höchstens bis zu ihren (anteiligen) Monatsstunden
 *
 * Weiche Regeln (werden gewichtet möglichst gut erfüllt):
 * - jede Schicht besetzen (sehr hohes Gewicht; geht es nicht, landet sie in [Plan.unfilled])
 * - Aushilfen nur wenn nötig
 * - Frei-Wünsche erfüllen
 * - Monatsstunden anteilig treffen (Urlaubs- und Krankheitstage verringern das Soll)
 * - Wochenendschichten gerecht nach Stundenanteil verteilen
 */
object Planner {

    private const val UNIT_MINUTES = 15
    private const val AVG_DAYS_PER_MONTH = 30.4375

    /** Fester Startwert: gleiche Eingabe ergibt (bei gleicher Rechenzeit) möglichst den gleichen Plan. */
    private const val RANDOM_SEED = 42L

    /** Rechenbudget: auf jedem Gerät gleich, damit gleiche Eingaben den gleichen Plan ergeben. */
    const val DEFAULT_FAIL_LIMIT = 50_000L

    /**
     * @param timeLimitMillis Sicherheitsgrenze für sehr langsame Geräte. Greift sie vor dem Rechenbudget,
     *   kann der Plan etwas schlechter und nicht mehr exakt reproduzierbar sein.
     * @param failLimit Rechenbudget unabhängig von der Geschwindigkeit des Geräts (null = nur Zeitgrenze).
     */
    fun plan(request: PlanningRequest, timeLimitMillis: Long = 15_000, failLimit: Long? = DEFAULT_FAIL_LIMIT): Plan {
        val shifts = ShiftCalendar.shifts(request.firstDay, request.days, request.shop)
        val employees = request.employees
        val rules = request.rules
        val weights = rules.weights
        val model = Model("Schichtplan")

        // Nur Einträge im Zeitraum und von Personen im Plan: Urlaub in anderen Monaten darf das Soll nicht senken.
        val absences = Wishes.forPlan(request.absences, employees, request.firstDay, request.lastDay)
        val blocked = absences
            .filter { it.type == AbsenceType.VACATION || it.type == AbsenceType.SICK }
            .map { it.employeeId to it.date }
            .toSet()
        val wishes = absences
            .filter { it.type == AbsenceType.DAY_OFF_WISH }
            .map { it.employeeId to it.date }
            .toSet()

        val minRest = Duration.ofHours(rules.minRestHours)

        fun restedSincePreviousPlan(e: Employee, s: Shift): Boolean {
            val lastEnd = request.lastShiftEndBefore[e.id] ?: return true
            return Duration.between(lastEnd, s.start) >= minRest
        }

        fun eligible(e: Employee, s: Shift): Boolean =
            e.canWork(s.kind) &&
                s.date.dayOfWeek !in e.blockedWeekdays &&
                (e.id to s.date) !in blocked &&
                restedSincePreviousPlan(e, s)

        // x[s][e]: Person e arbeitet Schicht s. Nur für zulässige Paare angelegt.
        val x: Array<Array<BoolVar?>> = Array(shifts.size) { si ->
            Array(employees.size) { ei ->
                if (eligible(employees[ei], shifts[si])) model.boolVar("x_${si}_$ei") else null
            }
        }
        val unfilled = Array(shifts.size) { model.boolVar("frei_$it") }

        // Jede Schicht: genau eine Person oder als unbesetzt markiert.
        for (si in shifts.indices) {
            val vars: List<IntVar> = x[si].filterNotNull() + listOf(unfilled[si])
            model.sum(vars.toTypedArray<IntVar>(), "=", 1).post()
        }

        // Pro Person und Tag höchstens eine Schicht.
        val dates = (0 until request.days).map { request.firstDay.plusDays(it.toLong()) }
        val shiftsByDate = shifts.indices.groupBy { shifts[it].date }
        val worksOn = Array(employees.size) { ei ->
            Array(dates.size) { di ->
                val vars: List<IntVar> = shiftsByDate[dates[di]].orEmpty().mapNotNull { x[it][ei] }
                when (vars.size) {
                    0 -> null
                    1 -> vars[0]
                    else -> model.intVar("tag_${ei}_$di", 0, 1).also { model.sum(vars.toTypedArray<IntVar>(), "=", it).post() }
                }
            }
        }

        // Ruhezeit: zwei Schichten, zwischen denen weniger als minRestHours liegen, schließen sich aus.
        for (a in shifts.indices) for (b in shifts.indices) {
            val sa = shifts[a]
            val sb = shifts[b]
            if (sa.date >= sb.date) continue
            if (Duration.between(sa.end, sb.start) >= minRest) continue
            for (ei in employees.indices) {
                val va = x[a][ei] ?: continue
                val vb = x[b][ei] ?: continue
                model.arithm(va, "+", vb, "<=", 1).post()
            }
        }

        // Höchstens maxConsecutiveDays Tage am Stück, inklusive der Tage direkt vor dem Zeitraum.
        val maxRun = rules.maxConsecutiveDays
        for (ei in employees.indices) {
            val before = (request.workedDaysBefore[employees[ei].id] ?: 0).coerceIn(0, maxRun)
            for (windowStart in -before..(dates.size - 1)) {
                val windowEnd = windowStart + maxRun // inklusiv, Fenster hat maxRun + 1 Tage
                if (windowEnd < 0) continue
                if (windowEnd >= dates.size && windowStart >= 0) break
                val inside = (maxOf(windowStart, 0)..minOf(windowEnd, dates.size - 1))
                    .mapNotNull { worksOn[ei][it] }
                val alreadyWorked = if (windowStart < 0) -windowStart else 0
                val allowed = maxRun - alreadyWorked
                if (inside.size <= allowed) continue
                model.sum(inside.toTypedArray<IntVar>(), "<=", allowed).post()
            }
        }

        // ---------- Zielfunktion ----------
        val terms = mutableListOf<IntVar>()
        val coeffs = mutableListOf<Int>()
        fun penalize(v: IntVar, weight: Int) {
            if (weight == 0) return
            terms += v
            coeffs += weight
        }

        unfilled.forEach { penalize(it, weights.unfilledShift) }

        for (ei in employees.indices) {
            val e = employees[ei]
            if (e.isBackup) {
                for (si in shifts.indices) x[si][ei]?.let { penalize(it, weights.backupShift) }
            }
            for (si in shifts.indices) {
                val v = x[si][ei] ?: continue
                if ((e.id to shifts[si].date) in wishes) penalize(v, weights.dayOffWishBroken)
            }
        }

        // Monatsstunden anteilig auf den Zeitraum umgerechnet, in Viertelstunden.
        val regulars = employees.indices.filter { !employees[it].isBackup }
        // Urlaubs- und Krankheitstage verringern das Soll anteilig, sonst würde das volle Soll
        // in die restlichen Tage gepresst.
        val absentDays = blocked.groupingBy { it.first }.eachCount()
        fun targetUnits(e: Employee): Int {
            val presentDays = (request.days - (absentDays[e.id] ?: 0)).coerceAtLeast(0)
            return (e.monthlyHours * 60.0 * presentDays / AVG_DAYS_PER_MONTH / UNIT_MINUTES).roundToInt()
        }
        fun units(s: Shift): Int = (s.paidMinutes.toDouble() / UNIT_MINUTES).roundToInt()

        // Aushilfen: Monatsstunden (anteilig) sind eine harte Obergrenze, z. B. wegen Minijob-Grenze.
        for (ei in employees.indices.filter { employees[it].isBackup }) {
            val idx = shifts.indices.filter { x[it][ei] != null }
            if (idx.isEmpty()) continue
            val vars = idx.map<Int, IntVar> { x[it][ei]!! }.toTypedArray()
            val cap = targetUnits(employees[ei])
            model.scalar(vars, idx.map { units(shifts[it]) }.toIntArray(), "<=", cap).post()
        }

        for (ei in regulars) {
            val e = employees[ei]
            val idx = shifts.indices.filter { x[it][ei] != null }
            val vars = idx.map<Int, IntVar> { x[it][ei]!! }.toTypedArray()
            val unitArr = idx.map { units(shifts[it]) }.toIntArray()
            val maxUnits = unitArr.sum()
            val target = targetUnits(e)
            val worked = model.intVar("std_$ei", 0, maxUnits)
            model.scalar(vars, unitArr, "=", worked).post()
            val dev = model.intVar("abw_$ei", 0, maxOf(target, maxUnits))
            model.arithm(worked, "-", dev, "<=", target).post()
            model.arithm(worked, "+", dev, ">=", target).post()
            penalize(dev, weights.hoursDeviationPerQuarterHour)
        }

        // Wochenenden: Anteil wie Stundenanteil.
        val weekendShiftIdx = shifts.indices.filter { shifts[it].isWeekend }
        val totalTarget = regulars.sumOf { targetUnits(employees[it]) }
        if (weekendShiftIdx.isNotEmpty() && totalTarget > 0) {
            for (ei in regulars) {
                val vars: List<IntVar> = weekendShiftIdx.mapNotNull { x[it][ei] }
                if (vars.isEmpty()) continue
                val count = model.intVar("we_$ei", 0, vars.size)
                model.sum(vars.toTypedArray<IntVar>(), "=", count).post()
                val expected = (weekendShiftIdx.size.toDouble() * targetUnits(employees[ei]) / totalTarget).roundToInt()
                val dev = model.intVar("we_abw_$ei", 0, maxOf(expected, vars.size))
                model.arithm(count, "-", dev, "<=", expected).post()
                model.arithm(count, "+", dev, ">=", expected).post()
                penalize(dev, weights.weekendImbalance)
            }
        }

        val upper = terms.indices.sumOf { coeffs[it].toLong() * terms[it].ub }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val objective = model.intVar("strafe", 0, upper)
        model.scalar(terms.toTypedArray(), coeffs.toIntArray(), "=", objective).post()
        model.setObjective(Model.MINIMIZE, objective)

        // ---------- Lösen ----------
        val solver = model.solver
        solver.limitTime(timeLimitMillis)
        failLimit?.let { solver.limitFail(it) }

        // Suche: Schicht für Schicht besetzen ("ja" zuerst), Reihenfolge der Personen pro Schicht
        // rotiert, damit der erste Plan schon halbwegs verteilt ist.
        val ordered: List<IntVar> = shifts.indices.flatMap { si ->
            employees.indices.map { (it + si) % employees.size }.mapNotNull { x[si][it] }
        }
        if (ordered.isNotEmpty()) {
            val decisionVars = ordered.toTypedArray()
            // Alles Übrige (Abweichungen, Zielwert) so klein wie möglich festlegen.
            val decided = ordered.toSet()
            val rest = model.retrieveIntVars(true).filter { it !in decided }.toTypedArray()
            solver.setSearch(
                Search.intVarSearch(InputOrder(model), IntDomainMax(), *decisionVars),
                Search.inputOrderLBSearch(*rest),
            )
            // Danach verbessern: immer wieder einen zufälligen Teil des Plans neu rechnen (LNS).
            solver.setLNS(INeighborFactory.random(RANDOM_SEED, *decisionVars), FailCounter(model, 200))
        }
        var best: List<Pair<Int, Int>>? = null
        var bestUnfilled: List<Int> = shifts.indices.toList()
        while (solver.solve()) {
            best = buildList {
                for (si in shifts.indices) for (ei in employees.indices) {
                    if (x[si][ei]?.value == 1) add(si to ei)
                }
            }
            bestUnfilled = shifts.indices.filter { unfilled[it].value == 1 }
        }

        return Plan(
            assignments = best.orEmpty().map { (si, ei) -> Assignment(shifts[si], employees[ei].id) }
                .sortedWith(compareBy({ it.shift.start }, { it.employeeId })),
            unfilled = bestUnfilled.map { shifts[it] },
            solved = best != null,
        )
    }
}
