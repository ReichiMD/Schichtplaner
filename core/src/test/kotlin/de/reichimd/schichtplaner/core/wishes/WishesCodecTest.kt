package de.reichimd.schichtplaner.core.wishes

import de.reichimd.schichtplaner.core.Absence
import de.reichimd.schichtplaner.core.AbsenceType
import de.reichimd.schichtplaner.core.storage.DataFileException
import de.reichimd.schichtplaner.core.storage.DataFileTooNewException
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WishesCodecTest {

    private val eintraege = listOf(
        Absence("a1", LocalDate.of(2026, 11, 9), AbsenceType.VACATION),
        Absence("a1", LocalDate.of(2026, 11, 21), AbsenceType.DAY_OFF_WISH),
        Absence("b2", LocalDate.of(2026, 12, 31), AbsenceType.SICK),
    )

    @Test
    fun hinUndZurueck() {
        assertEquals(eintraege, WishesCodec.decode(WishesCodec.encode(eintraege)))
    }

    @Test
    fun leer() {
        assertEquals(emptyList(), WishesCodec.decode(WishesCodec.encode(emptyList())))
    }

    @Test
    fun lesbaresFormatMitKuerzeln() {
        val text = WishesCodec.encode(eintraege)
        assertTrue(text.contains("\"version\": 1"))
        assertTrue(text.contains("\"date\": \"2026-11-09\""), text)
        assertTrue(text.contains("\"type\": \"Fr\""), text)
    }

    @Test
    fun doppelteEintraegeWerdenZusammengefasst() {
        val text = """{"version":1,"entries":[
            {"employeeId":"a","date":"2026-11-09","type":"U"},
            {"employeeId":"a","date":"2026-11-09","type":"K"}]}"""
        assertEquals(listOf(Absence("a", LocalDate.of(2026, 11, 9), AbsenceType.SICK)), WishesCodec.decode(text))
    }

    @Test
    fun unbekannteFelderWerdenUeberlesen() {
        val text = """{"version":1,"neu":true,"entries":[{"employeeId":"a","date":"2026-11-09","type":"U","notiz":"x"}]}"""
        assertEquals(1, WishesCodec.decode(text).size)
    }

    @Test
    fun kaputteDateienWerdenErkannt() {
        val kaputt = listOf(
            "",
            "kein json",
            """{"version":0}""",
            """{"version":1,"entries":[{"employeeId":"","date":"2026-11-09","type":"U"}]}""",
            """{"version":1,"entries":[{"employeeId":"a","date":"2026-13-40","type":"U"}]}""",
            """{"version":1,"entries":[{"employeeId":"a","date":"2026-11-09","type":"X"}]}""",
            """{"version":1,"entries":[{"employeeId":"a","date":"2026-11-09"}]}""",
        )
        kaputt.forEach { assertFailsWith<DataFileException>(it) { WishesCodec.decode(it) } }
    }

    @Test
    fun neuereVersionWirdNichtAngefasst() {
        assertFailsWith<DataFileTooNewException> { WishesCodec.decode("""{"version":2,"entries":[]}""") }
    }
}
