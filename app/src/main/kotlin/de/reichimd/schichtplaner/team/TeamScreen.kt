@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package de.reichimd.schichtplaner.team

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.reichimd.schichtplaner.core.Employee
import de.reichimd.schichtplaner.core.Problem
import de.reichimd.schichtplaner.core.team.TeamField
import de.reichimd.schichtplaner.ui.Abschnitt
import de.reichimd.schichtplaner.ui.Akzent
import de.reichimd.schichtplaner.ui.AkzentDunkel
import de.reichimd.schichtplaner.ui.Auswahl
import de.reichimd.schichtplaner.ui.Chip
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
import de.reichimd.schichtplaner.core.team.Team
import de.reichimd.schichtplaner.core.wishes.Wishes
import de.reichimd.schichtplaner.wuensche.WuenscheStore
import de.reichimd.schichtplaner.wuensche.eintraege
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.util.UUID

/** Markiert "neuer Mitarbeiter" im Zustand des Bearbeiten-Screens. */
private const val NEU = "__neu__"

/**
 * Team-Bereich: Liste und Bearbeiten. [bearbeitetGerade] meldet nach außen, ob gerade ein
 * Mitarbeiter offen ist (dann blendet die App die untere Leiste aus).
 */
@Composable
internal fun TeamBereich(
    store: TeamStore = TeamStore.get(LocalContext.current),
    wuensche: WuenscheStore = WuenscheStore.get(LocalContext.current),
    bearbeitetGerade: (Boolean) -> Unit = {},
) {
    val zustand by store.zustand.collectAsState()
    var offen by rememberSaveable { mutableStateOf<String?>(null) }
    val bearbeitet = offen != null
    SideEffect { bearbeitetGerade(bearbeitet) }

    val id = offen
    if (id == null) {
        TeamListe(zustand, store, oeffnen = { offen = it })
    } else {
        val vorhanden = zustand.team.firstOrNull { it.id == id }
        if (id != NEU && vorhanden == null) {
            // Gelöscht oder nicht mehr da: zurück zur Liste. Während des Ladens (nach Neustart) noch warten.
            if (!zustand.laedt) LaunchedEffect(id) { offen = null }
            return
        }
        MitarbeiterBearbeiten(
            original = vorhanden,
            team = zustand.team,
            store = store,
            wuensche = wuensche,
            schliessen = { offen = null },
        )
    }
}

@Composable
private fun TeamListe(zustand: TeamZustand, store: TeamStore, oeffnen: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Team", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Schrift, modifier = Modifier.weight(1f))
            Button(
                onClick = { oeffnen(NEU) },
                enabled = zustand.bearbeitbar,
                colors = ButtonDefaults.buttonColors(containerColor = Akzent),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("neu"),
            ) { Text("+ Neu", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }

        zustand.meldung?.let { m ->
            Hinweis(m, Modifier.padding(horizontal = 16.dp)) {
                TextButton(onClick = store::meldungGelesen) { Text("OK", color = Akzent) }
            }
            Spacer(Modifier.height(8.dp))
        }

        when {
            zustand.laedt -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Akzent)
            }
            zustand.team.isEmpty() -> LeeresTeam(
                bearbeitbar = zustand.bearbeitbar,
                neu = { oeffnen(NEU) },
                beispiel = store::beispielLaden,
            )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(zustand.team, key = { it.id }) { e ->
                    MitarbeiterZeile(e, enabled = zustand.bearbeitbar) { oeffnen(e.id) }
                }
            }
        }
    }
}

@Composable
private fun LeeresTeam(bearbeitbar: Boolean, neu: () -> Unit, beispiel: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text("Noch niemand im Team.", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Schrift)
        Text(
            "Lege jede Person einmal an: Vorname, Stunden im Monat und was sie kann. " +
                "Das bleibt gespeichert, auch nach dem Schließen der App.",
            fontSize = 15.sp,
            color = TextMittel,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = neu,
            enabled = bearbeitbar,
            colors = ButtonDefaults.buttonColors(containerColor = Akzent),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) { Text("Erste Person anlegen", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
        OutlinedButton(
            onClick = beispiel,
            enabled = bearbeitbar,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("beispiel"),
        ) { Text("Beispiel-Team laden", fontSize = 16.sp, color = Akzent) }
        Text(
            "Lädt ${Team.EXAMPLE.size} Personen mit Beispielwerten. Danach einfach anpassen oder löschen.",
            fontSize = 13.sp,
            color = TextLeise,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MitarbeiterZeile(e: Employee, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(14.dp))
            .border(1.dp, RandHell, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(12.dp)
            .testTag("mitarbeiter-${e.name}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .background(Frueh, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(e.name.take(1).uppercase(), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AkzentDunkel)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    e.name,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Schrift,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text("${e.monthlyHours} h / Monat", fontSize = 14.sp, color = TextMittel)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Team.tags(e).forEach {
                    Text(
                        it,
                        fontSize = 12.sp,
                        color = TextMittel,
                        modifier = Modifier
                            .background(Chip, RoundedCornerShape(10.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Text("›", fontSize = 22.sp, color = TextLeise)
    }
}

/** So heißen die Abschnitte im Formular; damit nennt der Kasten über "Speichern", wo es hakt. */
private val FELDNAME = mapOf(
    TeamField.NAME to "Vorname",
    TeamField.HOURS to "Stunden im Monat",
    TeamField.SHIFTS to "Welche Schichten?",
    TeamField.DAYS to "Kann an diesen Tagen nie",
)

/** Wochentage als Zeichenkette "135" (Mo, Mi, Fr), damit sie Drehen und Neustart überstehen. */
private fun tageAlsText(tage: Set<DayOfWeek>) = tage.sorted().joinToString("") { it.value.toString() }
private fun tageAusText(text: String) = text.mapNotNull { it.digitToIntOrNull() }.filter { it in 1..7 }.map(DayOfWeek::of).toSet()

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MitarbeiterBearbeiten(
    original: Employee?,
    team: List<Employee>,
    store: TeamStore,
    wuensche: WuenscheStore,
    schliessen: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(original?.name ?: "") }
    var stunden by rememberSaveable { mutableStateOf(original?.monthlyHours ?: 80) }
    var frueh by rememberSaveable { mutableStateOf(original?.canEarly ?: true) }
    var spaet by rememberSaveable { mutableStateOf(original?.canLate ?: true) }
    var gesperrt by rememberSaveable { mutableStateOf(tageAlsText(original?.blockedWeekdays ?: emptySet())) }
    var aushilfe by rememberSaveable { mutableStateOf(original?.isBackup ?: false) }
    val neueId = rememberSaveable { UUID.randomUUID().toString() }
    // Nach dem ersten Speichern-Versuch wird laufend geprüft: Rote Hinweise verschwinden, sobald es passt.
    var versucht by rememberSaveable { mutableStateOf(false) }
    var allgemein by remember { mutableStateOf(emptyList<Problem<TeamField>>()) }
    var verwerfenFrage by remember { mutableStateOf(false) }
    var loeschenFrage by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val requester = remember { TeamField.entries.associateWith { BringIntoViewRequester() } }

    fun entwurf() = Employee(
        id = original?.id ?: neueId,
        name = Team.cleanName(name),
        monthlyHours = stunden,
        canEarly = frueh,
        canLate = spaet,
        blockedWeekdays = tageAusText(gesperrt),
        isBackup = aushilfe,
    )

    val probleme = if (versucht) Team.problems(entwurf(), team) + allgemein else emptyList()
    fun fehler(feld: TeamField) = probleme.filter { it.field == feld }.map { it.message }

    // Ausgangsstand fest aus dem Original (bzw. den Startwerten), damit es auch nach dem Drehen stimmt.
    val start = original?.copy(name = Team.cleanName(original.name)) ?: Employee(neueId, "", 80)
    val geaendert = entwurf() != start

    fun zurueck() {
        if (geaendert) verwerfenFrage = true else schliessen()
    }
    BackHandler { zurueck() }

    fun speichern() {
        val ergebnis = store.speichern(entwurf())
        if (ergebnis.isEmpty()) {
            schliessen()
            return
        }
        versucht = true
        allgemein = ergebnis.filter { it.field == TeamField.GENERAL }
        // Zum ersten Fehler scrollen, damit er sicher zu sehen ist.
        val erstes = TeamField.entries.firstOrNull { f -> ergebnis.any { it.field == f } }
        if (erstes != null) scope.launch { requester.getValue(erstes).bringIntoView() }
    }

    Column(
        Modifier.fillMaxSize(),
    ) {
        Row(
            Modifier.padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = ::zurueck,
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = "Zurück zum Team" },
                contentPadding = PaddingValues(0.dp),
            ) { Text("‹", fontSize = 28.sp, color = Schrift) }
            Text(
                if (original == null) "Neue Person" else "Person bearbeiten",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Schrift,
            )
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Abschnitt("Vorname", fehler = fehler(TeamField.NAME), requester = requester[TeamField.NAME], tag = "vorname") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= Team.MAX_NAME_LENGTH) name = it },
                    singleLine = true,
                    isError = fehler(TeamField.NAME).isNotEmpty(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 18.sp, color = Schrift),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Done,
                    ),
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
                        .testTag("vorname"),
                )
            }

            Abschnitt("Stunden im Monat", fehler = fehler(TeamField.HOURS), requester = requester[TeamField.HOURS]) {
                Stepper(
                    wert = "$stunden h",
                    weniger = "${Team.HOURS_STEP} Stunden weniger",
                    mehr = "${Team.HOURS_STEP} Stunden mehr",
                    tag = "stunden",
                    groesse = 32,
                    onWeniger = { stunden = Team.clampHours(stunden - Team.HOURS_STEP) },
                    onMehr = { stunden = Team.clampHours(stunden + Team.HOURS_STEP) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Team.HOURS_PRESETS.forEach { h ->
                        Auswahl(h.toString(), gewaehlt = stunden == h, modifier = Modifier.weight(1f)) { stunden = h }
                    }
                }
            }

            Abschnitt(
                "Welche Schichten?",
                "Antippen schaltet um. „Ja“ heißt: kann diese Schicht.",
                fehler = fehler(TeamField.SHIFTS),
                requester = requester[TeamField.SHIFTS],
                tag = "schichten",
            ) {
                Schalter("Frühschicht", frueh, Frueh) { frueh = !frueh }
                Schalter("Spätschicht", spaet, Spaet) { spaet = !spaet }
            }

            Abschnitt(
                "Kann an diesen Tagen nie",
                "Zum Beispiel wegen Schule. Antippen zum Markieren.",
                fehler = fehler(TeamField.DAYS),
                requester = requester[TeamField.DAYS],
            ) {
                val tage = tageAusText(gesperrt)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DayOfWeek.entries.forEach { d ->
                        Auswahl(
                            Team.shortDay(d),
                            gewaehlt = d in tage,
                            gewaehltFarbe = Akzent,
                            modifier = Modifier.weight(1f),
                        ) {
                            gesperrt = tageAlsText(if (d in tage) tage - d else tage + d)
                        }
                    }
                }
            }

            Abschnitt("Aushilfe", "Zum Beispiel aus einer anderen Filiale. Wird nur eingeplant, wenn es ohne sie nicht geht.") {
                Schalter("Nur bei Bedarf einplanen", aushilfe, Chip) { aushilfe = !aushilfe }
            }

            if (original != null) {
                TextButton(onClick = { loeschenFrage = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("${original.name} aus dem Team löschen", color = Akzent)
                }
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(Color.White)
                .border(1.dp, RandHell)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Direkt über dem Knopf, damit der Hinweis nie außerhalb des Bildes landet.
            if (probleme.isNotEmpty()) {
                NichtGespeichert(
                    wo = TeamField.entries.filter { f -> probleme.any { it.field == f } }.mapNotNull { FELDNAME[it] },
                    weitere = probleme.filter { it.field == TeamField.GENERAL }.map { it.message },
                )
            }
            Button(
                onClick = ::speichern,
                colors = ButtonDefaults.buttonColors(containerColor = Akzent),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("speichern"),
            ) { Text("Speichern", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
        }
    }

    if (verwerfenFrage) {
        AlertDialog(
            onDismissRequest = { verwerfenFrage = false },
            title = { Text("Änderungen verwerfen?") },
            text = { Text("Was du hier geändert hast, wird nicht gespeichert.") },
            confirmButton = {
                TextButton(onClick = { verwerfenFrage = false; schliessen() }) { Text("Verwerfen", color = Akzent) }
            },
            dismissButton = {
                TextButton(onClick = { verwerfenFrage = false }) { Text("Weiter bearbeiten", color = Schrift) }
            },
        )
    }
    if (loeschenFrage && original != null) {
        val eintraege by wuensche.zustand.collectAsState()
        val anzahl = Wishes.ofEmployee(eintraege.eintraege, original.id).size
        // Ist der Kalender gesperrt, bleiben die Einträge in der Datei, werden aber nirgends mehr genutzt.
        val kalenderOk = eintraege.bearbeitbar
        AlertDialog(
            onDismissRequest = { loeschenFrage = false },
            title = { Text("${original.name} löschen?") },
            text = {
                Text(
                    "${original.name} wird aus dem Team entfernt und nicht mehr eingeplant." +
                        when {
                            !kalenderOk -> ""
                            anzahl == 0 -> ""
                            anzahl == 1 -> " Der eine eingetragene Tag im Wünsche-Kalender wird mitgelöscht."
                            else -> " Die $anzahl eingetragenen Tage im Wünsche-Kalender werden mitgelöscht."
                        },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    loeschenFrage = false
                    // Erst die Kalendereinträge, dann die Person: so bleibt nie etwas ohne Besitzer zurück.
                    wuensche.personEntfernen(original.id)
                    store.loeschen(original.id)
                    schliessen()
                }) { Text("Löschen", color = Akzent) }
            },
            dismissButton = { TextButton(onClick = { loeschenFrage = false }) { Text("Abbrechen", color = Schrift) } },
        )
    }
}
