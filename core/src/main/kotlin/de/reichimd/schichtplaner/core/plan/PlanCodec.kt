package de.reichimd.schichtplaner.core.plan

import de.reichimd.schichtplaner.core.ShiftKind
import de.reichimd.schichtplaner.core.storage.DataFileException
import de.reichimd.schichtplaner.core.storage.DataFileTooNewException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

/**
 * Speicherformat des fertigen Plans auf dem Handy (plan.json), samt aller Änderungen von Hand.
 * Datum und Zeit als ISO-Text, Schichtart als "early"/"late". Leere Datei = noch kein Plan.
 */
object PlanCodec {

    /** Format eines einzelnen Zeitraums (bis Version 0.1.8 so in plan.json). */
    const val VERSION = 1

    /** plan.json ab Version 0.1.9: alle Wochen als eine Liste von Schichten. Liest auch Version 1. */
    const val YEAR_VERSION = 2

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    @Serializable
    private data class FileV1(
        val version: Int,
        /** null: Es gibt (noch) keinen Plan. */
        val plan: StoredPlan? = null,
    )

    @Serializable
    private data class StoredPlan(
        val firstDay: String,
        val days: Int,
        val slots: List<StoredSlot> = emptyList(),
    )

    @Serializable
    private data class StoredSlot(
        val date: String,
        val templateId: String,
        val slot: Int,
        val name: String,
        val kind: String,
        val start: String,
        val end: String,
        val employeeId: String? = null,
    )

    @Serializable
    private data class FileAny(
        val version: Int,
        val plan: StoredPlan? = null,
        val slots: List<StoredSlot> = emptyList(),
    )

    private fun stored(it: PlanSlot) = StoredSlot(
        date = it.date.toString(),
        templateId = it.key.templateId,
        slot = it.key.slot,
        name = it.name,
        kind = kindCode(it.kind),
        start = it.start.toString(),
        end = it.end.toString(),
        employeeId = it.employeeId,
    )

    private fun slot(s: StoredSlot): PlanSlot {
        if (s.templateId.isBlank()) throw DataFileException("Schicht ohne Vorlage")
        val kind = when (s.kind) {
            "early" -> ShiftKind.EARLY
            "late" -> ShiftKind.LATE
            else -> throw DataFileException("Unbekannte Schichtart ${s.kind}")
        }
        val date = LocalDate.parse(s.date)
        val start = LocalDateTime.parse(s.start)
        val end = LocalDateTime.parse(s.end)
        if (start.toLocalDate() != date) throw DataFileException("Schicht beginnt nicht an ihrem Tag")
        return PlanSlot(
            key = SlotKey(date, s.templateId, s.slot),
            name = s.name,
            kind = kind,
            start = start,
            end = end,
            employeeId = s.employeeId?.takeIf { it.isNotBlank() },
        )
    }

    fun encodeYear(plan: YearPlan): String = json.encodeToString(
        FileAny.serializer(),
        FileAny(version = YEAR_VERSION, slots = plan.slots.sortedWith(ShiftPlan.ORDER).map(::stored)),
    )

    /** Liest plan.json in beiden Formaten. @throws DataFileException wenn der Text kein gültiger Plan ist. */
    fun decodeYear(text: String): YearPlan {
        val file = try {
            json.decodeFromString(FileAny.serializer(), text)
        } catch (e: SerializationException) {
            throw DataFileException("Plan ist kein gültiges Format", e)
        } catch (e: IllegalArgumentException) {
            throw DataFileException("Plan ist kein gültiges Format", e)
        }
        if (file.version > YEAR_VERSION) throw DataFileTooNewException(file.version, "Plan")
        if (file.version < 1) throw DataFileException("Unbekannte Version ${file.version}")
        // Version 1: ein Zeitraum, mit dessen eigenen Prüfungen.
        if (file.version == 1) return YearPlan.of(decode(text))
        return try {
            YearPlan(file.slots.map(::slot))
        } catch (e: DateTimeParseException) {
            throw DataFileException("Ungültiges Datum im Plan", e)
        } catch (e: IllegalArgumentException) {
            throw DataFileException("Plan ist ungültig: ${e.message}", e)
        }
    }

    private fun kindCode(k: ShiftKind) = when (k) {
        ShiftKind.EARLY -> "early"
        ShiftKind.LATE -> "late"
    }

    fun encode(plan: ShiftPlan?): String = json.encodeToString(
        FileV1.serializer(),
        FileV1(
            version = VERSION,
            plan = plan?.let { p ->
                StoredPlan(
                    firstDay = p.firstDay.toString(),
                    days = p.days,
                    slots = p.slots.sortedWith(ShiftPlan.ORDER).map(::stored),
                )
            },
        ),
    )

    /** @throws DataFileException wenn der Text kein gültiger Plan ist. */
    fun decode(text: String): ShiftPlan? {
        val file = try {
            json.decodeFromString(FileV1.serializer(), text)
        } catch (e: SerializationException) {
            throw DataFileException("Plan ist kein gültiges Format", e)
        } catch (e: IllegalArgumentException) {
            throw DataFileException("Plan ist kein gültiges Format", e)
        }
        if (file.version > VERSION) throw DataFileTooNewException(file.version, "Plan")
        if (file.version < 1) throw DataFileException("Unbekannte Version ${file.version}")
        val p = file.plan ?: return null

        return try {
            ShiftPlan(
                firstDay = LocalDate.parse(p.firstDay),
                days = p.days,
                slots = p.slots.map(::slot),
            )
        } catch (e: DateTimeParseException) {
            throw DataFileException("Ungültiges Datum im Plan", e)
        } catch (e: IllegalArgumentException) {
            // Prüfungen von ShiftPlan (doppelte Schicht, Ende vor Beginn, außerhalb des Zeitraums).
            throw DataFileException("Plan ist ungültig: ${e.message}", e)
        }
    }
}
