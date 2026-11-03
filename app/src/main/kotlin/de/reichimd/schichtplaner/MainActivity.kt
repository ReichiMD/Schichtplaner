package de.reichimd.schichtplaner

import android.app.Application
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.reichimd.schichtplaner.core.Plan
import de.reichimd.schichtplaner.core.Planner
import de.reichimd.schichtplaner.core.PlanningRequest
import de.reichimd.schichtplaner.core.Shift
import de.reichimd.schichtplaner.core.ShiftKind
import de.reichimd.schichtplaner.core.shop.Shop
import de.reichimd.schichtplaner.laden.LadenBereich
import de.reichimd.schichtplaner.laden.LadenStore
import de.reichimd.schichtplaner.laden.eingerichtet
import de.reichimd.schichtplaner.laden.laden
import de.reichimd.schichtplaner.team.TeamBereich
import de.reichimd.schichtplaner.team.team
import de.reichimd.schichtplaner.ui.Akzent
import de.reichimd.schichtplaner.ui.Frueh
import de.reichimd.schichtplaner.ui.Hintergrund
import de.reichimd.schichtplaner.ui.Hinweis
import de.reichimd.schichtplaner.ui.Rand
import de.reichimd.schichtplaner.ui.Schrift
import de.reichimd.schichtplaner.ui.Spaet
import de.reichimd.schichtplaner.ui.TextLeise
import de.reichimd.schichtplaner.team.TeamStore
import de.reichimd.schichtplaner.update.UpdateBanner
import de.reichimd.schichtplaner.update.VersionZeile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Akzent, background = Hintergrund)) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Hintergrund)
                        .safeDrawingPadding(),
                ) {
                    Hauptbereich()
                }
            }
        }
    }
}

private enum class Bereich(val titel: String) { PLAN("Plan"), TEAM("Team"), LADEN("Laden") }

/** Untere Leiste mit Plan, Team und Laden. Beim Bearbeiten (Person, Schicht, Zeiten) wird sie ausgeblendet. */
@Composable
private fun Hauptbereich() {
    var bereich by rememberSaveable { mutableStateOf(Bereich.PLAN) }
    var bearbeitet by remember { mutableStateOf(false) }

    // Zurück im Team- oder Laden-Bereich führt erst zum Plan, statt die App zu schließen.
    BackHandler(enabled = bereich != Bereich.PLAN && !bearbeitet) { bereich = Bereich.PLAN }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (bereich) {
                Bereich.PLAN -> PlanScreen()
                Bereich.TEAM -> TeamBereich(bearbeitetGerade = { bearbeitet = it })
                Bereich.LADEN -> LadenBereich(bearbeitetGerade = { bearbeitet = it })
            }
        }
        if (!(bereich != Bereich.PLAN && bearbeitet)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .border(1.dp, Rand),
            ) {
                Bereich.entries.forEach { b ->
                    val aktiv = b == bereich
                    TextButton(
                        onClick = { bereich = b; bearbeitet = false },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .testTag("tab-${b.titel}"),
                    ) {
                        Text(
                            b.titel,
                            fontSize = 15.sp,
                            fontWeight = if (aktiv) FontWeight.Bold else FontWeight.Normal,
                            color = if (aktiv) Akzent else TextLeise,
                        )
                    }
                }
            }
        }
    }
}

internal sealed interface Zustand {
    data object Leer : Zustand
    data object Rechnet : Zustand
    data class Fertig(val request: PlanningRequest, val plan: Plan, val millis: Long) : Zustand
    data class Fehler(val meldung: String) : Zustand
}

/**
 * Hält den Zustand über Drehen des Handys hinweg. Die Berechnung läuft im ViewModel weiter
 * und wird nicht bei jedem Drehen neu gestartet.
 */
internal class PlanViewModel(app: Application) : AndroidViewModel(app) {
    private val teamStore = TeamStore.get(app)
    private val ladenStore = LadenStore.get(app)

    init {
        // Ändert sich Team oder Laden, passt ein fertiger Plan nicht mehr: verwerfen statt Altes zu zeigen.
        viewModelScope.launch {
            combine(
                teamStore.zustand.map { it.team },
                ladenStore.zustand.map { it.laden },
            ) { team, laden -> team to laden }.distinctUntilChanged().drop(1).collect {
                if (zustand is Zustand.Fertig) zustand = Zustand.Leer
            }
        }
    }

    var zustand by mutableStateOf<Zustand>(Zustand.Leer)
        private set
    var woche by mutableIntStateOf(0)

    fun erstellen() {
        if (zustand is Zustand.Rechnet) return
        zustand = Zustand.Rechnet
        woche = 0
        viewModelScope.launch {
            // Mit dem gespeicherten Team, solange noch keins angelegt ist mit dem erfundenen Beispiel.
            val team = teamStore.zustand.first { !it.laedt }.team
            val laden = ladenStore.zustand.first { !it.laedt }.laden
            zustand = try {
                // Der Planer rechnet einige Sekunden (höchstens 15 s): nie im UI-Thread.
                withContext(Dispatchers.Default) {
                    val request = DemoData.request(employees = team.ifEmpty { DemoData.team }, laden = laden)
                    val start = System.currentTimeMillis()
                    val plan = Planner.plan(request)
                    Zustand.Fertig(request, plan, System.currentTimeMillis() - start)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Zustand.Fehler("${t::class.java.simpleName}: ${t.message}")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlanScreen(vm: PlanViewModel = viewModel()) {
    val zustand = vm.zustand
    val context = LocalContext.current
    val team by TeamStore.get(context).zustand.collectAsState()
    val laden by LadenStore.get(context).zustand.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Schichtplan (Test-Version)",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Schrift,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Text(
            buildString {
                append(
                    if (team.team.isEmpty()) "Noch kein Team angelegt: Der Plan nutzt ein erfundenes Beispiel-Team. "
                    else "Plan mit deinem Team (${team.team.size} Personen). ",
                )
                append(
                    if (laden.eingerichtet) "Laden und Schichten wie unter „Laden“ eingestellt."
                    else "Laden und Schichten sind noch Beispielwerte (unter „Laden“ prüfen).",
                )
            },
            fontSize = 14.sp,
            color = TextLeise,
            modifier = Modifier.padding(horizontal = 8.dp),
        )

        UpdateBanner()

        Shop.warnings(laden.laden).forEach { Hinweis(it, Modifier.padding(horizontal = 8.dp)) }

        Button(
            onClick = vm::erstellen,
            enabled = zustand !is Zustand.Rechnet,
            colors = ButtonDefaults.buttonColors(containerColor = Akzent),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .height(52.dp),
        ) {
            Text("Plan erstellen (${laden.laden.planWeeks} Wochen)", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }

        when (val z = zustand) {
            Zustand.Leer -> Unit
            Zustand.Rechnet -> Row(
                Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(Modifier.size(24.dp), color = Akzent)
                Text("Plan wird berechnet …", color = Schrift)
            }
            is Zustand.Fehler -> Hinweis("Fehler im Planer: ${z.meldung}", Modifier.padding(horizontal = 8.dp))
            is Zustand.Fertig -> {
                val wochen = (z.request.days + 6) / 7
                // Jeder neue Plan beginnt mit eigenem Blätter-Zustand; die Woche übersteht Drehen über das ViewModel.
                key(z) {
                    val pager = rememberPagerState(initialPage = vm.woche.coerceIn(0, wochen - 1)) { wochen }
                    val scope = rememberCoroutineScope()
                    LaunchedEffect(pager) {
                        snapshotFlow { pager.currentPage }.collect { vm.woche = it }
                    }
                    WochenKopf(
                        ersterTag = z.request.firstDay.plusWeeks(pager.currentPage.toLong()),
                        woche = pager.currentPage,
                        wochen = wochen,
                        zurueck = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                        weiter = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                    )
                    // Nach links und rechts wischen wechselt die Woche.
                    HorizontalPager(
                        state = pager,
                        verticalAlignment = Alignment.Top,
                        pageSpacing = 16.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("wochen-pager"),
                    ) { seite ->
                        WochenRaster(z.request, z.plan, z.request.firstDay.plusWeeks(seite.toLong()))
                    }
                }
                Zusammenfassung(z)
            }
        }

        Spacer(Modifier.height(8.dp))
        VersionZeile()
    }
}

@Composable
private fun WochenKopf(ersterTag: LocalDate, woche: Int, wochen: Int, zurueck: () -> Unit, weiter: () -> Unit) {
    val f = DateTimeFormatter.ofPattern("dd.MM.")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = zurueck,
            enabled = woche > 0,
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = "Vorige Woche" },
            contentPadding = ButtonDefaults.TextButtonContentPadding,
        ) {
            Text("‹", fontSize = 22.sp, color = if (woche > 0) Schrift else Rand)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "KW ${ersterTag.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)} · Woche ${woche + 1} von $wochen",
                fontSize = 13.sp,
                color = TextLeise,
            )
            Text(
                "${ersterTag.format(f)} – ${ersterTag.plusDays(6).format(f)}",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Schrift,
                modifier = Modifier.testTag("wochen-titel"),
            )
            Text("wischen zum Blättern", fontSize = 11.sp, color = TextLeise)
        }
        OutlinedButton(
            onClick = weiter,
            enabled = woche < wochen - 1,
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = "Nächste Woche" },
            contentPadding = ButtonDefaults.TextButtonContentPadding,
        ) {
            Text("›", fontSize = 22.sp, color = if (woche < wochen - 1) Schrift else Rand)
        }
    }
}

private val TAGE = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")

@Composable
private fun WochenRaster(request: PlanningRequest, plan: Plan, ersterTag: LocalDate) {
    val zuteilung: Map<Pair<String, LocalDate>, Shift> =
        plan.assignments.associate { (it.employeeId to it.shift.date) to it.shift }

    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Spacer(Modifier.width(58.dp))
            TAGE.forEach {
                Text(
                    it,
                    Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = Schrift,
                )
            }
        }
        request.employees.forEach { e ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    e.name,
                    Modifier.width(58.dp),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Schrift,
                    maxLines = 1,
                )
                (0L until 7L).forEach { d ->
                    Zelle(zuteilung[e.id to ersterTag.plusDays(d)], Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Zelle(shift: Shift?, modifier: Modifier) {
    val f = DateTimeFormatter.ofPattern("H:mm")
    val farbe = when (shift?.kind) {
        ShiftKind.EARLY -> Frueh
        ShiftKind.LATE -> Spaet
        null -> Color.White
    }
    Column(
        modifier
            .heightIn(min = 44.dp)
            .background(farbe, RoundedCornerShape(6.dp))
            .border(1.dp, if (shift == null) Rand else farbe, RoundedCornerShape(6.dp))
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (shift == null) {
            Text("frei", fontSize = 10.sp, color = TextLeise)
        } else {
            Text(shift.start.format(f), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Schrift)
            Text(shift.end.format(f), fontSize = 10.sp, color = Schrift)
        }
    }
}

@Composable
private fun Zusammenfassung(z: Zustand.Fertig) {
    if (!z.plan.solved) {
        Hinweis("In der Rechenzeit wurde kein Plan gefunden. Bitte erneut versuchen.", Modifier.padding(horizontal = 8.dp))
        return
    }
    val text = buildString {
        append("Berechnet in ${z.millis / 1000.0} s. ")
        if (z.plan.unfilled.isEmpty()) append("Alle Schichten sind besetzt.")
        else append("${z.plan.unfilled.size} Schichten sind unbesetzt.")
        append("\n\nStunden in ${(z.request.days + 6) / 7} Wochen:\n")
        z.request.employees.forEach { e ->
            append("${e.name}: ${"%.1f".format(z.plan.paidMinutesOf(e.id) / 60.0)} h\n")
        }
    }
    Hinweis(text.trimEnd(), Modifier.padding(horizontal = 8.dp))
}
