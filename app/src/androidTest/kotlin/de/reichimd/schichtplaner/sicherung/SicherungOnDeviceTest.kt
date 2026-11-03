package de.reichimd.schichtplaner.sicherung

import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.reichimd.schichtplaner.core.Absence
import de.reichimd.schichtplaner.core.AbsenceType
import de.reichimd.schichtplaner.core.Employee
import de.reichimd.schichtplaner.core.ShiftKind
import de.reichimd.schichtplaner.core.plan.PlanPrint
import de.reichimd.schichtplaner.core.plan.PlanSlot
import de.reichimd.schichtplaner.core.plan.ShiftPlan
import de.reichimd.schichtplaner.core.plan.SlotKey
import de.reichimd.schichtplaner.core.shop.Shop
import de.reichimd.schichtplaner.laden.LadenStore
import de.reichimd.schichtplaner.laden.laden
import de.reichimd.schichtplaner.plan.PlanPdf
import de.reichimd.schichtplaner.plan.PlanStore
import de.reichimd.schichtplaner.plan.plan
import de.reichimd.schichtplaner.team.TeamStore
import de.reichimd.schichtplaner.team.team
import de.reichimd.schichtplaner.wuensche.WuenscheStore
import de.reichimd.schichtplaner.wuensche.eintraege
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/** Sicherung und PDF auf einem echten Android: Datei schreiben, auf einem "neuen Handy" einspielen, PDF öffnen. */
@RunWith(AndroidJUnit4::class)
class SicherungOnDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val wurzel = File(context.cacheDir, "sicherung-${System.nanoTime()}").also { it.mkdirs() }
    private val heute = LocalDate.of(2026, 11, 3)
    private val mo = LocalDate.of(2026, 11, 9)

    @After
    fun aufraeumen() {
        wurzel.deleteRecursively()
    }

    private fun <T> warte(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private fun speicher(name: String): Speicher {
        val dir = File(wurzel, name).also { it.mkdirs() }
        return Speicher(TeamStore(dir), LadenStore(dir), WuenscheStore(dir), PlanStore(dir))
    }

    private suspend fun Speicher.fertig() {
        team.warten(); laden.warten(); wuensche.warten(); plan.warten()
    }

    private val anna = Employee("a", "Anna", 120)
    private val ben = Employee("b", "Ben", 80, isBackup = true)
    private val plan = ShiftPlan(
        firstDay = mo,
        days = 14,
        slots = listOf(
            PlanSlot(SlotKey(mo, "f1", 0), "Früh 1", ShiftKind.EARLY, mo.atTime(4, 30), mo.atTime(11, 30), "a"),
            PlanSlot(SlotKey(mo, "sp", 0), "Spät", ShiftKind.LATE, mo.atTime(12, 30), mo.atTime(18, 30), null),
        ),
    )

    @Test
    fun sichernUndAufNeuemHandyEinspielen() {
        val alt = speicher("alt")
        warte { alt.fertig() }
        assertTrue(alt.team.ersetzen(listOf(anna, ben)))
        assertTrue(alt.laden.ersetzen(Shop.EXAMPLE.copy(planWeeks = 4)))
        assertTrue(
            alt.wuensche.ersetzen(
                listOf(
                    Absence("a", mo, AbsenceType.VACATION),
                    Absence("weg", mo, AbsenceType.SICK), // Person gibt es nicht mehr
                    Absence("a", LocalDate.of(2023, 5, 1), AbsenceType.SICK), // uralt
                ),
            ),
        )
        assertTrue(alt.plan.ersetzenOderLeeren(plan))
        warte { alt.fertig() }

        val datei = File(wurzel, "sicherung.json").also { it.createNewFile() }
        val daten = warte { Sicherung.stand(alt) }
        Sicherung.schreiben(context, Uri.fromFile(datei), daten)

        val neu = speicher("neu")
        warte { neu.fertig() }
        val gelesen = Sicherung.lesen(context, Uri.fromFile(datei))
        val rueckweg = File(wurzel, "rueckweg.json")
        warte { Sicherung.einspielen(neu, gelesen, rueckweg, heute) }
        warte { neu.fertig() }

        // Wie nach einem Neustart: alles frisch aus den Dateien lesen.
        val neustart = speicher("neu")
        warte { neustart.fertig() }
        assertEquals(listOf(anna, ben), neustart.team.zustand.value.team)
        assertEquals(4, neustart.laden.zustand.value.laden.planWeeks)
        assertEquals(listOf(Absence("a", mo, AbsenceType.VACATION)), neustart.wuensche.zustand.value.eintraege)
        assertEquals(plan, neustart.plan.zustand.value.plan)

        // Der vorherige (leere) Stand liegt als Rückweg bereit.
        val vorher = Sicherung.lesen(context, Uri.fromFile(rueckweg))
        assertEquals(emptyList<Employee>(), vorher.team)
        assertNull(vorher.plan)
    }

    @Test
    fun falscheDateiAendertNichts() {
        val s = speicher("handy")
        warte { s.fertig() }
        assertTrue(s.team.ersetzen(listOf(anna)))
        warte { s.fertig() }
        val datei = File(wurzel, "team.json").also { it.writeText("""{"version":1,"employees":[]}""") }
        try {
            Sicherung.lesen(context, Uri.fromFile(datei))
            fail("Eine Teamliste ist keine Sicherung")
        } catch (e: SicherungsFehler) {
            assertEquals("Das ist keine Sicherung des Schichtplaners.", e.message)
        }
        assertEquals(listOf(anna), s.team.zustand.value.team)
    }

    @Test
    fun aufraeumenBeimStartLaesstUnbekanntesTeamInRuhe() {
        val s = speicher("start")
        warte { s.fertig() }
        assertTrue(s.wuensche.ersetzen(listOf(Absence("x", mo, AbsenceType.VACATION), Absence("x", LocalDate.of(2020, 1, 1), AbsenceType.SICK))))
        warte { s.fertig() }
        // Team leer: Personen werden nicht abgeglichen, nur Uraltes fliegt raus.
        warte { Sicherung.aufraeumen(s, heute) }
        warte { s.fertig() }
        assertEquals(listOf(Absence("x", mo, AbsenceType.VACATION)), s.wuensche.zustand.value.eintraege)
        assertNull(s.plan.zustand.value.plan)
    }

    @Test
    fun pdfHatEineSeiteProWoche() {
        val pdf = PlanPdf.datei(context, plan, listOf(anna, ben), listOf(Absence("a", mo.plusDays(1), AbsenceType.VACATION)))
        assertEquals(PlanPrint.fileName(plan), pdf.name)
        assertTrue(pdf.length() > 1000)
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                assertEquals(2, r.pageCount)
                r.openPage(0).use { seite ->
                    // A4 quer
                    assertEquals(842, seite.width)
                    assertEquals(595, seite.height)
                }
            }
        }
    }
}
