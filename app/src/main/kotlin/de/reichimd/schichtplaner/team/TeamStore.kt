package de.reichimd.schichtplaner.team

import android.content.Context
import de.reichimd.schichtplaner.core.Employee
import de.reichimd.schichtplaner.core.Problem
import de.reichimd.schichtplaner.core.team.Team
import de.reichimd.schichtplaner.core.team.TeamCodec
import de.reichimd.schichtplaner.core.team.TeamField
import de.reichimd.schichtplaner.daten.DateiSpeicher
import de.reichimd.schichtplaner.daten.DateiTexte
import de.reichimd.schichtplaner.daten.DateiZustand
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID

internal typealias TeamZustand = DateiZustand<List<Employee>>

internal val DateiZustand<List<Employee>>.team: List<Employee> get() = wert

/**
 * Teamliste auf dem Handy, als JSON-Datei im privaten App-Ordner (siehe [DateiSpeicher]).
 * Es gibt pro Ordner genau eine Instanz für den ganzen Prozess.
 */
internal class TeamStore(dir: File) {

    private val datei = DateiSpeicher(
        ordner = dir,
        dateiname = DATEINAME,
        leer = emptyList(),
        texte = DateiTexte(
            name = "Die Teamliste",
            danachLeer = "Das Team ist jetzt leer.",
            gesperrt = "ist das Team gesperrt.",
        ),
        encode = TeamCodec::encode,
        decode = TeamCodec::decode,
    )

    val zustand: StateFlow<TeamZustand> = datei.zustand

    /** Speichert einen neuen oder geänderten Mitarbeiter. Gibt Probleme zurück; leer = gespeichert. */
    fun speichern(mitarbeiter: Employee): List<Problem<TeamField>> {
        val z = zustand.value
        if (!z.bearbeitbar) return listOf(Problem(TeamField.GENERAL, "Die Teamliste ist gerade nicht bearbeitbar."))
        val probleme = Team.problems(mitarbeiter, z.team)
        if (probleme.isNotEmpty()) return probleme
        datei.aendern { Team.upsert(it, mitarbeiter) }
        return emptyList()
    }

    /** Person eine Zeile nach oben oder unten, wie die Reihenfolge auf dem Papierplan. */
    fun verschieben(id: String, hoch: Boolean) {
        datei.aendern { Team.move(it, id, hoch) }
    }

    fun loeschen(id: String) {
        datei.aendern { Team.remove(it, id) }
    }

    /** Beispiel-Team, nur solange das Team leer ist. Neue IDs, damit nichts mit Alt-Daten kollidiert. */
    fun beispielLaden() {
        datei.aendern { team ->
            if (team.isNotEmpty()) team
            else Team.EXAMPLE.map { it.copy(id = UUID.randomUUID().toString()) }
        }
    }

    /** Ganzes Team ersetzen, z. B. beim Einspielen einer Sicherung. Gibt false zurück, wenn gesperrt. */
    fun ersetzen(team: List<Employee>): Boolean = datei.aendern { team }

    fun meldungGelesen() = datei.meldungGelesen()

    /** Für Tests: wartet, bis geladen und alles geschrieben ist. */
    suspend fun warten(): TeamZustand = datei.warten()

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
