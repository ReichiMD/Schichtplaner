package de.reichimd.schichtplaner.team

import android.content.Context
import androidx.core.util.AtomicFile
import de.reichimd.schichtplaner.core.Employee
import de.reichimd.schichtplaner.core.team.Team
import de.reichimd.schichtplaner.core.team.TeamCodec
import de.reichimd.schichtplaner.core.team.TeamFileException
import de.reichimd.schichtplaner.core.team.TeamFileTooNewException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

internal data class TeamZustand(
    val team: List<Employee> = emptyList(),
    val laedt: Boolean = true,
    /** Änderungen, die noch nicht in der Datei stehen. */
    val ungespeichert: Boolean = false,
    /** Lesen ging schief, ohne dass die Datei kaputt ist: dann nichts überschreiben. */
    val nurLesen: Boolean = false,
    val meldung: String? = null,
) {
    val bearbeitbar: Boolean get() = !laedt && !nurLesen
}

/**
 * Teamliste auf dem Handy, als JSON-Datei im privaten App-Ordner. Ohne Cloud, ohne Konto.
 *
 * Es gibt pro Ordner genau eine Instanz für den ganzen Prozess. Geschrieben wird in einem eigenen
 * Hintergrund-Job, der Reihe nach und immer mit dem neuesten Stand. Darum geht beim schnellen Antippen
 * nichts durcheinander, und Drehen oder Schließen eines Screens bricht das Speichern nicht ab.
 */
internal class TeamStore(dir: File) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val datei = AtomicFile(File(dir, DATEINAME))
    private val ordner = dir
    private val _zustand = MutableStateFlow(TeamZustand())
    val zustand: StateFlow<TeamZustand> = _zustand.asStateFlow()

    /** CONFLATED: Wenn mehrere Änderungen anstehen, wird nur die neueste geschrieben. */
    private val zuSchreiben = Channel<List<Employee>>(Channel.CONFLATED)

    init {
        scope.launch {
            _zustand.value = try {
                laden()
            } catch (e: Exception) {
                // Unerwartet (z. B. SecurityException): nichts überschreiben, aber nicht ewig "lädt" zeigen.
                TeamZustand(laedt = false, nurLesen = true, meldung = "Die Teamliste konnte nicht geladen werden: $e")
            }
            for (team in zuSchreiben) {
                try {
                    schreiben(team)
                } catch (e: Exception) {
                    _zustand.update { it.copy(meldung = "Speichern fehlgeschlagen: $e") }
                }
            }
        }
    }

    /** Speichert einen neuen oder geänderten Mitarbeiter. Gibt Probleme zurück; leer = gespeichert. */
    fun speichern(mitarbeiter: Employee): List<String> {
        val z = _zustand.value
        if (!z.bearbeitbar) return listOf("Die Teamliste ist gerade nicht bearbeitbar.")
        val probleme = Team.problems(mitarbeiter, z.team)
        if (probleme.isNotEmpty()) return probleme
        aendern { Team.upsert(it, mitarbeiter) }
        return emptyList()
    }

    fun loeschen(id: String) {
        if (!_zustand.value.bearbeitbar) return
        aendern { Team.remove(it, id) }
    }

    /** Beispiel-Team, nur solange das Team leer ist. Neue IDs, damit nichts mit Alt-Daten kollidiert. */
    fun beispielLaden() {
        if (!_zustand.value.bearbeitbar) return
        aendern { team ->
            if (team.isNotEmpty()) team
            else Team.EXAMPLE.map { it.copy(id = UUID.randomUUID().toString()) }
        }
    }

    fun meldungGelesen() = _zustand.update { it.copy(meldung = null) }

    private fun aendern(wie: (List<Employee>) -> List<Employee>) {
        _zustand.update { it.copy(team = wie(it.team), ungespeichert = true) }
        zuSchreiben.trySend(_zustand.value.team)
    }

    /** Für Tests: wartet, bis geladen und alles geschrieben ist. */
    suspend fun warten(): TeamZustand = zustand.first { !it.laedt && !it.ungespeichert }

    private fun laden(): TeamZustand {
        val text = try {
            datei.readFully().toString(Charsets.UTF_8)
        } catch (e: FileNotFoundException) {
            return TeamZustand(laedt = false)
        } catch (e: IOException) {
            return TeamZustand(
                laedt = false,
                nurLesen = true,
                meldung = "Die Teamliste konnte nicht gelesen werden. Bitte die App neu starten. (${e.message})",
            )
        }
        return try {
            TeamZustand(team = TeamCodec.decode(text), laedt = false)
        } catch (e: TeamFileTooNewException) {
            TeamZustand(
                laedt = false,
                nurLesen = true,
                meldung = "Die Teamliste stammt aus einer neueren Version der App. Bitte die neueste Version installieren.",
            )
        } catch (e: TeamFileException) {
            // Nicht einfach überschreiben: Die alte Datei bleibt zur Rettung im App-Ordner liegen.
            val gesichert = beiseiteLegen()
            if (gesichert == null) {
                TeamZustand(
                    laedt = false,
                    nurLesen = true,
                    meldung = "Die gespeicherte Teamliste ist beschädigt und konnte nicht gesichert werden. " +
                        "Damit nichts verloren geht, ist das Team gesperrt.",
                )
            } else {
                TeamZustand(
                    laedt = false,
                    meldung = "Die gespeicherte Teamliste war beschädigt und wurde beiseitegelegt ($gesichert). " +
                        "Das Team ist jetzt leer.",
                )
            }
        }
    }

    private fun beiseiteLegen(): String? {
        val stempel = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val ziel = File(ordner, "team-beschaedigt-$stempel.json")
        return try {
            datei.baseFile.copyTo(ziel, overwrite = true)
            datei.delete()
            ziel.name
        } catch (e: IOException) {
            null
        }
    }

    private fun schreiben(team: List<Employee>) {
        val stream = try {
            datei.startWrite()
        } catch (e: IOException) {
            fehlerBeimSchreiben(e)
            return
        }
        try {
            stream.write(TeamCodec.encode(team).toByteArray(Charsets.UTF_8))
            datei.finishWrite(stream)
        } catch (e: IOException) {
            datei.failWrite(stream)
            fehlerBeimSchreiben(e)
            return
        }
        // Nur "gespeichert" melden, wenn inzwischen nichts Neueres ansteht.
        _zustand.update { if (it.team == team) it.copy(ungespeichert = false) else it }
    }

    private fun fehlerBeimSchreiben(e: IOException) {
        _zustand.update {
            it.copy(meldung = "Speichern fehlgeschlagen: ${e.message ?: "unbekannter Fehler"}. Ist der Speicher voll?")
        }
    }

    companion object {
        const val DATEINAME = "team.json"

        @Volatile
        private var instanz: TeamStore? = null

        fun get(context: Context): TeamStore =
            instanz ?: synchronized(this) {
                instanz ?: TeamStore(context.applicationContext.filesDir).also { instanz = it }
            }
    }
}
