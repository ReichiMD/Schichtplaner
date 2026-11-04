package de.reichimd.schichtplaner.laden

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.reichimd.schichtplaner.core.ShiftEnd
import de.reichimd.schichtplaner.core.ShiftKind
import de.reichimd.schichtplaner.core.shop.DayHours
import de.reichimd.schichtplaner.core.shop.Shop
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.DayOfWeek
import java.time.LocalTime

/** Laden-Einstellungen auf einem echten Android: speichern, Neustart, Bedienung wie ein Mensch. */
@RunWith(AndroidJUnit4::class)
class LadenOnDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private val dir = File(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "laden-${System.nanoTime()}",
    ).also { it.mkdirs() }

    @After
    fun aufraeumen() {
        dir.deleteRecursively()
    }

    private fun warte(store: LadenStore) = runBlocking { withTimeout(10_000) { store.warten() } }

    @Test
    fun ohneDateiGeltenDieBeispielwerte() {
        val z = warte(LadenStore(dir))
        assertFalse(z.eingerichtet)
        assertEquals(Shop.EXAMPLE, z.laden)
        assertNull(z.meldung)
    }

    @Test
    fun gespeichertBleibtNachNeustart() {
        val store = LadenStore(dir)
        warte(store)
        val mo = DayHours(LocalTime.of(7, 0), LocalTime.of(19, 0))
        assertEquals(emptyList<Any>(), store.speichern { it.copy(openingHours = it.openingHours + (DayOfWeek.MONDAY to mo), planWeeks = 4) })
        warte(store)
        val danach = warte(LadenStore(dir))
        assertTrue(danach.eingerichtet)
        assertEquals(mo, danach.laden.openingHours[DayOfWeek.MONDAY])
        assertEquals(4, danach.laden.planWeeks)
    }

    @Test
    fun ungueltigesWirdNichtGespeichert() {
        val store = LadenStore(dir)
        warte(store)
        val falsch = DayHours(LocalTime.of(18, 0), LocalTime.of(6, 0))
        assertTrue(store.speichern { it.copy(openingHours = it.openingHours + (DayOfWeek.MONDAY to falsch)) }.isNotEmpty())
        assertFalse(warte(store).eingerichtet)
    }

    @Test
    fun beschaedigteDateiWirdBeiseitegelegt() {
        File(dir, LadenStore.DATEINAME).writeText("{ kaputt")
        val z = warte(LadenStore(dir))
        assertFalse(z.eingerichtet)
        assertNotNull(z.meldung)
        assertEquals(1, dir.listFiles()!!.count { it.name.startsWith("laden-beschaedigt-") })
    }

    @Test
    fun schichtAnlegenMitFehlerAmFeld() {
        val store = LadenStore(dir)
        warte(store)
        compose.setContent { LadenBereich(store = store) }

        compose.onNodeWithTag("neue-schicht").performScrollTo().performClick()
        compose.onNodeWithTag("speichern").performClick()
        // Kein Name: Fehler steht direkt am Namen.
        compose.onNodeWithTag("fehler-name").assertIsDisplayed()
        compose.onNodeWithTag("nicht-gespeichert").assertIsDisplayed()

        compose.onNodeWithTag("schicht-name").performTextInput("Mittag")
        compose.onNodeWithTag("fehler-name").assertDoesNotExist()
        compose.onNodeWithText("Spät").performScrollTo().performClick()
        // Ende "bis Arbeitsende" ab 06:00 (bis 18:30) wäre über 10 Stunden: Fehler am Ende.
        compose.onNodeWithText("Bis Arbeitsende").performScrollTo().performClick()
        compose.onNodeWithTag("speichern").performClick()
        compose.onNodeWithTag("fehler-ende").assertExists()
        // Wieder feste Uhrzeit (06:00–12:00): jetzt klappt es.
        compose.onNodeWithTag("ende-fest").performScrollTo().performClick()
        compose.onNodeWithTag("speichern").performClick()

        compose.onNodeWithTag("schicht-Mittag").performScrollTo().assertIsDisplayed()
        val z = warte(store)
        assertTrue(z.eingerichtet)
        val mittag = z.laden.templates.single { it.name == "Mittag" }
        assertEquals(ShiftKind.LATE, mittag.kind)
        assertEquals(ShiftEnd.At(LocalTime.of(12, 0)), mittag.end)
    }

    @Test
    fun feiertageEinstellen() {
        val store = LadenStore(dir)
        warte(store)
        compose.setContent { LadenBereich(store = store) }

        compose.onNodeWithTag("laden-feiertage").performScrollTo().performClick()
        // Bundesland wechseln: Bayern.
        compose.onNodeWithText("Sachsen-Anhalt").performScrollTo().performClick()
        compose.onNodeWithTag("land-BY").performScrollTo().performClick()
        // Silvester ist als kürzerer Tag vorbelegt.
        compose.onNodeWithContentDescription("Feierabend Silvester später").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("speichern").performClick()

        val z = warte(store)
        assertTrue(z.eingerichtet)
        assertEquals(de.reichimd.schichtplaner.core.shop.FederalState.BY, z.laden.state)
        assertEquals(Shop.EXAMPLE.holidayHours, z.laden.holidayHours)
        compose.onNodeWithTag("laden-feiertage").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun feiertagsZeitFalschZeigtFehler() {
        val store = LadenStore(dir)
        warte(store)
        compose.setContent { LadenBereich(store = store) }

        compose.onNodeWithTag("laden-feiertage").performScrollTo().performClick()
        // Feierabend an Feiertagen vor den Beginn stellen (17:30 -> 04:15).
        repeat(53) { compose.onNodeWithContentDescription("Feierabend an Feiertagen früher").performClick() }
        compose.onNodeWithTag("speichern").performClick()
        compose.onNodeWithTag("nicht-gespeichert").assertIsDisplayed()
        assertFalse(warte(store).eingerichtet)
    }

    @Test
    fun oeffnungszeitFalschZeigtFehlerAmTag() {
        val store = LadenStore(dir)
        warte(store)
        compose.setContent { LadenBereich(store = store) }

        compose.onNodeWithTag("laden-zeiten").performClick()
        // Montag: Feierabend so lange früher stellen, bis er vor dem Beginn liegt (18:30 -> 04:15).
        repeat(57) { compose.onNodeWithContentDescription("Feierabend Mo früher").performClick() }
        compose.onNodeWithTag("speichern").performClick()
        compose.onNodeWithTag("fehler-tag-Mo").assertIsDisplayed()
        assertFalse(warte(store).eingerichtet)
    }

    @Test
    fun beispielUebernehmen() {
        val store = LadenStore(dir)
        warte(store)
        compose.setContent { LadenBereich(store = store) }

        compose.onNodeWithTag("beispiel-uebernehmen").performClick()
        val z = warte(store)
        assertTrue(z.eingerichtet)
    }
}
