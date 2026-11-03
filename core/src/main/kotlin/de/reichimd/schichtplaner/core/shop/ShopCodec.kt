package de.reichimd.schichtplaner.core.shop

import de.reichimd.schichtplaner.core.ShiftEnd
import de.reichimd.schichtplaner.core.ShiftKind
import de.reichimd.schichtplaner.core.ShiftStart
import de.reichimd.schichtplaner.core.ShiftTemplate
import de.reichimd.schichtplaner.core.storage.DataFileException
import de.reichimd.schichtplaner.core.storage.DataFileTooNewException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * Speicherformat der Laden-Einstellungen auf dem Handy (JSON), mit Versionsnummer wie die Teamliste.
 * Uhrzeiten als "HH:MM", Wochentage als 1 (Montag) bis 7 (Sonntag).
 */
object ShopCodec {

    const val VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    @Serializable
    private data class FileV1(
        val version: Int,
        val openingHours: List<StoredDay> = emptyList(),
        val templates: List<StoredTemplate> = emptyList(),
        val planWeeks: Int = 2,
    )

    @Serializable
    private data class StoredDay(val day: Int, val open: String, val close: String)

    @Serializable
    private data class StoredTemplate(
        val id: String,
        val name: String,
        /** "early" oder "late". */
        val kind: String,
        /** Genau eins von beiden: feste Uhrzeit oder Minuten vor Ladenschluss. */
        val startTime: String? = null,
        val startBeforeClose: Long? = null,
        /** Genau eins von beiden: feste Uhrzeit oder Minuten nach Ladenschluss. */
        val endTime: String? = null,
        val endAfterClose: Long? = null,
        val weekdays: List<Int> = (1..7).toList(),
        val staff: Int = 1,
    )

    fun encode(settings: ShopSettings): String = json.encodeToString(
        FileV1.serializer(),
        FileV1(
            version = VERSION,
            openingHours = settings.openingHours.toSortedMap().map { (day, h) ->
                StoredDay(day.value, Shop.format(h.open), Shop.format(h.close))
            },
            templates = settings.templates.map { t ->
                StoredTemplate(
                    id = t.id,
                    name = t.name,
                    kind = when (t.kind) {
                        ShiftKind.EARLY -> "early"
                        ShiftKind.LATE -> "late"
                    },
                    startTime = (t.start as? ShiftStart.At)?.let { Shop.format(it.time) },
                    startBeforeClose = (t.start as? ShiftStart.BeforeClose)?.minutes,
                    endTime = (t.end as? ShiftEnd.At)?.let { Shop.format(it.time) },
                    endAfterClose = (t.end as? ShiftEnd.AfterClose)?.minutes,
                    weekdays = t.weekdays.map(DayOfWeek::getValue).sorted(),
                    staff = t.staff,
                )
            },
            planWeeks = settings.planWeeks,
        ),
    )

    /**
     * @throws DataFileException wenn der Text keine gültigen Laden-Einstellungen sind. Auch Einstellungen,
     * die die App selbst nie speichern würde (siehe [Shop.problems]), werden abgelehnt, damit der Planer
     * nie mit kaputten Schichten rechnet.
     */
    fun decode(text: String): ShopSettings {
        val file = try {
            json.decodeFromString(FileV1.serializer(), text)
        } catch (e: SerializationException) {
            throw DataFileException("Laden-Einstellungen sind kein gültiges Format", e)
        } catch (e: IllegalArgumentException) {
            throw DataFileException("Laden-Einstellungen sind kein gültiges Format", e)
        }
        if (file.version > VERSION) throw DataFileTooNewException(file.version, "Laden-Einstellungen")
        if (file.version < 1) throw DataFileException("Unbekannte Version ${file.version}")

        val days = file.openingHours.map { it.day }
        if (days.size != days.toSet().size) throw DataFileException("Wochentag doppelt")
        val openingHours = file.openingHours.associate { d ->
            day(d.day) to DayHours(time(d.open), time(d.close))
        }
        val templates = file.templates.map { s ->
            val kind = when (s.kind) {
                "early" -> ShiftKind.EARLY
                "late" -> ShiftKind.LATE
                else -> throw DataFileException("Unbekannte Schichtart ${s.kind}")
            }
            val start = when {
                s.startTime != null && s.startBeforeClose == null -> ShiftStart.At(time(s.startTime))
                s.startTime == null && s.startBeforeClose != null -> ShiftStart.BeforeClose(s.startBeforeClose)
                else -> throw DataFileException("Beginn von ${s.id} unklar")
            }
            val end = when {
                s.endTime != null && s.endAfterClose == null -> ShiftEnd.At(time(s.endTime))
                s.endTime == null && s.endAfterClose != null -> ShiftEnd.AfterClose(s.endAfterClose)
                else -> throw DataFileException("Ende von ${s.id} unklar")
            }
            ShiftTemplate(
                id = s.id,
                name = s.name,
                kind = kind,
                start = start,
                end = end,
                weekdays = s.weekdays.map(::day).toSet(),
                staff = s.staff,
            )
        }
        val settings = ShopSettings(openingHours, templates, file.planWeeks)
        val problems = Shop.problems(settings)
        if (problems.isNotEmpty()) {
            throw DataFileException("Laden-Einstellungen ungültig: ${problems.joinToString("; ") { it.message }}")
        }
        return settings
    }

    private fun day(value: Int): DayOfWeek {
        if (value !in 1..7) throw DataFileException("Ungültiger Wochentag $value")
        return DayOfWeek.of(value)
    }

    private val TIME = Regex("""([01]\d|2[0-3]):([0-5]\d)""")

    private fun time(text: String): LocalTime {
        val m = TIME.matchEntire(text) ?: throw DataFileException("Ungültige Uhrzeit $text")
        return LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }
}
