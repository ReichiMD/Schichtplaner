package de.reichimd.schichtplaner.core.wishes

import de.reichimd.schichtplaner.core.Absence
import de.reichimd.schichtplaner.core.AbsenceType
import de.reichimd.schichtplaner.core.storage.DataFileException
import de.reichimd.schichtplaner.core.storage.DataFileTooNewException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Speicherformat des Wünsche-Kalenders auf dem Handy (wuensche.json).
 * Datum als ISO-Text (2026-11-09), Art als Kürzel wie auf dem Papier (U, Fr, K).
 */
object WishesCodec {

    const val VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    @Serializable
    private data class FileV1(
        val version: Int,
        val entries: List<StoredEntry> = emptyList(),
    )

    @Serializable
    private data class StoredEntry(
        val employeeId: String,
        val date: String,
        val type: String,
    )

    private val BY_CODE = AbsenceType.entries.associateBy { Wishes.code(it) }

    fun encode(entries: List<Absence>): String = json.encodeToString(
        FileV1.serializer(),
        FileV1(
            version = VERSION,
            entries = Wishes.normalize(entries).map { StoredEntry(it.employeeId, it.date.toString(), Wishes.code(it.type)) },
        ),
    )

    /** @throws DataFileException wenn der Text kein gültiger Wünsche-Kalender ist. */
    fun decode(text: String): List<Absence> {
        val file = try {
            json.decodeFromString(FileV1.serializer(), text)
        } catch (e: SerializationException) {
            throw DataFileException("Wünsche-Kalender ist kein gültiges Format", e)
        } catch (e: IllegalArgumentException) {
            throw DataFileException("Wünsche-Kalender ist kein gültiges Format", e)
        }
        if (file.version > VERSION) throw DataFileTooNewException(file.version, "Wünsche-Kalender")
        if (file.version < 1) throw DataFileException("Unbekannte Version ${file.version}")

        return Wishes.normalize(
            file.entries.map { s ->
                if (s.employeeId.isBlank()) throw DataFileException("Eintrag ohne Mitarbeiter-ID")
                val date = try {
                    LocalDate.parse(s.date)
                } catch (e: DateTimeParseException) {
                    throw DataFileException("Ungültiges Datum ${s.date}", e)
                }
                val type = BY_CODE[s.type] ?: throw DataFileException("Unbekannte Art ${s.type}")
                Absence(s.employeeId, date, type)
            },
        )
    }
}
