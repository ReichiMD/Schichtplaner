package de.reichimd.schichtplaner

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Plan erstellen und mit Wischen die Woche wechseln. */
@RunWith(AndroidJUnit4::class)
class PlanScreenOnDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun wischenWechseltDieWoche() {
        compose.setContent { PlanScreen() }
        compose.onNodeWithText("Plan erstellen", substring = true).performClick()
        compose.waitUntil(60_000) { compose.onAllNodes(hasTestTag("wochen-pager")).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Woche 1 von", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("wochen-pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText("Woche 2 von", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("wochen-pager").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithText("Woche 1 von", substring = true).assertIsDisplayed()
    }
}
