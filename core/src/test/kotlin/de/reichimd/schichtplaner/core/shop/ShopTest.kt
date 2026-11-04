package de.reichimd.schichtplaner.core.shop

import de.reichimd.schichtplaner.core.Planner
import de.reichimd.schichtplaner.core.PlanningRequest
import de.reichimd.schichtplaner.core.ShiftCalendar
import de.reichimd.schichtplaner.core.ShiftEnd
import de.reichimd.schichtplaner.core.ShiftKind
import de.reichimd.schichtplaner.core.ShiftStart
import de.reichimd.schichtplaner.core.ShiftTemplate
import de.reichimd.schichtplaner.core.Weekdays
import de.reichimd.schichtplaner.core.team.Team
import java.time.DayOfWeek
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShopTest {

    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)
    private val beispiel = Shop.EXAMPLE

    private fun mit(template: ShiftTemplate) = Shop.upsert(beispiel, template)

    /** Nur diese eine Schicht, nur montags: damit andere Schichten nicht mitgemeldet werden. */
    private fun nurMontag(template: ShiftTemplate, montag: DayHours) = ShopSettings(
        openingHours = mapOf(MONDAY to montag),
        templates = listOf(template.copy(weekdays = setOf(MONDAY))),
    )
    private fun felder(settings: ShopSettings) = Shop.problems(settings).map { it.field }

    private val neu = ShiftTemplate("neu", "Mittag", ShiftKind.EARLY, ShiftStart.At(t(9)), ShiftEnd.At(t(14)))

    @Test
    fun beispielIstGueltigUndOhneHinweise() {
        assertEquals(emptyList(), Shop.problems(beispiel))
        assertEquals(emptyList(), Shop.warnings(beispiel))
    }

    @Test
    fun beispielPasstZumEchtenPlan() {
        val pv = { id: String -> Shop.preview(beispiel.templates.first { it.id == id }, beispiel.openingHours) }
        assertEquals(listOf("Mo–Fr 12:30–18:30", "Sa 12:30–16:30"), pv("sp"))
        assertEquals(listOf("So 12:00–17:30"), pv("sp-so"))
        assertEquals(listOf("täglich 04:30–11:30"), pv("f1"))
    }

    @Test
    fun schlussVorOeffnungStehtAmTag() {
        val s = beispiel.copy(openingHours = beispiel.openingHours + (MONDAY to DayHours(t(18), t(6))))
        val p = Shop.problems(s)
        assertEquals(listOf<ShopField>(ShopField.Day(MONDAY)), p.map { it.field })
    }

    @Test
    fun gleicheUhrzeitFuerOeffnungUndSchlussIstUngueltig() {
        val s = beispiel.copy(openingHours = beispiel.openingHours + (MONDAY to DayHours(t(6), t(6))))
        assertEquals(listOf<ShopField>(ShopField.Day(MONDAY)), felder(s))
    }

    @Test
    fun geschlossenerTagIstErlaubt() {
        val s = beispiel.copy(openingHours = beispiel.openingHours - SUNDAY)
        assertEquals(emptyList(), Shop.problems(s))
        // Die Sonntags-Spätschicht gilt dann nur noch an Feiertagen: kein Hinweis.
        assertTrue(Shop.warnings(s).none { it.contains("Spät So") })
        // Ist an Feiertagen auch zu, hat sie keinen Tag mehr: Hinweis, kein Fehler.
        assertTrue(Shop.warnings(s.copy(holidayHours = null)).any { it.contains("Spät So") })
    }

    @Test
    fun nameFehltZuLangOderDoppelt() {
        assertEquals(
            listOf<ShopField>(ShopField.Template("neu", TemplatePart.NAME)),
            felder(mit(neu.copy(name = "  "))),
        )
        assertEquals(
            listOf<ShopField>(ShopField.Template("neu", TemplatePart.NAME)),
            felder(mit(neu.copy(name = "x".repeat(Shop.MAX_NAME_LENGTH + 1)))),
        )
        // Doppelt: beide Schichten sind betroffen, die App zeigt es bei der gerade bearbeiteten.
        assertEquals(
            listOf<ShopField>(ShopField.Template("sp", TemplatePart.NAME), ShopField.Template("neu", TemplatePart.NAME)),
            felder(mit(neu.copy(name = " spät "))),
        )
        assertEquals(emptyList(), felder(mit(neu)))
    }

    @Test
    fun ohneTageUndMitFalscherPersonenzahl() {
        assertEquals(
            listOf<ShopField>(ShopField.Template("neu", TemplatePart.DAYS)),
            felder(mit(neu.copy(weekdays = emptySet(), onHolidays = false))),
        )
        assertEquals(
            listOf<ShopField>(ShopField.Template("neu", TemplatePart.STAFF)),
            felder(mit(neu.copy(staff = 0))),
        )
        assertEquals(
            listOf<ShopField>(ShopField.Template("neu", TemplatePart.STAFF)),
            felder(mit(neu.copy(staff = Shop.MAX_STAFF + 1))),
        )
    }

    @Test
    fun endeVorBeginnWirdAmEndeGemeldet() {
        val p = Shop.problems(mit(neu.copy(end = ShiftEnd.At(t(8)))))
        assertEquals(listOf<ShopField>(ShopField.Template("neu", TemplatePart.END)), p.map { it.field })
        assertTrue(p[0].message.contains("Mo–Fr") || p[0].message.contains("Mo, Di"), p[0].message)
    }

    @Test
    fun ueberMitternachtGehtNicht() {
        val nacht = neu.copy(start = ShiftStart.At(t(20)), end = ShiftEnd.AfterClose(Shop.MAX_AFTER_CLOSE_MINUTES))
        val s = nurMontag(nacht, DayHours(t(6), t(22)))
        val p = Shop.problems(s)
        assertEquals(listOf<ShopField>(ShopField.Template("neu", TemplatePart.END)), p.map { it.field })
        assertTrue(p[0].message.contains("Mitternacht"))
        assertTrue(p[0].message.contains("Mo"))
    }

    @Test
    fun endeGenauUmMitternachtGehtAuchNicht() {
        val s = nurMontag(neu.copy(start = ShiftStart.At(t(15)), end = ShiftEnd.AfterClose(60)), DayHours(t(6), t(23)))
        assertEquals(listOf<ShopField>(ShopField.Template("neu", TemplatePart.END)), felder(s))
    }

    @Test
    fun beginnVorMitternachtBeiFruehemSchluss() {
        val s = nurMontag(neu.copy(start = ShiftStart.BeforeClose(600), end = ShiftEnd.AfterClose(0)), DayHours(t(4), t(8)))
        val p = Shop.problems(s)
        assertEquals(listOf<ShopField>(ShopField.Template("neu", TemplatePart.START)), p.map { it.field })
    }

    @Test
    fun mehrAlsZehnStundenWirdAbgelehnt() {
        val lang = neu.copy(start = ShiftStart.At(t(6)), end = ShiftEnd.At(t(16, 15)))
        val p = Shop.problems(mit(lang))
        assertEquals(listOf<ShopField>(ShopField.Template("neu", TemplatePart.END)), p.map { it.field })
        assertTrue(p[0].message.contains("10 Stunden"))
        assertEquals(emptyList(), felder(mit(lang.copy(end = ShiftEnd.At(t(16))))))
    }

    @Test
    fun relativeMinutenAusserhalbDesBereichs() {
        assertEquals(
            listOf<ShopField>(ShopField.Template("neu", TemplatePart.START)),
            felder(mit(neu.copy(start = ShiftStart.BeforeClose(-15)))),
        )
        assertEquals(
            listOf<ShopField>(ShopField.Template("neu", TemplatePart.END)),
            felder(mit(neu.copy(end = ShiftEnd.AfterClose(Shop.MAX_AFTER_CLOSE_MINUTES + 15)))),
        )
    }

    @Test
    fun arbeitszeitAendernKannEineSchichtUngueltigMachen() {
        // Spät beginnt fest 12:30 und endet mit Arbeitsende: Feierabend um 12 Uhr am Samstag geht nicht.
        val s = beispiel.copy(openingHours = beispiel.openingHours + (SATURDAY to DayHours(t(7), t(12))))
        val p = Shop.problems(s)
        assertEquals(listOf<ShopField>(ShopField.Template("sp", TemplatePart.END)), p.map { it.field })
        assertTrue(p[0].message.contains("Sa"))
    }

    @Test
    fun zuVieleSchichtenUndFalscherZeitraum() {
        val viele = (1..Shop.MAX_TEMPLATES + 1).fold(beispiel.copy(templates = emptyList())) { s, i ->
            Shop.upsert(s, neu.copy(id = "id$i", name = "S$i"))
        }
        assertEquals(listOf<ShopField>(ShopField.Templates), felder(viele))
        assertEquals(listOf<ShopField>(ShopField.Weeks), felder(beispiel.copy(planWeeks = 3)))
    }

    @Test
    fun hinweiseBeiOffenemTagOhneSchicht() {
        val s = beispiel.copy(templates = beispiel.templates.filter { it.id == "sp" })
        assertEquals(
            listOf("Am So ist Arbeitszeit, aber keine Schicht vorgesehen.", "An Feiertagen ist Arbeitszeit, aber keine Schicht vorgesehen."),
            Shop.warnings(s),
        )
        assertTrue(Shop.warnings(s.copy(templates = emptyList())).single().contains("keine Schicht"))
        assertTrue(Shop.warnings(s.copy(openingHours = emptyMap())).single().contains("zu"))
    }

    @Test
    fun schichtAusserhalbDerArbeitszeitGibtHinweis() {
        val s = mit(neu.copy(start = ShiftStart.At(t(4)), end = ShiftEnd.At(t(9))))
        assertEquals(emptyList(), Shop.problems(s))
        assertEquals(listOf("„Mittag“ liegt am Mo, Di, Mi, Do, Fr, Sa, So außerhalb der Arbeitszeit."), Shop.warnings(s))
    }

    @Test
    fun stundenProTagUndWoche() {
        // Mo: Früh 1 7 h -> 6,5 h, Früh 2 7 h -> 6,5 h, Spät 12:30–18:30 6 h = 19 h.
        assertEquals(19 * 60L, Shop.paidMinutesOn(beispiel, MONDAY))
        // Sa: 6,5 + 6,5 + 4 = 17 h; So: 6,5 + 6,5 + 5,5 = 18,5 h.
        assertEquals(17 * 60L, Shop.paidMinutesOn(beispiel, SATURDAY))
        assertEquals(18 * 60L + 30, Shop.paidMinutesOn(beispiel, SUNDAY))
        assertEquals(5 * 19 * 60L + 17 * 60 + 18 * 60 + 30, Shop.paidMinutesPerWeek(beispiel))
        assertEquals(0L, Shop.paidMinutesOn(beispiel.copy(openingHours = beispiel.openingHours - MONDAY), MONDAY))
        // Zwei Personen zählen doppelt.
        val doppelt = Shop.upsert(beispiel, beispiel.templates.first().copy(staff = 2))
        assertEquals(19 * 60L + 6 * 60 + 30, Shop.paidMinutesOn(doppelt, MONDAY))
        // 4 × 121,75 h im Monat = 487 h -> 112 h pro Woche.
        assertEquals(112 * 60L, Shop.teamMinutesPerWeek(listOf(121, 122, 122, 122)) / 60 * 60)
    }

    @Test
    fun uhrzeitSchritte() {
        assertEquals(t(6, 15), Shop.stepTime(t(6), up = true))
        assertEquals(t(5, 45), Shop.stepTime(t(6), up = false))
        assertEquals(t(5, 15), Shop.stepTime(t(5, 10), up = true))
        assertEquals(t(5), Shop.stepTime(t(5, 10), up = false))
        assertEquals(t(0), Shop.stepTime(t(0), up = false))
        assertEquals(t(23, 45), Shop.stepTime(t(23, 45), up = true))
    }

    @Test
    fun beispielFeiertageWieSonntag() {
        val feiertag = LocalDate.of(2026, 12, 25) // Freitag
        val config = beispiel.toConfig(feiertag, feiertag)
        val schichten = ShiftCalendar.shifts(feiertag, 1, config)
        assertEquals(listOf("Früh 1", "Früh 2", "Spät So"), schichten.map { it.template.name })
        assertEquals(t(17, 30), schichten.last().end.toLocalTime())
    }

    @Test
    fun reformationstagErkanntUndMitFeiertagszeit() {
        val reformation = LocalDate.of(2025, 10, 31) // Freitag
        val s = beispiel.copy(holidayHours = DayHours(t(6), t(12)))
        // "Spät So" beginnt 12:00 und endet zum Feierabend 12:00: Das meldet Shop.problems vor dem Speichern.
        assertTrue(Shop.problems(s).any { it.field == ShopField.Template("sp-so", TemplatePart.END) })
        // Gültig machen: "Spät So" nicht an Feiertagen.
        val ok = Shop.upsert(s, s.templates.first { it.id == "sp-so" }.copy(onHolidays = false))
        assertEquals(emptyList(), Shop.problems(ok))
        val feiertag = ShiftCalendar.shifts(reformation, 1, ok.toConfig(reformation, reformation))
        assertEquals(listOf("Früh 1", "Früh 2"), feiertag.map { it.template.name })
        assertEquals(t(11, 30), feiertag.first().end.toLocalTime())
    }

    @Test
    fun inAnderemBundeslandIstReformationstagNormal() {
        val tag = LocalDate.of(2025, 10, 31)
        val bayern = beispiel.copy(state = FederalState.BY)
        val schichten = ShiftCalendar.shifts(tag, 1, bayern.toConfig(tag, tag))
        assertEquals(listOf("Früh 1", "Früh 2", "Spät"), schichten.map { it.template.name })
    }

    @Test
    fun feiertagGeschlossen() {
        val weihnachten = LocalDate.of(2026, 12, 25)
        val zu = beispiel.copy(closedHolidays = setOf(HolidayId.WEIHNACHTEN_1))
        assertEquals(emptyList(), ShiftCalendar.shifts(weihnachten, 1, zu.toConfig(weihnachten, weihnachten)))
        // Der 2. Weihnachtstag bleibt offen.
        assertEquals(3, ShiftCalendar.shifts(weihnachten.plusDays(1), 1, zu.toConfig(weihnachten, weihnachten.plusDays(1))).size)
        // Ganz ohne Feiertags-Arbeitszeit: an allen Feiertagen zu.
        val alleZu = beispiel.copy(holidayHours = null)
        assertEquals(emptyList(), ShiftCalendar.shifts(weihnachten, 2, alleZu.toConfig(weihnachten, weihnachten.plusDays(1))))
        assertEquals(emptyList(), Shop.problems(alleZu))
    }

    @Test
    fun heiligabendUndSilvesterKuerzer() {
        val heiligabend = LocalDate.of(2026, 12, 24) // Donnerstag
        val schichten = ShiftCalendar.shifts(heiligabend, 1, beispiel.toConfig(heiligabend, heiligabend))
        // Vorgabe bis 14:30: Früh unverändert, Spät 12:30–14:30.
        assertEquals(listOf("Früh 1", "Früh 2", "Spät"), schichten.map { it.template.name })
        assertEquals(t(14, 30), schichten.last().end.toLocalTime())

        // Feierabend 12:00: Früh 2 wird gekürzt, Spät fällt weg.
        val frueh = beispiel.copy(shortDays = mapOf(ShortDay.HEILIGABEND to DayHours(t(4, 30), t(12))))
        val kurz = ShiftCalendar.shifts(heiligabend, 1, frueh.toConfig(heiligabend, heiligabend))
        assertEquals(listOf("Früh 1", "Früh 2"), kurz.map { it.template.name })
        assertEquals(t(12), kurz.last().end.toLocalTime())

        // Ausgeschaltet: wie ein normaler Donnerstag.
        val normal = beispiel.copy(shortDays = emptyMap())
        val voll = ShiftCalendar.shifts(heiligabend, 1, normal.toConfig(heiligabend, heiligabend))
        assertEquals(t(18, 30), voll.last().end.toLocalTime())

        // Silvester auch, über den Jahreswechsel gerechnet.
        val silvester = LocalDate.of(2026, 12, 31)
        val wechsel = ShiftCalendar.shifts(silvester, 2, beispiel.toConfig(silvester, silvester.plusDays(1)))
        assertEquals(t(14, 30), wechsel.filter { it.date == silvester }.maxOf { it.end }.toLocalTime())
        // Neujahr ist Feiertag: Sonntagsschichten.
        assertEquals(listOf("Früh 1", "Früh 2", "Spät So"), wechsel.filter { it.date == silvester.plusDays(1) }.map { it.template.name })
    }

    @Test
    fun kuerzererTagAnFreiemWochentagBleibtFrei() {
        val heiligabend = LocalDate.of(2026, 12, 24) // Donnerstag
        val s = beispiel.copy(openingHours = beispiel.openingHours - DayOfWeek.THURSDAY)
        assertEquals(emptyList(), ShiftCalendar.shifts(heiligabend, 1, s.toConfig(heiligabend, heiligabend)))
    }

    @Test
    fun feiertagsZeitenWerdenGeprueft() {
        val falsch = beispiel.copy(holidayHours = DayHours(t(12), t(6)))
        assertTrue(ShopField.HolidayHours in felder(falsch))
        val kurzFalsch = beispiel.copy(shortDays = mapOf(ShortDay.SILVESTER to DayHours(t(14), t(14))))
        assertTrue(ShopField.Short(ShortDay.SILVESTER) in felder(kurzFalsch))
        // Schicht nur an Feiertagen ist erlaubt.
        val nurFeiertag = mit(neu.copy(weekdays = emptySet(), onHolidays = true))
        assertEquals(emptyList(), Shop.problems(nurFeiertag))
        val nirgends = mit(neu.copy(weekdays = emptySet(), onHolidays = false))
        assertTrue(ShopField.Template("neu", TemplatePart.DAYS) in felder(nirgends))
    }

    @Test
    fun feiertagsHinweise() {
        val ohne = beispiel.copy(templates = beispiel.templates.map { it.copy(onHolidays = false) })
        assertTrue(Shop.warnings(ohne).any { it.contains("Feiertagen") })
        val vorschau = Shop.preview(beispiel.templates.first { it.id == "f1" }, beispiel.openingHours, beispiel.holidayHours)
        assertEquals(listOf("täglich 04:30–11:30", "Feiertag 04:30–11:30"), vorschau)
        assertEquals("12:30–14:30", Shop.shortDayTimes(beispiel.templates.first { it.id == "sp" }, DayHours(t(4, 30), t(14, 30))))
        assertEquals(null, Shop.shortDayTimes(beispiel.templates.first { it.id == "sp" }, DayHours(t(4, 30), t(12, 30))))
    }

    @Test
    fun vorschauFasstTageZusammen() {
        assertEquals("Mo–Fr", Weekdays.describe(DayOfWeek.entries.take(5)))
        assertEquals("Mo, Di", Weekdays.describe(listOf(MONDAY, DayOfWeek.TUESDAY)))
        assertEquals("Mo, Mi, Sa, So", Weekdays.describe(listOf(MONDAY, DayOfWeek.WEDNESDAY, SATURDAY, SUNDAY)))
        assertEquals("täglich", Weekdays.describe(DayOfWeek.entries))
    }

    @Test
    fun beschreibungen() {
        assertEquals("04:30", Shop.describeStart(ShiftStart.At(t(4, 30))))
        assertEquals("30 min nach Arbeitsende", Shop.describeEnd(ShiftEnd.AfterClose(30)))
        assertEquals("1 h 30 min vor Arbeitsende", Shop.describeStart(ShiftStart.BeforeClose(90)))
        assertEquals("Arbeitsende", Shop.describeEnd(ShiftEnd.AfterClose(0)))
    }

    @Test
    fun beispielLadenUndBeispielTeamLassenSichVierWochenVerplanen() {
        val s = beispiel.copy(planWeeks = 4)
        val request = PlanningRequest(
            firstDay = LocalDate.of(2026, 11, 2),
            days = s.planDays,
            shop = s.toConfig(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 29)),
            employees = Team.EXAMPLE,
        )
        val plan = Planner.plan(request)
        assertTrue(plan.solved)
        assertEquals(emptyList(), plan.unfilled)
    }
}
