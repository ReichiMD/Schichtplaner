package de.reichimd.schichtplaner.wuensche

import android.content.Context
import de.reichimd.schichtplaner.core.Absence
import de.reichimd.schichtplaner.core.AbsenceType
import de.reichimd.schichtplaner.core.backup.WishCleanup
import de.reichimd.schichtplaner.core.wishes.Wishes
import de.reichimd.schichtplaner.core.wishes.WishesCodec
import de.reichimd.schichtplaner.daten.DateiSpeicher
import de.reichimd.schichtplaner.daten.DateiTexte
import de.reichimd.schichtplaner.daten.DateiZustand
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.time.LocalDate

internal typealias WuenscheZustand = DateiZustand<List<Absence>>

internal val DateiZustand<List<Absence>>.eintraege: List<Absence> get() = wert

/**
 * Urlaub, Frei-Wünsche und Krankmeldungen auf dem Handy (wuensche.json im App-Ordner, siehe [DateiSpeicher]).
 * Jedes Antippen wird sofort gespeichert, ohne Speichern-Knopf.
 */
internal class WuenscheStore(dir: File) {

    private val datei = DateiSpeicher(
        ordner = dir,
        dateiname = DATEINAME,
        leer = emptyList(),
        texte = DateiTexte(
            name = "Der Wünsche-Kalender",
            danachLeer = "Der Kalender ist jetzt leer.",
            gesperrt = "ist der Kalender gesperrt.",
        ),
        encode = WishesCodec::encode,
        decode = WishesCodec::decode,
    )

    val zustand: StateFlow<WuenscheZustand> = datei.zustand

    /** Tag antippen. Gibt false zurück, wenn der Kalender gerade nicht bearbeitbar ist. */
    fun antippen(mitarbeiterId: String, tag: LocalDate, art: AbsenceType): Boolean =
        datei.aendern { Wishes.toggle(it, mitarbeiterId, tag, art) }

    /** Alle Einträge einer Person entfernen, z. B. wenn sie aus dem Team gelöscht wird. */
    fun personEntfernen(mitarbeiterId: String): Boolean =
        datei.aendern { Wishes.removeEmployee(it, mitarbeiterId) }

    /** Ganzen Kalender ersetzen, z. B. beim Einspielen einer Sicherung. */
    fun ersetzen(eintraege: List<Absence>): Boolean = datei.aendern { eintraege }

    /**
     * Alte Monate und Einträge von Personen, die nicht mehr im Team sind, entfernen (siehe [WishCleanup]).
     * Schreibt nur, wenn sich wirklich etwas ändert.
     */
    fun aufraeumen(teamIds: Set<String>?, behaltenAb: LocalDate) {
        val z = zustand.value
        if (!z.bearbeitbar) return
        if (WishCleanup.clean(z.eintraege, teamIds, behaltenAb) == z.eintraege) return
        datei.aendern { WishCleanup.clean(it, teamIds, behaltenAb) }
    }

    fun meldungGelesen() = datei.meldungGelesen()

    /** Für Tests: wartet, bis geladen und alles geschrieben ist. */
    suspend fun warten(): WuenscheZustand = datei.warten()

    companion object {
        const val DATEINAME = "wuensche.json"

        @Volatile
        private var instanz: WuenscheStore? = null

        fun get(context: Context): WuenscheStore =
            instanz ?: synchronized(this) {
                instanz ?: WuenscheStore(context.applicationContext.filesDir).also { instanz = it }
            }
    }
}
