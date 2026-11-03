package de.reichimd.schichtplaner.core

import java.time.LocalDate
import java.time.LocalDateTime

/** Erzeugt aus den Schichtvorlagen die konkreten Schichten eines Zeitraums. */
object ShiftCalendar {

    fun shifts(firstDay: LocalDate, days: Int, shop: ShopConfig): List<Shift> {
        val result = mutableListOf<Shift>()
        for (offset in 0 until days) {
            val date = firstDay.plusDays(offset.toLong())
            val hours = shop.hoursOn(date) ?: continue
            val isHoliday = date in shop.holidays
            for (template in shop.templates) {
                val applies = if (isHoliday) template.onHolidays else date.dayOfWeek in template.weekdays
                if (!applies) continue
                val (start, end) = times(template, date, hours)
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
