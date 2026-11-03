package de.reichimd.schichtplaner.sicherung

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.reichimd.schichtplaner.core.backup.BackupCodec
import de.reichimd.schichtplaner.core.backup.BackupData
import de.reichimd.schichtplaner.ui.Akzent
import de.reichimd.schichtplaner.ui.Fehler
import de.reichimd.schichtplaner.ui.RandHell
import de.reichimd.schichtplaner.ui.Schrift
import de.reichimd.schichtplaner.ui.TextLeise
import de.reichimd.schichtplaner.ui.TextMittel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val TAG = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val TAG_ZEIT = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm")
private val TAG_KURZ = DateTimeFormatter.ofPattern("dd.MM.")

/** Hält Sichern und Einspielen über Drehen des Handys hinweg. */
internal class SicherungViewModel : ViewModel() {
    var arbeitet by mutableStateOf(false)
        private set
    /** Gelesene, geprüfte Sicherung, die auf "Einspielen" wartet. */
    var frage by mutableStateOf<BackupData?>(null)
        private set
    var meldung by mutableStateOf<Pair<Boolean, String>?>(null)
        private set

    private fun arbeiten(block: suspend () -> Unit) {
        if (arbeitet) return
        arbeitet = true
        meldung = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SicherungsFehler) {
                meldung = false to (e.message ?: "Unbekannter Fehler.")
            } catch (e: Exception) {
                meldung = false to "Unerwarteter Fehler: ${e::class.java.simpleName} ${e.message.orEmpty()}"
            } finally {
                arbeitet = false
            }
        }
    }

    fun sichern(context: Context, speicher: Speicher, ziel: Uri) = arbeiten {
        val daten = Sicherung.stand(speicher)
        withContext(Dispatchers.IO) { Sicherung.schreiben(context, ziel, daten) }
        meldung = true to "Gesichert: ${beschreibung(daten)}"
    }

    fun lesen(context: Context, quelle: Uri) = arbeiten {
        frage = withContext(Dispatchers.IO) { Sicherung.lesen(context, quelle) }
        frageIstRueckweg = false
    }

    /** Gibt es einen Stand von vor dem letzten Einspielen? */
    var rueckwegDa by mutableStateOf(false)
        private set

    fun rueckwegPruefen(context: Context) {
        viewModelScope.launch { rueckwegDa = withContext(Dispatchers.IO) { Sicherung.rueckweg(context).exists() } }
    }

    /** true: die Frage gilt dem Stand von vor dem letzten Einspielen (der wird dann nicht überschrieben). */
    var frageIstRueckweg by mutableStateOf(false)
        private set

    fun rueckwegLesen(context: Context) = arbeiten {
        frage = withContext(Dispatchers.IO) { Sicherung.lesen(context, Uri.fromFile(Sicherung.rueckweg(context))) }
        frageIstRueckweg = true
    }

    fun einspielen(context: Context, speicher: Speicher) {
        val daten = frage ?: return
        val rueckweg = if (frageIstRueckweg) null else Sicherung.rueckweg(context)
        frage = null
        arbeiten {
            Sicherung.einspielen(speicher, daten, rueckweg)
            if (rueckweg != null) rueckwegDa = true
            meldung = true to "Sicherung eingespielt: ${beschreibung(daten)}"
        }
    }

    fun abbrechen() { frage = null }

    fun ohneDateiauswahl() {
        meldung = false to "Auf diesem Handy lässt sich keine Datei auswählen (Dateiauswahl fehlt)."
    }

    fun meldungGelesen() { meldung = null }
}

/** "8 Personen, 23 Kalendereinträge, Plan 02.11. – 15.11." */
internal fun beschreibung(d: BackupData): String = buildString {
    append(if (d.team.size == 1) "1 Person" else "${d.team.size} Personen")
    append(", ")
    append(if (d.wishes.size == 1) "1 Kalendereintrag" else "${d.wishes.size} Kalendereinträge")
    append(", ")
    append(d.plan?.let { "Plan ${it.firstDay.format(TAG_KURZ)} – ${it.lastDay.format(TAG_KURZ)}" } ?: "kein Plan")
}

/**
 * Karte "Sicherung" im Laden-Reiter: alle Daten in eine Datei sichern oder von dort zurückholen.
 * Die Datei liegt dort, wo die Filialleitung sie hinlegt (Drive, Downloads, ...), nicht in der App.
 */
@Composable
internal fun SicherungKarte(
    speicher: Speicher = Speicher.get(LocalContext.current.applicationContext),
    vm: SicherungViewModel = viewModel(),
) {
    val context = LocalContext.current.applicationContext
    val speichernUnter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.sichern(context, speicher, uri)
    }
    val oeffnen = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.lesen(context, uri)
    }

    LaunchedEffect(Unit) { vm.rueckwegPruefen(context) }

    val form = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White, form)
            .border(1.dp, RandHell, form)
            .padding(14.dp)
            .testTag("sicherung"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Sicherung", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Schrift)
        Text(
            "Team, Laden, Wünsche-Kalender und Plan liegen nur auf diesem Handy. " +
                "Ab und zu sichern, z. B. in Google Drive. Damit kommt alles auch auf ein neues Handy.",
            fontSize = 13.sp,
            color = TextLeise,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    try {
                        speichernUnter.launch(BackupCodec.fileName(LocalDate.now()))
                    } catch (e: ActivityNotFoundException) {
                        vm.ohneDateiauswahl()
                    }
                },
                enabled = !vm.arbeitet,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
                    .testTag("sichern"),
            ) { Text("Sichern", fontSize = 16.sp, color = Akzent, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
            OutlinedButton(
                onClick = {
                    try {
                        // Manche Dateiablagen kennen JSON nicht und melden "application/octet-stream".
                        oeffnen.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
                    } catch (e: ActivityNotFoundException) {
                        vm.ohneDateiauswahl()
                    }
                },
                enabled = !vm.arbeitet,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
                    .testTag("zurueckholen"),
            ) { Text("Zurückholen", fontSize = 16.sp, color = Akzent, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
        }
        if (vm.rueckwegDa) {
            TextButton(
                onClick = { vm.rueckwegLesen(context) },
                enabled = !vm.arbeitet,
                modifier = Modifier.testTag("rueckweg"),
            ) { Text("Stand vor dem letzten Zurückholen wiederherstellen", fontSize = 14.sp, color = Akzent) }
        }
        if (vm.arbeitet) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Akzent)
                Text("Einen Moment …", fontSize = 14.sp, color = TextMittel)
            }
        }
        vm.meldung?.let { (gut, text) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text,
                    fontSize = 14.sp,
                    fontWeight = if (gut) FontWeight.Normal else FontWeight.Bold,
                    color = if (gut) TextMittel else Fehler,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("sicherung-meldung"),
                )
                TextButton(onClick = vm::meldungGelesen) { Text("OK", color = Akzent) }
            }
        }
    }

    vm.frage?.let { daten ->
        AlertDialog(
            onDismissRequest = vm::abbrechen,
            title = { Text(if (vm.frageIstRueckweg) "Alten Stand wiederherstellen?" else "Sicherung einspielen?") },
            text = {
                Text(
                    buildString {
                        append("Sicherung")
                        val erstellt = daten.created
                        erstellt?.let { append(" vom ${it.format(TAG_ZEIT)} Uhr") }
                        append(":\n${beschreibung(daten)}.\n\n")
                        append("Alles, was jetzt auf diesem Handy steht (Team, Laden, Kalender und Plan), wird ersetzt.")
                        if (!vm.frageIstRueckweg) append(" Der jetzige Stand wird vorher in der App aufbewahrt.")
                        if (erstellt != null && erstellt.toLocalDate().isBefore(LocalDate.now().minusDays(30))) {
                            append("\n\nAchtung: Die Sicherung ist älter als 30 Tage (${erstellt.format(TAG)}).")
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.einspielen(context, speicher) }, modifier = Modifier.testTag("einspielen")) {
                    Text("Einspielen", color = Akzent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = vm::abbrechen) { Text("Abbrechen", color = Schrift) } },
        )
    }
}
