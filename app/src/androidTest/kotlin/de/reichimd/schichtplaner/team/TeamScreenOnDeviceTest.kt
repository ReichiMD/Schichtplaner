package de.reichimd.schichtplaner.team

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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import de.reichimd.schichtplaner.wuensche.WuenscheStore
import java.io.File
import java.time.DayOfWeek

/** Bedient den Team-Screen wie ein Mensch: anlegen, speichern, wieder öffnen. */
@RunWith(AndroidJUnit4::class)
class TeamScreenOnDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private val dir = File(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "teamui-${System.nanoTime()}",
    ).also { it.mkdirs() }

    @After
    fun aufraeumen() {
        dir.deleteRecursively()
    }

    @Test
    fun personAnlegenUndBearbeiten() {
        val store = TeamStore(dir)
        runBlocking { withTimeout(10_000) { store.warten() } }
        compose.setContent { TeamBereich(store = store, wuensche = WuenscheStore(dir)) }

        compose.onNodeWithText("Noch niemand im Team.").assertIsDisplayed()
        compose.onNodeWithTag("neu").performClick()
        compose.onNodeWithTag("vorname").performTextInput("Ida")
        compose.onNodeWithText("100").performScrollTo().performClick()
        compose.onNodeWithText("Frühschicht").performScrollTo().performClick()
        compose.onNodeWithText("Mi").performScrollTo().performClick()
        compose.onNodeWithTag("speichern").performClick()

        compose.onNodeWithTag("mitarbeiter-Ida").assertIsDisplayed()
        compose.onNodeWithText("100 h / Monat").assertIsDisplayed()
        compose.onNodeWithText("Nur spät").assertIsDisplayed()
        compose.onNodeWithText("Nie Mi").assertIsDisplayed()

        val z = runBlocking { withTimeout(10_000) { store.warten() } }
        assertEquals(1, z.team.size)
        assertFalse(z.team[0].canEarly)
        assertEquals(setOf(DayOfWeek.WEDNESDAY), z.team[0].blockedWeekdays)

        // Wieder öffnen, Stunden mit Plus erhöhen.
        compose.onNodeWithTag("mitarbeiter-Ida").performClick()
        compose.onNodeWithText("100 h").assertIsDisplayed()
        compose.onNodeWithContentDescription("5 Stunden mehr").performClick()
        compose.onNodeWithText("105 h").assertIsDisplayed()
        compose.onNodeWithTag("speichern").performClick()
        compose.onNodeWithText("105 h / Monat").assertIsDisplayed()

        // Löschen mit Nachfrage.
        compose.onNodeWithTag("mitarbeiter-Ida").performClick()
        compose.onNodeWithText("Ida aus dem Team löschen").performScrollTo().performClick()
        compose.onNodeWithText("Löschen").performClick()
        compose.onNodeWithText("Noch niemand im Team.").assertIsDisplayed()
    }

    @Test
    fun ohneNameKommtEinHinweis() {
        val store = TeamStore(dir)
        runBlocking { withTimeout(10_000) { store.warten() } }
        compose.setContent { TeamBereich(store = store, wuensche = WuenscheStore(dir)) }

        compose.onNodeWithTag("neu").performClick()
        compose.onNodeWithTag("speichern").performClick()
        compose.onNodeWithText("Bitte einen Vornamen eingeben.").assertIsDisplayed()
        compose.onNodeWithTag("nicht-gespeichert").assertIsDisplayed()
    }

    @Test
    fun ohneSchichtStehtDerFehlerAmAbschnittUndVerschwindetBeimKorrigieren() {
        val store = TeamStore(dir)
        runBlocking { withTimeout(10_000) { store.warten() } }
        compose.setContent { TeamBereich(store = store, wuensche = WuenscheStore(dir)) }

        compose.onNodeWithTag("neu").performClick()
        compose.onNodeWithTag("vorname").performTextInput("Ida")
        compose.onNodeWithText("Frühschicht").performScrollTo().performClick()
        compose.onNodeWithText("Spätschicht").performScrollTo().performClick()
        compose.onNodeWithTag("speichern").performClick()

        // Nicht gespeichert, Fehler direkt bei "Welche Schichten?" und als Kasten über dem Knopf.
        compose.onNodeWithTag("fehler-schichten").assertIsDisplayed()
        compose.onNodeWithTag("nicht-gespeichert").assertIsDisplayed()
        assertEquals(0, runBlocking { withTimeout(10_000) { store.warten() } }.team.size)

        // Spätschicht wieder an: Der Fehler verschwindet sofort, Speichern klappt.
        compose.onNodeWithText("Spätschicht").performScrollTo().performClick()
        compose.onNodeWithTag("fehler-schichten").assertDoesNotExist()
        compose.onNodeWithTag("speichern").performClick()
        compose.onNodeWithTag("mitarbeiter-Ida").assertIsDisplayed()
    }

    @Test
    fun beispielTeamLaden() {
        val store = TeamStore(dir)
        runBlocking { withTimeout(10_000) { store.warten() } }
        compose.setContent { TeamBereich(store = store, wuensche = WuenscheStore(dir)) }

        compose.onNodeWithTag("beispiel").performClick()
        compose.onNodeWithTag("mitarbeiter-Conny").assertIsDisplayed()
    }

    @Test
    fun reihenfolgeMitPfeilenAendern() {
        val store = TeamStore(dir)
        runBlocking { withTimeout(10_000) { store.warten() } }
        assertEquals(emptyList<Any>(), store.speichern(de.reichimd.schichtplaner.core.Employee("i", "Ida", 100)))
        assertEquals(emptyList<Any>(), store.speichern(de.reichimd.schichtplaner.core.Employee("o", "Ole", 80)))
        runBlocking { withTimeout(10_000) { store.warten() } }
        compose.setContent { TeamBereich(store = store, wuensche = WuenscheStore(dir)) }

        compose.onNodeWithTag("runter-Ida").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Ole", "Ida"), runBlocking { withTimeout(10_000) { store.warten() } }.team.map { it.name })
        // Wieder hoch, und die Reihenfolge übersteht einen Neustart.
        compose.onNodeWithTag("hoch-Ida").performClick()
        compose.waitForIdle()
        runBlocking { withTimeout(10_000) { store.warten() } }
        assertEquals(listOf("Ida", "Ole"), runBlocking { withTimeout(10_000) { TeamStore(dir).warten() } }.team.map { it.name })
    }
}
