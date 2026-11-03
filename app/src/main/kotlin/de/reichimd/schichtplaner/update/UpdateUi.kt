package de.reichimd.schichtplaner.update

import android.app.Application
import android.content.ActivityNotFoundException
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.reichimd.schichtplaner.BuildConfig
import de.reichimd.schichtplaner.core.update.UpdateCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

internal sealed interface UpdateZustand {
    data object Still : UpdateZustand
    data object Sucht : UpdateZustand
    data object Aktuell : UpdateZustand
    data class Verfuegbar(val update: UpdateCheck.Update) : UpdateZustand
    data class Laedt(val update: UpdateCheck.Update, val anteil: Float) : UpdateZustand
    data class Bereit(val update: UpdateCheck.Update, val apk: File) : UpdateZustand
    data class BrauchtErlaubnis(val update: UpdateCheck.Update, val apk: File) : UpdateZustand
    data class Fehler(val meldung: String, val update: UpdateCheck.Update?) : UpdateZustand
    /** Debug-Build aus den GitHub Actions: anders signiert, kann nicht auf Releases updaten. */
    data object NurTestbuild : UpdateZustand
}

internal class UpdateViewModel(app: Application) : AndroidViewModel(app) {
    private val updater = Updater(app)
    private var job: Job? = null

    var zustand by mutableStateOf<UpdateZustand>(UpdateZustand.Still)
        private set

    init {
        if (Updater.isReleaseBuild()) {
            updater.cleanUp()
            suchen(still = true)
        } else {
            zustand = UpdateZustand.NurTestbuild
        }
    }

    /** [still] = automatische Suche beim Start: ohne Netz keine Fehlermeldung. */
    fun suchen(still: Boolean = false) {
        if (!Updater.isReleaseBuild()) {
            zustand = UpdateZustand.NurTestbuild
            return
        }
        if (job?.isActive == true) return
        zustand = UpdateZustand.Sucht
        job = viewModelScope.launch {
            zustand = try {
                updater.check()?.let { UpdateZustand.Verfuegbar(it) } ?: UpdateZustand.Aktuell
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (still) UpdateZustand.Still else UpdateZustand.Fehler(meldung(e), null)
            }
        }
    }

    fun laden(update: UpdateCheck.Update) {
        if (job?.isActive == true) return
        zustand = UpdateZustand.Laedt(update, 0f)
        job = viewModelScope.launch {
            zustand = try {
                var gemeldet = 0f
                val apk = updater.download(update) { anteil ->
                    // Läuft im Download-Thread. Nur in 2-%-Schritten neu zeichnen.
                    if (anteil - gemeldet >= 0.02f || anteil >= 1f) {
                        gemeldet = anteil
                        viewModelScope.launch {
                            if (zustand is UpdateZustand.Laedt) zustand = UpdateZustand.Laedt(update, anteil)
                        }
                    }
                }
                nachLaden(update, apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateZustand.Fehler(meldung(e), update)
            }
        }
    }

    private fun nachLaden(update: UpdateCheck.Update, apk: File): UpdateZustand =
        if (updater.canInstall()) {
            try {
                updater.install(apk)
                UpdateZustand.Bereit(update, apk)
            } catch (e: ActivityNotFoundException) {
                UpdateZustand.Fehler("Auf diesem Handy lässt sich der Installer nicht öffnen.", update)
            }
        } else {
            UpdateZustand.BrauchtErlaubnis(update, apk)
        }

    fun erlaubnisOeffnen() {
        try {
            updater.openInstallPermissionSettings()
        } catch (e: ActivityNotFoundException) {
            val z = zustand as? UpdateZustand.BrauchtErlaubnis
            zustand = UpdateZustand.Fehler(
                "Die Einstellung lässt sich nicht öffnen. Bitte unter Einstellungen › Apps › Schichtplan " +
                    "\"Unbekannte Apps installieren\" erlauben.",
                z?.update,
            )
        }
    }

    /** Nach dem Erlauben in den Einstellungen oder wenn der Installer abgebrochen wurde. */
    fun installieren(update: UpdateCheck.Update, apk: File) {
        zustand = if (!apk.exists()) {
            UpdateZustand.Verfuegbar(update)
        } else {
            nachLaden(update, apk)
        }
    }

    private fun meldung(e: Exception) = when (e) {
        is Updater.RateLimitException -> "GitHub hat gerade zu viele Anfragen. Bitte in einer Stunde nochmal versuchen."
        is IOException -> "Keine Verbindung zu GitHub oder Download fehlgeschlagen. ${e.message ?: ""}".trim()
        else -> "Die Antwort von GitHub war unerwartet. Bitte später nochmal versuchen."
    }
}

private val Akzent = Color(0xFF8A3A12)
private val Hinweis = Color(0xFFEAF3E6)
private val Schrift = Color(0xFF2B2620)
private val Leise = Color(0xFF6B6258)

/** Hinweis oben in der App, sobald es etwas zum Update zu sagen gibt. */
@Composable
internal fun UpdateBanner(vm: UpdateViewModel = viewModel()) {
    val z = vm.zustand
    val text: String
    val knopf: Pair<String, () -> Unit>?
    when (z) {
        is UpdateZustand.Verfuegbar -> {
            text = "Neue Version ${z.update.versionName} ist da."
            knopf = "Jetzt aktualisieren" to { vm.laden(z.update) }
        }
        is UpdateZustand.Laedt -> {
            text = "Version ${z.update.versionName} wird geladen …"
            knopf = null
        }
        is UpdateZustand.Bereit -> {
            text = "Version ${z.update.versionName} ist geladen. Bitte im Fenster auf \"Aktualisieren\" tippen."
            knopf = "Installer erneut öffnen" to { vm.installieren(z.update, z.apk) }
        }
        is UpdateZustand.BrauchtErlaubnis -> {
            text = "Einmalig nötig: Erlaube in den Einstellungen \"Apps aus dieser Quelle installieren\" " +
                "und komm dann mit Zurück hierher."
            knopf = "Einstellungen öffnen" to { vm.erlaubnisOeffnen() }
        }
        is UpdateZustand.Fehler -> {
            text = z.meldung
            val update = z.update
            knopf = "Nochmal versuchen" to { if (update != null) vm.laden(update) else vm.suchen() }
        }
        else -> return
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .background(Hinweis, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, fontSize = 14.sp, color = Schrift)
        if (z is UpdateZustand.Laedt) {
            LinearProgressIndicator(progress = { z.anteil }, modifier = Modifier.fillMaxWidth(), color = Akzent)
        }
        if (z is UpdateZustand.BrauchtErlaubnis) {
            TextButton(onClick = { vm.installieren(z.update, z.apk) }) {
                Text("Erlaubt, jetzt installieren", color = Akzent)
            }
        }
        knopf?.let { (label, aktion) ->
            Button(
                onClick = aktion,
                colors = ButtonDefaults.buttonColors(containerColor = Akzent),
                shape = RoundedCornerShape(10.dp),
            ) { Text(label, fontWeight = FontWeight.Bold) }
        }
    }
}

/** Versionszeile unten mit Knopf zum Suchen. */
@Composable
internal fun VersionZeile(vm: UpdateViewModel = viewModel()) {
    val status = when (vm.zustand) {
        UpdateZustand.Sucht -> "sucht nach Updates …"
        UpdateZustand.Aktuell -> "aktuell"
        UpdateZustand.NurTestbuild -> "Testbuild ohne Updates. Für Updates die APK aus GitHub-Releases installieren."
        else -> null
    }
    Column(Modifier.padding(horizontal = 8.dp)) {
        Text(
            "Version ${BuildConfig.VERSION_NAME}" + (status?.let { " · $it" } ?: ""),
            fontSize = 12.sp,
            color = Leise,
        )
        // Nicht während Laden oder Installieren: sonst ginge eine fertig geladene APK verloren.
        val z = vm.zustand
        val frei = z is UpdateZustand.Still || z is UpdateZustand.Aktuell || z is UpdateZustand.Fehler
        if (z !is UpdateZustand.NurTestbuild) {
            TextButton(onClick = { vm.suchen() }, enabled = frei) {
                Text("Nach Updates suchen", fontSize = 13.sp, color = Akzent)
            }
        }
    }
}
