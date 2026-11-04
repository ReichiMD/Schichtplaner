@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package de.reichimd.schichtplaner.laden

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.reichimd.schichtplaner.core.Weekdays
import de.reichimd.schichtplaner.core.shop.DayHours
import de.reichimd.schichtplaner.core.shop.FederalState
import de.reichimd.schichtplaner.core.shop.Holiday
import de.reichimd.schichtplaner.core.shop.HolidayId
import de.reichimd.schichtplaner.core.shop.Holidays
import de.reichimd.schichtplaner.core.shop.Shop
import de.reichimd.schichtplaner.core.shop.ShopField
import de.reichimd.schichtplaner.core.shop.ShopSettings
import de.reichimd.schichtplaner.core.shop.ShortDay
import de.reichimd.schichtplaner.ui.Abschnitt
import de.reichimd.schichtplaner.ui.Akzent
import de.reichimd.schichtplaner.ui.Auswahl
import de.reichimd.schichtplaner.ui.Frueh
import de.reichimd.schichtplaner.ui.NichtGespeichert
import de.reichimd.schichtplaner.ui.Schalter
import de.reichimd.schichtplaner.ui.Schrift
import de.reichimd.schichtplaner.ui.TextLeise
import de.reichimd.schichtplaner.ui.TextMittel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DATUM = DateTimeFormatter.ofPattern("dd.MM.yyyy")

/** "Do 25.12.2026" */
private fun datumText(d: LocalDate) = "${Weekdays.short(d.dayOfWeek)} ${d.format(DATUM)}"

/** Der nächste Termin jedes Feiertags ab [heute] (dieses oder nächstes Jahr). */
internal fun naechsteFeiertage(state: FederalState, heute: LocalDate): List<Holiday> =
    Holidays.between(heute, heute.plusYears(1).minusDays(1), state).values.sortedBy { it.date }

/** Übersicht im Laden-Reiter: Bundesland, Arbeitszeit an Feiertagen, Heiligabend/Silvester, nächste Feiertage. */
@Composable
internal fun FeiertageKarte(laden: ShopSettings, enabled: Boolean, heute: LocalDate = LocalDate.now(), onClick: () -> Unit) {
    Karte(enabled = enabled, tag = "laden-feiertage", beschreibung = "Feiertage bearbeiten", onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Feiertage", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Schrift, modifier = Modifier.weight(1f))
            Text("›", fontSize = 22.sp, color = TextLeise)
        }
        Text(laden.state.label, fontSize = 13.sp, color = TextLeise)
        Zeile("Feiertag", laden.holidayHours?.let { "${Shop.format(it.open)} – ${Shop.format(it.close)}" } ?: "zu", "feiertag-zeit")
        if (laden.holidayHours != null && laden.closedHolidays.isNotEmpty()) {
            val zu = HolidayId.entries.filter { it in laden.closedHolidays }.joinToString(", ") { it.label }
            Text("Zu: $zu", fontSize = 14.sp, color = TextMittel)
        }
        ShortDay.entries.forEach { d ->
            val h = laden.shortDays[d]
            Zeile(d.label, if (h == null) "wie normaler Tag" else "${Shop.format(h.open)} – ${Shop.format(h.close)}", "kurz-${d.code}")
        }
        val naechste = naechsteFeiertage(laden.state, heute).take(2)
        if (naechste.isNotEmpty()) {
            Text(
                "Nächste: " + naechste.joinToString(" · ") { "${it.name} (${datumText(it.date)})" },
                fontSize = 13.sp,
                color = TextLeise,
                modifier = Modifier.testTag("naechste-feiertage"),
            )
        }
        Text("Freie Tage an Feiertagen stehen im Plan als „FF“.", fontSize = 13.sp, color = TextLeise)
    }
}

@Composable
private fun Zeile(was: String, wert: String, tag: String) {
    Row {
        Text(was, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextMittel, modifier = Modifier.width(110.dp))
        Text(wert, fontSize = 15.sp, color = Schrift, modifier = Modifier.weight(1f).testTag(tag))
    }
}

/** Entwurf des Formulars. Zeiten in Minuten ab Mitternacht. Als Text speicherbar, damit er Drehen übersteht. */
private data class FeiertagEntwurf(
    val state: FederalState,
    val offen: Boolean,
    val auf: Int,
    val zu: Int,
    val geschlossen: Set<HolidayId>,
    /** Je kürzerem Tag: an?, Beginn, Feierabend. */
    val kurz: Map<ShortDay, Triple<Boolean, Int, Int>>,
) {
    fun alsText(): String = listOf(
        state.code,
        "${if (offen) 1 else 0},$auf,$zu",
        HolidayId.entries.filter { it in geschlossen }.joinToString(",") { it.code },
        ShortDay.entries.joinToString(";") { d -> kurz.getValue(d).let { "${if (it.first) 1 else 0},${it.second},${it.third}" } },
    ).joinToString("|")

    fun anwenden(laden: ShopSettings): ShopSettings = laden.copy(
        state = state,
        holidayHours = if (offen) DayHours(zeit(auf), zeit(zu)) else null,
        // Gemerkt wird nur, was es im gewählten Bundesland gibt.
        closedHolidays = geschlossen.filter { it in Holidays.idsIn(state) }.toSet(),
        shortDays = kurz.filterValues { it.first }.mapValues { (_, v) -> DayHours(zeit(v.second), zeit(v.third)) },
    )

    companion object {
        fun von(laden: ShopSettings): FeiertagEntwurf {
            // Ausgeschaltet bekommt beim Einschalten die Sonntagszeiten (oder 04:30–17:30) vorgeschlagen.
            val vorschlag = laden.holidayHours ?: laden.openingHours[java.time.DayOfWeek.SUNDAY]
                ?: Shop.EXAMPLE.holidayHours!!
            return FeiertagEntwurf(
                state = laden.state,
                offen = laden.holidayHours != null,
                auf = minuten(vorschlag.open),
                zu = minuten(vorschlag.close),
                geschlossen = laden.closedHolidays,
                kurz = ShortDay.entries.associateWith { d ->
                    val h = laden.shortDays[d] ?: Shop.SHORT_DAYS_DEFAULT.getValue(d)
                    Triple(d in laden.shortDays, minuten(h.open), minuten(h.close))
                },
            )
        }

        fun ausText(text: String): FeiertagEntwurf {
            val (s, h, g, k) = text.split("|")
            val (o, a, z) = h.split(",").map { it.toInt() }
            val kurz = k.split(";").map { teil -> teil.split(",").map { it.toInt() } }
            return FeiertagEntwurf(
                state = FederalState.of(s) ?: FederalState.ST,
                offen = o == 1,
                auf = a,
                zu = z,
                geschlossen = g.split(",").mapNotNull(HolidayId::of).toSet(),
                kurz = ShortDay.entries.zip(kurz).associate { (d, v) -> d to Triple(v[0] == 1, v[1], v[2]) },
            )
        }
    }
}

@Composable
internal fun FeiertageBearbeiten(laden: ShopSettings, store: LadenStore, schliessen: () -> Unit, heute: LocalDate = LocalDate.now()) {
    val anfang = remember { FeiertagEntwurf.von(laden).alsText() }
    var text by rememberSaveable { mutableStateOf(anfang) }
    var versucht by rememberSaveable { mutableStateOf(false) }
    var verwerfenFrage by remember { mutableStateOf(false) }
    var landWaehlen by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val zeitRequester = remember { BringIntoViewRequester() }
    val kurzRequester = remember { ShortDay.entries.associateWith { BringIntoViewRequester() } }
    val e = FeiertagEntwurf.ausText(text)
    fun neu(wie: (FeiertagEntwurf) -> FeiertagEntwurf) {
        text = wie(e).alsText()
    }

    val kandidat = e.anwenden(laden)
    val probleme = if (versucht) Shop.problems(kandidat) else emptyList()
    fun eigene(feld: ShopField) = probleme.filter { it.field == feld }.map { it.message }
    val geaendert = text != anfang

    fun zurueck() {
        if (geaendert) verwerfenFrage = true else schliessen()
    }
    BackHandler { zurueck() }

    Formular(
        titel = "Feiertage",
        zurueck = ::zurueck,
        unten = {
            if (probleme.isNotEmpty()) {
                val eigeneFelder = setOf<ShopField>(ShopField.HolidayHours) + ShortDay.entries.map { ShopField.Short(it) }
                NichtGespeichert(
                    wo = buildList {
                        if (eigene(ShopField.HolidayHours).isNotEmpty()) add("Feiertage")
                        ShortDay.entries.forEach { if (eigene(ShopField.Short(it)).isNotEmpty()) add(it.label) }
                    },
                    weitere = probleme.filter { it.field !in eigeneFelder }.map { fremdText(it, kandidat) },
                )
            }
            SpeichernKnopf {
                val ergebnis = store.speichern { e.anwenden(it) }
                if (ergebnis.isEmpty()) {
                    schliessen()
                } else {
                    versucht = true
                    val ziel = when {
                        ergebnis.any { it.field == ShopField.HolidayHours } -> zeitRequester
                        else -> ShortDay.entries.firstOrNull { d -> ergebnis.any { it.field == ShopField.Short(d) } }?.let { kurzRequester[it] }
                    }
                    if (ziel != null) scope.launch { ziel.bringIntoView() }
                }
            }
        },
    ) {
        Text(
            "Die Feiertage rechnet die App selbst aus. Freie Tage an Feiertagen stehen im Plan als „FF“.",
            fontSize = 14.sp,
            color = TextLeise,
        )

        Abschnitt("Bundesland", "Danach richten sich die Feiertage.", tag = "bundesland") {
            Auswahl(e.state.label, true, Modifier.fillMaxWidth(), gewaehltFarbe = Akzent) { landWaehlen = !landWaehlen }
            if (landWaehlen) {
                FederalState.entries.filter { it != e.state }.chunked(2).forEach { paar ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        paar.forEach { s ->
                            Auswahl(s.label, false, Modifier.weight(1f).testTag("land-${s.code}")) {
                                neu { it.copy(state = s) }
                                landWaehlen = false
                            }
                        }
                        if (paar.size == 1) Column(Modifier.weight(1f)) {}
                    }
                }
            }
        }

        Abschnitt(
            "Arbeitszeit an Feiertagen",
            "Welche Schichten: bei jeder Schicht „Auch an Feiertagen“.",
            fehler = eigene(ShopField.HolidayHours),
            requester = zeitRequester,
            tag = "feiertag-zeiten",
        ) {
            Schalter("An Feiertagen wird gearbeitet", e.offen, Frueh) { neu { it.copy(offen = !it.offen) } }
            if (e.offen) {
                ZeitZeile("Beginn", e.auf, "Beginn an Feiertagen") { m -> neu { it.copy(auf = m) } }
                ZeitZeile("Feierabend", e.zu, "Feierabend an Feiertagen") { m -> neu { it.copy(zu = m) } }
            }
        }

        if (e.offen) {
            Abschnitt("Welche Feiertage?", "Ausschalten, wenn der Laden an diesem Feiertag zu ist.", tag = "feiertag-liste") {
                naechsteFeiertage(e.state, heute).forEach { h ->
                    Schalter("${h.name}\n${datumText(h.date)}", h.id !in e.geschlossen, Frueh) {
                        neu { it.copy(geschlossen = if (h.id in it.geschlossen) it.geschlossen - h.id else it.geschlossen + h.id) }
                    }
                }
            }
        }

        ShortDay.entries.forEach { d ->
            val (an, auf, zu) = e.kurz.getValue(d)
            fun setze(wie: (Triple<Boolean, Int, Int>) -> Triple<Boolean, Int, Int>) =
                neu { it.copy(kurz = it.kurz + (d to wie(it.kurz.getValue(d)))) }
            Abschnitt(
                "${d.label} (${d.monthDay.dayOfMonth}.12.)",
                "Kein Feiertag, aber kürzer. Schichten wie an dem Wochentag, sie enden spätestens zum Feierabend.",
                fehler = eigene(ShopField.Short(d)),
                requester = kurzRequester[d],
                tag = "kurz-${d.code}-form",
            ) {
                Schalter("Kürzer arbeiten", an, Frueh) { setze { it.copy(first = !it.first) } }
                if (an) {
                    ZeitZeile("Beginn", auf, "Beginn ${d.label}") { m -> setze { it.copy(second = m) } }
                    ZeitZeile("Feierabend", zu, "Feierabend ${d.label}") { m -> setze { it.copy(third = m) } }
                }
            }
        }
    }

    if (verwerfenFrage) VerwerfenFrage(abbrechen = { verwerfenFrage = false }, verwerfen = schliessen)
}
