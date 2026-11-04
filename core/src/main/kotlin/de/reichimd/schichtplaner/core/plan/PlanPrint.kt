package de.reichimd.schichtplaner.core.plan

import de.reichimd.schichtplaner.core.Absence
import de.reichimd.schichtplaner.core.AbsenceType
import de.reichimd.schichtplaner.core.Employee
import de.reichimd.schichtplaner.core.ShiftKind
import de.reichimd.schichtplaner.core.Weekdays
import de.reichimd.schichtplaner.core.wishes.Wishes
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields

/** Eine Zelle des gedruckten Wochenblatts: oben Zeit oder Kürzel, unten Pause und Stunden. */
data class PrintCell(
    val top: String,
    val bottom: String = "",
    /** Schichtart für eine leichte Hintergrundfarbe, null ohne Schicht. */
    val kind: ShiftKind? = null,
    /** false: Tag liegt außerhalb des Plans (z. B. Rest der letzten Woche), wird grau. */
    val inPlan: Boolean = true,
    /** true: freier Tag, den sich die Person gewünscht hat ("Fr" farbig hinterlegt). */
    val wish: Boolean = false,
)

sealed interface PrintRow {
    data class Person(val name: String, val cells: List<PrintCell>, val hours: String) : PrintRow
    /** Zwischenüberschrift, z. B. "Verk. aus and. Fil." wie auf dem Papier. */
    data class Section(val title: String) : PrintRow
    /** Offene Schichten je Tag (nur wenn es welche gibt). */
    data class Open(val cells: List<PrintCell>, val total: String) : PrintRow
    /** "ges. Stunden" je Tag und für die Woche. */
    data class Totals(val cells: List<String>, val total: String) : PrintRow
}

/** Ein Blatt pro Woche, wie der gewohnte Papier-Vordruck. */
data class PrintWeek(
    /** z. B. "KW 45 · 02.11. – 08.11.2026" */
    val title: String,
    /** Kopf der Tagesspalten, z. B. "Mo 02.11." */
    val days: List<String>,
    val rows: List<PrintRow>,
    /** Je Tag eine zweite Kopfzeile, z. B. "Reformationstag", sonst leer. */
    val notes: List<String> = days.map { "" },
)

/**
 * Inhalt des PDF-Exports, reines Kotlin und damit ohne Handy testbar. Gezeichnet wird in der App.
 * Gedruckt wird nur, was das Team braucht: Zeiten, Pause, Stunden und die Kürzel U, K, Fr, FF
 * (jeder freie Tag ist "Fr", an Feiertagen "FF", ein erfüllter Frei-Wunsch farbig hinterlegt).
 * Warnungen (⚠, nicht erfüllte Wünsche) gehören nicht aufs Blatt im Laden.
 */
object PlanPrint {

    const val BACKUP_SECTION = "Verk. aus and. Fil."
    const val TOTALS_LABEL = "ges. Stunden"
    const val OPEN_LABEL = "offen"
    const val FREE = "Fr"
    const val HOLIDAY = "FF"

    private val DAY = DateTimeFormatter.ofPattern("dd.MM.")
    private val DAY_YEAR = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    /** [notes]: Feiertage und kürzere Tage, siehe [PlanSheet.dayNotes]. */
    fun weeks(
        plan: ShiftPlan,
        employees: List<Employee>,
        absences: List<Absence>,
        notes: Map<LocalDate, DayNote> = emptyMap(),
    ): List<PrintWeek> = (0 until plan.weeks).map { week(plan, employees, absences, it, notes) }

    fun week(
        plan: ShiftPlan,
        employees: List<Employee>,
        absences: List<Absence>,
        week: Int,
        notes: Map<LocalDate, DayNote> = emptyMap(),
    ): PrintWeek {
        val days = PlanSheet.weekDays(plan, week)
        val teamIds = employees.map { it.id }.toSet()
        val absent = absences.associate { (it.employeeId to it.date) to it.type }

        fun person(e: Employee) = PrintRow.Person(
            name = e.name,
            cells = days.map { d ->
                when {
                    !PlanSheet.inPlan(plan, d) -> PrintCell("", inPlan = false)
                    else -> plan.slotsOf(e.id, d).firstOrNull()?.let { cell(it) } ?: free(absent[e.id to d], e.isBackup, notes[d]?.holiday == true)
                }
            },
            hours = PlanSheet.hours(PlanSheet.paidMinutes(plan, e.id, week)),
        )

        // Ausgeschiedene nur in Wochen, in denen sie gearbeitet haben.
        val working = plan.slots.filter { it.date in days }.mapNotNull { it.employeeId }.toSet()
        val shown = employees.filter { !it.archived || it.id in working }
        val rows = buildList<PrintRow> {
            shown.filter { !it.isBackup }.forEach { add(person(it)) }
            val backups = shown.filter { it.isBackup }
            if (backups.isNotEmpty()) {
                add(PrintRow.Section(BACKUP_SECTION))
                backups.forEach { add(person(it)) }
            }
            val open = days.map { d -> if (PlanSheet.inPlan(plan, d)) PlanSheet.openOn(plan, d, teamIds) else emptyList() }
            if (open.any { it.isNotEmpty() }) {
                add(
                    PrintRow.Open(
                        cells = days.zip(open).map { (d, slots) ->
                            when {
                                !PlanSheet.inPlan(plan, d) -> PrintCell("", inPlan = false)
                                slots.isEmpty() -> PrintCell("")
                                else -> PrintCell(
                                    top = "${slots.size} $OPEN_LABEL",
                                    bottom = slots.joinToString(", ") { PlanSheet.time(it.start) },
                                )
                            }
                        },
                        total = "${open.sumOf { it.size }}",
                    ),
                )
            }
            val totals = days.map { d -> if (PlanSheet.inPlan(plan, d)) PlanSheet.dayTotalMinutes(plan, d, teamIds) else null }
            add(
                PrintRow.Totals(
                    cells = totals.map { m -> m?.let { PlanSheet.hours(it) } ?: "" },
                    total = PlanSheet.hours(totals.sumOf { it ?: 0L }),
                ),
            )
        }

        val first = days.first()
        val last = days.last()
        return PrintWeek(
            title = "KW ${first.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)} · ${first.format(DAY)} – ${last.format(DAY_YEAR)}",
            days = days.map { "${Weekdays.short(it.dayOfWeek)} ${it.format(DAY)}" },
            rows = rows,
            notes = days.map { d ->
                val n = notes[d]
                when {
                    n == null || !PlanSheet.inPlan(plan, d) -> ""
                    // "zu" vorn, damit es beim Kürzen langer Namen nicht verloren geht.
                    n.closed -> "zu: ${n.label}"
                    else -> n.label
                }
            },
        )
    }

    /**
     * Tag ohne Schicht: U oder K aus dem Kalender, sonst "Fr" (farbig, wenn es ein Frei-Wunsch war).
     * An einem Feiertag ([holiday]) heißt frei "FF", auch bei Urlaub (ein Feiertag ist kein Urlaubstag);
     * nur K bleibt stehen.
     * Aushilfen aus anderen Filialen ohne Eintrag bleiben leer, sie sind ja nicht unser "Frei".
     */
    fun free(absence: AbsenceType?, backup: Boolean = false, holiday: Boolean = false): PrintCell {
        val free = if (holiday) HOLIDAY else FREE
        return when (absence) {
            null -> PrintCell(if (backup) "" else free)
            AbsenceType.DAY_OFF_WISH -> PrintCell(free, wish = true)
            AbsenceType.VACATION -> PrintCell(if (holiday) HOLIDAY else Wishes.code(absence))
            AbsenceType.SICK -> PrintCell(Wishes.code(absence))
        }
    }

    /** "4:30–11:30" und darunter "P 0,5 · 6,5" bzw. nur "6". */
    fun cell(slot: PlanSlot): PrintCell {
        val hours = PlanSheet.hours(slot.paidMinutes)
        return PrintCell(
            top = "${PlanSheet.time(slot.start)}–${PlanSheet.time(slot.end)}",
            bottom = if (slot.pauseMinutes > 0) "${PlanSheet.pause(slot)} · $hours" else hours,
            kind = slot.kind,
        )
    }

    /** Dateiname, z. B. "schichtplan-2026-11-02.pdf". */
    fun fileName(plan: ShiftPlan): String = "schichtplan-${plan.firstDay}.pdf"
}
