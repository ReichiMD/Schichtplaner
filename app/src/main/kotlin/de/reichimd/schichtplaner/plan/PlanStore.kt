package de.reichimd.schichtplaner.plan

import android.content.Context
import de.reichimd.schichtplaner.core.plan.PlanCodec
import de.reichimd.schichtplaner.core.plan.ShiftPlan
import de.reichimd.schichtplaner.core.plan.YearPlan
import de.reichimd.schichtplaner.core.plan.YearPlans
import de.reichimd.schichtplaner.core.shop.ShopSettings
import de.reichimd.schichtplaner.daten.DateiSpeicher
import de.reichimd.schichtplaner.daten.DateiTexte
import de.reichimd.schichtplaner.daten.DateiZustand
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.time.LocalDate

internal typealias PlanZustand = DateiZustand<YearPlan>

internal val DateiZustand<YearPlan>.plan: YearPlan get() = wert

/**
 * Der ganze Plan, Woche für Woche (plan.json im App-Ordner, siehe [DateiSpeicher] und [YearPlan]).
 * Jede Änderung wird sofort gespeichert. Wochen, die nie angefasst wurden, stehen nicht in der Datei.
 */
internal class PlanStore(dir: File) {

    private val datei = DateiSpeicher(
        ordner = dir,
        dateiname = DATEINAME,
        leer = YearPlan.EMPTY,
        texte = DateiTexte(
            name = "Der gespeicherte Plan",
            danachLeer = "Der Plan ist jetzt leer.",
            gesperrt = "kann der Plan gerade nicht geändert werden.",
        ),
        encode = PlanCodec::encodeYear,
        decode = PlanCodec::decodeYear,
    )

    val zustand: StateFlow<PlanZustand> = datei.zustand

    /** Ganzen Plan ersetzen, z. B. beim Einspielen einer Sicherung. */
    fun ersetzen(plan: YearPlan): Boolean = datei.aendern { plan }

    /**
     * Woche ab [montag] ändern. [wie] bekommt die Woche wie angezeigt (gespeichert oder aus [laden]).
     * Gibt false zurück, wenn gerade nicht gespeichert werden darf.
     */
    fun woche(montag: LocalDate, laden: ShopSettings, wie: (ShiftPlan) -> ShiftPlan): Boolean =
        datei.aendern { YearPlans.withWeek(it, wie(YearPlans.week(it, montag, laden))) }

    /** Woche leeren: danach gelten wieder die Schichtvorlagen des Ladens, alle offen. */
    fun wocheLeeren(montag: LocalDate): Boolean = datei.aendern { YearPlans.clearWeek(it, montag) }

    /** Nur ändern, wenn der Plan noch [erwartet] ist (z. B. nicht inzwischen eine Sicherung eingespielt). */
    fun wennUnveraendert(erwartet: YearPlan, wie: (YearPlan) -> YearPlan): Boolean {
        var ok = false
        val geaendert = datei.aendern { if (it === erwartet) wie(it).also { ok = true } else it }
        return geaendert && ok
    }

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
