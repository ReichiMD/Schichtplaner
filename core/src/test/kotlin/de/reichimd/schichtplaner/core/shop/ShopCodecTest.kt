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
            """{"version":1,"openingHours":[{"day":1,"open":"18:00","close":"06:00"}],"templates":[{$tpl,"staff":0}],"planWeeks":3}""",
        )
        assertEquals(3, Shop.problems(s).size)
    }

    @Test
    fun neuereVersionWirdNichtGelesen() {
        assertFailsWith<DataFileTooNewException> { ShopCodec.decode("""{"version":2}""") }
    }

    @Test
    fun festesEndeBleibtFest() {
        val s = Shop.EXAMPLE
        val f1 = ShopCodec.decode(ShopCodec.encode(s)).templates.first()
        assertEquals(ShiftEnd.At(LocalTime.of(11, 30)), f1.end)
    }
}
