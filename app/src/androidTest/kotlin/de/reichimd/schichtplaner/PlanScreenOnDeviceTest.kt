package de.reichimd.schichtplaner

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.reichimd.schichtplaner.core.Employee
import de.reichimd.schichtplaner.core.ShiftKind
import de.reichimd.schichtplaner.core.plan.PlanSlot
import de.reichimd.schichtplaner.core.plan.ShiftPlan
import de.reichimd.schichtplaner.core.plan.SlotKey
import de.reichimd.schichtplaner.laden.LadenStore
import de.reichimd.schichtplaner.plan.PlanScreen
import de.reichimd.schichtplaner.plan.PlanStore
import de.reichimd.schichtplaner.plan.plan
import de.reichimd.schichtplaner.team.TeamStore
import de.reichimd.schichtplaner.wuensche.WuenscheStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** Plan erstellen, Wochen wischen, Schichten von Hand ändern und speichern. */
@RunWith(AndroidJUnit4::class)
class PlanScreenOnDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private val dir = File(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "plan-${System.nanoTime()}",
    ).also { it.mkdirs() }

    @After
    fun aufraeumen() {
        dir.deleteRecursively()
    }

    private fun <T> warte(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    @Test
    fun erstellenWischenUndNachNeustartNochDa() {
        val planStore = PlanStore(dir)
        compose.setContent {
            PlanScreen(TeamStore(dir), LadenStore(dir), WuenscheStore(dir), planStore)
        }
        compose.onNodeWithText("Plan erstellen", substring = true).performClick()
        compose.waitUntil(60_000) { compose.onAllNodes(hasTestTag("wochen-pager")).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Woche 1 von", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("wochen-pager").performScrollTo().performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText("Woche 2 von", substring = true).assertIsDisplayed()
        // Wochentage bleiben oben stehen, sie gehören nicht zum Blätterbereich.
        compose.onNodeWithText("Mo").assertIsDisplayed()
        compose.onNodeWithTag("wochen-pager").performScrollTo().performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithText("Woche 1 von", substring = true).assertIsDisplayed()
        compose.onNodeWithText("ges. Std.").assertIsDisplayed()

        warte { planStore.warten() }
        assertNotNull(warte { PlanStore(dir).warten() }.plan)

        // Ein zweites Mal fragt nach, bevor der Plan mit Handänderungen ersetzt wird.
        compose.onNodeWithText("Neuen Plan erstellen", substring = true).performScrollTo().performClick()
        compose.onNodeWithTag("neu-erstellen").assertIsDisplayed()
        compose.onNodeWithText("Abbrechen").performClick()
    }

    /** Montag, weit genug in der Zukunft, damit der Test jedes Jahr gleich läuft. */
    private val mo = LocalDate.of(2030, 1, 7)

    private fun slot(id: String, kind: ShiftKind, von: LocalTime, bis: LocalTime, wer: String?) =
        PlanSlot(SlotKey(mo, id, 0), id, kind, mo.atTime(von), mo.atTime(bis), wer)

    @Test
    fun vonHandTauschenBesetzenUndZeitAendern() {
        val team = TeamStore(dir)
        warte { team.warten() }
        listOf(Employee("a", "Anna", 120), Employee("b", "Ben", 120), Employee("c", "Clara", 80)).forEach {
            assertEquals(emptyList<Any>(), team.speichern(it))
        }
        val planStore = PlanStore(dir)
        warte { planStore.warten() }
        planStore.ersetzen(
            ShiftPlan(
                mo, 7,
                listOf(
                    slot("f1", ShiftKind.EARLY, LocalTime.of(4, 30), LocalTime.of(11, 30), "a"),
                    slot("f2", ShiftKind.EARLY, LocalTime.of(5, 30), LocalTime.of(12, 30), null),
                    slot("sp", ShiftKind.LATE, LocalTime.of(12, 30), LocalTime.of(18, 30), "b"),
                ),
            ),
        )
        warte { team.warten() }
        warte { planStore.warten() }

        compose.setContent { PlanScreen(team, LadenStore(dir), WuenscheStore(dir), planStore) }
        compose.onNodeWithTag("zusammenfassung").assertTextContains("1 Schicht offen", substring = true)

        // Anna übernimmt die Spätschicht, Ben bekommt dafür ihre Frühschicht.
        compose.onNodeWithTag("zelle-a-$mo").performScrollTo().performClick()
        compose.onNodeWithTag("wahl-sp-0").performScrollTo().performClick()
        compose.onNodeWithTag("fertig").performScrollTo().performClick()
        var p = warte { planStore.warten() }.plan!!
        assertEquals("a", p.slot(SlotKey(mo, "sp", 0))!!.employeeId)
        assertEquals("b", p.slot(SlotKey(mo, "f1", 0))!!.employeeId)

        // Offene Schicht: Clara übernimmt.
        compose.onNodeWithTag("offen-$mo").performScrollTo().performClick()
        compose.onNodeWithTag("person-c").performScrollTo().performClick()
        compose.onNodeWithTag("fertig").performScrollTo().performClick()
        p = warte { planStore.warten() }.plan!!
        assertEquals("c", p.slot(SlotKey(mo, "f2", 0))!!.employeeId)
        compose.onNodeWithTag("zusammenfassung").assertTextContains("Alle Schichten besetzt", substring = true)

        // Claras Beginn eine Viertelstunde später.
        compose.onNodeWithTag("zelle-c-$mo").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Beginn später").performScrollTo().performClick()
        compose.onNodeWithTag("beginn").assertTextContains("5:45")
        compose.onNodeWithTag("fertig").performScrollTo().performClick()
        p = warte { planStore.warten() }.plan!!
        assertEquals(mo.atTime(5, 45), p.slot(SlotKey(mo, "f2", 0))!!.start)

        // Frei geben: Schicht wird wieder offen.
        compose.onNodeWithTag("zelle-b-$mo").performScrollTo().performClick()
        compose.onNodeWithTag("frei-geben").performScrollTo().performClick()
        compose.onNodeWithTag("fertig").performScrollTo().performClick()
        p = warte { planStore.warten() }.plan!!
        assertEquals(null, p.slot(SlotKey(mo, "f1", 0))!!.employeeId)
        assertEquals(p, warte { PlanStore(dir).warten() }.plan)
    }
}
