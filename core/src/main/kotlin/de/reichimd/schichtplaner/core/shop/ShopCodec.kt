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

    /**
     * 2: Die Tageszeiten sind die Arbeitszeit (erster Beginn bis Feierabend), nicht mehr die Öffnungszeit.
     * Dateien mit Version 1 werden beim Lesen umgerechnet, die Schichtzeiten bleiben dabei gleich.
     */
    const val VERSION = 2

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
     * @throws DataFileException wenn der Text kein gültiges Format hat. Regelverstöße (siehe [Shop.problems])
     * werden NICHT abgelehnt: Wird eine Regel in einer späteren Version strenger, sollen gespeicherte
     * Einstellungen nicht als "beschädigt" beiseitegelegt werden. Die App zeigt solche Probleme an.
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
        val settings = readSettings(file)
        return if (file.version == 1) fromOpeningHours(settings) else settings
    }

    private fun readSettings(file: FileV1): ShopSettings {
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
        val ids = templates.map { it.id }
        if (ids.any { it.isBlank() } || ids.size != ids.toSet().size) throw DataFileException("Schicht-Kennung fehlt oder doppelt")
        return ShopSettings(openingHours, templates, file.planWeeks)
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

    /**
     * Version 1 hatte Öffnungszeiten, und die Spätschicht endete z. B. "30 min nach Ladenschluss".
     * Umgerechnet auf Arbeitszeit: Feierabend = Ladenschluss + größter Nachlauf, Arbeitsbeginn = frühester
     * fester Schichtbeginn. Relative Zeiten werden so verschoben, dass jede Schicht genau gleich liegt.
     */
    private fun fromOpeningHours(old: ShopSettings): ShopSettings {
        val shift = old.templates.mapNotNull { (it.end as? ShiftEnd.AfterClose)?.minutes }.maxOrNull()?.coerceAtLeast(0) ?: 0L
        val hours = old.openingHours.mapValues { (day, h) ->
            val latest = LocalTime.of(23, 45)
            val close = if (h.close.isAfter(latest.minusMinutes(shift))) h.close else h.close.plusMinutes(shift)
            val realShift = java.time.Duration.between(h.close, close).toMinutes()
            val earliest = old.templates.filter { day in it.weekdays }
                .mapNotNull { (it.start as? ShiftStart.At)?.time }
                .minOrNull()
            val open = if (earliest != null && earliest.isBefore(h.open)) earliest else h.open
            DayHours(open, close) to realShift
        }
        // Feierabend nur verschieben, wenn das für jede Schicht genau aufgeht: alle Tage gleich weit und alle
        // relativen Enden mit demselben Nachlauf. Sonst bleibt der Feierabend wie der alte Ladenschluss
        // (Schichten liegen dann trotzdem gleich, nur mit Nachlauf nach Arbeitsende).
        val shifts = hours.values.map { it.second }.toSet()
        val afterCloses = old.templates.mapNotNull { (it.end as? ShiftEnd.AfterClose)?.minutes }.toSet()
        val beforeOk = old.templates.all { t ->
            val st = t.start
            st !is ShiftStart.BeforeClose || st.minutes + shift <= Shop.MAX_BEFORE_CLOSE_MINUTES
        }
        if (shifts.size > 1 || afterCloses.size > 1 || !beforeOk) {
            return old.copy(openingHours = hours.mapValues { (d, v) -> DayHours(v.first.open, old.openingHours.getValue(d).close) })
        }
        val delta = shifts.singleOrNull() ?: 0L
        return old.copy(
            openingHours = hours.mapValues { it.value.first },
            templates = old.templates.map { t ->
                t.copy(
                    start = when (val st = t.start) {
                        is ShiftStart.BeforeClose -> ShiftStart.BeforeClose(st.minutes + delta)
                        is ShiftStart.At -> st
                    },
                    end = when (val e = t.end) {
                        is ShiftEnd.AfterClose -> ShiftEnd.AfterClose(e.minutes - delta)
                        is ShiftEnd.At -> e
                    },
                )
            },
        )
    }
}
