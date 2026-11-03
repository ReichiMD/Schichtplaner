package de.reichimd.schichtplaner.core

/**
 * Ein Problem bei einer Eingabe, mit dem Feld, an dem die App es anzeigen soll.
 * So kann die App jeden Fehler direkt am betroffenen Feld nennen, nicht nur gesammelt unten.
 */
data class Problem<F>(val field: F, val message: String)

/** Kurzformen der Wochentage, wie auf dem Papierplan. */
object Weekdays {
    private val SHORT = mapOf(
        java.time.DayOfWeek.MONDAY to "Mo",
        java.time.DayOfWeek.TUESDAY to "Di",
        java.time.DayOfWeek.WEDNESDAY to "Mi",
        java.time.DayOfWeek.THURSDAY to "Do",
        java.time.DayOfWeek.FRIDAY to "Fr",
        java.time.DayOfWeek.SATURDAY to "Sa",
        java.time.DayOfWeek.SUNDAY to "So",
    )

    fun short(day: java.time.DayOfWeek): String = SHORT.getValue(day)

    /** "Mo, Di, Fr" oder zusammengefasst "Mo–Fr", wenn die Tage lückenlos aufeinander folgen. */
    fun describe(days: Collection<java.time.DayOfWeek>): String {
        val sorted = days.toSortedSet().toList()
        if (sorted.isEmpty()) return "keine Tage"
        if (sorted.size == 7) return "täglich"
        val groups = mutableListOf<MutableList<java.time.DayOfWeek>>()
        for (d in sorted) {
            val last = groups.lastOrNull()
            if (last != null && last.last().value + 1 == d.value) last += d else groups += mutableListOf(d)
        }
        return groups.joinToString(", ") { g ->
            when (g.size) {
                1 -> short(g[0])
                2 -> "${short(g[0])}, ${short(g[1])}"
                else -> "${short(g.first())}–${short(g.last())}"
            }
        }
    }
}
