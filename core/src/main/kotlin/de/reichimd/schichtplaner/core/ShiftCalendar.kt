package de.reichimd.schichtplaner.core

import java.time.LocalDate
import java.time.LocalDateTime

/** Erzeugt aus den Schichtvorlagen die konkreten Schichten eines Zeitraums. */
object ShiftCalendar {

    /** An kürzeren Tagen fallen Restschichten unter dieser Länge weg (z. B. 14:00–14:30). */
    const val MIN_SHORT_DAY_MINUTES = 60L

    fun shifts(firstDay: LocalDate, days: Int, shop: ShopConfig): List<Shift> {
        val result = mutableListOf<Shift>()
        for (offset in 0 until days) {
            val date = firstDay.plusDays(offset.toLong())
            val hours = shop.hoursOn(date) ?: continue
            val isHoliday = shop.isHoliday(date)
            val isShort = shop.isShortDay(date)
            for (template in shop.templates) {
                val applies = if (isHoliday) template.onHolidays else date.dayOfWeek in template.weekdays
                if (!applies) continue
                var (start, end) = times(template, date, hours)
                if (isShort) {
                    // Kürzerer Tag: Schicht endet spätestens zum Feierabend. Bleibt weniger als eine Stunde, fällt sie weg.
                    val close = date.atTime(hours.close)
                    if (end.isAfter(close)) end = close
                    if (start.toLocalDate() != date || java.time.Duration.between(start, end).toMinutes() < MIN_SHORT_DAY_MINUTES) continue
                }
                // Feiertag mit Zeiten, die nicht passen (nur bei ungeprüften Daten, z. B. aus einer Sicherung):
                // Schicht weglassen statt die Plan-Ansicht abstürzen zu lassen.
                if (isHoliday && (start.toLocalDate() != date || end.toLocalDate() != date || !end.isAfter(start))) continue
                require(end.isAfter(start)) { "Schicht ${template.name} am $date endet vor ihrem Beginn" }
                repeat(template.staff) { slot -> result += Shift(date, template, slot, start, end) }
            }
        }
        return result
    }

    /** Beginn und Ende einer Vorlage an einem Datum mit den Öffnungszeiten dieses Tages. */
    fun times(template: ShiftTemplate, date: LocalDate, hours: OpeningHours): Pair<LocalDateTime, LocalDateTime> {
        val start = when (val s = template.start) {
            is ShiftStart.At -> date.atTime(s.time)
            is ShiftStart.BeforeClose -> date.atTime(hours.close).minusMinutes(s.minutes)
        }
        val end = when (val e = template.end) {
            is ShiftEnd.At -> date.atTime(e.time)
            is ShiftEnd.AfterClose -> date.atTime(hours.close).plusMinutes(e.minutes)
        }
        return start to end
    }
}
