package de.reichimd.schichtplaner.core.team

import de.reichimd.schichtplaner.core.Employee
import de.reichimd.schichtplaner.core.Problem
import java.time.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TeamTest {

    private val anna = Employee("1", "Anna", 120)
    private val ben = Employee("2", "Ben", 80, canEarly = false)

    @Test
    fun gueltigerMitarbeiterHatKeineProbleme() {
        assertEquals(emptyList(), Team.problems(anna, listOf(anna, ben)))
    }

    @Test
    fun leererNameWirdAbgelehnt() {
        val p = Team.problems(anna.copy(name = "   "), emptyList())
        assertEquals(listOf(Problem(TeamField.NAME, "Bitte einen Vornamen eingeben.")), p)
    }

    @Test
    fun zuLangerNameWirdAbgelehnt() {
        val p = Team.problems(anna.copy(name = "A".repeat(Team.MAX_NAME_LENGTH + 1)), emptyList())
        assertEquals(1, p.size)
        assertTrue(p[0].message.contains("zu lang"))
        assertEquals(TeamField.NAME, p[0].field)
    }

    @Test
    fun doppelterNameOhneGrossKleinschreibungWirdAbgelehnt() {
        val neu = Employee("3", " anna ", 40)
        val p = Team.problems(neu, listOf(anna, ben))
        assertEquals(1, p.size)
        assertTrue(p[0].message.contains("gibt es schon"))
    }

    @Test
    fun eigenerNameBeimBearbeitenIstKeinDoppelter() {
        assertEquals(emptyList(), Team.problems(anna.copy(monthlyHours = 100), listOf(anna, ben)))
    }

    @Test
    fun stundenAusserhalbDesBereichsWerdenAbgelehnt() {
        assertEquals(1, Team.problems(anna.copy(monthlyHours = -5), emptyList()).size)
        assertEquals(1, Team.problems(anna.copy(monthlyHours = Team.MAX_HOURS + 1), emptyList()).size)
        assertEquals(0, Team.problems(anna.copy(monthlyHours = 0), emptyList()).size)
        assertEquals(0, Team.problems(anna.copy(monthlyHours = Team.MAX_HOURS), emptyList()).size)
    }

    @Test
    fun wederFruehNochSpaetWirdAbgelehnt() {
        val p = Team.problems(anna.copy(canEarly = false, canLate = false), emptyList())
        assertEquals(1, p.size)
        assertEquals(TeamField.SHIFTS, p[0].field)
        assertTrue(p[0].message.contains("nie eingeplant"))
    }

    @Test
    fun alleTageGesperrtWirdAbgelehnt() {
        val p = Team.problems(anna.copy(blockedWeekdays = DayOfWeek.entries.toSet()), emptyList())
        assertEquals(1, p.size)
        assertEquals(TeamField.DAYS, p[0].field)
    }

    @Test
    fun nameWirdBereinigt() {
        assertEquals("Anna Lena", Team.cleanName("  Anna   Lena "))
    }

    @Test
    fun stundenWerdenBegrenzt() {
        assertEquals(0, Team.clampHours(-10))
        assertEquals(Team.MAX_HOURS, Team.clampHours(999))
        assertEquals(80, Team.clampHours(80))
    }

    @Test
    fun upsertHaengtNeueHintenAnUndErsetztAnDerStelle() {
        val clara = Employee("3", " Clara ", 40)
        val team = Team.upsert(listOf(anna, ben), clara)
        assertEquals(listOf("1", "2", "3"), team.map { it.id })
        assertEquals("Clara", team[2].name)

        val geaendert = Team.upsert(team, anna.copy(monthlyHours = 100))
        assertEquals(listOf("1", "2", "3"), geaendert.map { it.id })
        assertEquals(100, geaendert[0].monthlyHours)
    }

    @Test
    fun entfernenLoeschtNurDiePerson() {
        assertEquals(listOf(ben), Team.remove(listOf(anna, ben), "1"))
        assertEquals(listOf(anna, ben), Team.remove(listOf(anna, ben), "gibt-es-nicht"))
    }

    @Test
    fun merkmaleFuerDieListe() {
        assertEquals(listOf("Früh und spät"), Team.tags(anna))
        assertEquals(listOf("Nur spät"), Team.tags(ben))
        val finn = Employee(
            "4", "Finn", 40,
            canLate = false,
            blockedWeekdays = setOf(DayOfWeek.TUESDAY, DayOfWeek.MONDAY),
            isBackup = true,
        )
        assertEquals(listOf("Nur früh", "Nie Mo, Di", "Aushilfe"), Team.tags(finn))
    }

    @Test
    fun beispielTeamIstGueltig() {
        assertEquals(8, Team.EXAMPLE.size)
        assertEquals(Team.EXAMPLE.size, Team.EXAMPLE.map { it.id }.toSet().size)
        for (e in Team.EXAMPLE) {
            assertEquals(emptyList(), Team.problems(e, Team.EXAMPLE), e.name)
        }
    }

    @Test
    fun reihenfolgeAendern() {
        val clara = Employee("3", "Clara", 100)
        val team = listOf(anna, ben, clara)
        assertEquals(listOf(anna, clara, ben), Team.move(team, "3", up = true))
        assertEquals(listOf(ben, anna, clara), Team.move(team, "1", up = false))
        // Am Rand und bei unbekannter ID passiert nichts.
        assertEquals(team, Team.move(team, "1", up = true))
        assertEquals(team, Team.move(team, "3", up = false))
        assertEquals(team, Team.move(team, "x", up = true))
    }
}
