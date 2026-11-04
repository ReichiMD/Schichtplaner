package de.reichimd.schichtplaner.plan

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.reichimd.schichtplaner.DemoData
import de.reichimd.schichtplaner.core.Absence
import de.reichimd.schichtplaner.core.AbsenceType
import de.reichimd.schichtplaner.core.Employee
import de.reichimd.schichtplaner.core.Planner
import de.reichimd.schichtplaner.core.ShiftKind
import de.reichimd.schichtplaner.core.Weekdays
import de.reichimd.schichtplaner.core.plan.PlanCheck
import de.reichimd.schichtplaner.core.plan.PlanEdit
import de.reichimd.schichtplaner.core.plan.PlanPrint
import de.reichimd.schichtplaner.core.plan.PlanSheet
import de.reichimd.schichtplaner.core.plan.PlanSlot
import de.reichimd.schichtplaner.core.plan.Severity
import de.reichimd.schichtplaner.core.plan.ShiftPlan
import de.reichimd.schichtplaner.core.plan.SlotKey
import de.reichimd.schichtplaner.core.plan.Warning
import de.reichimd.schichtplaner.core.team.Team
import de.reichimd.schichtplaner.core.plan.YearPlans
import de.reichimd.schichtplaner.core.shop.Shop
import de.reichimd.schichtplaner.core.wishes.Wishes
import de.reichimd.schichtplaner.laden.LadenStore
import de.reichimd.schichtplaner.laden.eingerichtet
import de.reichimd.schichtplaner.laden.laden
import de.reichimd.schichtplaner.team.TeamStore
import de.reichimd.schichtplaner.team.team
import de.reichimd.schichtplaner.ui.Akzent
import de.reichimd.schichtplaner.ui.Fehler
import de.reichimd.schichtplaner.ui.FehlerHell
import de.reichimd.schichtplaner.ui.FehlerText
import de.reichimd.schichtplaner.ui.FreiWunsch
import de.reichimd.schichtplaner.ui.WunschErfuellt
import de.reichimd.schichtplaner.ui.WunschErfuelltHell
import de.reichimd.schichtplaner.ui.Frueh
import de.reichimd.schichtplaner.ui.Hintergrund
import de.reichimd.schichtplaner.ui.Hinweis
import de.reichimd.schichtplaner.ui.Rand
import de.reichimd.schichtplaner.ui.RandHell
import de.reichimd.schichtplaner.ui.Schrift
import de.reichimd.schichtplaner.ui.Spaet
import de.reichimd.schichtplaner.ui.Stepper
import de.reichimd.schichtplaner.ui.TextLeise
import de.reichimd.schichtplaner.ui.TextMittel
import de.reichimd.schichtplaner.update.UpdateBanner
import de.reichimd.schichtplaner.update.VersionZeile
import de.reichimd.schichtplaner.wuensche.WuenscheStore
import de.reichimd.schichtplaner.wuensche.eintraege
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields

/** Stand von "Rest ausfüllen". Der Plan selbst liegt im [PlanStore] und übersteht Neustarts. */
internal sealed interface Rechnung {
    data object Ruht : Rechnung
    data class Rechnet(val montag: LocalDate) : Rechnung
    data class Fertig(val montag: LocalDate, val millis: Long, val offen: Int) : Rechnung
    /** Der Planer hat in der Rechenzeit gar keinen Plan gefunden (nicht dasselbe wie Personalmangel). */
    data class NichtGefunden(val montag: LocalDate) : Rechnung
    data class Fehler(val montag: LocalDate, val meldung: String) : Rechnung
}

/**
 * Hält "Rest ausfüllen" über Drehen des Handys und Wechsel der Reiter hinweg, und die gezeigte Woche.
 * Das Ergebnis landet im [PlanStore].
 */
internal class PlanViewModel : ViewModel() {
    var rechnung by mutableStateOf<Rechnung>(Rechnung.Ruht)
        private set

    /** Montag der gezeigten Woche; null = noch nicht gesetzt (dann die aktuelle Woche). */
    var montag by mutableStateOf<LocalDate?>(null)

    fun meldungWeg() {
        if (rechnung !is Rechnung.Rechnet) rechnung = Rechnung.Ruht
    }

    /** Offene Schichten der Woche ab [woche] besetzen. Was schon eingetragen ist, bleibt. */
    fun ausfuellen(woche: LocalDate, teamStore: TeamStore, ladenStore: LadenStore, wuenscheStore: WuenscheStore, planStore: PlanStore) {
        if (rechnung is Rechnung.Rechnet) return
        rechnung = Rechnung.Rechnet(woche)
        viewModelScope.launch {
            rechnung = try {
                val teamStand = teamStore.zustand.first { !it.laedt }
                // Teamliste nicht lesbar: nicht rechnen, sonst würden echte Einträge als "unbekannt" überschrieben.
                if (!teamStand.bearbeitbar) {
                    rechnung = Rechnung.Fehler(woche, "Die Teamliste ist gerade nicht lesbar. Bitte im Reiter „Team“ nachsehen.")
                    return@launch
                }
                // Mit dem gespeicherten Team, solange noch keins angelegt ist mit dem erfundenen Beispiel.
                val team = teamStand.team.ifEmpty { DemoData.team }
                val laden = ladenStore.zustand.first { !it.laedt }.laden
                val wuensche = wuenscheStore.zustand.first { !it.laedt }.eintraege
                val vorher = planStore.zustand.first { !it.laedt }.plan
                val start = System.currentTimeMillis()
                // Der Planer rechnet einige Sekunden (höchstens 15 s): nie im UI-Thread.
                val ergebnis = withContext(Dispatchers.Default) {
                    Planner.plan(YearPlans.fillRequest(vorher, woche, laden, team, wuensche))
                }
                val millis = System.currentTimeMillis() - start
                val ids = team.map { it.id }.toSet()
                // Team, Laden oder Kalender während der Rechnung geändert (z. B. jemand ausgeschieden): nicht eintragen.
                val geaendert = teamStore.zustand.value.team != teamStand.team ||
                    ladenStore.zustand.value.laden != laden ||
                    wuenscheStore.zustand.value.eintraege != wuensche
                when {
                    !ergebnis.solved -> Rechnung.NichtGefunden(woche)
                    geaendert -> Rechnung.Fehler(woche, "Team, Laden oder Wünsche wurden inzwischen geändert. Bitte noch einmal „Rest ausfüllen“.")
                    // Nur eintragen, wenn sich der Plan inzwischen nicht geändert hat (z. B. Sicherung eingespielt).
                    !planStore.wennUnveraendert(vorher) { p ->
                        YearPlans.withWeek(p, YearPlans.applyFill(YearPlans.week(p, woche, laden), ergebnis, ids))
                    } -> Rechnung.Fehler(woche, "Der Plan wurde inzwischen geändert. Bitte noch einmal „Rest ausfüllen“.")
                    else -> {
                        val neu = YearPlans.week(planStore.zustand.value.plan, woche, laden)
                        Rechnung.Fertig(woche, millis, PlanSheet.openCount(neu, ids))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Rechnung.Fehler(woche, "${t::class.java.simpleName}: ${t.message}")
            }
        }
    }
}

private val TAG_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.")

private fun tagText(d: LocalDate) = "${Weekdays.short(d.dayOfWeek)}, ${d.format(TAG_FORMAT)}"

private fun zeitText(s: PlanSlot) = "${PlanSheet.time(s.start)}–${PlanSheet.time(s.end)}"

/**
 * Plan-Reiter: Wochenblatt wie auf dem Papier, immer da, durch das ganze Jahr wischbar.
 * Antippen ändert eine Schicht, "Rest ausfüllen" besetzt die offenen Schichten der gezeigten Woche.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlanScreen(
    teamStore: TeamStore = TeamStore.get(LocalContext.current),
    ladenStore: LadenStore = LadenStore.get(LocalContext.current),
    wuenscheStore: WuenscheStore = WuenscheStore.get(LocalContext.current),
    planStore: PlanStore = PlanStore.get(LocalContext.current),
    heute: LocalDate = LocalDate.now(),
    vm: PlanViewModel = viewModel(),
) {
    val team by teamStore.zustand.collectAsState()
    val laden by ladenStore.zustand.collectAsState()
    val wuensche by wuenscheStore.zustand.collectAsState()
    val gespeichert by planStore.zustand.collectAsState()
    val rechnung = vm.rechnung

    var leerenFrage by remember { mutableStateOf(false) }
    // Geöffnetes Feld als Text, damit es Drehen übersteht: "Mitarbeiter-ID|Datum" bzw. Datum der offenen Schichten.
    var feld by rememberSaveable { mutableStateOf<String?>(null) }
    var offenerTag by rememberSaveable { mutableStateOf<String?>(null) }

    // Alle, auch Ausgeschiedene (für alte Wochen). Eingeplant und zur Auswahl angeboten werden nur Aktive.
    val alle = team.team.ifEmpty { DemoData.team }
    val aktive = remember(alle) { Team.active(alle) }
    val alleIds = remember(alle) { alle.map { it.id }.toSet() }
    val abwesend = wuensche.eintraege
    val plan = gespeichert.plan
    val shop = laden.laden
    // Einmal je Bildschirm festgelegt: Bleibt die App über Silvester offen, verrutscht sonst die Seite.
    val wochen = remember { YearPlans.range(heute) }
    val startSeite = remember(wochen) { wochen.indexOf(YearPlans.monday(heute)).coerceAtLeast(0) }
    val rechnet = rechnung is Rechnung.Rechnet
    val aenderbar = gespeichert.bearbeitbar && team.bearbeitbar && !rechnet

    val pager = rememberPagerState(
        initialPage = vm.montag?.let { wochen.indexOf(it) }?.takeIf { it >= 0 } ?: startSeite,
    ) { wochen.size }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { vm.montag = wochen[it] }
    }
    val montag = wochen[pager.currentPage.coerceIn(0, wochen.lastIndex)]
    val woche = remember(plan, montag, shop) { YearPlans.week(plan, montag, shop) }
    // Drei Wochen, damit Ruhezeit und Tage am Stück über den Wochenwechsel geprüft werden (auch in den Dialogen).
    val fenster = remember(plan, montag, shop) { YearPlans.weeks(plan, montag.minusWeeks(1), 3, shop) }
    val hinweise = remember(plan, montag, shop, alle, abwesend) { YearPlans.warnings(plan, montag, shop, alle, abwesend) }
    val zeilen = remember(alle, woche) { YearPlans.rows(alle, woche) }

    fun ausfuellen() = vm.ausfuellen(montag, teamStore, ladenStore, wuenscheStore, planStore)
    fun aendern(tag: LocalDate, wie: (ShiftPlan) -> ShiftPlan) = planStore.woche(YearPlans.monday(tag), shop, wie)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Schichtplan",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Schrift,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        if (team.team.isEmpty() || !laden.eingerichtet) {
            Text(
                buildString {
                    if (team.team.isEmpty()) append("Noch kein Team angelegt: Der Plan nutzt ein erfundenes Beispiel-Team. ")
                    if (!laden.eingerichtet) append("Laden und Schichten sind noch Beispielwerte (unter „Laden“ prüfen).")
                }.trim(),
                fontSize = 14.sp,
                color = TextLeise,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }

        UpdateBanner()

        laden.meldung?.let { Hinweis("Laden: $it", Modifier.padding(horizontal = 8.dp)) }
        gespeichert.meldung?.let { m ->
            Hinweis(m, Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = planStore::meldungGelesen) { Text("OK", color = Akzent) }
            }
        }
        if (!wuensche.laedt && wuensche.nurLesen) {
            Hinweis(
                "Der Wünsche-Kalender ist gerade gesperrt, „Rest ausfüllen“ rechnet ohne Urlaub und Wünsche. " +
                    "Mehr dazu unter „Wünsche“.",
                Modifier.padding(horizontal = 8.dp),
            )
        }
        if (Shop.problems(shop).isNotEmpty()) {
            Hinweis("Die Laden-Einstellungen haben Fehler. Bitte unter „Laden“ korrigieren.", Modifier.padding(horizontal = 8.dp))
        }
        Shop.warnings(shop).forEach { Hinweis(it, Modifier.padding(horizontal = 8.dp)) }

        if (gespeichert.laedt) {
            CircularProgressIndicator(Modifier.padding(horizontal = 8.dp).size(24.dp), color = Akzent)
        }

        WochenKopf(
            ersterTag = montag,
            heute = heute,
            zurueck = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
            weiter = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
            zurHeutigen = { scope.launch { pager.animateScrollToPage(startSeite) } },
            ersteWoche = pager.currentPage == 0,
            letzteWoche = pager.currentPage == wochen.lastIndex,
        )

        Row(
            Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = ::ausfuellen,
                enabled = aenderbar,
                colors = ButtonDefaults.buttonColors(containerColor = Akzent),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
                    .testTag("rest-ausfuellen"),
            ) {
                Text("Rest ausfüllen", fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
            OutlinedButton(
                onClick = { leerenFrage = true },
                enabled = aenderbar && plan.hasWeek(montag),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .heightIn(min = 52.dp)
                    .testTag("woche-leeren"),
            ) { Text("Leeren", fontSize = 15.sp, color = if (aenderbar && plan.hasWeek(montag)) Akzent else Rand) }
        }
        Text(
            "Was schon eingetragen ist, bleibt. Gefüllt wird nur diese Woche.",
            fontSize = 12.sp,
            color = TextLeise,
            modifier = Modifier.padding(horizontal = 8.dp),
        )

        // Meldungen von "Rest ausfüllen" nur in der Woche, für die gerechnet wurde.
        when (rechnung) {
            Rechnung.Ruht -> Unit
            is Rechnung.Rechnet -> Row(
                Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(Modifier.size(24.dp), color = Akzent)
                Text(
                    if (rechnung.montag == montag) "Woche wird ausgefüllt …"
                    else "Woche ab ${rechnung.montag.format(TAG_FORMAT)} wird ausgefüllt …",
                    color = Schrift,
                )
            }
            is Rechnung.Fertig -> if (rechnung.montag == montag) {
                Text(
                    (if (rechnung.offen == 0) "Ausgefüllt" else "Ausgefüllt, ${rechnung.offen} offen (zu wenig Personal oder Regeln)") +
                        " · ${rechnung.millis / 1000.0} s",
                    fontSize = 13.sp,
                    color = TextLeise,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            is Rechnung.NichtGefunden -> if (rechnung.montag == montag) {
                Hinweis(
                    "In der Rechenzeit wurde keine Lösung gefunden. Bitte erneut versuchen. Es wurde nichts geändert.",
                    Modifier.padding(horizontal = 8.dp),
                ) { TextButton(onClick = vm::meldungWeg) { Text("OK", color = Akzent) } }
            }
            is Rechnung.Fehler -> if (rechnung.montag == montag) {
                Hinweis("Rest ausfüllen: ${rechnung.meldung}", Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = vm::meldungWeg) { Text("OK", color = Akzent) }
                }
            }
        }

        Zusammenfassung(woche, alleIds, hinweise)
        Wochenblatt(
            wochen = wochen,
            wocheVon = { YearPlans.week(plan, it, shop) },
            personen = zeilen,
            alleIds = alleIds,
            abwesend = abwesend,
            hinweise = hinweise,
            pager = pager,
            aenderbar = aenderbar,
            feldAntippen = { id, tag -> feld = "$id|$tag" },
            offenAntippen = { tag -> offenerTag = tag.toString() },
        )
        Legende()
        PdfKnoepfe(plan, montag, shop, alle, abwesend, Modifier.padding(horizontal = 8.dp))
        WochenHinweise(woche, hinweise) { w -> if (aenderbar) feld = "${w.employeeId}|${w.key.date}" }

        Spacer(Modifier.height(8.dp))
        VersionZeile()
    }

    if (leerenFrage) {
        AlertDialog(
            onDismissRequest = { leerenFrage = false },
            title = { Text("Woche leeren?") },
            text = {
                Text(
                    "Alle Einträge der Woche ${montag.format(TAG_FORMAT)} – ${montag.plusDays(6).format(TAG_FORMAT)} " +
                        "werden entfernt, auch Änderungen von Hand. Danach gelten wieder die Schichten aus „Laden“.",
                )
            },
            confirmButton = {
                TextButton(onClick = { leerenFrage = false; planStore.wocheLeeren(montag) }, modifier = Modifier.testTag("leeren-ok")) {
                    Text("Leeren", color = Akzent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { leerenFrage = false }) { Text("Abbrechen", color = Schrift) } },
        )
    }

    val offenesFeld = feld
    if (offenesFeld != null) {
        // Das Datum steht hinten, die ID davor (sie darf selbst "|" enthalten).
        val person = alle.firstOrNull { it.id == offenesFeld.substringBeforeLast('|') }
        val tag = runCatching { LocalDate.parse(offenesFeld.substringAfterLast('|')) }.getOrNull()
        if (person == null || tag == null || YearPlans.monday(tag) != montag || !aenderbar) {
            LaunchedEffect(feld) { feld = null }
        } else {
            PersonTagDialog(
                plan = fenster,
                person = person,
                tag = tag,
                leute = alle,
                abwesend = abwesend,
                hinweise = hinweise,
                // Ausgeschiedene behalten ihre alten Schichten, bekommen aber keine neuen.
                uebernehmen = { stelle -> if (!person.archived) aendern(tag) { PlanEdit.assign(it, stelle, person.id, alleIds) } },
                freiGeben = { aendern(tag) { PlanEdit.free(it, person.id, tag) } },
                zeiten = { stelle, beginn, ende ->
                    PlanEdit.timeProblem(beginn, ende) ?: run {
                        aendern(tag) { PlanEdit.setTimes(it, stelle, beginn, ende) }
                        null
                    }
                },
                schliessen = { feld = null },
            )
        }
    }

    val offenDatum = offenerTag?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    if (offenerTag != null) {
        if (offenDatum == null || YearPlans.monday(offenDatum) != montag || !aenderbar) {
            LaunchedEffect(offenerTag) { offenerTag = null }
        } else {
            OffenDialog(
                plan = fenster,
                tag = offenDatum,
                leute = aktive,
                teamIds = alleIds,
                abwesend = abwesend,
                uebernehmen = { stelle, id -> aendern(offenDatum) { PlanEdit.assign(it, stelle, id, alleIds) } },
                schliessen = { offenerTag = null },
            )
        }
    }
}

@Composable
private fun Zusammenfassung(woche: ShiftPlan, teamIds: Set<String>, hinweise: List<Warning>) {
    val offen = PlanSheet.openCount(woche, teamIds)
    val regeln = hinweise.count { it.severity == Severity.RULE }
    val wuensche = hinweise.count { it.severity == Severity.WISH }
    val besetzt = woche.slots.size - offen
    val text = buildString {
        append("Diese Woche: ")
        val teile = buildList {
            if (offen > 0) add(if (offen == 1) "1 Schicht offen" else "$offen Schichten offen")
            if (regeln > 0) add(if (regeln == 1) "1 Regel verletzt" else "$regeln Regeln verletzt")
            if (wuensche > 0) add(if (wuensche == 1) "1 Frei-Wunsch nicht erfüllt" else "$wuensche Frei-Wünsche nicht erfüllt")
        }
        append(
            when {
                woche.slots.isEmpty() -> "keine Schichten."
                teile.isEmpty() -> "Alle Schichten besetzt, keine Regel verletzt."
                else -> teile.joinToString(", ") + "."
            },
        )
    }
    // Eine ganz leere Woche ist kein Fehler, nur noch nicht geplant.
    val gut = (offen == 0 || besetzt == 0) && regeln == 0
    Text(
        text,
        fontSize = 14.sp,
        fontWeight = if (gut) FontWeight.Normal else FontWeight.Bold,
        color = if (gut) TextMittel else Fehler,
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .testTag("zusammenfassung"),
    )
}

@Composable
private fun WochenKopf(
    ersterTag: LocalDate,
    heute: LocalDate,
    zurueck: () -> Unit,
    weiter: () -> Unit,
    zurHeutigen: () -> Unit,
    ersteWoche: Boolean,
    letzteWoche: Boolean,
) {
    val dieseWoche = YearPlans.monday(heute) == ersterTag
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = zurueck,
            enabled = !ersteWoche,
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = "Vorige Woche" },
            contentPadding = ButtonDefaults.TextButtonContentPadding,
        ) {
            Text("‹", fontSize = 22.sp, color = if (!ersteWoche) Schrift else Rand)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "KW ${ersterTag.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)} · ${ersterTag.get(IsoFields.WEEK_BASED_YEAR)}" +
                    if (dieseWoche) " · diese Woche" else "",
                fontSize = 13.sp,
                color = TextLeise,
                modifier = Modifier.testTag("kw"),
            )
            Text(
                "${ersterTag.format(TAG_FORMAT)} – ${ersterTag.plusDays(6).format(TAG_FORMAT)}",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Schrift,
                modifier = Modifier.testTag("wochen-titel"),
            )
            if (dieseWoche) {
                Text("wischen zum Blättern", fontSize = 11.sp, color = TextLeise)
            } else {
                Text(
                    "zur aktuellen Woche",
                    fontSize = 12.sp,
                    color = Akzent,
                    modifier = Modifier
                        .clickable(role = Role.Button, onClick = zurHeutigen)
                        .padding(vertical = 2.dp)
                        .testTag("heute"),
                )
            }
        }
        OutlinedButton(
            onClick = weiter,
            enabled = !letzteWoche,
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = "Nächste Woche" },
            contentPadding = ButtonDefaults.TextButtonContentPadding,
        ) {
            Text("›", fontSize = 22.sp, color = if (!letzteWoche) Schrift else Rand)
        }
    }
}

private val TAGE = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")

/** Zeilenhöhe einer Textzeile in den Zellen; die Zeilen wachsen mit der Schriftgröße des Handys mit. */
private val ZELLE_ZEILENHOEHE = 13.sp
private val NAMENSPALTE = 68.dp
private val ABSTAND = 3.dp

/** Zeilen des Wochenblatts, links (Namen) und rechts (Tage) genau gleich hoch. */
private sealed interface BlattZeile {
    data class Person(val e: Employee) : BlattZeile
    /** Überschrift für Aushilfen aus anderen Filialen, wie auf dem Papier. */
    data object Aushilfen : BlattZeile
    data object Offen : BlattZeile
    data object Summe : BlattZeile
}

/**
 * Wochenblatt wie auf dem Papier: je Person Zeit, Pause/Kürzel und Stunden, unten "Verk. aus and. Fil.",
 * offene Schichten und "ges. Std.". Namen links und Wochentage oben bleiben stehen, nur die Tage wischen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Wochenblatt(
    wochen: List<LocalDate>,
    wocheVon: (LocalDate) -> ShiftPlan,
    /** Zeilen der gezeigten Woche (mit Ausgeschiedenen, die dort gearbeitet haben). */
    personen: List<Employee>,
    /** Alle bekannten Personen: Wer hier drin ist, besetzt eine Schicht. */
    alleIds: Set<String>,
    abwesend: List<Absence>,
    /** Hinweise der gezeigten Woche. */
    hinweise: List<Warning>,
    pager: PagerState,
    aenderbar: Boolean,
    feldAntippen: (String, LocalDate) -> Unit,
    offenAntippen: (LocalDate) -> Unit,
) {
    val leute = personen
    val teamIds = alleIds
    val plan = wocheVon(wochen[pager.currentPage.coerceIn(0, wochen.lastIndex)])
    val abwesendAm: Map<Pair<String, LocalDate>, AbsenceType> = remember(abwesend) {
        abwesend.associate { (it.employeeId to it.date) to it.type }
    }
    val hinweiseAn: Map<Pair<String, SlotKey>, List<Warning>> = remember(hinweise) {
        hinweise.groupBy { it.employeeId to it.key }
    }
    val stamm = leute.filter { !it.isBackup }
    val aushilfen = leute.filter { it.isBackup }
    val hatOffene = PlanSheet.openCount(plan, teamIds) > 0
    val zeilen: List<BlattZeile> = buildList {
        stamm.forEach { add(BlattZeile.Person(it)) }
        if (aushilfen.isNotEmpty()) {
            add(BlattZeile.Aushilfen)
            aushilfen.forEach { add(BlattZeile.Person(it)) }
        }
        if (hatOffene) add(BlattZeile.Offen)
        add(BlattZeile.Summe)
    }

    val zeile = with(LocalDensity.current) { ZELLE_ZEILENHOEHE.toDp() }
    fun hoehe(z: BlattZeile): Dp = when (z) {
        is BlattZeile.Person -> maxOf(64.dp, zeile * 4 + 10.dp)
        BlattZeile.Aushilfen -> maxOf(30.dp, zeile * 2 + 6.dp)
        BlattZeile.Offen, BlattZeile.Summe -> maxOf(40.dp, zeile * 2 + 10.dp)
    }
    Column(verticalArrangement = Arrangement.spacedBy(ABSTAND)) {
        Row(horizontalArrangement = Arrangement.spacedBy(ABSTAND)) {
            Spacer(Modifier.width(NAMENSPALTE))
            TAGE.forEach {
                Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Schrift)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ABSTAND)) {
            Column(Modifier.width(NAMENSPALTE), verticalArrangement = Arrangement.spacedBy(ABSTAND)) {
                zeilen.forEach { z ->
                    Box(Modifier.height(hoehe(z)).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        NamensZelle(z, plan, abwesend, teamIds, 0)
                    }
                }
            }
            HorizontalPager(
                state = pager,
                verticalAlignment = Alignment.Top,
                pageSpacing = 16.dp,
                modifier = Modifier
                    .weight(1f)
                    .testTag("wochen-pager"),
            ) { seite ->
                // Nachbarwochen beim Wischen: ohne Hinweise, die gibt es für die gezeigte Woche.
                val seitenPlan = if (seite == pager.currentPage) plan else wocheVon(wochen[seite])
                val seitenHinweise = if (seite == pager.currentPage) hinweiseAn else emptyMap()
                val tage = PlanSheet.weekDays(seitenPlan, 0)
                Column(verticalArrangement = Arrangement.spacedBy(ABSTAND)) {
                    zeilen.forEach { z ->
                        Row(Modifier.height(hoehe(z)), horizontalArrangement = Arrangement.spacedBy(ABSTAND)) {
                            tage.forEach { tag ->
                                val m = Modifier.weight(1f).fillMaxSize()
                                val imPlan = PlanSheet.inPlan(seitenPlan, tag)
                                when (z) {
                                    is BlattZeile.Person -> {
                                        val schicht = seitenPlan.slotsOf(z.e.id, tag).firstOrNull()
                                        SchichtZelle(
                                            person = z.e,
                                            tag = tag,
                                            schicht = schicht,
                                            abwesend = abwesendAm[z.e.id to tag],
                                            hinweise = schicht?.let { seitenHinweise[z.e.id to it.key] }.orEmpty(),
                                            imPlan = imPlan,
                                            aenderbar = aenderbar,
                                            modifier = m,
                                        ) { feldAntippen(z.e.id, tag) }
                                    }
                                    BlattZeile.Aushilfen -> Box(m.padding(vertical = 2.dp).background(RandHell, RoundedCornerShape(3.dp)))
                                    BlattZeile.Offen -> OffenZelle(
                                        anzahl = if (imPlan) PlanSheet.openOn(seitenPlan, tag, teamIds).size else 0,
                                        tag = tag,
                                        aenderbar = aenderbar,
                                        modifier = m,
                                    ) { offenAntippen(tag) }
                                    BlattZeile.Summe -> SummenZelle(
                                        if (imPlan) PlanSheet.hours(PlanSheet.dayTotalMinutes(seitenPlan, tag, teamIds)) else "",
                                        m,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NamensZelle(z: BlattZeile, plan: ShiftPlan, abwesend: List<Absence>, teamIds: Set<String>, woche: Int) {
    when (z) {
        is BlattZeile.Person -> Column {
            Text(z.e.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Schrift, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val ist = PlanSheet.hours(PlanSheet.paidMinutes(plan, z.e.id, woche))
            // Ist und anteiliges Soll der Woche; bei Aushilfen nur Ist.
            val text = if (z.e.isBackup) "$ist h" else "$ist/${PlanSheet.hours(PlanSheet.targetMinutes(plan, z.e, abwesend, woche))} h"
            Text(text, fontSize = 10.sp, color = TextMittel, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
        BlattZeile.Aushilfen -> Text("Verk. aus and. Fil.", fontSize = 10.sp, lineHeight = ZELLE_ZEILENHOEHE, fontWeight = FontWeight.Bold, color = TextMittel)
        BlattZeile.Offen -> Text("offen", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Fehler)
        BlattZeile.Summe -> Column {
            Text("ges. Std.", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Schrift, maxLines = 1)
            val summe = PlanSheet.weekDays(plan, woche).filter { PlanSheet.inPlan(plan, it) }
                .sumOf { PlanSheet.dayTotalMinutes(plan, it, teamIds) }
            Text(PlanSheet.hours(summe), fontSize = 10.sp, color = TextMittel, maxLines = 1, modifier = Modifier.testTag("wochensumme"))
        }
    }
}

/** Eine Zelle wie auf dem Papier: Beginn, Ende, Pause oder "Fr", Stunden. Ohne Schicht das Kürzel (U, K, Fr). */
@Composable
private fun SchichtZelle(
    person: Employee,
    tag: LocalDate,
    schicht: PlanSlot?,
    abwesend: AbsenceType?,
    hinweise: List<Warning>,
    imPlan: Boolean,
    aenderbar: Boolean,
    modifier: Modifier,
    antippen: () -> Unit,
) {
    val form = RoundedCornerShape(6.dp)
    if (!imPlan) {
        Box(modifier.background(Hintergrund, form).border(1.dp, RandHell, form))
        return
    }
    val regel = hinweise.any { it.severity == Severity.RULE }
    val wunsch = hinweise.any { it.severity == Severity.WISH }
    val freiWunsch = schicht == null && abwesend == AbsenceType.DAY_OFF_WISH
    val farbe = when {
        freiWunsch -> WunschErfuelltHell
        schicht == null -> Color.White
        regel -> FehlerHell
        schicht.kind == ShiftKind.EARLY -> Frueh
        else -> Spaet
    }
    val beschreibung = buildString {
        append("${person.name}, ${tagText(tag)}: ")
        append(
            when {
                schicht != null -> "${schicht.name} ${zeitText(schicht)}, ${PlanSheet.hours(schicht.paidMinutes)} Stunden"
                freiWunsch -> "frei (Frei-Wunsch)"
                abwesend != null -> Wishes.label(abwesend)
                else -> "frei"
            },
        )
        hinweise.forEach { append(". ${it.message}") }
    }
    Column(
        modifier
            .background(farbe, form)
            .border(
                if (regel || freiWunsch) 2.dp else 1.dp,
                when {
                    regel -> Fehler
                    freiWunsch -> WunschErfuellt
                    schicht == null -> Rand
                    else -> farbe
                },
                form,
            )
            .clickable(enabled = aenderbar, role = Role.Button, onClick = antippen)
            .semantics(mergeDescendants = true) { contentDescription = beschreibung }
            .padding(vertical = 2.dp)
            .testTag("zelle-${person.id}-$tag"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (schicht == null) {
            // Wie auf dem Papier: jeder freie Tag "Fr", ein Frei-Wunsch grün hinterlegt.
            val zelle = PlanPrint.free(abwesend, person.isBackup)
            Text(
                zelle.top,
                fontSize = 13.sp,
                lineHeight = ZELLE_ZEILENHOEHE,
                fontWeight = if (abwesend != null) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    freiWunsch -> WunschErfuellt
                    abwesend != null -> Schrift
                    else -> TextLeise
                },
            )
        } else {
            Text(PlanSheet.time(schicht.start), fontSize = 10.sp, lineHeight = ZELLE_ZEILENHOEHE, fontWeight = FontWeight.Bold, color = Schrift, maxLines = 1)
            Text(PlanSheet.time(schicht.end), fontSize = 10.sp, lineHeight = ZELLE_ZEILENHOEHE, color = Schrift, maxLines = 1)
            // Zweite Zeile wie auf dem Papier: Kürzel oder Pause. "Fr" = Frei-Wunsch nicht erfüllt.
            when {
                regel -> Text("⚠", fontSize = 10.sp, lineHeight = ZELLE_ZEILENHOEHE, fontWeight = FontWeight.Bold, color = Fehler, maxLines = 1)
                wunsch -> Text("Fr", fontSize = 10.sp, lineHeight = ZELLE_ZEILENHOEHE, fontWeight = FontWeight.Bold, color = FreiWunsch, maxLines = 1)
                else -> Text(PlanSheet.pause(schicht), fontSize = 9.sp, lineHeight = ZELLE_ZEILENHOEHE, color = TextMittel, maxLines = 1)
            }
            Text(PlanSheet.hours(schicht.paidMinutes), fontSize = 10.sp, lineHeight = ZELLE_ZEILENHOEHE, fontWeight = FontWeight.Bold, color = Schrift, maxLines = 1)
        }
    }
}

@Composable
private fun OffenZelle(anzahl: Int, tag: LocalDate, aenderbar: Boolean, modifier: Modifier, antippen: () -> Unit) {
    val form = RoundedCornerShape(6.dp)
    if (anzahl == 0) {
        Box(modifier)
        return
    }
    Column(
        modifier
            .background(FehlerHell, form)
            .border(2.dp, Fehler, form)
            .clickable(enabled = aenderbar, role = Role.Button, onClick = antippen)
            .semantics(mergeDescendants = true) { contentDescription = "${tagText(tag)}: $anzahl offen" }
            .testTag("offen-$tag"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("$anzahl", fontSize = 13.sp, lineHeight = ZELLE_ZEILENHOEHE, fontWeight = FontWeight.Bold, color = Fehler)
        Text("offen", fontSize = 9.sp, lineHeight = ZELLE_ZEILENHOEHE, color = Fehler)
    }
}

@Composable
private fun SummenZelle(text: String, modifier: Modifier) {
    Box(modifier.border(1.dp, Rand, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Schrift, maxLines = 1)
    }
}

@Composable
private fun Eintrag(farbe: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(12.dp).background(farbe, RoundedCornerShape(3.dp)))
        Text(text, fontSize = 12.sp, color = TextMittel)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legende() {
    Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Eintrag(Frueh, "Früh")
            Eintrag(Spaet, "Spät")
            Eintrag(FehlerHell, "⚠ Regel verletzt")
            Eintrag(WunschErfuelltHell, "Fr = Frei-Wunsch erfüllt")
            Text("Fr in einer Schicht = Frei-Wunsch nicht erfüllt", fontSize = 12.sp, color = FreiWunsch)
            Text("P = Pause", fontSize = 12.sp, color = TextMittel)
            Text("U Urlaub · K krank", fontSize = 12.sp, color = TextMittel)
        }
        Text("Feld antippen, um die Schicht zu ändern oder zu tauschen.", fontSize = 12.sp, color = TextLeise)
    }
}

/** Alle Hinweise der Woche als Liste, antippen öffnet das Feld. */
@Composable
private fun WochenHinweise(woche: ShiftPlan, hinweise: List<Warning>, oeffnen: (Warning) -> Unit) {
    val tage = PlanSheet.weekDays(woche, 0).toSet()
    val dieseWoche = hinweise.filter { it.key.date in tage }.sortedWith(compareBy<Warning>({ it.key.date }, { it.severity }))
    if (dieseWoche.isEmpty()) return
    Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Hinweise in dieser Woche", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextMittel)
        dieseWoche.forEach { w ->
            Text(
                "${tagText(w.key.date)}: ${w.message}",
                fontSize = 14.sp,
                color = if (w.severity == Severity.RULE) Fehler else FreiWunsch,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { oeffnen(w) }
                    .padding(vertical = 4.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- Ändern von Hand

@Composable
private fun DialogRahmen(titel: String, schliessen: () -> Unit, inhalt: @Composable () -> Unit) {
    Dialog(onDismissRequest = schliessen, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.94f)
                .background(Hintergrund, RoundedCornerShape(16.dp))
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .testTag("aendern-dialog"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(titel, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Schrift)
            inhalt()
            Button(
                onClick = schliessen,
                colors = ButtonDefaults.buttonColors(containerColor = Akzent),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag("fertig"),
            ) { Text("Fertig", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun HinweisTexte(hinweise: List<Warning>) {
    hinweise.forEach {
        if (it.severity == Severity.RULE) FehlerText(it.message)
        else Text(it.message, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = FreiWunsch)
    }
}

/** Auswahlknopf im Dialog: Titel, Unterzeile und Hinweise, was die Wahl verletzen würde. */
@Composable
private fun Wahl(
    titel: String,
    unterzeile: String,
    gewaehlt: Boolean,
    hinweise: List<Warning>,
    tag: String,
    onClick: () -> Unit,
) {
    val form = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .background(if (gewaehlt) Frueh else Color.White, form)
            .border(if (gewaehlt) 2.dp else 1.dp, if (gewaehlt) Akzent else Rand, form)
            .clickable(enabled = !gewaehlt, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(titel, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Schrift)
        Text(unterzeile, fontSize = 13.sp, color = TextMittel)
        hinweise.forEach {
            Text(
                (if (it.severity == Severity.RULE) "⚠ " else "") + it.message,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (it.severity == Severity.RULE) Fehler else FreiWunsch,
            )
        }
    }
}

/** Feld einer Person an einem Tag: andere Schicht wählen (mit Tausch), frei geben oder Uhrzeit ändern. */
@Composable
private fun PersonTagDialog(
    plan: ShiftPlan,
    person: Employee,
    tag: LocalDate,
    leute: List<Employee>,
    abwesend: List<Absence>,
    hinweise: List<Warning>,
    uebernehmen: (SlotKey) -> Unit,
    freiGeben: () -> Unit,
    zeiten: (SlotKey, LocalTime, LocalTime) -> String?,
    schliessen: () -> Unit,
) {
    val namen = leute.associate { it.id to it.name }
    val eigene = plan.slotsOf(person.id, tag)
    val eintrag = abwesend.firstOrNull { it.employeeId == person.id && it.date == tag }?.type
    var zeitFehler by remember { mutableStateOf<String?>(null) }

    DialogRahmen("${person.name} · ${tagText(tag)}", schliessen) {
        if (eintrag != null) {
            Text("Im Kalender: ${Wishes.label(eintrag)}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextMittel)
        }
        if (eigene.isEmpty()) {
            Text("Jetzt: frei", fontSize = 16.sp, color = Schrift)
        } else {
            eigene.forEach { s ->
                Text(
                    "Jetzt: ${s.name} ${zeitText(s)} (${PlanSheet.hours(s.paidMinutes)} h)",
                    fontSize = 16.sp,
                    color = Schrift,
                    modifier = Modifier.testTag("jetzt"),
                )
                HinweisTexte(hinweise.filter { it.employeeId == person.id && it.key == s.key })
            }
        }

        val schichten = plan.slotsOn(tag)
        if (schichten.isEmpty()) {
            Text("An diesem Tag gibt es keine Schichten.", fontSize = 15.sp, color = TextMittel)
        } else {
            Text("Schicht wählen", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextMittel)
            schichten.forEach { s ->
                val meine = s.employeeId == person.id
                val inhaber = s.employeeId?.let { namen[it] }
                val unterzeile = when {
                    meine -> "eingeteilt"
                    inhaber != null && eigene.isNotEmpty() -> "tauscht mit $inhaber"
                    inhaber != null -> "$inhaber hat dann frei"
                    else -> "offen"
                }
                // Beim Tausch auch zeigen, was die andere Person auf der alten Schicht verletzen würde.
                val tauschHinweise = if (meine || s.employeeId == null || eigene.isEmpty()) emptyList()
                else PlanCheck.warnings(PlanEdit.assign(plan, s.key, person.id), leute, abwesend)
                    .filter { it.employeeId == s.employeeId && it.key == eigene.first().key }
                Wahl(
                    titel = "${s.name} ${zeitText(s)}",
                    unterzeile = unterzeile,
                    gewaehlt = meine,
                    hinweise = if (meine) emptyList() else PlanCheck.preview(plan, s.key, person.id, leute, abwesend) + tauschHinweise,
                    tag = "wahl-${s.key.templateId}-${s.key.slot}",
                ) { zeitFehler = null; uebernehmen(s.key) }
            }
        }

        if (eigene.isNotEmpty()) {
            OutlinedButton(
                onClick = { zeitFehler = null; freiGeben() },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag("frei-geben"),
            ) { Text("Frei geben (Schicht wird offen)", fontSize = 16.sp, color = Akzent, textAlign = TextAlign.Center) }

            val s = eigene.first()
            Text("Uhrzeit nur an diesem Tag ändern", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextMittel)
            val beginn = s.start.toLocalTime()
            val ende = s.end.toLocalTime()
            Text("Beginn", fontSize = 13.sp, color = TextLeise)
            Stepper(
                wert = PlanSheet.time(s.start),
                weniger = "Beginn früher",
                mehr = "Beginn später",
                tag = "beginn",
                onWeniger = { zeitFehler = zeiten(s.key, Shop.stepTime(beginn, up = false), ende) },
                onMehr = { zeitFehler = zeiten(s.key, Shop.stepTime(beginn, up = true), ende) },
            )
            Text("Ende", fontSize = 13.sp, color = TextLeise)
            Stepper(
                wert = PlanSheet.time(s.end),
                weniger = "Ende früher",
                mehr = "Ende später",
                tag = "ende",
                onWeniger = { zeitFehler = zeiten(s.key, beginn, Shop.stepTime(ende, up = false)) },
                onMehr = { zeitFehler = zeiten(s.key, beginn, Shop.stepTime(ende, up = true)) },
            )
            zeitFehler?.let { FehlerText(it) }
        }
    }
}

/** Offene Schichten eines Tages: Person antippen, die sie übernimmt. */
@Composable
private fun OffenDialog(
    plan: ShiftPlan,
    tag: LocalDate,
    leute: List<Employee>,
    teamIds: Set<String>,
    abwesend: List<Absence>,
    uebernehmen: (SlotKey, String) -> Unit,
    schliessen: () -> Unit,
) {
    val offen = PlanSheet.openOn(plan, tag, teamIds)
    DialogRahmen("Offen · ${tagText(tag)}", schliessen) {
        if (offen.isEmpty()) {
            Text("Alle Schichten an diesem Tag sind besetzt.", fontSize = 16.sp, color = Schrift)
        }
        offen.forEach { s ->
            Text("${s.name} ${zeitText(s)} – wer übernimmt?", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Schrift)
            leute.forEach { e ->
                val schonDa = plan.slotsOf(e.id, tag).firstOrNull()
                Wahl(
                    titel = e.name,
                    unterzeile = schonDa?.let { "wechselt von ${it.name} ${zeitText(it)}" } ?: "hat an dem Tag frei",
                    gewaehlt = false,
                    hinweise = PlanCheck.preview(plan, s.key, e.id, leute, abwesend),
                    tag = "person-${e.id}",
                ) { uebernehmen(s.key, e.id) }
            }
        }
    }
}
