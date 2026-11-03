package de.reichimd.schichtplaner.core.shop

import de.reichimd.schichtplaner.core.OpeningHours
import de.reichimd.schichtplaner.core.Problem
import de.reichimd.schichtplaner.core.ShiftCalendar
import de.reichimd.schichtplaner.core.ShiftEnd
import de.reichimd.schichtplaner.core.ShiftKind
import de.reichimd.schichtplaner.core.ShiftStart
import de.reichimd.schichtplaner.core.ShiftTemplate
import de.reichimd.schichtplaner.core.ShopConfig
import de.reichimd.schichtplaner.core.Weekdays
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

/**
 * Öffnungszeit eines Tages, so wie sie eingegeben wurde. Anders als [OpeningHours] darf sie
 * vorübergehend ungültig sein (Schluss vor Öffnung), damit die App das am Tag selbst anzeigen kann.
 */
data class DayHours(val open: LocalTime, val close: LocalTime)

/**
 * Alles, was die Filialleitung zum Laden einstellt. Fehlt ein Wochentag in [openingHours], ist dann zu.
 * Feiertage: vorerst wie Sonntag (Öffnungszeit und Schichten), bis die echten Zeiten geklärt sind.
 */
data class ShopSettings(
    val openingHours: Map<DayOfWeek, DayHours>,
    val templates: List<ShiftTemplate>,
    /** Planungszeitraum in Wochen: 2 oder 4. */
    val planWeeks: Int = 2,
) {
    val planDays: Int get() = planWeeks * 7

    /** Für den Planer. Nur mit gültigen Einstellungen aufrufen (siehe [Shop.problems]). */
    fun toConfig(holidays: Set<LocalDate> = emptySet()): ShopConfig = ShopConfig(
        openingHours = openingHours.mapValues { (_, h) -> OpeningHours(h.open, h.close) },
        holidayHours = openingHours[DayOfWeek.SUNDAY]?.let { OpeningHours(it.open, it.close) },
        holidays = holidays,
        templates = templates.map { it.copy(onHolidays = DayOfWeek.SUNDAY in it.weekdays) },
    )
}

/** Wo ein Problem in den Laden-Einstellungen angezeigt wird. */
sealed interface ShopField {
    data class Day(val day: DayOfWeek) : ShopField
    data class Template(val id: String, val part: TemplatePart) : ShopField
    /** Betrifft die Schichten insgesamt (z. B. zu viele). */
    data object Templates : ShopField
    data object Weeks : ShopField
}

enum class TemplatePart { NAME, START, END, DAYS, STAFF }

/** Regeln und Hilfen für die Laden-Einstellungen. Reines Kotlin, komplett ohne Handy testbar. */
object Shop {

    const val TIME_STEP_MINUTES = 15
    const val MAX_TEMPLATES = 12
    const val MAX_STAFF = 5
    const val MAX_NAME_LENGTH = 20
    /** Arbeitszeitgesetz §3: höchstens 10 Stunden am Tag. */
    const val MAX_SHIFT_HOURS = 10L
    const val MAX_BEFORE_CLOSE_MINUTES = 12 * 60L
    const val MAX_AFTER_CLOSE_MINUTES = 3 * 60L
    val PLAN_WEEKS = listOf(2, 4)

    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)

    private val MO_SA = DayOfWeek.entries.toSet() - DayOfWeek.SUNDAY

    /**
     * Vorgabe, solange die Filialleitung noch nichts eingestellt hat. Alles davon ist in der App änderbar.
     * Zwei Frühschichten und eine Spätschicht bis 30 min nach Ladenschluss (sonntags ab 12 Uhr).
     */
    val EXAMPLE = ShopSettings(
        openingHours = DayOfWeek.entries.associateWith {
            when (it) {
                DayOfWeek.SATURDAY -> DayHours(t(7), t(16))
                DayOfWeek.SUNDAY -> DayHours(t(7), t(17))
                else -> DayHours(t(6), t(18))
            }
        },
        templates = listOf(
            ShiftTemplate("f1", "Früh 1", ShiftKind.EARLY, ShiftStart.At(t(4, 30)), ShiftEnd.At(t(11, 30))),
            ShiftTemplate("f2", "Früh 2", ShiftKind.EARLY, ShiftStart.At(t(5, 30)), ShiftEnd.At(t(12, 30))),
            ShiftTemplate("sp", "Spät", ShiftKind.LATE, ShiftStart.At(t(12, 30)), ShiftEnd.AfterClose(30), weekdays = MO_SA),
            ShiftTemplate(
                "sp-so", "Spät So", ShiftKind.LATE, ShiftStart.At(t(12)), ShiftEnd.AfterClose(30),
                weekdays = setOf(DayOfWeek.SUNDAY),
            ),
        ),
        planWeeks = 2,
    )

    fun cleanName(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")

    /**
     * Uhrzeit einen Rasterschritt vor ([up]) oder zurück, im Bereich 00:00 bis 23:45.
     * Liegt die Zeit neben dem Raster (z. B. 05:10), geht es zuerst auf den nächsten Rasterpunkt.
     */
    fun stepTime(time: LocalTime, up: Boolean): LocalTime {
        val now = time.hour * 60 + time.minute
        val snapped = now / TIME_STEP_MINUTES * TIME_STEP_MINUTES
        val next = when {
            up -> snapped + TIME_STEP_MINUTES
            snapped != now -> snapped
            else -> snapped - TIME_STEP_MINUTES
        }.coerceIn(0, 24 * 60 - TIME_STEP_MINUTES)
        return LocalTime.of(next / 60, next % 60)
    }

    /** Alle Probleme, die das Speichern verhindern. Leere Liste = alles in Ordnung. */
    fun problems(settings: ShopSettings): List<Problem<ShopField>> {
        val result = mutableListOf<Problem<ShopField>>()

        for ((day, h) in settings.openingHours.toSortedMap()) {
            if (!h.close.isAfter(h.open)) {
                result += Problem(ShopField.Day(day), "Schluss muss nach der Öffnung liegen.")
            }
        }

        if (settings.planWeeks !in PLAN_WEEKS) {
            result += Problem(ShopField.Weeks, "Bitte 2 oder 4 Wochen wählen.")
        }

        if (settings.templates.size > MAX_TEMPLATES) {
            result += Problem(ShopField.Templates, "Höchstens $MAX_TEMPLATES Schichten.")
        }
        val ids = settings.templates.map { it.id }
        if (ids.any { it.isBlank() } || ids.size != ids.toSet().size) {
            result += Problem(ShopField.Templates, "Interner Fehler: Schicht-Kennungen sind nicht eindeutig.")
        }

        for (template in settings.templates) {
            result += templateProblems(template, settings)
        }
        return result
    }

    private fun templateProblems(template: ShiftTemplate, settings: ShopSettings): List<Problem<ShopField>> {
        val result = mutableListOf<Problem<ShopField>>()
        fun add(part: TemplatePart, message: String) {
            result += Problem(ShopField.Template(template.id, part), message)
        }

        val name = cleanName(template.name)
        when {
            name.isEmpty() -> add(TemplatePart.NAME, "Bitte einen Namen eingeben, z. B. „Früh 1“.")
            name.length > MAX_NAME_LENGTH -> add(TemplatePart.NAME, "Der Name ist zu lang (höchstens $MAX_NAME_LENGTH Zeichen).")
            settings.templates.any { it.id != template.id && cleanName(it.name).equals(name, ignoreCase = true) } ->
                add(TemplatePart.NAME, "„$name“ gibt es schon. Bitte einen anderen Namen wählen.")
        }

        if (template.weekdays.isEmpty()) add(TemplatePart.DAYS, "Bitte mindestens einen Tag wählen.")
        if (template.staff !in 1..MAX_STAFF) add(TemplatePart.STAFF, "Pro Schicht 1 bis $MAX_STAFF Personen.")

        when (val s = template.start) {
            is ShiftStart.BeforeClose -> if (s.minutes !in 0..MAX_BEFORE_CLOSE_MINUTES) {
                add(TemplatePart.START, "Höchstens ${MAX_BEFORE_CLOSE_MINUTES / 60} Stunden vor Ladenschluss.")
            }
            is ShiftStart.At -> Unit
        }
        when (val e = template.end) {
            is ShiftEnd.AfterClose -> if (e.minutes !in 0..MAX_AFTER_CLOSE_MINUTES) {
                add(TemplatePart.END, "Höchstens ${MAX_AFTER_CLOSE_MINUTES / 60} Stunden nach Ladenschluss.")
            }
            is ShiftEnd.At -> Unit
        }
        if (result.any { (it.field as ShopField.Template).part in setOf(TemplatePart.START, TemplatePart.END) }) {
            return result
        }

        // Uhrzeiten an jedem Tag prüfen, an dem die Schicht gebraucht wird und der Laden offen ist.
        val reference = LocalDate.of(2024, 1, 1) // ein Montag; nur zum Rechnen
        val startBeforeMidnight = mutableListOf<DayOfWeek>()
        val endAfterMidnight = mutableListOf<DayOfWeek>()
        val endBeforeStart = mutableListOf<DayOfWeek>()
        val tooLong = mutableListOf<DayOfWeek>()
        for (day in template.weekdays.sorted()) {
            val h = settings.openingHours[day] ?: continue
            if (!h.close.isAfter(h.open)) continue // steht schon am Tag selbst
            val date = reference.plusDays(day.value - 1L)
            val (start, end) = ShiftCalendar.times(template, date, OpeningHours(h.open, h.close))
            when {
                start.toLocalDate() != date -> startBeforeMidnight += day
                !end.isBefore(date.plusDays(1).atStartOfDay()) -> endAfterMidnight += day
                !end.isAfter(start) -> endBeforeStart += day
                Duration.between(start, end) > Duration.ofHours(MAX_SHIFT_HOURS) -> tooLong += day
            }
        }
        fun days(list: List<DayOfWeek>) = list.joinToString(", ") { Weekdays.short(it) }
        if (startBeforeMidnight.isNotEmpty()) {
            add(TemplatePart.START, "Am ${days(startBeforeMidnight)} läge der Beginn vor Mitternacht. Bitte früher oder fest einstellen.")
        }
        if (endAfterMidnight.isNotEmpty()) {
            add(TemplatePart.END, "Am ${days(endAfterMidnight)} ginge die Schicht über Mitternacht. Das geht nicht.")
        }
        if (endBeforeStart.isNotEmpty()) {
            add(TemplatePart.END, "Am ${days(endBeforeStart)} wäre das Ende nicht nach dem Beginn.")
        }
        if (tooLong.isNotEmpty()) {
            add(TemplatePart.END, "Am ${days(tooLong)} wäre die Schicht länger als $MAX_SHIFT_HOURS Stunden (Arbeitszeitgesetz).")
        }
        return result
    }

    /** Hinweise, die das Speichern nicht verhindern, aber auffallen sollen. */
    fun warnings(settings: ShopSettings): List<String> {
        val result = mutableListOf<String>()
        if (settings.openingHours.isEmpty()) {
            result += "Der Laden ist an allen Tagen zu. So gibt es nichts zu planen."
            return result
        }
        if (settings.templates.isEmpty()) {
            result += "Noch keine Schicht angelegt. So gibt es nichts zu planen."
            return result
        }
        val withoutShift = settings.openingHours.keys.sorted().filter { day -> settings.templates.none { day in it.weekdays } }
        if (withoutShift.isNotEmpty()) {
            result += "Am ${withoutShift.joinToString(", ") { Weekdays.short(it) }} ist offen, aber keine Schicht vorgesehen."
        }
        for (template in settings.templates) {
            if (template.weekdays.isNotEmpty() && template.weekdays.none { it in settings.openingHours }) {
                result += "„${template.name}“ fällt nur auf Tage, an denen der Laden zu ist."
            }
        }
        return result
    }

    fun format(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

    /** Dauer als "30 min", "1 h" oder "1 h 30 min". */
    fun formatMinutes(minutes: Long): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0L -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }

    fun describeStart(start: ShiftStart): String = when (start) {
        is ShiftStart.At -> format(start.time)
        is ShiftStart.BeforeClose -> if (start.minutes == 0L) "Ladenschluss" else "${formatMinutes(start.minutes)} vor Schluss"
    }

    fun describeEnd(end: ShiftEnd): String = when (end) {
        is ShiftEnd.At -> format(end.time)
        is ShiftEnd.AfterClose -> if (end.minutes == 0L) "Ladenschluss" else "${formatMinutes(end.minutes)} nach Schluss"
    }

    /**
     * Wann die Schicht an welchen Tagen konkret liegt, z. B. ["Mo–Fr 12:30–18:30", "Sa 12:30–16:30"].
     * Tage, an denen der Laden zu ist oder die Zeiten ungültig sind, fehlen.
     */
    fun preview(template: ShiftTemplate, openingHours: Map<DayOfWeek, DayHours>): List<String> {
        val reference = LocalDate.of(2024, 1, 1)
        val byTimes = linkedMapOf<String, MutableList<DayOfWeek>>()
        for (day in template.weekdays.sorted()) {
            val h = openingHours[day] ?: continue
            if (!h.close.isAfter(h.open)) continue
            val date = reference.plusDays(day.value - 1L)
            val (start, end) = ShiftCalendar.times(template, date, OpeningHours(h.open, h.close))
            if (start.toLocalDate() != date || end.toLocalDate() != date || !end.isAfter(start)) continue
            byTimes.getOrPut("${format(start.toLocalTime())}–${format(end.toLocalTime())}") { mutableListOf() } += day
        }
        return byTimes.map { (times, days) -> "${Weekdays.describe(days)} $times" }
    }

    /** Fügt eine neue Schicht hinten an oder ersetzt die mit gleicher ID an ihrer Stelle. */
    fun upsert(settings: ShopSettings, template: ShiftTemplate): ShopSettings {
        val cleaned = template.copy(name = cleanName(template.name))
        val index = settings.templates.indexOfFirst { it.id == cleaned.id }
        val templates = if (index < 0) settings.templates + cleaned else settings.templates.toMutableList().also { it[index] = cleaned }
        return settings.copy(templates = templates)
    }

    fun remove(settings: ShopSettings, id: String): ShopSettings =
        settings.copy(templates = settings.templates.filterNot { it.id == id })
}
