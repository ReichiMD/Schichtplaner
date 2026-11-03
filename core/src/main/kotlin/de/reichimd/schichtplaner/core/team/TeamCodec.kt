package de.reichimd.schichtplaner.core.team

import de.reichimd.schichtplaner.core.Employee
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.DayOfWeek

/** Die gespeicherte Teamliste ist unlesbar oder stammt aus einer neueren App-Version. */
class TeamFileException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Speicherformat der Teamliste auf dem Handy (JSON).
 *
 * Das Format hat eine Versionsnummer. Neue Felder bekommen einen Standardwert, damit alte Dateien
 * lesbar bleiben. Unbekannte Felder werden überlesen, damit ein späteres Format nicht alles kaputt macht.
 */
object TeamCodec {

    const val VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    @Serializable
    private data class FileV1(
        val version: Int,
        val employees: List<StoredEmployee> = emptyList(),
    )

    @Serializable
    private data class StoredEmployee(
        val id: String,
        val name: String,
        val monthlyHours: Int,
        val canEarly: Boolean = true,
        val canLate: Boolean = true,
        /** Wochentage als 1 (Montag) bis 7 (Sonntag), unabhängig von Sprache und Enum-Namen. */
        val blockedWeekdays: List<Int> = emptyList(),
        val isBackup: Boolean = false,
    )

    fun encode(team: List<Employee>): String = json.encodeToString(
        FileV1.serializer(),
        FileV1(
            version = VERSION,
            employees = team.map {
                StoredEmployee(
                    id = it.id,
                    name = it.name,
                    monthlyHours = it.monthlyHours,
                    canEarly = it.canEarly,
                    canLate = it.canLate,
                    blockedWeekdays = it.blockedWeekdays.map(DayOfWeek::getValue).sorted(),
                    isBackup = it.isBackup,
                )
            },
        ),
    )

    /** @throws TeamFileException wenn der Text keine gültige Teamliste ist. */
    fun decode(text: String): List<Employee> {
        val file = try {
            json.decodeFromString(FileV1.serializer(), text)
        } catch (e: SerializationException) {
            throw TeamFileException("Teamliste ist kein gültiges Format", e)
        } catch (e: IllegalArgumentException) {
            throw TeamFileException("Teamliste ist kein gültiges Format", e)
        }
        if (file.version > VERSION) {
            throw TeamFileException("Teamliste stammt aus einer neueren App-Version (${file.version})")
        }
        if (file.version < 1) throw TeamFileException("Unbekannte Version ${file.version}")

        val ids = file.employees.map { it.id }
        if (ids.any { it.isBlank() }) throw TeamFileException("Mitarbeiter ohne ID")
        if (ids.size != ids.toSet().size) throw TeamFileException("Doppelte Mitarbeiter-ID")

        return file.employees.map { s ->
            if (s.monthlyHours < 0) throw TeamFileException("Negative Monatsstunden bei ${s.id}")
            Employee(
                id = s.id,
                name = s.name,
                monthlyHours = s.monthlyHours,
                canEarly = s.canEarly,
                canLate = s.canLate,
                blockedWeekdays = s.blockedWeekdays.map { day ->
                    if (day !in 1..7) throw TeamFileException("Ungültiger Wochentag $day bei ${s.id}")
                    DayOfWeek.of(day)
                }.toSet(),
                isBackup = s.isBackup,
            )
        }
    }
}
