package de.reichimd.schichtplaner.daten

import androidx.core.util.AtomicFile
import de.reichimd.schichtplaner.core.storage.DataFileException
import de.reichimd.schichtplaner.core.storage.DataFileTooNewException
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

internal data class DateiZustand<T>(
    val wert: T,
    val laedt: Boolean = true,
    /** Änderungen, die noch nicht in der Datei stehen. */
    val ungespeichert: Boolean = false,
    /** Lesen ging schief, ohne dass die Datei kaputt ist: dann nichts überschreiben. */
    val nurLesen: Boolean = false,
    val meldung: String? = null,
) {
    val bearbeitbar: Boolean get() = !laedt && !nurLesen
}

/** Wie die Meldungen den Inhalt nennen, z. B. "Die Teamliste" und "Das Team ist jetzt leer." */
internal data class DateiTexte(
    /** Mit Artikel am Satzanfang, z. B. "Die Teamliste". */
    val name: String,
    /** Was nach dem Beiseitelegen einer beschädigten Datei gilt. */
    val danachLeer: String,
    /** Was gesperrt wird, wenn die Datei nicht gesichert werden kann. */
    val gesperrt: String,
)

/**
 * Ein Wert als JSON-Datei im privaten App-Ordner. Ohne Cloud, ohne Konto.
 *
 * Geschrieben wird in einem eigenen Hintergrund-Job, der Reihe nach und immer mit dem neuesten Stand.
 * Darum geht beim schnellen Antippen nichts durcheinander, und Drehen oder Schließen eines Screens
 * bricht das Speichern nicht ab. Eine beschädigte Datei wird nie überschrieben, sondern beiseitegelegt.
 */
internal class DateiSpeicher<T>(
    private val ordner: File,
    dateiname: String,
    private val leer: T,
    private val texte: DateiTexte,
    private val encode: (T) -> String,
    private val decode: (String) -> T,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val datei = AtomicFile(File(ordner, dateiname))
    private val stamm = dateiname.substringBeforeLast('.')
    private val _zustand = MutableStateFlow(DateiZustand(leer))
    val zustand: StateFlow<DateiZustand<T>> = _zustand.asStateFlow()

    /** CONFLATED: Wenn mehrere Änderungen anstehen, wird nur die neueste geschrieben. */
    private val zuSchreiben = Channel<T>(Channel.CONFLATED)

    init {
        scope.launch {
            _zustand.value = try {
                laden()
            } catch (e: Exception) {
                // Unerwartet (z. B. SecurityException): nichts überschreiben, aber nicht ewig "lädt" zeigen.
                DateiZustand(leer, laedt = false, nurLesen = true, meldung = "${texte.name} konnte nicht geladen werden: $e")
            }
            for (wert in zuSchreiben) {
                try {
                    schreiben(wert)
                } catch (e: Exception) {
                    _zustand.update { it.copy(meldung = "Speichern fehlgeschlagen: $e") }
                }
            }
        }
    }

    /**
     * Ändert den Wert, wenn bearbeitbar. [wie] läuft atomar auf dem aktuellen Stand.
     * Gibt false zurück, wenn gerade nicht bearbeitet werden darf.
     */
    fun aendern(wie: (T) -> T): Boolean {
        var ok = false
        _zustand.update {
            ok = it.bearbeitbar
            if (ok) it.copy(wert = wie(it.wert), ungespeichert = true) else it
        }
        if (ok) zuSchreiben.trySend(_zustand.value.wert)
        return ok
    }

    fun meldungGelesen() = _zustand.update { it.copy(meldung = null) }

    /** Für Tests: wartet, bis geladen und alles geschrieben ist. */
    suspend fun warten(): DateiZustand<T> = zustand.first { !it.laedt && !it.ungespeichert }

    private fun laden(): DateiZustand<T> {
        val text = try {
            datei.readFully().toString(Charsets.UTF_8)
        } catch (e: FileNotFoundException) {
            return DateiZustand(leer, laedt = false)
        } catch (e: IOException) {
            return DateiZustand(
                leer,
                laedt = false,
                nurLesen = true,
                meldung = "${texte.name} konnte nicht gelesen werden. Bitte die App neu starten. (${e.message})",
            )
        }
        return try {
            DateiZustand(decode(text), laedt = false)
        } catch (e: DataFileTooNewException) {
            DateiZustand(
                leer,
                laedt = false,
                nurLesen = true,
                meldung = "${texte.name} stammt aus einer neueren Version der App. Bitte die neueste Version installieren.",
            )
        } catch (e: DataFileException) {
            // Nicht einfach überschreiben: Die alte Datei bleibt zur Rettung im App-Ordner liegen.
            val gesichert = beiseiteLegen()
            if (gesichert == null) {
                DateiZustand(
                    leer,
                    laedt = false,
                    nurLesen = true,
                    meldung = "${texte.name} ist beschädigt und konnte nicht gesichert werden. " +
                        "Damit nichts verloren geht, ${texte.gesperrt}",
                )
            } else {
                DateiZustand(
                    leer,
                    laedt = false,
                    meldung = "${texte.name} war beschädigt und wurde beiseitegelegt ($gesichert). ${texte.danachLeer}",
                )
            }
        }
    }

    private fun beiseiteLegen(): String? {
        val stempel = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val ziel = File(ordner, "$stamm-beschaedigt-$stempel.json")
        return try {
            datei.baseFile.copyTo(ziel, overwrite = true)
            datei.delete()
            ziel.name
        } catch (e: IOException) {
            null
        }
    }

    private fun schreiben(wert: T) {
        val stream = try {
            datei.startWrite()
        } catch (e: IOException) {
            fehlerBeimSchreiben(e)
            return
        }
        try {
            stream.write(encode(wert).toByteArray(Charsets.UTF_8))
            datei.finishWrite(stream)
        } catch (e: IOException) {
            datei.failWrite(stream)
            fehlerBeimSchreiben(e)
            return
        }
        // Nur "gespeichert" melden, wenn inzwischen nichts Neueres ansteht.
        _zustand.update { if (it.wert == wert) it.copy(ungespeichert = false) else it }
    }

    private fun fehlerBeimSchreiben(e: IOException) {
        _zustand.update {
            it.copy(meldung = "Speichern fehlgeschlagen: ${e.message ?: "unbekannter Fehler"}. Ist der Speicher voll?")
        }
    }
}
