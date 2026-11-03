package de.reichimd.schichtplaner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.reichimd.schichtplaner.laden.LadenBereich
import de.reichimd.schichtplaner.plan.PlanScreen
import de.reichimd.schichtplaner.team.TeamBereich
import de.reichimd.schichtplaner.ui.Akzent
import de.reichimd.schichtplaner.ui.Hintergrund
import de.reichimd.schichtplaner.ui.Rand
import de.reichimd.schichtplaner.ui.TextLeise
import de.reichimd.schichtplaner.wuensche.WuenscheBereich

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Akzent, background = Hintergrund)) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Hintergrund)
                        .safeDrawingPadding(),
                ) {
                    Hauptbereich()
                }
            }
        }
    }
}

private enum class Bereich(val titel: String) { PLAN("Plan"), WUENSCHE("Wünsche"), TEAM("Team"), LADEN("Laden") }

/** Untere Leiste mit Plan, Wünsche, Team und Laden. Beim Bearbeiten (Person, Schicht, Zeiten) wird sie ausgeblendet. */
@Composable
private fun Hauptbereich() {
    var bereich by rememberSaveable { mutableStateOf(Bereich.PLAN) }
    var bearbeitet by remember { mutableStateOf(false) }

    // Zurück in Wünsche, Team oder Laden führt erst zum Plan, statt die App zu schließen.
    BackHandler(enabled = bereich != Bereich.PLAN && !bearbeitet) { bereich = Bereich.PLAN }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (bereich) {
                Bereich.PLAN -> PlanScreen()
                Bereich.WUENSCHE -> WuenscheBereich()
                Bereich.TEAM -> TeamBereich(bearbeitetGerade = { bearbeitet = it })
                Bereich.LADEN -> LadenBereich(bearbeitetGerade = { bearbeitet = it })
            }
        }
        if (!(bereich != Bereich.PLAN && bearbeitet)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .border(1.dp, Rand),
            ) {
                Bereich.entries.forEach { b ->
                    val aktiv = b == bereich
                    TextButton(
                        onClick = { bereich = b; bearbeitet = false },
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 56.dp)
                            .testTag("tab-${b.titel}"),
                    ) {
                        // Vier Reiter: bei großer Schrift lieber kleiner als umgebrochen.
                        Text(
                            b.titel,
                            fontSize = 15.sp,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Visible,
                            fontWeight = if (aktiv) FontWeight.Bold else FontWeight.Normal,
                            color = if (aktiv) Akzent else TextLeise,
                        )
                    }
                }
            }
        }
    }
}
