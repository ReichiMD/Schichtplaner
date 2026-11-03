package de.reichimd.schichtplaner.team

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.reichimd.schichtplaner.core.Employee
import de.reichimd.schichtplaner.core.team.Team
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.DayOfWeek

/** Speichern der Teamliste auf einem echten Android (Emulator in GitHub Actions). */
@RunWith(AndroidJUnit4::class)
class TeamStoreOnDeviceTest {

    private lateinit var dir: File

    @Before
    fun ordner() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        dir = File(ctx.cacheDir, "teamtest-${System.nanoTime()}").also { it.mkdirs() }
    }

    @After
    fun aufraeumen() {
        dir.deleteRecursively()
    }

    private fun warte(store: TeamStore) = runBlocking { withTimeout(10_000) { store.warten() } }

    @Test
    fun ohneDateiIstDasTeamLeer() {
        val z = warte(TeamStore(dir))
        assertTrue(z.team.isEmpty())
        assertTrue(z.bearbeitbar)
        assertEquals(null, z.meldung)
    }

    @Test
    fun gespeichertesTeamIstNachNeustartWiederDa() {
        val store = TeamStore(dir)
        warte(store)
        val anna = Employee("a", " Anna ", 120, canEarly = false, blockedWeekdays = setOf(DayOfWeek.MONDAY))
        assertEquals(emptyList<String>(), store.speichern(anna))
        assertEquals(emptyList<String>(), store.speichern(Employee("b", "Ben", 80, isBackup = true)))
        store.loeschen("b")
        assertEquals(emptyList<String>(), store.speichern(Employee("c", "Clara", 40)))
        warte(store)

        // Neue Instanz = wie nach einem Neustart der App.
        val danach = warte(TeamStore(dir))
        assertEquals(listOf("Anna", "Clara"), danach.team.map { it.name })
        assertEquals(setOf(DayOfWeek.MONDAY), danach.team[0].blockedWeekdays)
        assertFalse(danach.team[0].canEarly)
    }

    @Test
    fun ungueltigeEingabenWerdenNichtGespeichert() {
        val store = TeamStore(dir)
        warte(store)
        assertTrue(store.speichern(Employee("a", "", 120)).isNotEmpty())
        assertEquals(emptyList<String>(), store.speichern(Employee("a", "Anna", 120)))
        assertTrue(store.speichern(Employee("b", "anna", 80)).isNotEmpty())
        assertEquals(1, warte(store).team.size)
    }

    @Test
    fun vieleSchnelleAenderungenLandenAlleInDerDatei() {
        val store = TeamStore(dir)
        warte(store)
        repeat(200) { i -> store.speichern(Employee("id$i", "Name$i", i % 200)) }
        warte(store)
        assertEquals(200, warte(TeamStore(dir)).team.size)
    }

    @Test
    fun beispielTeamNurBeiLeeremTeam() {
        val store = TeamStore(dir)
        warte(store)
        store.beispielLaden()
        assertEquals(Team.EXAMPLE.map { it.name }, warte(store).team.map { it.name })
        store.beispielLaden()
        assertEquals(Team.EXAMPLE.size, warte(store).team.size)
        assertEquals(Team.EXAMPLE.map { it.name }, warte(TeamStore(dir)).team.map { it.name })
    }

    @Test
    fun beschaedigteDateiWirdBeiseitegelegtUndNichtUeberschrieben() {
        File(dir, TeamStore.DATEINAME).writeText("{ kaputt")
        val store = TeamStore(dir)
        val z = warte(store)
        assertTrue(z.team.isEmpty())
        assertNotNull(z.meldung)
        assertTrue(z.bearbeitbar)
        val gesichert = dir.listFiles()!!.filter { it.name.startsWith("team-beschaedigt-") }
        assertEquals(1, gesichert.size)
        assertEquals("{ kaputt", gesichert[0].readText())

        // Danach normal weiterarbeiten.
        assertEquals(emptyList<String>(), store.speichern(Employee("a", "Anna", 120)))
        warte(store)
        assertEquals(listOf("Anna"), warte(TeamStore(dir)).team.map { it.name })
    }
}
