package de.reichimd.schichtplaner.core.backup

import de.reichimd.schichtplaner.core.Absence
import de.reichimd.schichtplaner.core.AbsenceType
import de.reichimd.schichtplaner.core.Employee
import de.reichimd.schichtplaner.core.ShiftKind
import de.reichimd.schichtplaner.core.TestData
import de.reichimd.schichtplaner.core.plan.PlanCodec
import de.reichimd.schichtplaner.core.plan.PlanSlot
import de.reichimd.schichtplaner.core.plan.ShiftPlan
import de.reichimd.schichtplaner.core.plan.SlotKey
import de.reichimd.schichtplaner.core.shop.Shop
import de.reichimd.schichtplaner.core.storage.DataFileException
import de.reichimd.schichtplaner.core.storage.DataFileTooNewException
import de.reichimd.schichtplaner.core.team.TeamCodec
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupTest {

    private val mo: LocalDate = TestData.MONDAY_START
    private val anna = Employee("a", "Anna", 120)
    private val ben = Employee("b", "Ben", 80, canEarly = false, isBackup = true)

    private val plan = ShiftPlan(
        firstDay = mo,
        days = 14,
        slots = listOf(
            PlanSlot(SlotKey(mo, "fa", 0), "Früh A", ShiftKind.EARLY, mo.atTime(5, 0), mo.atTime(12, 0), "a"),
            PlanSlot(SlotKey(mo, "sp", 0), "Spät", ShiftKind.LATE, mo.atTime(13, 0), mo.atTime(18, 30), null),
        ),
    )

    private val daten = BackupData(
        team = listOf(anna, ben),
        shop = Shop.EXAMPLE.copy(planWeeks = 4),
        // Sortiert nach Datum und Person, wie der Kalender sie speichert.
        wishes = listOf(Absence("b", mo, AbsenceType.DAY_OFF_WISH), Absence("a", mo.plusDays(2), AbsenceType.VACATION)),
        plan = plan,
        created = LocalDateTime.of(2026, 11, 3, 22, 15, 7),
        appVersion = "0.1.8",
    )

    @Test
    fun hinUndZurueck() {
        assertEquals(daten, BackupCodec.decode(BackupCodec.encode(daten)))
    }

    @Test
    fun ohnePlanUndOhneZeitstempel() {
        val ohne = daten.copy(plan = null, created = null, appVersion = null)
        val zurueck = BackupCodec.decode(BackupCodec.encode(ohne))
        assertEquals(ohne, zurueck)
        assertNull(zurueck.plan)
    }

    @Test
    fun leeresTeamIstErlaubt() {
        val leer = daten.copy(team = emptyList(), wishes = emptyList(), plan = null)
        assertEquals(leer, BackupCodec.decode(BackupCodec.encode(leer)))
    }

    @Test
    fun teileStehenImGleichenFormatWieAufDemHandy() {
        val text = BackupCodec.encode(daten)
        assertTrue("\"format\": \"schichtplaner-sicherung\"" in text)
        assertTrue("\"employees\"" in text)
        assertTrue("\"entries\"" in text)
        assertTrue("\"slots\"" in text)
    }

    @Test
    fun falscheDateienWerdenVerstaendlichAbgelehnt() {
        val keine = "Das ist keine Sicherung des Schichtplaners."
        assertEquals(keine, assertFailsWith<DataFileException> { BackupCodec.decode("") }.message)
        assertEquals(keine, assertFailsWith<DataFileException> { BackupCodec.decode("hallo") }.message)
        assertEquals(keine, assertFailsWith<DataFileException> { BackupCodec.decode("[1,2]") }.message)
        // Eine einzelne Datei der App ist keine Sicherung.
        assertEquals(keine, assertFailsWith<DataFileException> { BackupCodec.decode(TeamCodec.encode(listOf(anna))) }.message)
        assertEquals(keine, assertFailsWith<DataFileException> { BackupCodec.decode(PlanCodec.encode(plan)) }.message)
    }

    @Test
    fun neuereSicherungWirdAbgelehnt() {
        val text = BackupCodec.encode(daten).replace("\"version\": 1,\n    \"created\"", "\"version\": 99,\n    \"created\"")
        assertTrue("\"version\": 99" in text)
        assertFailsWith<DataFileTooNewException> { BackupCodec.decode(text) }
    }

    @Test
    fun fehlenderTeilWirdAbgelehnt() {
        val text = """{"format":"schichtplaner-sicherung","version":1,"laden":null}"""
        val e = assertFailsWith<DataFileException> { BackupCodec.decode(text) }
        assertTrue("fehlt das Team" in e.message!!, e.message)
    }

    @Test
    fun neuererTeilWirdMitHinweisAufUpdateAbgelehnt() {
        val text = BackupCodec.encode(daten).replace(TEAM_VERSION, TEAM_VERSION.replace("1", "7"))
        val e = assertFailsWith<DataFileException> { BackupCodec.decode(text) }
        assertTrue("neueren App-Version" in e.message!!, e.message)
    }

    @Test
    fun beschaedigterTeilWirdAbgelehnt() {
        val text = BackupCodec.encode(daten).replace("\"monthlyHours\": 120", "\"monthlyHours\": -5")
        val e = assertFailsWith<DataFileException> { BackupCodec.decode(text) }
        assertTrue(e.message!!.startsWith("Das Team in der Sicherung ist beschädigt"), e.message)
    }

    @Test
    fun werteDieSichNichtSpeichernLassenWerdenAbgelehnt() {
        // Über 250 h wäre in der App nicht speicherbar.
        val text = BackupCodec.encode(daten).replace("\"monthlyHours\": 120", "\"monthlyHours\": 400")
        val e = assertFailsWith<DataFileException> { BackupCodec.decode(text) }
        assertTrue("Anna" in e.message!! && "250" in e.message!!, e.message)

        val doppelt = daten.copy(team = listOf(anna, ben.copy(name = "anna")))
        assertFailsWith<DataFileException> { BackupCodec.decode(BackupCodec.encode(doppelt)) }

    }

    @Test
    fun ladenMitFehlernWirdWieBeimNormalenLadenAngenommen() {
        // Die App zeigt solche Fehler im Laden-Reiter an; die Sicherung darf daran nicht scheitern.
        val fehlerhaft = daten.copy(shop = Shop.EXAMPLE.copy(planWeeks = 3))
        assertEquals(fehlerhaft, BackupCodec.decode(BackupCodec.encode(fehlerhaft)))
    }

    @Test
    fun unbekannteFelderWerdenUeberlesen() {
        val text = BackupCodec.encode(daten).replaceFirst("{", "{\"neuesFeld\": [1, 2],")
        assertEquals(daten, BackupCodec.decode(text))
    }

    @Test
    fun dateiname() {
        assertEquals("schichtplaner-sicherung-2026-11-03.json", BackupCodec.fileName(LocalDate.of(2026, 11, 3)))
    }

    // ---------------------------------------------------------------- Aufräumen

    @Test
    fun behaltenAbVorjahrOderPlanbeginn() {
        val heute = LocalDate.of(2026, 11, 3)
        assertEquals(LocalDate.of(2025, 1, 1), WishCleanup.keepFrom(heute, null))
        assertEquals(LocalDate.of(2025, 1, 1), WishCleanup.keepFrom(heute, plan))
        val alterPlan = plan.copy(firstDay = LocalDate.of(2024, 12, 30), slots = emptyList())
        assertEquals(LocalDate.of(2024, 12, 30), WishCleanup.keepFrom(heute, alterPlan))
    }

    @Test
    fun aufraeumenEntferntAlteUndVerwaisteEintraege() {
        val ab = LocalDate.of(2025, 1, 1)
        val eintraege = listOf(
            Absence("a", LocalDate.of(2024, 12, 31), AbsenceType.VACATION),
            Absence("a", ab, AbsenceType.VACATION),
            Absence("weg", mo, AbsenceType.SICK),
            Absence("b", mo, AbsenceType.DAY_OFF_WISH),
        )
        assertEquals(
            listOf(Absence("a", ab, AbsenceType.VACATION), Absence("b", mo, AbsenceType.DAY_OFF_WISH)),
            WishCleanup.clean(eintraege, setOf("a", "b"), ab),
        )
    }

    @Test
    fun ohneBekanntesTeamBleibenAllePersonen() {
        val ab = LocalDate.of(2025, 1, 1)
        val eintraege = listOf(Absence("weg", mo, AbsenceType.SICK), Absence("a", LocalDate.of(2020, 5, 5), AbsenceType.SICK))
        assertEquals(listOf(Absence("weg", mo, AbsenceType.SICK)), WishCleanup.clean(eintraege, null, ab))
    }

    private companion object {
        /** So steht die Version der Teamliste in der Sicherung (eingerückt im Teil "team"). */
        const val TEAM_VERSION = "\"version\": 1,\n        \"employees\""
    }
}
