package de.reichimd.schichtplaner.core.shop

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay
import java.time.temporal.TemporalAdjusters

/** Bundesland, nach dem sich die gesetzlichen Feiertage richten. [code] steht so im Speicherformat. */
enum class FederalState(val code: String, val label: String) {
    BW("BW", "Baden-Württemberg"),
    BY("BY", "Bayern"),
    BE("BE", "Berlin"),
    BB("BB", "Brandenburg"),
    HB("HB", "Bremen"),
    HH("HH", "Hamburg"),
    HE("HE", "Hessen"),
    MV("MV", "Mecklenburg-Vorpommern"),
    NI("NI", "Niedersachsen"),
    NW("NW", "Nordrhein-Westfalen"),
    RP("RP", "Rheinland-Pfalz"),
    SL("SL", "Saarland"),
    SN("SN", "Sachsen"),
    ST("ST", "Sachsen-Anhalt"),
    SH("SH", "Schleswig-Holstein"),
    TH("TH", "Thüringen"),
    ;

    companion object {
        fun of(code: String): FederalState? = entries.firstOrNull { it.code == code }
    }
}

/** Gesetzlicher Feiertag. [code] steht so im Speicherformat (z. B. für "an diesem Feiertag zu"). */
enum class HolidayId(val code: String, val label: String) {
    NEUJAHR("neujahr", "Neujahr"),
    DREI_KOENIGE("drei-koenige", "Heilige Drei Könige"),
    FRAUENTAG("frauentag", "Frauentag"),
    KARFREITAG("karfreitag", "Karfreitag"),
    OSTERSONNTAG("ostersonntag", "Ostersonntag"),
    OSTERMONTAG("ostermontag", "Ostermontag"),
    TAG_DER_ARBEIT("tag-der-arbeit", "Tag der Arbeit"),
    HIMMELFAHRT("himmelfahrt", "Christi Himmelfahrt"),
    PFINGSTSONNTAG("pfingstsonntag", "Pfingstsonntag"),
    PFINGSTMONTAG("pfingstmontag", "Pfingstmontag"),
    FRONLEICHNAM("fronleichnam", "Fronleichnam"),
    MARIAE_HIMMELFAHRT("mariae-himmelfahrt", "Mariä Himmelfahrt"),
    WELTKINDERTAG("weltkindertag", "Weltkindertag"),
    EINHEIT("einheit", "Tag der Deutschen Einheit"),
    REFORMATION("reformation", "Reformationstag"),
    ALLERHEILIGEN("allerheiligen", "Allerheiligen"),
    BUSS_UND_BETTAG("buss-und-bettag", "Buß- und Bettag"),
    WEIHNACHTEN_1("weihnachten-1", "1. Weihnachtstag"),
    WEIHNACHTEN_2("weihnachten-2", "2. Weihnachtstag"),
    ;

    companion object {
        fun of(code: String): HolidayId? = entries.firstOrNull { it.code == code }
    }
}

data class Holiday(val date: LocalDate, val id: HolidayId) {
    val name: String get() = id.label
}

/** Tage, die kein Feiertag sind, an denen aber meist kürzer gearbeitet wird. */
enum class ShortDay(val code: String, val label: String, val monthDay: MonthDay) {
    HEILIGABEND("heiligabend", "Heiligabend", MonthDay.of(12, 24)),
    SILVESTER("silvester", "Silvester", MonthDay.of(12, 31)),
    ;

    fun dateIn(year: Int): LocalDate = monthDay.atYear(year)

    companion object {
        fun of(code: String): ShortDay? = entries.firstOrNull { it.code == code }
        fun on(date: LocalDate): ShortDay? = entries.firstOrNull { it.monthDay == MonthDay.from(date) }
    }
}

/**
 * Gesetzliche Feiertage je Bundesland, offline berechnet. Feiertage, die nur in einzelnen Gemeinden gelten
 * (z. B. Fronleichnam in Teilen Sachsens und Thüringens, Mariä Himmelfahrt in Teilen Bayerns,
 * Augsburger Friedensfest), sind nicht dabei. Gilt für die Regeln ab 2023.
 */
object Holidays {

    /** Ostersonntag nach der Gaußschen Osterformel (gregorianischer Kalender, anonyme Variante). */
    fun easterSunday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, day)
    }

    private val S = FederalState.entries.toSet()
    private fun only(vararg s: FederalState) = s.toSet()

    /** Gesetzliche Feiertage eines Jahres in einem Bundesland, nach Datum sortiert. */
    fun of(year: Int, state: FederalState): List<Holiday> {
        val easter = easterSunday(year)
        val all = listOf(
            Triple(HolidayId.NEUJAHR, LocalDate.of(year, 1, 1), S),
            Triple(HolidayId.DREI_KOENIGE, LocalDate.of(year, 1, 6), only(FederalState.BW, FederalState.BY, FederalState.ST)),
            Triple(HolidayId.FRAUENTAG, LocalDate.of(year, 3, 8), only(FederalState.BE, FederalState.MV)),
            Triple(HolidayId.KARFREITAG, easter.minusDays(2), S),
            Triple(HolidayId.OSTERSONNTAG, easter, only(FederalState.BB, FederalState.HE)),
            Triple(HolidayId.OSTERMONTAG, easter.plusDays(1), S),
            Triple(HolidayId.TAG_DER_ARBEIT, LocalDate.of(year, 5, 1), S),
            Triple(HolidayId.HIMMELFAHRT, easter.plusDays(39), S),
            Triple(HolidayId.PFINGSTSONNTAG, easter.plusDays(49), only(FederalState.BB, FederalState.HE)),
            Triple(HolidayId.PFINGSTMONTAG, easter.plusDays(50), S),
            Triple(
                HolidayId.FRONLEICHNAM, easter.plusDays(60),
                only(FederalState.BW, FederalState.BY, FederalState.HE, FederalState.NW, FederalState.RP, FederalState.SL),
            ),
            Triple(HolidayId.MARIAE_HIMMELFAHRT, LocalDate.of(year, 8, 15), only(FederalState.SL)),
            Triple(HolidayId.WELTKINDERTAG, LocalDate.of(year, 9, 20), only(FederalState.TH)),
            Triple(HolidayId.EINHEIT, LocalDate.of(year, 10, 3), S),
            Triple(
                HolidayId.REFORMATION, LocalDate.of(year, 10, 31),
                only(
                    FederalState.BB, FederalState.HB, FederalState.HH, FederalState.MV, FederalState.NI,
                    FederalState.SN, FederalState.ST, FederalState.SH, FederalState.TH,
                ),
            ),
            Triple(
                HolidayId.ALLERHEILIGEN, LocalDate.of(year, 11, 1),
                only(FederalState.BW, FederalState.BY, FederalState.NW, FederalState.RP, FederalState.SL),
            ),
            // Mittwoch vor dem 23. November.
            Triple(
                HolidayId.BUSS_UND_BETTAG,
                LocalDate.of(year, 11, 22).with(TemporalAdjusters.previousOrSame(DayOfWeek.WEDNESDAY)),
                only(FederalState.SN),
            ),
            Triple(HolidayId.WEIHNACHTEN_1, LocalDate.of(year, 12, 25), S),
            Triple(HolidayId.WEIHNACHTEN_2, LocalDate.of(year, 12, 26), S),
        )
        return all.filter { state in it.third }.map { Holiday(it.second, it.first) }.sortedBy { it.date }
    }

    /** Alle Feiertage von [first] bis [last] (beide eingeschlossen), nach Datum. */
    fun between(first: LocalDate, last: LocalDate, state: FederalState): Map<LocalDate, Holiday> {
        if (last.isBefore(first)) return emptyMap()
        return (first.year..last.year).flatMap { of(it, state) }
            .filter { !it.date.isBefore(first) && !it.date.isAfter(last) }
            .associateBy { it.date }
    }

    /** Welche Feiertage es in einem Bundesland überhaupt gibt (für die Einstellungen). */
    fun idsIn(state: FederalState): List<HolidayId> = of(2026, state).map { it.id }
}
