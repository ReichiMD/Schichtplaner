package de.reichimd.schichtplaner.plan

import android.content.Context
import de.reichimd.schichtplaner.core.plan.PlanCodec
import de.reichimd.schichtplaner.core.plan.ShiftPlan
import de.reichimd.schichtplaner.daten.DateiSpeicher
import de.reichimd.schichtplaner.daten.DateiTexte
import de.reichimd.schichtplaner.daten.DateiZustand
import kotlinx.coroutines.flow.StateFlow
import java.io.File

internal typealias PlanZustand = DateiZustand<ShiftPlan?>

internal val DateiZustand<ShiftPlan?>.plan: ShiftPlan? get() = wert

/**
 * Der fertige Plan samt Änderungen von Hand (plan.json im App-Ordner, siehe [DateiSpeicher]).
 * Jede Änderung wird sofort gespeichert. Ändern sich später Team, Laden oder Kalender, bleibt der Plan stehen.
 */
internal class PlanStore(dir: File) {

    private val datei = DateiSpeicher<ShiftPlan?>(
        ordner = dir,
        dateiname = DATEINAME,
        leer = null,
        texte = DateiTexte(
            name = "Der gespeicherte Plan",
            danachLeer = "Bitte den Plan neu erstellen.",
            gesperrt = "kann der Plan gerade nicht geändert werden.",
        ),
        encode = PlanCodec::encode,
        decode = PlanCodec::decode,
    )

    val zustand: StateFlow<PlanZustand> = datei.zustand

    /** Neuer Plan ersetzt den alten. Gibt false zurück, wenn gerade nicht gespeichert werden darf. */
    fun ersetzen(plan: ShiftPlan): Boolean = datei.aendern { plan }

    /** Änderung von Hand am vorhandenen Plan. Ohne Plan passiert nichts. */
    fun aendern(wie: (ShiftPlan) -> ShiftPlan): Boolean = datei.aendern { it?.let(wie) }

    /** Plan aus einer Sicherung übernehmen; null = kein Plan. */
    fun ersetzenOderLeeren(plan: ShiftPlan?): Boolean = datei.aendern { plan }

    fun meldungGelesen() = datei.meldungGelesen()

    /** Für Tests: wartet, bis geladen und alles geschrieben ist. */
    suspend fun warten(): PlanZustand = datei.warten()

    companion object {
        const val DATEINAME = "plan.json"

        @Volatile
        private var instanz: PlanStore? = null

        fun get(context: Context): PlanStore =
            instanz ?: synchronized(this) {
                instanz ?: PlanStore(context.applicationContext.filesDir).also { instanz = it }
            }
    }
}
