package de.reichimd.schichtplaner

import android.app.Application
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import de.reichimd.schichtplaner.team.TeamBereich
import de.reichimd.schichtplaner.team.TeamStore
import de.reichimd.schichtplaner.update.UpdateBanner
import de.reichimd.schichtplaner.update.VersionZeile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields

private val Hintergrund = Color(0xFFFBF8F3)
private val Schrift = Color(0xFF2B2620)
private val TextLeise = Color(0xFF6B6258)
private val Akzent = Color(0xFF8A3A12)
private val Frueh = Color(0xFFF6D9B8)
private val Spaet = Color(0xFFCFE0F3)
private val Rand = Color(0xFFDDD3C4)
private val Warnung = Color(0xFFFFF4E5)

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

private enum class Bereich(val titel: String) { PLAN("Plan"), TEAM("Team") }

/** Untere Leiste mit Plan und Team. Beim Bearbeiten einer Person wird sie ausgeblendet. */
@Composable
private fun Hauptbereich() {
    var bereich by rememberSaveable { mutableStateOf(Bereich.PLAN) }
    var bearbeitet by remember { mutableStateOf(false) }

    // Zurück im Team-Bereich führt erst zum Plan, statt die App zu schließen.
    BackHandler(enabled = bereich != Bereich.PLAN && !bearbeitet) { bereich = Bereich.PLAN }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (bereich) {
                Bereich.PLAN -> PlanScreen()
                Bereich.TEAM -> TeamBereich(bearbeitetGerade = { bearbeitet = it })
            }
        }
        if (!(bereich == Bereich.TEAM && bearbeitet)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .border(1.dp, Rand),
            ) {
                Bereich.entries.forEach { b ->
                    val aktiv = b == bereich
                    TextButton(
                        onClick = { bereich = b },
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
            zustand = try {
                // Der Planer rechnet einige Sekunden (höchstens 15 s): nie im UI-Thread.
                withContext(Dispatchers.Default) {
                    val request = if (team.isEmpty()) DemoData.request() else DemoData.request(employees = team)
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

@Composable
internal fun PlanScreen(vm: PlanViewModel = viewModel()) {
    val zustand = vm.zustand
    val woche = vm.woche

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
        val team by TeamStore.get(LocalContext.current).zustand.collectAsState()
        Text(
            if (team.team.isEmpty()) {
                "Noch kein Team angelegt: Der Plan nutzt ein erfundenes Beispiel-Team. Laden und Schichten sind noch Beispielwerte."
            } else {
                "Plan mit deinem Team (${team.team.size} Personen). Laden und Schichten sind noch Beispielwerte."
            },
            fontSize = 14.sp,
            color = TextLeise,
            modifier = Modifier.padding(horizontal = 8.dp),
        )

        UpdateBanner()

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
            Text("Plan erstellen", fontSize = 17.sp, fontWeight = FontWeight.Bold)
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
            is Zustand.Fehler -> Hinweis("Fehler im Planer: ${z.meldung}")
            is Zustand.Fertig -> {
                val ersterTag = z.request.firstDay.plusWeeks(woche.toLong())
                WochenKopf(
                    ersterTag = ersterTag,
                    zurueck = { if (woche > 0) vm.woche-- },
                    weiter = { if (woche < z.request.days / 7 - 1) vm.woche++ },
                )
                WochenRaster(z.request, z.plan, ersterTag)
                Zusammenfassung(z)
            }
        }

        Spacer(Modifier.height(8.dp))
        VersionZeile()
    }
}

@Composable
private fun WochenKopf(ersterTag: LocalDate, zurueck: () -> Unit, weiter: () -> Unit) {
    val f = DateTimeFormatter.ofPattern("dd.MM.")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = zurueck, modifier = Modifier.size(48.dp), contentPadding = ButtonDefaults.TextButtonContentPadding) {
            Text("‹", fontSize = 22.sp, color = Schrift)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("KW ${ersterTag.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}", fontSize = 13.sp, color = TextLeise)
            Text(
                "${ersterTag.format(f)} – ${ersterTag.plusDays(6).format(f)}",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Schrift,
            )
        }
        OutlinedButton(onClick = weiter, modifier = Modifier.size(48.dp), contentPadding = ButtonDefaults.TextButtonContentPadding) {
            Text("›", fontSize = 22.sp, color = Schrift)
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
        Hinweis("In der Rechenzeit wurde kein Plan gefunden. Bitte erneut versuchen.")
        return
    }
    val text = buildString {
        append("Berechnet in ${z.millis / 1000.0} s. ")
        if (z.plan.unfilled.isEmpty()) append("Alle Schichten sind besetzt.")
        else append("${z.plan.unfilled.size} Schichten sind unbesetzt.")
        append("\n\nStunden in 2 Wochen:\n")
        z.request.employees.forEach { e ->
            append("${e.name}: ${"%.1f".format(z.plan.paidMinutesOf(e.id) / 60.0)} h\n")
        }
    }
    Hinweis(text.trimEnd())
}

@Composable
private fun Hinweis(text: String) {
    Text(
        text,
        fontSize = 14.sp,
        color = Schrift,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .background(Warnung, RoundedCornerShape(10.dp))
            .padding(12.dp),
    )
}
