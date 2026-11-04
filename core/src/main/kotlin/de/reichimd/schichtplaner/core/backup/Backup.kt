package de.reichimd.schichtplaner.core.backup

import de.reichimd.schichtplaner.core.Absence
import de.reichimd.schichtplaner.core.Employee
import de.reichimd.schichtplaner.core.plan.PlanCodec
import de.reichimd.schichtplaner.core.plan.YearPlan
import de.reichimd.schichtplaner.core.shop.ShopCodec
import de.reichimd.schichtplaner.core.shop.ShopSettings
import de.reichimd.schichtplaner.core.storage.DataFileException
import de.reichimd.schichtplaner.core.storage.DataFileTooNewException
import de.reichimd.schichtplaner.core.team.Team
import de.reichimd.schichtplaner.core.team.TeamCodec
import de.reichimd.schichtplaner.core.wishes.WishesCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.time.LocalDate
import java.time.LocalDateTime

/** Alles, was die App auf dem Handy speichert, in einer Sicherungsdatei. */
data class BackupData(
    val team: List<Employee>,
    val shop: ShopSettings,
    val wishes: List<Absence>,
    val plan: YearPlan,
    /** Wann die Sicherung erstellt wurde (nur zur Anzeige). */
    val created: LocalDateTime? = null,
    /** Welche App-Version sie geschrieben hat (nur zur Anzeige). */
    val appVersion: String? = null,
)

/**
 * Sicherungsdatei: eine JSON-Datei mit den vier gespeicherten Dateien der App (Team, Laden, Wünsche, Plan).
 * Jeder Teil steht im selben Format wie auf dem Handy und wird beim Einlesen von seinem eigenen Codec geprüft.
 * So bleibt eine alte Sicherung lesbar, solange die einzelnen Dateien es sind.
 */
object BackupCodec {

    const val FORMAT = "schichtplaner-sicherung"
    const val VERSION = 1

    /** Größer wird eine echte Sicherung nie (10 Personen, Jahre an Wünschen: einige hundert KB). */
    const val MAX_BYTES = 5L * 1024 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    @Serializable
    private data class FileV1(
        val format: String? = null,
        val version: Int = 0,
        val created: String? = null,
        val appVersion: String? = null,
        val team: JsonElement? = null,
        val laden: JsonElement? = null,
        val wuensche: JsonElement? = null,
        val plan: JsonElement? = null,
    )

    fun encode(data: BackupData): String = json.encodeToString(
        FileV1.serializer(),
        FileV1(
            format = FORMAT,
            version = VERSION,
            created = data.created?.withNano(0)?.toString(),
            appVersion = data.appVersion,
            team = json.parseToJsonElement(TeamCodec.encode(data.team)),
            laden = json.parseToJsonElement(ShopCodec.encode(data.shop)),
            wuensche = json.parseToJsonElement(WishesCodec.encode(data.wishes)),
            plan = json.parseToJsonElement(PlanCodec.encodeYear(data.plan)),
        ),
    )

    /**
     * Liest und prüft eine Sicherung. Gelesen wird alles oder nichts.
     * @throws DataFileException mit einer Meldung für die Filialleitung, wenn die Datei nicht passt.
     */
    fun decode(text: String): BackupData {
        val file = try {
            // Am PC gespeicherte Dateien beginnen manchmal mit einem unsichtbaren BOM-Zeichen.
            json.decodeFromString(FileV1.serializer(), text.removePrefix("\uFEFF"))
        } catch (e: SerializationException) {
            throw DataFileException("Das ist keine Sicherung des Schichtplaners.", e)
        } catch (e: IllegalArgumentException) {
            throw DataFileException("Das ist keine Sicherung des Schichtplaners.", e)
        }
        if (file.format != FORMAT) throw DataFileException("Das ist keine Sicherung des Schichtplaners.")
        if (file.version > VERSION) throw DataFileTooNewException(file.version, "Die Sicherung")
        if (file.version < 1) throw DataFileException("Die Sicherung hat eine unbekannte Version (${file.version}).")

        fun teil(el: JsonElement?, was: String): String {
            if (el == null || el is JsonNull) throw DataFileException("In der Sicherung fehlt $was.")
            return json.encodeToString(JsonElement.serializer(), el)
        }

        fun <T> lesen(was: String, block: () -> T): T = try {
            block()
        } catch (e: DataFileTooNewException) {
            throw DataFileException("$was in der Sicherung stammt aus einer neueren App-Version. Bitte zuerst die App aktualisieren.", e)
        } catch (e: DataFileException) {
            throw DataFileException("$was in der Sicherung ist beschädigt (${e.message}).", e)
        }

        val teamText = teil(file.team, "das Team")
        val ladenText = teil(file.laden, "der Laden")
        val wuenscheText = teil(file.wuensche, "der Wünsche-Kalender")
        // Ein fehlender Plan ist erlaubt (es gab noch keinen).
        val planText = file.plan?.takeIf { it !is JsonNull }?.let { teil(it, "der Plan") }

        val team = lesen("Das Team") { TeamCodec.decode(teamText) }
        val shop = lesen("Der Laden") { ShopCodec.decode(ladenText) }
        val wishes = lesen("Der Wünsche-Kalender") { WishesCodec.decode(wuenscheText) }
        // Sicherungen aus 0.1.8 haben den Plan noch im alten Format, decodeYear liest beide.
        val plan = lesen("Der Plan") { planText?.let { PlanCodec.decodeYear(it) } ?: YearPlan.EMPTY }

        // Dieselben Regeln wie beim Speichern einer Person, sonst stünden Werte drin, die sich nicht mehr speichern lassen.
        team.forEach { e ->
            // Ausgeschiedene nur für sich prüfen: Ihr Name darf inzwischen wieder vergeben sein.
            Team.problems(e, if (e.archived) emptyList() else team).firstOrNull()?.let {
                throw DataFileException("Das Team in der Sicherung passt nicht (${e.name.ifBlank { "ohne Namen" }}: ${it.message})")
            }
        }
        // Fehler im Laden werden wie beim normalen Laden angenommen: Der Laden-Reiter zeigt sie rot zum Korrigieren.

        return BackupData(
            team = team,
            shop = shop,
            wishes = wishes,
            plan = plan,
            created = file.created?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() },
            appVersion = file.appVersion,
        )
    }

    /** Vorschlag für den Dateinamen, z. B. "schichtplaner-sicherung-2026-11-03.json". */
    fun fileName(day: LocalDate): String = "$FORMAT-$day.json"
}
