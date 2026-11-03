package de.reichimd.schichtplaner.wuensche

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.reichimd.schichtplaner.core.Absence
import de.reichimd.schichtplaner.core.AbsenceType
import de.reichimd.schichtplaner.core.Employee
import de.reichimd.schichtplaner.team.TeamBereich
import de.reichimd.schichtplaner.team.TeamStore
import de.reichimd.schichtplaner.team.team
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate

/** Wünsche-Kalender auf einem echten Android: Speichern, Antippen, Löschen einer Person. */
@RunWith(AndroidJUnit4::class)
class WuenscheOnDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private val dir = File(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "wuensche-${System.nanoTime()}",
    ).also { it.mkdirs() }

    /** Fester Tag, damit der Test jeden Tag gleich läuft: Dienstag, 3. November 2026. Plan beginnt Montag, 9. November. */
    private val heute = LocalDate.of(2026, 11, 3)

    @After
    fun aufraeumen() {
        dir.deleteRecursively()
    }

    private fun <T> warte(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private fun teamMit(vararg e: Employee): TeamStore {
        val store = TeamStore(dir)
        warte { store.warten() }
        e.forEach { assertEquals(emptyList<Any>(), store.speichern(it)) }
        warte { store.warten() }
        return store
    }

    @Test
    fun eintraegeSindNachNeustartWiederDa() {
        val store = WuenscheStore(dir)
        warte { store.warten() }
        val tag = LocalDate.of(2026, 11, 9)
        assertTrue(store.antippen("a", tag, AbsenceType.VACATION))
        assertTrue(store.antippen("a", tag.plusDays(1), AbsenceType.DAY_OFF_WISH))
        assertTrue(store.antippen("b", tag, AbsenceType.SICK))
        // Nochmal antippen nimmt wieder weg.
        assertTrue(store.antippen("a", tag.plusDays(1), AbsenceType.DAY_OFF_WISH))
        warte { store.warten() }

        val danach = warte { WuenscheStore(dir).warten() }
        assertEquals(
            listOf(Absence("a", tag, AbsenceType.VACATION), Absence("b", tag, AbsenceType.SICK)),
            danach.eintraege,
        )
    }

    @Test
    fun beschaedigteDateiWirdBeiseitegelegt() {
        File(dir, WuenscheStore.DATEINAME).writeText("{ kaputt")
        val z = warte { WuenscheStore(dir).warten() }
        assertTrue(z.eintraege.isEmpty())
        assertTrue(z.bearbeitbar)
        assertEquals(1, dir.listFiles()!!.count { it.name.startsWith("wuensche-beschaedigt-") })
    }

    @Test
    fun ohneTeamKommtEinHinweis() {
        val team = teamMit()
        val store = WuenscheStore(dir)
        compose.setContent { WuenscheBereich(teamStore = team, store = store, heute = heute) }
        compose.onNodeWithText("Noch niemand im Team", substring = true).assertIsDisplayed()
    }

    @Test
    fun tageAntippenWieAufDemPapier() {
        val team = teamMit(Employee("a", "Ida", 120), Employee("b", "Ole", 80, blockedWeekdays = setOf(DayOfWeek.MONDAY)))
        val store = WuenscheStore(dir)
        warte { store.warten() }
        compose.setContent { WuenscheBereich(teamStore = team, store = store, heute = heute) }

        // Startet im Monat des nächsten Plans, mit der ersten Person und "Urlaub".
        compose.onNodeWithTag("monats-titel").assertTextContains("November 2026")
        compose.onNodeWithTag("tag-2026-11-09").performScrollTo().performClick()
        compose.onNodeWithTag("tag-2026-11-10").performScrollTo().performClick()
        compose.onNodeWithContentDescription("9. November, Urlaub").assertIsDisplayed()

        // Frei-Wunsch auf einen Urlaubstag ersetzt den Urlaub, nochmal antippen löscht.
        compose.onNodeWithTag("art-Frei-Wunsch").performClick()
        compose.onNodeWithTag("tag-2026-11-10").performScrollTo().performClick()
        compose.onNodeWithTag("tag-2026-11-21").performScrollTo().performClick()
        compose.onNodeWithTag("tag-2026-11-21").performScrollTo().performClick()
        compose.onNodeWithTag("zusammenfassung").performScrollTo()
        compose.onNodeWithText("1 Tag Urlaub, 1 Frei-Wunsch").assertIsDisplayed()

        // Andere Person: eigener Kalender, gesperrte Montage sind markiert.
        compose.onNodeWithTag("person-Ole").performClick()
        compose.onNodeWithContentDescription("9. November, kann nie").assertExists()
        compose.onNodeWithTag("art-Krank").performClick()
        compose.onNodeWithTag("tag-2026-11-04").performScrollTo().performClick()

        val z = warte { store.warten() }
        assertEquals(
            listOf(
                Absence("b", LocalDate.of(2026, 11, 4), AbsenceType.SICK),
                Absence("a", LocalDate.of(2026, 11, 9), AbsenceType.VACATION),
                Absence("a", LocalDate.of(2026, 11, 10), AbsenceType.DAY_OFF_WISH),
            ),
            z.eintraege,
        )
    }

    @Test
    fun monateBlaetternMitWischenUndKnopf() {
        val team = teamMit(Employee("a", "Ida", 120))
        val store = WuenscheStore(dir)
        compose.setContent { WuenscheBereich(teamStore = team, store = store, heute = heute) }

        compose.onNodeWithContentDescription("Nächster Monat").performClick()
        compose.onNodeWithTag("monats-titel").assertTextContains("Dezember 2026")
        compose.onNodeWithTag("monats-pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("monats-titel").assertTextContains("Januar 2027")
        compose.onNodeWithTag("tag-2027-01-02").performScrollTo().performClick()
        assertEquals(LocalDate.of(2027, 1, 2), warte { store.warten() }.eintraege.single().date)
    }

    @Test
    fun personLoeschenNimmtIhreEintraegeMit() {
        val team = teamMit(Employee("a", "Ida", 120), Employee("b", "Ole", 80))
        val store = WuenscheStore(dir)
        warte { store.warten() }
        store.antippen("a", LocalDate.of(2026, 11, 9), AbsenceType.VACATION)
        store.antippen("a", LocalDate.of(2026, 11, 10), AbsenceType.VACATION)
        store.antippen("b", LocalDate.of(2026, 11, 9), AbsenceType.VACATION)
        warte { store.warten() }
        compose.setContent { TeamBereich(store = team, wuensche = store) }

        compose.onNodeWithTag("mitarbeiter-Ida").performClick()
        compose.onNodeWithText("Ida aus dem Team löschen").performScrollTo().performClick()
        compose.onNodeWithText("Die 2 eingetragenen Tage", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Löschen").performClick()

        assertEquals(listOf("Ole"), warte { team.warten() }.team.map { it.name })
        assertEquals(listOf("b"), warte { store.warten() }.eintraege.map { it.employeeId })
    }
}
