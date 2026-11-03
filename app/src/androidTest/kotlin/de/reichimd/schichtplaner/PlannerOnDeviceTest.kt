package de.reichimd.schichtplaner

import androidx.test.ext.junit.runners.AndroidJUnit4
import de.reichimd.schichtplaner.core.Planner
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.LocalDate

/**
 * Läuft auf einem echten Android (Emulator in GitHub Actions) und beweist,
 * dass Choco und seine Bibliotheken dort funktionieren.
 */
@RunWith(AndroidJUnit4::class)
class PlannerOnDeviceTest {

    @Test
    fun planerLaeuftAufAndroidUndHaeltDieRegelnEin() {
        val request = DemoData.request(LocalDate.of(2026, 11, 3))
        val start = System.currentTimeMillis()
        val plan = Planner.plan(request)
        val millis = System.currentTimeMillis() - start

        assertTrue("kein Plan gefunden", plan.solved)
        assertTrue("unbesetzt: ${plan.unfilled.size}", plan.unfilled.isEmpty())
        assertTrue("Rechenzeit $millis ms", millis < 30_000)

        for (e in request.employees) {
            val shifts = plan.shiftsOf(e.id).sortedBy { it.start }
            assertTrue("${e.name}: zwei Schichten an einem Tag", shifts.map { it.date }.toSet().size == shifts.size)
            for (i in 1 until shifts.size) {
                val rest = Duration.between(shifts[i - 1].end, shifts[i].start)
                assertTrue("${e.name}: Ruhezeit ${rest.toMinutes()} min", rest >= Duration.ofHours(11))
            }
        }
        println("Planer auf Android: $millis ms")
    }
}
