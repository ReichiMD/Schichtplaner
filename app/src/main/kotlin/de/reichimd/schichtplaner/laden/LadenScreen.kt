@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package de.reichimd.schichtplaner.laden

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.reichimd.schichtplaner.core.Problem
import de.reichimd.schichtplaner.core.ShiftEnd
import de.reichimd.schichtplaner.core.ShiftKind
import de.reichimd.schichtplaner.core.ShiftStart
import de.reichimd.schichtplaner.core.ShiftTemplate
import de.reichimd.schichtplaner.core.Weekdays
import de.reichimd.schichtplaner.core.shop.DayHours
import de.reichimd.schichtplaner.core.shop.Shop
import de.reichimd.schichtplaner.core.shop.ShopField
import de.reichimd.schichtplaner.core.shop.ShopSettings
import de.reichimd.schichtplaner.core.shop.TemplatePart
import de.reichimd.schichtplaner.sicherung.SicherungKarte
import de.reichimd.schichtplaner.team.TeamStore
import de.reichimd.schichtplaner.team.team
import de.reichimd.schichtplaner.ui.Abschnitt
import de.reichimd.schichtplaner.ui.Akzent
import de.reichimd.schichtplaner.ui.Auswahl
import de.reichimd.schichtplaner.ui.Fehler
import de.reichimd.schichtplaner.ui.FehlerHell
import de.reichimd.schichtplaner.ui.FehlerText
import de.reichimd.schichtplaner.ui.Frueh
import de.reichimd.schichtplaner.ui.Hinweis
import de.reichimd.schichtplaner.ui.NichtGespeichert
import de.reichimd.schichtplaner.ui.Rand
import de.reichimd.schichtplaner.ui.RandHell
import de.reichimd.schichtplaner.ui.Schalter
import de.reichimd.schichtplaner.ui.Schrift
import de.reichimd.schichtplaner.ui.Spaet
import de.reichimd.schichtplaner.ui.Stepper
import de.reichimd.schichtplaner.ui.TextLeise
import de.reichimd.schichtplaner.ui.TextMittel
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.UUID

private const val ZEITEN = "__zeiten__"
private const val NEU = "__neu__"

/**
 * Laden-Bereich: Übersicht, Arbeitszeit und Schichten bearbeiten. [bearbeitetGerade] meldet nach
 * außen, ob gerade ein Formular offen ist (dann blendet die App die untere Leiste aus).
 */
@Composable
internal fun LadenBereich(
    store: LadenStore = LadenStore.get(LocalContext.current),
    teamStore: TeamStore = TeamStore.get(LocalContext.current),
    bearbeitetGerade: (Boolean) -> Unit = {},
) {
    val zustand by store.zustand.collectAsState()
    val team by teamStore.zustand.collectAsState()
    var offen by rememberSaveable { mutableStateOf<String?>(null) }
    SideEffect { bearbeitetGerade(offen != null) }

    val id = offen
    when {
        id == null -> LadenUebersicht(zustand, store, team.team.map { it.monthlyHours }, oeffnen = { offen = it })
        id == ZEITEN -> OeffnungszeitenBearbeiten(zustand.laden, store, schliessen = { offen = null })
        else -> {
            val vorhanden = zustand.laden.templates.firstOrNull { it.id == id }
            if (id != NEU && vorhanden == null) {
                if (!zustand.laedt) LaunchedEffect(id) { offen = null }
                return
            }
            SchichtBearbeiten(vorhanden, zustand.laden, store, schliessen = { offen = null })
        }
    }
}

@Composable
private fun LadenUebersicht(zustand: LadenZustand, store: LadenStore, teamMonatsstunden: List<Int>, oeffnen: (String) -> Unit) {
    if (zustand.laedt) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Akzent) }
        return
    }
    val laden = zustand.laden
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Laden", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Schrift)

        zustand.meldung?.let { m ->
            Hinweis(m) { TextButton(onClick = store::meldungGelesen) { Text("OK", color = Akzent) } }
        }
        if (!zustand.eingerichtet) {
            Hinweis(
                "Das sind Beispielwerte. Bitte einmal prüfen und anpassen. " +
                    "Wenn alles stimmt, auf „So übernehmen“ tippen.",
            ) {
                TextButton(
                    onClick = { store.speichern { it } },
                    enabled = zustand.bearbeitbar,
                    modifier = Modifier.testTag("beispiel-uebernehmen"),
                ) { Text("So übernehmen", color = Akzent, fontWeight = FontWeight.Bold) }
            }
        }
        // Kann nur vorkommen, wenn eine neuere Regel strenger ist als beim Speichern.
        val probleme = Shop.problems(laden)
        if (probleme.isNotEmpty()) {
            Hinweis("Bitte korrigieren, sonst klappt der Plan nicht:\n" + probleme.joinToString("\n") { "• " + fremdText(it, laden) })
        }
        Shop.warnings(laden).forEach { Hinweis(it) }

        // Arbeitszeit je Tag und die Stunden, die die Schichten zusammen ergeben
        Karte(
            enabled = zustand.bearbeitbar,
            tag = "laden-zeiten",
            beschreibung = "Arbeitszeit bearbeiten",
            onClick = { oeffnen(ZEITEN) },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Arbeitszeit", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Schrift, modifier = Modifier.weight(1f))
                Text("›", fontSize = 22.sp, color = TextLeise)
            }
            Text("Vom ersten Schichtbeginn bis Feierabend. Rechts: Stunden aller Schichten.", fontSize = 13.sp, color = TextLeise)
            DayOfWeek.entries.forEach { d ->
                val h = laden.openingHours[d]
                Row {
                    Text(Weekdays.short(d), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextMittel, modifier = Modifier.width(36.dp))
                    Text(
                        if (h == null) "frei" else "${Shop.format(h.open)} – ${Shop.format(h.close)}",
                        fontSize = 15.sp,
                        color = if (h == null) TextLeise else Schrift,
                        modifier = Modifier.weight(1f),
                    )
                    if (h != null) Text(stunden(Shop.paidMinutesOn(laden, d)), fontSize = 15.sp, color = TextMittel)
                }
            }
            val woche = Shop.paidMinutesPerWeek(laden)
            Row {
                Text("Woche", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Schrift, modifier = Modifier.weight(1f))
                Text(stunden(woche), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Schrift, modifier = Modifier.testTag("stunden-woche"))
            }
            if (teamMonatsstunden.isNotEmpty()) {
                val team = Shop.teamMinutesPerWeek(teamMonatsstunden)
                Text(
                    "Team: ca. ${stunden(team)} pro Woche (${teamMonatsstunden.sum()} h im Monat)",
                    fontSize = 14.sp,
                    color = TextMittel,
                )
                if (team < woche) {
                    Text(
                        "Dem Team fehlen ca. ${stunden(woche - team)} pro Woche. Dann gibt es Überstunden oder Lücken.",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Akzent,
                    )
                }
            }
            Text("Feiertage: wie Sonntag (vorläufig). Stunden nach Pausenabzug.", fontSize = 13.sp, color = TextLeise)
        }

        // Schichten
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Schichten", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Schrift, modifier = Modifier.weight(1f))
            Button(
                onClick = { oeffnen(NEU) },
                enabled = zustand.bearbeitbar && laden.templates.size < Shop.MAX_TEMPLATES,
                colors = ButtonDefaults.buttonColors(containerColor = Akzent),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("neue-schicht"),
            ) { Text("+ Schicht", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
        laden.templates.forEach { t ->
            Karte(
                enabled = zustand.bearbeitbar,
                tag = "schicht-${t.name}",
                beschreibung = "Schicht ${t.name} bearbeiten",
                farbe = if (t.kind == ShiftKind.EARLY) Frueh else Spaet,
                onClick = { oeffnen(t.id) },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Schrift, modifier = Modifier.weight(1f))
                    Text(if (t.kind == ShiftKind.EARLY) "früh" else "spät", fontSize = 14.sp, color = TextMittel)
                    Spacer(Modifier.width(8.dp))
                    Text("›", fontSize = 22.sp, color = TextLeise)
                }
                val vorschau = Shop.preview(t, laden.openingHours)
                if (vorschau.isEmpty()) {
                    Text("Fällt auf keinen Arbeitstag", fontSize = 14.sp, color = TextLeise)
                } else {
                    vorschau.forEach { Text(it, fontSize = 14.sp, color = Schrift) }
                }
                if (t.staff > 1) Text("${t.staff} Personen", fontSize = 14.sp, color = TextMittel)
            }
        }

        // Planungszeitraum
        Abschnitt("Plan für wie viele Wochen?", "Gilt beim nächsten „Plan erstellen“.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Shop.PLAN_WEEKS.forEach { w ->
                    Auswahl(
                        "$w Wochen",
                        gewaehlt = laden.planWeeks == w,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("wochen-$w"),
                    ) { if (zustand.bearbeitbar) store.speichern { it.copy(planWeeks = w) } }
                }
            }
        }

        // Alle Daten in eine Datei sichern oder zurückholen
        SicherungKarte()
    }
}

@Composable
private fun Karte(
    enabled: Boolean,
    tag: String,
    beschreibung: String,
    farbe: Color = Color.White,
    onClick: () -> Unit,
    inhalt: @Composable () -> Unit,
) {
    val form = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(farbe, form)
            .border(1.dp, RandHell, form)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = beschreibung, onClick = onClick)
            .padding(14.dp)
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { inhalt() }
}

// ---------- Öffnungszeiten ----------

/** Entwurf eines Tages. Zeiten in Minuten ab Mitternacht, damit der Entwurf Drehen übersteht. */
private data class TagEntwurf(val offen: Boolean, val auf: Int, val zu: Int)

private fun minuten(t: LocalTime) = t.hour * 60 + t.minute
private fun zeit(min: Int) = LocalTime.of(min / 60, min % 60)

/** "1,1,360,1080;2,..." für alle sieben Tage. */
private fun tageAlsText(tage: Map<DayOfWeek, TagEntwurf>) =
    DayOfWeek.entries.joinToString(";") { d -> tage.getValue(d).let { "${d.value},${if (it.offen) 1 else 0},${it.auf},${it.zu}" } }

private fun tageAusText(text: String): Map<DayOfWeek, TagEntwurf> =
    text.split(";").associate { teil ->
        val (d, o, a, z) = teil.split(",").map { it.toInt() }
        DayOfWeek.of(d) to TagEntwurf(o == 1, a, z)
    }

private fun entwurfVon(laden: ShopSettings): Map<DayOfWeek, TagEntwurf> = DayOfWeek.entries.associateWith { d ->
    val h = laden.openingHours[d]
    // Ein freier Tag bekommt beim Einschalten die Zeiten von Montag (oder 04:30–18:30) vorgeschlagen.
    val vorschlag = laden.openingHours[DayOfWeek.MONDAY] ?: DayHours(LocalTime.of(4, 30), LocalTime.of(18, 30))
    if (h == null) TagEntwurf(false, minuten(vorschlag.open), minuten(vorschlag.close))
    else TagEntwurf(true, minuten(h.open), minuten(h.close))
}

private fun oeffnungszeiten(tage: Map<DayOfWeek, TagEntwurf>): Map<DayOfWeek, DayHours> =
    tage.filterValues { it.offen }.mapValues { (_, t) -> DayHours(zeit(t.auf), zeit(t.zu)) }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OeffnungszeitenBearbeiten(laden: ShopSettings, store: LadenStore, schliessen: () -> Unit) {
    val anfang = remember { tageAlsText(entwurfVon(laden)) }
    var text by rememberSaveable { mutableStateOf(anfang) }
    var versucht by rememberSaveable { mutableStateOf(false) }
    var verwerfenFrage by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val requester = remember { DayOfWeek.entries.associateWith { BringIntoViewRequester() } }
    val tage = tageAusText(text)

    fun neu(d: DayOfWeek, wie: (TagEntwurf) -> TagEntwurf) {
        text = tageAlsText(tage + (d to wie(tage.getValue(d))))
    }

    val kandidat = laden.copy(openingHours = oeffnungszeiten(tage))
    val probleme = if (versucht) Shop.problems(kandidat) else emptyList()
    val geaendert = text != anfang

    fun zurueck() {
        if (geaendert) verwerfenFrage = true else schliessen()
    }
    BackHandler { zurueck() }

    Formular(
        titel = "Arbeitszeit",
        zurueck = ::zurueck,
        unten = {
            if (probleme.isNotEmpty()) {
                NichtGespeichert(
                    wo = DayOfWeek.entries.filter { d -> probleme.any { it.field == ShopField.Day(d) } }.map(Weekdays::short),
                    weitere = probleme.filter { it.field !is ShopField.Day }.map { fremdText(it, kandidat) },
                )
            }
            SpeichernKnopf {
                val ergebnis = store.speichern { it.copy(openingHours = oeffnungszeiten(tage)) }
                if (ergebnis.isEmpty()) {
                    schliessen()
                } else {
                    versucht = true
                    val erster = DayOfWeek.entries.firstOrNull { d -> ergebnis.any { it.field == ShopField.Day(d) } }
                    if (erster != null) scope.launch { requester.getValue(erster).bringIntoView() }
                }
            }
        },
    ) {
        Text(
            "Vom ersten Schichtbeginn bis Feierabend, nicht die Öffnungszeit. „Bis Arbeitsende“ bei einer Schicht " +
                "meint diese Uhrzeit. Feiertage gelten vorläufig wie Sonntag.",
            fontSize = 14.sp,
            color = TextLeise,
        )
        DayOfWeek.entries.forEach { d ->
            val t = tage.getValue(d)
            Abschnitt(
                Weekdays.short(d) + " – " + TAGNAME.getValue(d),
                fehler = probleme.filter { it.field == ShopField.Day(d) }.map { it.message },
                requester = requester[d],
                tag = "tag-${Weekdays.short(d)}",
            ) {
                Schalter("Wird gearbeitet", t.offen, Frueh) { neu(d) { it.copy(offen = !it.offen) } }
                if (t.offen) {
                    ZeitZeile("Beginn", t.auf, "Beginn ${Weekdays.short(d)}") { m -> neu(d) { it.copy(auf = m) } }
                    ZeitZeile("Feierabend", t.zu, "Feierabend ${Weekdays.short(d)}") { m -> neu(d) { it.copy(zu = m) } }
                    if (d == DayOfWeek.MONDAY) {
                        OutlinedButton(
                            onClick = {
                                val mo = tage.getValue(DayOfWeek.MONDAY)
                                text = tageAlsText(tage + WERKTAGE.associateWith { mo })
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("mo-uebertragen"),
                        ) { Text("Diese Zeiten auch für Di–Fr", color = Akzent) }
                    }
                }
            }
        }
    }

    if (verwerfenFrage) VerwerfenFrage(abbrechen = { verwerfenFrage = false }, verwerfen = schliessen)
}

private val WERKTAGE = listOf(DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

private val TAGNAME = mapOf(
    DayOfWeek.MONDAY to "Montag",
    DayOfWeek.TUESDAY to "Dienstag",
    DayOfWeek.WEDNESDAY to "Mittwoch",
    DayOfWeek.THURSDAY to "Donnerstag",
    DayOfWeek.FRIDAY to "Freitag",
    DayOfWeek.SATURDAY to "Samstag",
    DayOfWeek.SUNDAY to "Sonntag",
)

/** Uhrzeit mit Minus und Plus in 15-Minuten-Schritten. */
@Composable
private fun ZeitZeile(was: String, minuten: Int, beschreibung: String, neu: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(was, fontSize = 15.sp, color = TextMittel, modifier = Modifier.width(76.dp))
        Box(Modifier.weight(1f)) {
            Stepper(
                wert = Shop.format(zeit(minuten)),
                weniger = "$beschreibung früher",
                mehr = "$beschreibung später",
                onWeniger = { neu(minuten(Shop.stepTime(zeit(minuten), up = false))) },
                onMehr = { neu(minuten(Shop.stepTime(zeit(minuten), up = true))) },
            )
        }
    }
}

// ---------- Schicht bearbeiten ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SchichtBearbeiten(original: ShiftTemplate?, laden: ShopSettings, store: LadenStore, schliessen: () -> Unit) {
    val neueId = rememberSaveable { UUID.randomUUID().toString() }
    val id = original?.id ?: neueId
    var name by rememberSaveable { mutableStateOf(original?.name ?: "") }
    var frueh by rememberSaveable { mutableStateOf((original?.kind ?: ShiftKind.EARLY) == ShiftKind.EARLY) }
    var beginnFest by rememberSaveable { mutableStateOf(original?.start !is ShiftStart.BeforeClose) }
    var beginnZeit by rememberSaveable { mutableStateOf((original?.start as? ShiftStart.At)?.time?.let(::minuten) ?: (6 * 60)) }
    var beginnVor by rememberSaveable { mutableStateOf((original?.start as? ShiftStart.BeforeClose)?.minutes?.toInt() ?: (6 * 60)) }
    var endeFest by rememberSaveable { mutableStateOf(original?.end !is ShiftEnd.AfterClose) }
    var endeZeit by rememberSaveable { mutableStateOf((original?.end as? ShiftEnd.At)?.time?.let(::minuten) ?: (12 * 60)) }
    var endeNach by rememberSaveable { mutableStateOf((original?.end as? ShiftEnd.AfterClose)?.minutes?.toInt() ?: 0) }
    var tage by rememberSaveable { mutableStateOf((original?.weekdays ?: DayOfWeek.entries.toSet()).sorted().joinToString("") { "${it.value}" }) }
    var personen by rememberSaveable { mutableStateOf(original?.staff ?: 1) }
    var versucht by rememberSaveable { mutableStateOf(false) }
    var verwerfenFrage by remember { mutableStateOf(false) }
    var loeschenFrage by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val requester = remember { TemplatePart.entries.associateWith { BringIntoViewRequester() } }

    fun tageSet() = tage.mapNotNull { it.digitToIntOrNull() }.filter { it in 1..7 }.map(DayOfWeek::of).toSet()

    fun entwurf() = ShiftTemplate(
        id = id,
        name = Shop.cleanName(name),
        kind = if (frueh) ShiftKind.EARLY else ShiftKind.LATE,
        start = if (beginnFest) ShiftStart.At(zeit(beginnZeit)) else ShiftStart.BeforeClose(beginnVor.toLong()),
        end = if (endeFest) ShiftEnd.At(zeit(endeZeit)) else ShiftEnd.AfterClose(endeNach.toLong()),
        weekdays = tageSet(),
        staff = personen,
    )

    // Fester Ausgangsstand (nicht aus den Eingaben), damit "geändert?" auch nach dem Drehen stimmt.
    val anfang = original?.copy(name = Shop.cleanName(original.name)) ?: ShiftTemplate(
        id = id,
        name = "",
        kind = ShiftKind.EARLY,
        start = ShiftStart.At(LocalTime.of(6, 0)),
        end = ShiftEnd.At(LocalTime.of(12, 0)),
    )
    val geaendert = entwurf() != anfang
    val kandidat = Shop.upsert(laden, entwurf())
    val probleme = if (versucht) Shop.problems(kandidat) else emptyList()
    fun eigene(part: TemplatePart) = probleme.filter { it.field == ShopField.Template(id, part) }.map { it.message }
    // Doppelte Namen stehen schon bei dieser Schicht; die andere Schicht nicht nochmal nennen.
    val fremde = probleme.filter {
        val f = it.field
        !(f is ShopField.Template && (f.id == id || f.part == TemplatePart.NAME))
    }

    fun zurueck() {
        if (geaendert) verwerfenFrage = true else schliessen()
    }
    BackHandler { zurueck() }

    Formular(
        titel = if (original == null) "Neue Schicht" else "Schicht bearbeiten",
        zurueck = ::zurueck,
        unten = {
            if (probleme.isNotEmpty()) {
                NichtGespeichert(
                    wo = TemplatePart.entries.filter { eigene(it).isNotEmpty() }.map { TEILNAME.getValue(it) },
                    weitere = fremde.map { fremdText(it, kandidat) },
                )
            }
            SpeichernKnopf {
                val ergebnis = store.speichern { Shop.upsert(it, entwurf()) }
                if (ergebnis.isEmpty()) {
                    schliessen()
                } else {
                    versucht = true
                    val erster = TemplatePart.entries.firstOrNull { p -> ergebnis.any { it.field == ShopField.Template(id, p) } }
                    if (erster != null) scope.launch { requester.getValue(erster).bringIntoView() }
                }
            }
        },
    ) {
        Abschnitt("Name", "Zum Beispiel „Früh 1“ oder „Spät“.", eigene(TemplatePart.NAME), requester[TemplatePart.NAME], "name") {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= Shop.MAX_NAME_LENGTH) name = it },
                singleLine = true,
                isError = eigene(TemplatePart.NAME).isNotEmpty(),
                textStyle = TextStyle(fontSize = 18.sp, color = Schrift),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Akzent,
                    unfocusedBorderColor = Rand,
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
                    errorContainerColor = Color.White,
                    cursorColor = Akzent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("schicht-name"),
            )
        }

        Abschnitt("Art", "Wer nur früh oder nur spät kann, bekommt nur diese Art.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Auswahl("Früh", frueh, Modifier.weight(1f), gewaehltFarbe = Akzent) { frueh = true }
                Auswahl("Spät", !frueh, Modifier.weight(1f), gewaehltFarbe = Akzent) { frueh = false }
            }
        }

        Abschnitt("Beginn", fehler = eigene(TemplatePart.START), requester = requester[TemplatePart.START], tag = "beginn") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Auswahl("Feste Uhrzeit", beginnFest, Modifier.weight(1f), gewaehltFarbe = Akzent) { beginnFest = true }
                Auswahl("Vor Arbeitsende", !beginnFest, Modifier.weight(1f), gewaehltFarbe = Akzent) { beginnFest = false }
            }
            if (beginnFest) {
                Stepper(
                    wert = Shop.format(zeit(beginnZeit)),
                    weniger = "Beginn früher",
                    mehr = "Beginn später",
                    tag = "beginn-wert",
                    onWeniger = { beginnZeit = minuten(Shop.stepTime(zeit(beginnZeit), up = false)) },
                    onMehr = { beginnZeit = minuten(Shop.stepTime(zeit(beginnZeit), up = true)) },
                )
            } else {
                Stepper(
                    wert = if (beginnVor == 0) "genau Arbeitsende" else Shop.formatMinutes(beginnVor.toLong()) + " vorher",
                    weniger = "Beginn später",
                    mehr = "Beginn früher",
                    groesse = 22,
                    onWeniger = { beginnVor = (beginnVor - Shop.TIME_STEP_MINUTES).coerceAtLeast(0) },
                    onMehr = { beginnVor = (beginnVor + Shop.TIME_STEP_MINUTES).coerceAtMost(Shop.MAX_BEFORE_CLOSE_MINUTES.toInt()) },
                )
            }
        }

        Abschnitt(
            "Ende",
            "„Bis Arbeitsende“ passt sich an: z. B. werktags 18:30, samstags 16:30 (siehe Arbeitszeit).",
            eigene(TemplatePart.END),
            requester[TemplatePart.END],
            "ende",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Auswahl("Feste Uhrzeit", endeFest, Modifier.weight(1f).testTag("ende-fest"), gewaehltFarbe = Akzent) { endeFest = true }
                Auswahl("Bis Arbeitsende", !endeFest, Modifier.weight(1f), gewaehltFarbe = Akzent) { endeFest = false }
            }
            if (endeFest) {
                Stepper(
                    wert = Shop.format(zeit(endeZeit)),
                    weniger = "Ende früher",
                    mehr = "Ende später",
                    tag = "ende-wert",
                    onWeniger = { endeZeit = minuten(Shop.stepTime(zeit(endeZeit), up = false)) },
                    onMehr = { endeZeit = minuten(Shop.stepTime(zeit(endeZeit), up = true)) },
                )
            } else {
                Stepper(
                    wert = if (endeNach == 0) "genau Arbeitsende" else Shop.formatMinutes(endeNach.toLong()) + " danach",
                    weniger = "Ende früher",
                    mehr = "Ende später",
                    groesse = 22,
                    onWeniger = { endeNach = (endeNach - Shop.TIME_STEP_MINUTES).coerceAtLeast(0) },
                    onMehr = { endeNach = (endeNach + Shop.TIME_STEP_MINUTES).coerceAtMost(Shop.MAX_AFTER_CLOSE_MINUTES.toInt()) },
                )
            }
        }

        Abschnitt("An welchen Tagen?", "Antippen zum Ab- oder Anwählen.", eigene(TemplatePart.DAYS), requester[TemplatePart.DAYS], "tage") {
            val gewaehlt = tageSet()
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.entries.forEach { d ->
                    Auswahl(Weekdays.short(d), d in gewaehlt, Modifier.weight(1f), gewaehltFarbe = Akzent) {
                        val neu = if (d in gewaehlt) gewaehlt - d else gewaehlt + d
                        tage = neu.sorted().joinToString("") { "${it.value}" }
                    }
                }
            }
        }

        Abschnitt("Wie viele Personen?", fehler = eigene(TemplatePart.STAFF), requester = requester[TemplatePart.STAFF]) {
            Stepper(
                wert = if (personen == 1) "1 Person" else "$personen Personen",
                weniger = "Eine Person weniger",
                mehr = "Eine Person mehr",
                groesse = 22,
                onWeniger = { personen = (personen - 1).coerceAtLeast(1) },
                onMehr = { personen = (personen + 1).coerceAtMost(Shop.MAX_STAFF) },
            )
        }

        // Zeigt sofort, wann die Schicht wirklich liegt. Gerade bei "bis Arbeitsende" hilfreich.
        val vorschau = Shop.preview(entwurf(), laden.openingHours)
        Column(
            Modifier
                .fillMaxWidth()
                .background(if (frueh) Frueh else Spaet, RoundedCornerShape(12.dp))
                .padding(12.dp)
                .testTag("vorschau"),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("So liegt die Schicht", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextMittel)
            if (vorschau.isEmpty()) Text("an keinem Arbeitstag mit gültigen Zeiten", fontSize = 15.sp, color = Schrift)
            vorschau.forEach { Text(it, fontSize = 16.sp, color = Schrift) }
        }

        if (original != null) {
            TextButton(onClick = { loeschenFrage = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Schicht „${original.name}“ löschen", color = Akzent)
            }
        }
    }

    if (verwerfenFrage) VerwerfenFrage(abbrechen = { verwerfenFrage = false }, verwerfen = schliessen)
    if (loeschenFrage && original != null) {
        AlertDialog(
            onDismissRequest = { loeschenFrage = false },
            title = { Text("„${original.name}“ löschen?") },
            text = { Text("Diese Schicht wird nicht mehr eingeplant.") },
            confirmButton = {
                TextButton(onClick = {
                    loeschenFrage = false
                    store.speichern { Shop.remove(it, original.id) }
                    schliessen()
                }) { Text("Löschen", color = Akzent) }
            },
            dismissButton = { TextButton(onClick = { loeschenFrage = false }) { Text("Abbrechen", color = Schrift) } },
        )
    }
}

private val TEILNAME = mapOf(
    TemplatePart.NAME to "Name",
    TemplatePart.START to "Beginn",
    TemplatePart.END to "Ende",
    TemplatePart.DAYS to "Tage",
    TemplatePart.STAFF to "Personen",
)

/** Problem, das nicht in diesem Formular steht, z. B. eine Schicht, die durch eine neue Arbeitszeit nicht mehr passt. */
private fun fremdText(p: Problem<ShopField>, laden: ShopSettings): String = when (val f = p.field) {
    is ShopField.Template -> {
        val name = laden.templates.firstOrNull { it.id == f.id }?.name ?: "?"
        "Schicht „$name“ (${TEILNAME.getValue(f.part)}): ${p.message}"
    }
    is ShopField.Day -> "${Weekdays.short(f.day)}: ${p.message}"
    else -> p.message
}

// ---------- gemeinsame Teile der Formulare ----------

@Composable
private fun Formular(
    titel: String,
    zurueck: () -> Unit,
    unten: @Composable () -> Unit,
    inhalt: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = zurueck,
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = "Zurück zum Laden" },
                contentPadding = PaddingValues(0.dp),
            ) { Text("‹", fontSize = 28.sp, color = Schrift) }
            Text(titel, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Schrift)
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) { inhalt() }
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color.White)
                .border(1.dp, RandHell)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { unten() }
    }
}

@Composable
private fun SpeichernKnopf(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Akzent),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .testTag("speichern"),
    ) { Text("Speichern", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun VerwerfenFrage(abbrechen: () -> Unit, verwerfen: () -> Unit) {
    AlertDialog(
        onDismissRequest = abbrechen,
        title = { Text("Änderungen verwerfen?") },
        text = { Text("Was du hier geändert hast, wird nicht gespeichert.") },
        confirmButton = { TextButton(onClick = { abbrechen(); verwerfen() }) { Text("Verwerfen", color = Akzent) } },
        dismissButton = { TextButton(onClick = abbrechen) { Text("Weiter bearbeiten", color = Schrift) } },
    )
}

/** "19 h", "18,5 h" oder "18,25 h" (Viertelstunden ohne Rundung). */
private fun stunden(minuten: Long): String = when {
    minuten % 60 == 0L -> "${minuten / 60} h"
    minuten % 30 == 0L -> String.format(java.util.Locale.GERMANY, "%.1f h", minuten / 60.0)
    else -> String.format(java.util.Locale.GERMANY, "%.2f h", minuten / 60.0)
}
