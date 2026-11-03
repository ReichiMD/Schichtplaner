package de.reichimd.schichtplaner.core.team

import de.reichimd.schichtplaner.core.Employee
import java.time.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TeamCodecTest {

    private val team = listOf(
        Employee("a1", "Anna", 120),
        Employee("b2", "Ben", 80, canEarly = false, blockedWeekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY)),
        Employee("c3", "Jürgen-Ömer", 40, canLate = false, isBackup = true),
    )

    @Test
    fun hinUndZurueckErgibtDasGleicheTeam() {
        assertEquals(team, TeamCodec.decode(TeamCodec.encode(team)))
    }

    @Test
    fun leeresTeam() {
        assertEquals(emptyList(), TeamCodec.decode(TeamCodec.encode(emptyList())))
    }

    @Test
    fun reihenfolgeBleibtErhalten() {
        val umgedreht = team.reversed()
        assertEquals(umgedreht.map { it.id }, TeamCodec.decode(TeamCodec.encode(umgedreht)).map { it.id })
    }

    @Test
    fun wochentageWerdenAlsZahlenGespeichert() {
        val text = TeamCodec.encode(team)
        assertTrue(text.contains("\"version\": 1"))
        assertTrue(Regex("\"blockedWeekdays\": \\[\\s*1,\\s*7\\s*]").containsMatchIn(text), text)
    }

    @Test
    fun fehlendeFelderBekommenStandardwerte() {
        val alt = """{"version":1,"employees":[{"id":"x","name":"Ida","monthlyHours":100}]}"""
        assertEquals(listOf(Employee("x", "Ida", 100)), TeamCodec.decode(alt))
    }

    @Test
    fun unbekannteFelderWerdenUeberlesen() {
        val neuer = """{"version":1,"farbe":"rot","employees":[{"id":"x","name":"Ida","monthlyHours":100,"spitzname":"I"}]}"""
        assertEquals(listOf(Employee("x", "Ida", 100)), TeamCodec.decode(neuer))
    }

    @Test
    fun kaputteDateienWerdenErkannt() {
        val kaputt = listOf(
            "",
            "nicht json",
            "{\"version\":1,\"employees\":[{\"id\":\"x\",\"name\":\"Ida\"",
            """{"employees":[]}""",
            """{"version":0,"employees":[]}""",
            """{"version":1,"employees":[{"id":"x","name":"Ida","monthlyHours":"viel"}]}""",
            """{"version":1,"employees":[{"id":"x","name":"Ida","monthlyHours":-1}]}""",
            """{"version":1,"employees":[{"id":"","name":"Ida","monthlyHours":1}]}""",
            """{"version":1,"employees":[{"id":"x","name":"A","monthlyHours":1},{"id":"x","name":"B","monthlyHours":1}]}""",
            """{"version":1,"employees":[{"id":"x","name":"Ida","monthlyHours":1,"blockedWeekdays":[8]}]}""",
            """[1,2,3]""",
        )
        for (text in kaputt) {
            assertFailsWith<TeamFileException>("sollte abgelehnt werden: $text") { TeamCodec.decode(text) }
        }
    }

    @Test
    fun neuereVersionWirdNichtStillschweigendGelesen() {
        val e = assertFailsWith<TeamFileException> {
            TeamCodec.decode("""{"version":2,"employees":[]}""")
        }
        assertTrue(e.message!!.contains("neueren"))
    }

    @Test
    fun vieleMitarbeiter() {
        val viele = (1..500).map { Employee("id$it", "Name$it", it % 200) }
        assertEquals(viele, TeamCodec.decode(TeamCodec.encode(viele)))
    }
}
