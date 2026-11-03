package de.reichimd.schichtplaner.sicherung

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import de.reichimd.schichtplaner.BuildConfig
import de.reichimd.schichtplaner.core.backup.BackupCodec
import de.reichimd.schichtplaner.core.backup.BackupData
import de.reichimd.schichtplaner.core.backup.WishCleanup
import de.reichimd.schichtplaner.core.storage.DataFileException
import de.reichimd.schichtplaner.core.storage.DataFileTooNewException
import de.reichimd.schichtplaner.laden.LadenStore
import de.reichimd.schichtplaner.laden.laden
import de.reichimd.schichtplaner.plan.PlanStore
import de.reichimd.schichtplaner.plan.plan
import de.reichimd.schichtplaner.team.TeamStore
import de.reichimd.schichtplaner.team.team
import de.reichimd.schichtplaner.wuensche.WuenscheStore
import de.reichimd.schichtplaner.wuensche.eintraege
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime

/** Die vier Speicher der App zusammen, damit Sicherung und Aufräumen alle auf einmal sehen. */
internal class Speicher(
    val team: TeamStore,
    val laden: LadenStore,
    val wuensche: WuenscheStore,
    val plan: PlanStore,
) {
    companion object {
        fun get(context: Context) = Speicher(
            TeamStore.get(context),
            LadenStore.get(context),
            WuenscheStore.get(context),
            PlanStore.get(context),
        )
    }

    suspend fun geladen() {
        team.zustand.first { !it.laedt }
        laden.zustand.first { !it.laedt }
        wuensche.zustand.first { !it.laedt }
        plan.zustand.first { !it.laedt }
    }

    /** Name des ersten Speichers, der gerade gesperrt ist, oder null. */
    fun gesperrt(): String? = when {
        !team.zustand.value.bearbeitbar -> "das Team"
        !laden.zustand.value.bearbeitbar -> "der Laden"
        !wuensche.zustand.value.bearbeitbar -> "der Wünsche-Kalender"
        !plan.zustand.value.bearbeitbar -> "der Plan"
        else -> null
    }
}

/** Fehler mit einer Meldung, die so in der App stehen kann. */
internal class SicherungsFehler(meldung: String) : Exception(meldung)

/**
 * Sicherungsdatei schreiben und einspielen. Die Datei legt die Filialleitung selbst ab
 * (z. B. Google Drive, Downloads oder per WhatsApp an sich selbst), die App braucht dafür keine Berechtigung.
 */
internal object Sicherung {

    /** Was gerade auf dem Handy steht. Gesperrte Speicher würden leer in der Sicherung landen, darum dann Fehler. */
    suspend fun stand(speicher: Speicher, jetzt: LocalDateTime = LocalDateTime.now()): BackupData {
        speicher.geladen()
        speicher.gesperrt()?.let {
            throw SicherungsFehler("Sichern geht gerade nicht, weil $it gesperrt ist. Bitte die Meldung dort lesen.")
        }
        return BackupData(
            team = speicher.team.zustand.value.team,
            // Noch nie eingerichtet: Die Beispielwerte, die gerade gelten, kommen mit.
            shop = speicher.laden.zustand.value.laden,
            wishes = speicher.wuensche.zustand.value.eintraege,
            plan = speicher.plan.zustand.value.plan,
            created = jetzt,
            appVersion = BuildConfig.VERSION_NAME,
        )
    }

    /** Datei im App-Ordner mit dem Stand vor dem letzten Einspielen, als Rückweg bei falscher Datei. */
    fun rueckweg(context: Context) = File(context.filesDir, RUECKWEG)

    private const val RUECKWEG = "stand-vor-dem-einspielen.json"

    /** Läuft im Hintergrund (Dateizugriff). Geht es schief, wird die angefangene Datei wieder entfernt. */
    fun schreiben(context: Context, ziel: Uri, daten: BackupData) {
        val text = BackupCodec.encode(daten).toByteArray(Charsets.UTF_8)
        val resolver = context.contentResolver
        fun oeffnen(modus: String) = resolver.openOutputStream(ziel, modus)
            ?: throw SicherungsFehler("Die Datei ließ sich nicht anlegen.")
        try {
            // "wt": vorhandene Datei vollständig ersetzen. Manche Ablagen kennen nur "w" (die neue Datei ist ohnehin leer).
            val out = try {
                oeffnen("wt")
            } catch (e: IllegalArgumentException) {
                oeffnen("w")
            } catch (e: FileNotFoundException) {
                oeffnen("w")
            }
            out.use { it.write(text) }
        } catch (e: Exception) {
            // Eine leere oder halbe Datei sähe sonst wie eine Sicherung aus.
            if (ziel.scheme == "content") runCatching { DocumentsContract.deleteDocument(resolver, ziel) }
            throw when (e) {
                is SicherungsFehler -> e
                is SecurityException -> SicherungsFehler("Die App darf dort nicht schreiben. Bitte einen anderen Ort wählen.")
                else -> SicherungsFehler("Die Sicherung ließ sich nicht schreiben: ${e.message ?: "unbekannter Fehler"}")
            }
        }
    }

    /** Läuft im Hintergrund (Dateizugriff). Liest und prüft alles, bevor etwas geändert wird. */
    fun lesen(context: Context, quelle: Uri): BackupData {
        val bytes = try {
            val input = context.contentResolver.openInputStream(quelle)
                ?: throw SicherungsFehler("Die Datei ließ sich nicht öffnen.")
            input.use { s ->
                val puffer = ByteArrayOutputStream()
                val stueck = ByteArray(64 * 1024)
                while (true) {
                    val n = s.read(stueck)
                    if (n < 0) break
                    puffer.write(stueck, 0, n)
                    if (puffer.size() > BackupCodec.MAX_BYTES) {
                        throw SicherungsFehler("Die Datei ist viel zu groß für eine Sicherung des Schichtplaners.")
                    }
                }
                puffer.toByteArray()
            }
        } catch (e: IOException) {
            throw SicherungsFehler("Die Datei ließ sich nicht lesen: ${e.message ?: "unbekannter Fehler"}")
        } catch (e: SecurityException) {
            throw SicherungsFehler("Die App darf diese Datei nicht lesen.")
        }
        return try {
            BackupCodec.decode(bytes.toString(Charsets.UTF_8))
        } catch (e: DataFileTooNewException) {
            throw SicherungsFehler("Die Sicherung stammt aus einer neueren App-Version. Bitte zuerst die App aktualisieren.")
        } catch (e: DataFileException) {
            throw SicherungsFehler(e.message ?: "Die Datei ist keine gültige Sicherung.")
        }
    }

    /**
     * Ersetzt alles auf dem Handy durch die Sicherung. Vorher wird geprüft, dass kein Speicher gesperrt ist,
     * damit nicht nur ein Teil ersetzt wird. Alte Monate und verwaiste Wünsche werden dabei aufgeräumt.
     */
    suspend fun einspielen(
        speicher: Speicher,
        daten: BackupData,
        /** Hierhin kommt vorher der jetzige Stand, damit eine falsch gewählte Datei nichts zerstört. null = nicht sichern. */
        rueckweg: File?,
        heute: LocalDate = LocalDate.now(),
    ) {
        speicher.geladen()
        speicher.gesperrt()?.let {
            throw SicherungsFehler("Einspielen geht gerade nicht, weil $it gesperrt ist. Es wurde nichts geändert.")
        }
        if (rueckweg != null) {
            val vorher = BackupCodec.encode(stand(speicher))
            try {
                withContext(Dispatchers.IO) {
                    val neu = File(rueckweg.parentFile, rueckweg.name + ".neu")
                    neu.writeText(vorher, Charsets.UTF_8)
                    if (!neu.renameTo(rueckweg)) throw IOException("Umbenennen fehlgeschlagen")
                }
            } catch (e: IOException) {
                throw SicherungsFehler("Der jetzige Stand ließ sich nicht vorher sichern (${e.message}). Es wurde nichts geändert.")
            }
        }
        val teamIds = daten.team.map { it.id }.toSet().takeIf { it.isNotEmpty() }
        val wuensche = WishCleanup.clean(daten.wishes, teamIds, WishCleanup.keepFrom(heute, daten.plan))
        val ok = speicher.team.ersetzen(daten.team) and
            speicher.laden.ersetzen(daten.shop) and
            speicher.wuensche.ersetzen(wuensche) and
            speicher.plan.ersetzenOderLeeren(daten.plan)
        if (!ok) throw SicherungsFehler("Die Sicherung wurde nur teilweise übernommen. Bitte noch einmal einspielen.")
    }

    /**
     * Beim Start der App: alte Monate und Wünsche gelöschter Personen entfernen.
     * Personen werden nur abgeglichen, wenn das Team sicher geladen ist und nicht leer.
     */
    suspend fun aufraeumen(speicher: Speicher, heute: LocalDate = LocalDate.now()) {
        speicher.geladen()
        val team = speicher.team.zustand.value
        val plan = speicher.plan.zustand.value
        // Ist der Plan gesperrt, kennen wir seinen Beginn nicht: dann lieber nichts Altes löschen.
        if (!plan.bearbeitbar) return
        val teamIds = if (team.bearbeitbar && team.team.isNotEmpty()) team.team.map { it.id }.toSet() else null
        speicher.wuensche.aufraeumen(teamIds, WishCleanup.keepFrom(heute, plan.plan))
    }
}
