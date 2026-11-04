@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package de.reichimd.schichtplaner.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Farben der App: warm, ruhig, gut lesbar. Fehler kräftig rot, damit sie nicht übersehen werden.
internal val Hintergrund = Color(0xFFFBF8F3)
internal val Schrift = Color(0xFF2B2620)
internal val TextMittel = Color(0xFF4A4239)
internal val TextLeise = Color(0xFF6B6258)
internal val Akzent = Color(0xFF8A3A12)
internal val AkzentDunkel = Color(0xFF6B2C0D)
internal val Frueh = Color(0xFFF6D9B8)
internal val Spaet = Color(0xFFCFE0F3)
internal val Rand = Color(0xFFDDD3C4)
internal val RandHell = Color(0xFFE5DCCD)
internal val Chip = Color(0xFFF1ECE4)
internal val Warnung = Color(0xFFFFF4E5)
internal val Fehler = Color(0xFFB3261E)
internal val FehlerHell = Color(0xFFFDECEA)

// Wünsche-Kalender: Arten unterscheiden sich auch in der Helligkeit, nicht nur im Farbton.
internal val Urlaub = Color(0xFF6E4FA0)
// Grün, damit es sich vom hellblauen Spätdienst im Plan abhebt.
internal val FreiWunsch = Color(0xFF2E7D32)
internal val FreiWunschHell = Color(0xFFDDEFD8)
internal val Krank = Color(0xFFA23B2E)
internal val KrankHell = Color(0xFFF6DEDA)

/**
 * Abschnitt eines Formulars mit Überschrift. Mit [fehler] wird er rot umrandet und der Fehler steht
 * direkt unter der Überschrift, damit er nicht übersehen wird. [requester] erlaubt, ihn ins Bild zu holen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Abschnitt(
    titel: String,
    unterzeile: String? = null,
    fehler: List<String> = emptyList(),
    requester: BringIntoViewRequester? = null,
    tag: String? = null,
    inhalt: @Composable () -> Unit,
) {
    val form = RoundedCornerShape(14.dp)
    var m = Modifier.fillMaxWidth()
    if (requester != null) m = m.bringIntoViewRequester(requester)
    if (fehler.isNotEmpty()) {
        m = m
            .background(FehlerHell, form)
            .border(2.dp, Fehler, form)
            .padding(10.dp)
    }
    Column(m, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(titel, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (fehler.isEmpty()) TextMittel else Fehler)
        fehler.forEach { FehlerText(it, tag) }
        if (unterzeile != null) Text(unterzeile, fontSize = 13.sp, color = TextLeise)
        inhalt()
    }
}

/** Fehlertext in Rot mit Warnzeichen, auch für Bildschirmleser als Fehler markiert. */
@Composable
internal fun FehlerText(text: String, tag: String? = null) {
    var m: Modifier = Modifier.semantics { error(text) }
    if (tag != null) m = m.testTag("fehler-$tag")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("⚠", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Fehler)
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Fehler, modifier = m)
    }
}

/** Kasten über dem Speichern-Knopf: sagt, dass nichts gespeichert wurde und wo es hakt. */
@Composable
internal fun NichtGespeichert(wo: List<String>, weitere: List<String> = emptyList()) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(FehlerHell, RoundedCornerShape(10.dp))
            .border(2.dp, Fehler, RoundedCornerShape(10.dp))
            .padding(12.dp)
            .testTag("nicht-gespeichert"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Noch nicht gespeichert.", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Fehler)
        if (wo.isNotEmpty()) {
            Text("Bitte das rot Markierte prüfen: ${wo.joinToString(", ")}", fontSize = 14.sp, color = Schrift)
        }
        weitere.forEach { Text(it, fontSize = 14.sp, color = Schrift) }
    }
}

@Composable
internal fun RundKnopf(text: String, beschreibung: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier
            .size(52.dp)
            .semantics { contentDescription = beschreibung },
    ) { Text(text, fontSize = 24.sp, color = if (enabled) Schrift else Rand) }
}

/** Zahl oder Uhrzeit mit Minus- und Plus-Knopf, ohne Tippen. */
@Composable
internal fun Stepper(
    wert: String,
    weniger: String,
    mehr: String,
    tag: String? = null,
    groesse: Int = 26,
    onWeniger: () -> Unit,
    onMehr: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RundKnopf("−", weniger, onClick = onWeniger)
        var m = Modifier.weight(1f)
        if (tag != null) m = m.testTag(tag)
        Text(
            wert,
            fontSize = groesse.sp,
            fontWeight = FontWeight.Bold,
            color = Schrift,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = m,
        )
        RundKnopf("+", mehr, onClick = onMehr)
    }
}

@Composable
internal fun Auswahl(
    text: String,
    gewaehlt: Boolean,
    modifier: Modifier = Modifier,
    gewaehltFarbe: Color = Schrift,
    onClick: () -> Unit,
) {
    val form = RoundedCornerShape(12.dp)
    Box(
        modifier
            .heightIn(min = 48.dp)
            .background(if (gewaehlt) gewaehltFarbe else Color.White, form)
            .border(1.dp, if (gewaehlt) gewaehltFarbe else Rand, form)
            .clickable(role = Role.Checkbox, onClick = onClick)
            .semantics { selected = gewaehlt },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 15.sp,
            fontWeight = if (gewaehlt) FontWeight.Bold else FontWeight.Normal,
            color = if (gewaehlt) Color.White else Schrift,
        )
    }
}

/** Ja/Nein-Zeile mit einem echten Schalter rechts, damit klar ist, was an ist. */
@Composable
internal fun Schalter(text: String, an: Boolean, farbeAn: Color, onClick: () -> Unit) {
    val form = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(if (an) farbeAn else Color.White, form)
            .border(if (an) 2.dp else 1.dp, if (an) Akzent else Rand, form)
            .clickable(role = Role.Switch, onClick = onClick)
            .semantics { selected = an }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 16.sp, color = Schrift, modifier = Modifier.weight(1f))
        Text(if (an) "Ja" else "Nein", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Schrift)
        Switch(
            checked = an,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = Akzent, checkedThumbColor = Color.White),
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

@Composable
internal fun Hinweis(text: String, modifier: Modifier = Modifier, aktion: (@Composable () -> Unit)? = null) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Warnung, RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Text(text, fontSize = 14.sp, color = Schrift)
        aktion?.invoke()
    }
}
