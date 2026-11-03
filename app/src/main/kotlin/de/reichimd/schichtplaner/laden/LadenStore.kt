package de.reichimd.schichtplaner.laden

import android.content.Context
import de.reichimd.schichtplaner.core.Problem
import de.reichimd.schichtplaner.core.shop.Shop
import de.reichimd.schichtplaner.core.shop.ShopCodec
import de.reichimd.schichtplaner.core.shop.ShopField
import de.reichimd.schichtplaner.core.shop.ShopSettings
import de.reichimd.schichtplaner.daten.DateiSpeicher
import de.reichimd.schichtplaner.daten.DateiTexte
import de.reichimd.schichtplaner.daten.DateiZustand
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** null = noch nie gespeichert, dann gelten die Beispielwerte [Shop.EXAMPLE]. */
internal typealias LadenZustand = DateiZustand<ShopSettings?>

/** Was gerade gilt: das Gespeicherte oder, solange nichts gespeichert ist, das Beispiel. */
internal val DateiZustand<ShopSettings?>.laden: ShopSettings get() = wert ?: Shop.EXAMPLE

internal val DateiZustand<ShopSettings?>.eingerichtet: Boolean get() = wert != null

/** Laden-Einstellungen auf dem Handy (laden.json im App-Ordner, siehe [DateiSpeicher]). */
internal class LadenStore(dir: File) {

    private val datei = DateiSpeicher<ShopSettings?>(
        ordner = dir,
        dateiname = DATEINAME,
        leer = null,
        texte = DateiTexte(
            name = "Die Laden-Einstellung",
            danachLeer = "Es gelten wieder die Beispielwerte.",
            gesperrt = "sind die Laden-Einstellungen gesperrt.",
        ),
        encode = { ShopCodec.encode(it ?: Shop.EXAMPLE) },
        decode = ShopCodec::decode,
    )

    val zustand: StateFlow<LadenZustand> = datei.zustand

    /**
     * Ändert die Einstellungen, wenn das Ergebnis gültig ist. Gibt die Probleme des Ergebnisses zurück;
     * leer = gespeichert. Geprüft wird immer das Ganze, weil z. B. eine neue Öffnungszeit eine Schicht
     * ungültig machen kann.
     */
    fun speichern(wie: (ShopSettings) -> ShopSettings): List<Problem<ShopField>> {
        val z = zustand.value
        if (!z.bearbeitbar) return listOf(Problem(ShopField.Templates, "Die Laden-Einstellungen sind gerade nicht bearbeitbar."))
        val neu = wie(z.laden)
        val probleme = Shop.problems(neu)
        if (probleme.isNotEmpty()) return probleme
        datei.aendern { neu }
        return emptyList()
    }

    /** Alles ersetzen, z. B. beim Einspielen einer Sicherung. Ungeprüft wie beim Laden der Datei. */
    fun ersetzen(laden: ShopSettings): Boolean = datei.aendern { laden }

    fun meldungGelesen() = datei.meldungGelesen()

    /** Für Tests: wartet, bis geladen und alles geschrieben ist. */
    suspend fun warten(): LadenZustand = datei.warten()

    companion object {
        const val DATEINAME = "laden.json"

        @Volatile
        private var instanz: LadenStore? = null

        fun get(context: Context): LadenStore =
            instanz ?: synchronized(this) {
                instanz ?: LadenStore(context.applicationContext.filesDir).also { instanz = it }
            }
    }
}
