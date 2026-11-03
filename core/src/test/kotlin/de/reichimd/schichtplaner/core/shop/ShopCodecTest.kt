package de.reichimd.schichtplaner.core.shop

import de.reichimd.schichtplaner.core.ShiftEnd
import de.reichimd.schichtplaner.core.ShiftKind
import de.reichimd.schichtplaner.core.ShiftStart
import de.reichimd.schichtplaner.core.ShiftTemplate
import de.reichimd.schichtplaner.core.storage.DataFileException
import de.reichimd.schichtplaner.core.storage.DataFileTooNewException
import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ShopCodecTest {

    @Test
    fun beispielHinUndZurueck() {
        assertEquals(Shop.EXAMPLE, ShopCodec.decode(ShopCodec.encode(Shop.EXAMPLE)))
    }

    @Test
    fun relativerBeginnGeschlossenerTagUndVierWochen() {
        val s = Shop.EXAMPLE.copy(
            openingHours = Shop.EXAMPLE.openingHours - DayOfWeek.SUNDAY,
            templates = listOf(
                ShiftTemplate(
                    "x", "Abend", ShiftKind.LATE,
                    ShiftStart.BeforeClose(240), ShiftEnd.AfterClose(15),
                    weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), staff = 2,
                ),
            ),
            planWeeks = 4,
        )
        assertEquals(s, ShopCodec.decode(ShopCodec.encode(s)))
    }

    @Test
    fun unbekannteFelderWerdenUeberlesen() {
        val text = """{"version":1,"neu":true,"openingHours":[{"day":1,"open":"06:00","close":"18:00","x":1}],"templates":[]}"""
        val s = ShopCodec.decode(text)
        assertEquals(mapOf(DayOfWeek.MONDAY to DayHours(LocalTime.of(6, 0), LocalTime.of(18, 0))), s.openingHours)
        assertEquals(2, s.planWeeks)
    }

    @Test
    fun kaputteDateienWerdenAbgelehnt() {
        val tpl = """"id":"a","name":"A","kind":"early","startTime":"05:00","endTime":"12:00""""
        val kaputt = listOf(
            "",
            "nicht json",
            """{"openingHours":[]}""",
            """{"version":0}""",
            """{"version":1,"openingHours":[{"day":8,"open":"06:00","close":"18:00"}]}""",
            """{"version":1,"openingHours":[{"day":1,"open":"6 Uhr","close":"18:00"}]}""",
            """{"version":1,"openingHours":[{"day":1,"open":"24:00","close":"18:00"}]}""",
            """{"version":1,"openingHours":[{"day":1,"open":"06:00","close":"18:00"},{"day":1,"open":"07:00","close":"18:00"}]}""",
            """{"version":1,"templates":[{"id":"a","name":"A","kind":"mittag","startTime":"05:00","endTime":"12:00"}]}""",
            """{"version":1,"templates":[{"id":"a","name":"A","kind":"early","endTime":"12:00"}]}""",
            """{"version":1,"templates":[{"id":"a","name":"A","kind":"early","startTime":"05:00","startBeforeClose":30,"endTime":"12:00"}]}""",
            """{"version":1,"templates":[{$tpl},{$tpl}]}""",
            """[1,2]""",
        )
        for (text in kaputt) {
            assertFailsWith<DataFileException>("sollte abgelehnt werden: $text") { ShopCodec.decode(text) }
        }
    }

    @Test
    fun regelverstoesseWerdenGeladenUndNichtAlsBeschaedigtBehandelt() {
        // Sonst würde eine strengere Regel in einer neuen App-Version gespeicherte Einstellungen wegwerfen.
        val tpl = """"id":"a","name":"A","kind":"early","startTime":"05:00","endTime":"12:00""""
        val s = ShopCodec.decode(
            """{"version":2,"openingHours":[{"day":1,"open":"18:00","close":"06:00"}],"templates":[{$tpl,"staff":0}],"planWeeks":3}""",
        )
        assertEquals(3, Shop.problems(s).size)
    }

    @Test
    fun version1MitOeffnungszeitenWirdAufArbeitszeitUmgerechnet() {
        // So hat 0.1.3 das Beispiel gespeichert: Öffnung 06:00–18:00, Spät bis 30 min nach Ladenschluss.
        val alt = """{"version":1,
            "openingHours":[{"day":1,"open":"06:00","close":"18:00"},{"day":6,"open":"07:00","close":"16:00"}],
            "templates":[
              {"id":"f1","name":"Früh 1","kind":"early","startTime":"04:30","endTime":"11:30"},
              {"id":"sp","name":"Spät","kind":"late","startTime":"12:30","endAfterClose":30,"weekdays":[1,6]},
              {"id":"ab","name":"Abend","kind":"late","startBeforeClose":120,"endAfterClose":30,"weekdays":[1]}
            ]}"""
        val s = ShopCodec.decode(alt)
        val t = { h: Int, m: Int -> LocalTime.of(h, m) }
        assertEquals(DayHours(t(4, 30), t(18, 30)), s.openingHours[DayOfWeek.MONDAY])
        assertEquals(DayHours(t(4, 30), t(16, 30)), s.openingHours[DayOfWeek.SATURDAY])
        // Die Schichten liegen genau wie vorher.
        val sp = s.templates.single { it.id == "sp" }
        assertEquals(listOf("Mo 12:30–18:30", "Sa 12:30–16:30"), Shop.preview(sp, s.openingHours))
        val ab = s.templates.single { it.id == "ab" }
        assertEquals(listOf("Mo 16:00–18:30"), Shop.preview(ab, s.openingHours))
        assertEquals(emptyList(), Shop.problems(s))
        assertEquals(emptyList(), Shop.warnings(s).filter { "außerhalb" in it })
        // Wieder gespeichert ist es Version 2 und bleibt gleich.
        assertEquals(s, ShopCodec.decode(ShopCodec.encode(s)))
    }

    @Test
    fun version1MitVerschiedenenNachlaeufenBleibtGueltig() {
        // Spät +30, Abend +0: Verschieben ginge nicht für beide. Dann bleibt der Feierabend, nichts wird ungültig.
        val alt = """{"version":1,
            "openingHours":[{"day":1,"open":"06:00","close":"18:00"}],
            "templates":[
              {"id":"sp","name":"Spät","kind":"late","startTime":"12:30","endAfterClose":30,"weekdays":[1]},
              {"id":"ab","name":"Abend","kind":"late","startTime":"14:00","endAfterClose":0,"weekdays":[1]}
            ]}"""
        val s = ShopCodec.decode(alt)
        assertEquals(emptyList(), Shop.problems(s))
        assertEquals(listOf("Mo 12:30–18:30"), Shop.preview(s.templates[0], s.openingHours))
        assertEquals(listOf("Mo 14:00–18:00"), Shop.preview(s.templates[1], s.openingHours))
    }

    @Test
    fun neuereVersionWirdNichtGelesen() {
        assertFailsWith<DataFileTooNewException> { ShopCodec.decode("""{"version":3}""") }
    }

    @Test
    fun festesEndeBleibtFest() {
        val s = Shop.EXAMPLE
        val f1 = ShopCodec.decode(ShopCodec.encode(s)).templates.first()
        assertEquals(ShiftEnd.At(LocalTime.of(11, 30)), f1.end)
    }
}
