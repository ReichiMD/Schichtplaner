@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package de.reichimd.schichtplaner.wuensche

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.reichimd.schichtplaner.core.AbsenceType
import de.reichimd.schichtplaner.core.Employee
import de.reichimd.schichtplaner.core.Weekdays
import de.reichimd.schichtplaner.core.wishes.Wishes
import de.reichimd.schichtplaner.core.team.Team
import de.reichimd.schichtplaner.team.TeamStore
import de.reichimd.schichtplaner.team.team
import de.reichimd.schichtplaner.ui.Akzent
import de.reichimd.schichtplaner.ui.Chip
import de.reichimd.schichtplaner.ui.FreiWunsch
import de.reichimd.schichtplaner.ui.FreiWunschHell
import de.reichimd.schichtplaner.ui.Hinweis
import de.reichimd.schichtplaner.ui.Krank
import de.reichimd.schichtplaner.ui.KrankHell
import de.reichimd.schichtplaner.ui.Rand
import de.reichimd.schichtplaner.ui.RandHell
import de.reichimd.schichtplaner.ui.Schrift
import de.reichimd.schichtplaner.ui.TextLeise
import de.reichimd.schichtplaner.ui.TextMittel
import de.reichimd.schichtplaner.ui.Urlaub
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Eigene Liste statt Locale: unabhängig von der Spracheinstellung des Handys. */
private val MONATE = listOf(
    "Januar", "Februar", "März", "April", "Mai", "Juni",
    "Juli", "August", "September", "Oktober", "November", "Dezember",
)

private fun monatsName(m: YearMonth) = MONATE[m.monthValue - 1]

/** Farben einer Kalenderzelle: Hintergrund, Rand, Schrift. */
private data class Farben(val hinter: Color, val rand: Color, val schrift: Color)

private fun farben(art: AbsenceType?): Farben = when (art) {
    AbsenceType.VACATION -> Farben(Urlaub, Urlaub, Color.White)
    AbsenceType.DAY_OFF_WISH -> Farben(FreiWunschHell, FreiWunsch, Schrift)
    AbsenceType.SICK -> Farben(KrankHell, Krank, Schrift)
    null -> Farben(Color.White, RandHell, Schrift)
}

/** Farbe des gewählten Art-Knopfs. */
private fun knopfFarbe(art: AbsenceType): Color = when (art) {
    AbsenceType.VACATION -> Urlaub
    AbsenceType.DAY_OFF_WISH -> FreiWunsch
    AbsenceType.SICK -> Krank
}

/**
 * Wünsche-Kalender: Person wählen, Art wählen (Urlaub, Frei-Wunsch, Krank), Tage antippen.
 * Jedes Antippen wird sofort gespeichert. Nochmal antippen nimmt den Eintrag wieder weg.
 */
@Composable
internal fun WuenscheBereich(
    teamStore: TeamStore = TeamStore.get(LocalContext.current),
    store: WuenscheStore = WuenscheStore.get(LocalContext.current),
    heute: LocalDate = LocalDate.now(),
) {
    val team by teamStore.zustand.collectAsState()
    val zustand by store.zustand.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Wünsche", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Schrift)

        zustand.meldung?.let { m ->
            Hinweis(m) { TextButton(onClick = store::meldungGelesen) { Text("OK", color = Akzent) } }
        }

        when {
            team.laedt || zustand.laedt -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Akzent)
            }
            Team.active(team.team).isEmpty() -> Hinweis(
                "Noch niemand im Team. Lege die Personen zuerst unter „Team“ an, dann kannst du hier " +
                    "Urlaub und Frei-Wünsche eintragen.",
            )
            // Ausgeschiedene behalten ihre Einträge, stehen aber nicht mehr zur Auswahl.
            else -> Kalender(Team.active(team.team), zustand, store, heute)
        }
    }
}

@Composable
private fun Kalender(team: List<Employee>, zustand: WuenscheZustand, store: WuenscheStore, heute: LocalDate) {
    var gewaehlteId by rememberSaveable { mutableStateOf<String?>(null) }
    // Gelöschte oder noch nicht gewählte Person: die erste im Team.
    val person = team.firstOrNull { it.id == gewaehlteId } ?: team.first()
    var artName by rememberSaveable { mutableStateOf(AbsenceType.VACATION.name) }
    val art = AbsenceType.valueOf(artName)

    // Blättern über einen festen Bereich: ein Monat zurück bis zwölf Monate vor.
    val ersterMonat = YearMonth.from(heute).minusMonths(Wishes.MONTHS_BACK.toLong())
    val anzahl = Wishes.MONTHS_BACK + Wishes.MONTHS_AHEAD + 1
    val planStart = heute.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
    val startSeite = ersterMonat.until(Wishes.startMonth(planStart), ChronoUnit.MONTHS).toInt().coerceIn(0, anzahl - 1)
    val pager = rememberPagerState(initialPage = startSeite) { anzahl }
    val scope = rememberCoroutineScope()
    val monat = ersterMonat.plusMonths(pager.currentPage.toLong())
    val eintraege = zustand.eintraege

    // Personen als Knöpfe nebeneinander, mit Zahl der Einträge im Monat.
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        team.forEach { e ->
            val n = Wishes.inMonth(eintraege, e.id, monat).size
            PersonKnopf(e.name, n, gewaehlt = e.id == person.id) { gewaehlteId = e.id }
        }
    }

    Text("Erst die Art wählen, dann die Tage antippen. Nochmal antippen löscht.", fontSize = 14.sp, color = TextMittel)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Wishes.TYPES.forEach { t ->
            ArtKnopf(Wishes.label(t), gewaehlt = t == art, farbe = knopfFarbe(t), modifier = Modifier.weight(1f)) {
                artName = t.name
            }
        }
    }

    MonatsKopf(
        monat = monat,
        zurueck = if (pager.currentPage > 0) ({ scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }) else null,
        weiter = if (pager.currentPage < anzahl - 1) ({ scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) else null,
    )

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        DayOfWeek.entries.forEach {
            Text(
                Weekdays.short(it),
                Modifier.weight(1f),
                textAlign = TextAlign.Center,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = TextLeise,
            )
        }
    }

    // Höhe wächst mit der Schriftgröße des Handys, damit Tag und Kürzel nie abgeschnitten werden.
    val zellenHoehe = with(LocalDensity.current) { maxOf(48.dp, 17.sp.toDp() + 14.sp.toDp() + 12.dp) }

    HorizontalPager(
        state = pager,
        verticalAlignment = Alignment.Top,
        pageSpacing = 16.dp,
        modifier = Modifier.testTag("monats-pager"),
    ) { seite ->
        val m = ersterMonat.plusMonths(seite.toLong())
        val imMonat = Wishes.inMonth(eintraege, person.id, m)
        MonatsRaster(
            monat = m,
            eintraege = imMonat,
            gesperrt = person.blockedWeekdays,
            heute = heute,
            hoehe = zellenHoehe,
            bearbeitbar = zustand.bearbeitbar,
        ) { tag -> store.antippen(person.id, tag, art) }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(12.dp))
            .border(1.dp, RandHell, RoundedCornerShape(12.dp))
            .padding(12.dp)
            .testTag("zusammenfassung"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("${person.name} im ${monatsName(monat)}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Schrift)
        Text(Wishes.describeCounts(Wishes.countInMonth(eintraege, person.id, monat)), fontSize = 15.sp, color = Schrift)
        if (person.blockedWeekdays.isNotEmpty()) {
            Text(
                "Grau: kann ${Weekdays.describe(person.blockedWeekdays)} sowieso nie (unter „Team“ eingestellt).",
                fontSize = 13.sp,
                color = TextLeise,
            )
        }
        Text(
            "Urlaub und Krank werden nie verplant. Frei-Wünsche werden erfüllt, so gut es geht.",
            fontSize = 13.sp,
            color = TextLeise,
        )
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun PersonKnopf(name: String, anzahl: Int, gewaehlt: Boolean, onClick: () -> Unit) {
    val form = RoundedCornerShape(22.dp)
    Row(
        Modifier
            .heightIn(min = 44.dp)
            .background(if (gewaehlt) Schrift else Color.White, form)
            .border(1.dp, if (gewaehlt) Schrift else Rand, form)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { selected = gewaehlt }
            .padding(horizontal = 16.dp)
            .testTag("person-$name"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            name,
            fontSize = 15.sp,
            fontWeight = if (gewaehlt) FontWeight.Bold else FontWeight.Normal,
            color = if (gewaehlt) Color.White else Schrift,
        )
        if (anzahl > 0) {
            Text(
                "$anzahl",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (gewaehlt) Schrift else Color.White,
                modifier = Modifier
                    .background(if (gewaehlt) Color.White else TextMittel, RoundedCornerShape(10.dp))
                    .padding(horizontal = 6.dp),
            )
        }
    }
}

@Composable
private fun ArtKnopf(text: String, gewaehlt: Boolean, farbe: Color, modifier: Modifier, onClick: () -> Unit) {
    val form = RoundedCornerShape(12.dp)
    Box(
        modifier
            .heightIn(min = 48.dp)
            .background(if (gewaehlt) farbe else Color.White, form)
            .border(if (gewaehlt) 2.dp else 1.dp, if (gewaehlt) farbe else Rand, form)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { selected = gewaehlt }
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .testTag("art-$text"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = if (gewaehlt) Color.White else Schrift,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MonatsKopf(monat: YearMonth, zurueck: (() -> Unit)?, weiter: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = { zurueck?.invoke() },
            enabled = zurueck != null,
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .semantics { contentDescription = "Voriger Monat" },
            contentPadding = ButtonDefaults.TextButtonContentPadding,
        ) { Text("‹", fontSize = 22.sp, color = if (zurueck != null) Schrift else Rand) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${monatsName(monat)} ${monat.year}",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Schrift,
                modifier = Modifier.testTag("monats-titel"),
            )
            Text("wischen zum Blättern", fontSize = 11.sp, color = TextLeise)
        }
        OutlinedButton(
            onClick = { weiter?.invoke() },
            enabled = weiter != null,
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .semantics { contentDescription = "Nächster Monat" },
            contentPadding = ButtonDefaults.TextButtonContentPadding,
        ) { Text("›", fontSize = 22.sp, color = if (weiter != null) Schrift else Rand) }
    }
}

@Composable
private fun MonatsRaster(
    monat: YearMonth,
    eintraege: Map<LocalDate, AbsenceType>,
    gesperrt: Set<DayOfWeek>,
    heute: LocalDate,
    hoehe: Dp,
    bearbeitbar: Boolean,
    antippen: (LocalDate) -> Unit,
) {
    val felder = Wishes.monthGrid(monat)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        felder.chunked(7).forEach { woche ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                woche.forEach { tag ->
                    if (tag == null) {
                        Spacer(Modifier.weight(1f).height(hoehe))
                    } else {
                        TagZelle(
                            tag = tag,
                            art = eintraege[tag],
                            gesperrt = tag.dayOfWeek in gesperrt,
                            istHeute = tag == heute,
                            bearbeitbar = bearbeitbar,
                            modifier = Modifier.weight(1f).height(hoehe),
                        ) { antippen(tag) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TagZelle(
    tag: LocalDate,
    art: AbsenceType?,
    gesperrt: Boolean,
    istHeute: Boolean,
    bearbeitbar: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val form = RoundedCornerShape(10.dp)
    val f = farben(art)
    val hinter = if (art == null && gesperrt) Chip else f.hinter
    val beschreibung = buildString {
        append("${tag.dayOfMonth}. ${monatsName(YearMonth.from(tag))}")
        if (art != null) append(", ${Wishes.label(art)}")
        else if (gesperrt) append(", kann nie")
    }
    Column(
        modifier
            .background(hinter, form)
            .border(if (art != null || istHeute) 2.dp else 1.dp, if (istHeute && art == null) Schrift else f.rand, form)
            .clickable(enabled = bearbeitbar, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = beschreibung }
            .testTag("tag-$tag"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "${tag.dayOfMonth}",
            fontSize = 16.sp,
            lineHeight = 17.sp,
            fontWeight = if (istHeute) FontWeight.Bold else FontWeight.Normal,
            color = if (art == null && gesperrt) TextLeise else f.schrift,
        )
        val kuerzel = when {
            art != null -> Wishes.code(art)
            gesperrt -> "nie"
            else -> ""
        }
        Text(
            kuerzel,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (art == null) TextLeise else f.schrift,
            maxLines = 1,
        )
    }
}
