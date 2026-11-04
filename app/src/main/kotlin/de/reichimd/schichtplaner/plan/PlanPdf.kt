package de.reichimd.schichtplaner.plan

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import de.reichimd.schichtplaner.core.plan.YearPlan
import de.reichimd.schichtplaner.core.plan.YearPlans
import de.reichimd.schichtplaner.core.shop.ShopSettings
import de.reichimd.schichtplaner.ui.Auswahl
import de.reichimd.schichtplaner.ui.TextMittel
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import de.reichimd.schichtplaner.core.Absence
import de.reichimd.schichtplaner.core.Employee
import de.reichimd.schichtplaner.core.ShiftKind
import de.reichimd.schichtplaner.core.plan.PlanPrint
import de.reichimd.schichtplaner.core.plan.PrintCell
import de.reichimd.schichtplaner.core.plan.PrintRow
import de.reichimd.schichtplaner.core.plan.PrintWeek
import de.reichimd.schichtplaner.core.plan.ShiftPlan
import de.reichimd.schichtplaner.ui.Akzent
import de.reichimd.schichtplaner.ui.Hinweis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * PDF des Plans: ein Blatt (A4 quer) pro Woche, aufgebaut wie der Papier-Vordruck.
 * Den Inhalt liefert [PlanPrint] (getestet im core), hier wird nur gezeichnet.
 */
internal object PlanPdf {

    // A4 quer in Punkt (1/72 Zoll).
    private const val BREITE = 842
    private const val HOEHE = 595
    private const val RAND = 28f
    private const val NAME_BREITE = 96f
    private const val SUMME_BREITE = 48f
    private const val KOPF_HOEHE = 22f
    private const val ABSCHNITT_HOEHE = 16f
    private const val SUMMEN_HOEHE = 22f
    private const val OFFEN_HOEHE = 26f
    private const val TITEL_HOEHE = 34f
    private const val FUSS_HOEHE = 16f

    private const val SCHWARZ = 0xFF2B2620.toInt()
    private const val GRAU = 0xFF6B6258.toInt()
    private const val LINIE = 0xFF9A9083.toInt()
    private const val AUSSERHALB = 0xFFEDEAE5.toInt()
    // Hell, damit es auch schwarz-weiß gedruckt lesbar bleibt.
    private const val FRUEH = 0xFFFBEBD9.toInt()
    private const val SPAET = 0xFFE6EFF9.toInt()
    private const val ABSCHNITT = 0xFFF1ECE4.toInt()
    private const val ROT = 0xFFB3261E.toInt()
    // Frei-Wunsch erfüllt: grün hinterlegt, dazu ein Rahmen, damit es auch schwarz-weiß gedruckt auffällt.
    private const val WUNSCH = 0xFFDDEFD8.toInt()
    private const val WUNSCH_SCHRIFT = 0xFF1B5E20.toInt()

    private val DATUM = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    /** Zeichnet alle Wochen in [out]. Läuft im Hintergrund. */
    fun schreiben(wochen: List<PrintWeek>, out: OutputStream, erstellt: LocalDate = LocalDate.now()) {
        val doc = PdfDocument()
        try {
            wochen.forEachIndexed { i, w ->
                val seite = doc.startPage(PdfDocument.PageInfo.Builder(BREITE, HOEHE, i + 1).create())
                blatt(seite.canvas, w, "Erstellt am ${erstellt.format(DATUM)} · Seite ${i + 1} von ${wochen.size}")
                doc.finishPage(seite)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    private fun stift(groesse: Float, fett: Boolean = false, farbe: Int = SCHWARZ) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = groesse
        color = farbe
        typeface = if (fett) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun flaeche(farbe: Int) = Paint().apply { color = farbe; style = Paint.Style.FILL }

    private val linie = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LINIE; style = Paint.Style.STROKE; strokeWidth = 0.6f }
    private val wunschRahmen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WUNSCH_SCHRIFT; style = Paint.Style.STROKE; strokeWidth = 1.2f }

    /** Text in eine Breite einpassen: kleiner machen, notfalls hinten abschneiden. */
    private fun passend(text: String, stift: Paint, breite: Float): String {
        if (text.isEmpty()) return text
        val p = Paint(stift)
        while (p.measureText(text) > breite && p.textSize > 5f) p.textSize -= 0.5f
        stift.textSize = p.textSize
        if (stift.measureText(text) <= breite) return text
        var t = text
        while (t.isNotEmpty() && stift.measureText("$t…") > breite) t = t.dropLast(1)
        return "$t…"
    }

    private fun mitte(c: Canvas, text: String, stift: Paint, links: Float, breite: Float, grundlinie: Float) {
        val t = passend(text, stift, breite - 4f)
        c.drawText(t, links + (breite - stift.measureText(t)) / 2f, grundlinie, stift)
    }

    private fun links(c: Canvas, text: String, stift: Paint, x: Float, breite: Float, grundlinie: Float) {
        c.drawText(passend(text, stift, breite - 6f), x + 3f, grundlinie, stift)
    }

    private fun blatt(c: Canvas, w: PrintWeek, fuss: String) {
        val tagBreite = (BREITE - 2 * RAND - NAME_BREITE - SUMME_BREITE) / 7f
        val personen = w.rows.count { it is PrintRow.Person }
        val fest = TITEL_HOEHE + KOPF_HOEHE + FUSS_HOEHE +
            w.rows.sumOf {
                when (it) {
                    is PrintRow.Section -> ABSCHNITT_HOEHE.toDouble()
                    is PrintRow.Open -> OFFEN_HOEHE.toDouble()
                    is PrintRow.Totals -> SUMMEN_HOEHE.toDouble()
                    is PrintRow.Person -> 0.0
                }
            }.toFloat()
        // Zeilenhöhe wächst mit wenigen Personen bis 36 pt und schrumpft bei vielen bis 14 pt.
        val zeile = if (personen == 0) 30f else ((HOEHE - 2 * RAND - fest) / personen).coerceIn(14f, 36f)
        val oben = (zeile * 0.34f).coerceAtMost(11f)
        val unten = (zeile * 0.27f).coerceAtMost(8.5f)

        var y = RAND
        c.drawText("Schichtplan", RAND, y + 16f, stift(16f, fett = true))
        val titel = stift(13f)
        c.drawText(w.title, BREITE - RAND - titel.measureText(w.title), y + 16f, titel)
        y += TITEL_HOEHE

        // Kopf: Wochentage
        val kopf = stift(10f, fett = true)
        w.days.forEachIndexed { i, d -> mitte(c, d, kopf, RAND + NAME_BREITE + i * tagBreite, tagBreite, y + 14f) }
        mitte(c, "Std.", kopf, BREITE - RAND - SUMME_BREITE, SUMME_BREITE, y + 14f)
        y += KOPF_HOEHE
        c.drawLine(RAND, y, BREITE - RAND, y, linie)

        for (r in w.rows) {
            when (r) {
                is PrintRow.Section -> {
                    c.drawRect(RAND, y, BREITE - RAND, y + ABSCHNITT_HOEHE, flaeche(ABSCHNITT))
                    links(c, r.title, stift(8.5f, fett = true, farbe = GRAU), RAND, BREITE - 2 * RAND, y + 11.5f)
                    y += ABSCHNITT_HOEHE
                }
                is PrintRow.Person -> {
                    links(c, r.name, stift((zeile * 0.36f).coerceAtMost(11f), fett = true), RAND, NAME_BREITE, y + zeile / 2f + 3.5f)
                    r.cells.forEachIndexed { i, z -> zelle(c, z, RAND + NAME_BREITE + i * tagBreite, y, tagBreite, zeile, oben, unten) }
                    mitte(c, r.hours, stift(oben, fett = true), BREITE - RAND - SUMME_BREITE, SUMME_BREITE, y + zeile / 2f + 3.5f)
                    y += zeile
                }
                is PrintRow.Open -> {
                    links(c, PlanPrint.OPEN_LABEL, stift(9.5f, fett = true, farbe = ROT), RAND, NAME_BREITE, y + OFFEN_HOEHE / 2f + 3f)
                    r.cells.forEachIndexed { i, z ->
                        val x = RAND + NAME_BREITE + i * tagBreite
                        if (!z.inPlan) c.drawRect(x, y, x + tagBreite, y + OFFEN_HOEHE, flaeche(AUSSERHALB))
                        mitte(c, z.top, stift(8.5f, fett = true, farbe = ROT), x, tagBreite, y + 11f)
                        mitte(c, z.bottom, stift(7f, farbe = ROT), x, tagBreite, y + 21f)
                    }
                    mitte(c, r.total, stift(9.5f, fett = true, farbe = ROT), BREITE - RAND - SUMME_BREITE, SUMME_BREITE, y + OFFEN_HOEHE / 2f + 3f)
                    y += OFFEN_HOEHE
                }
                is PrintRow.Totals -> {
                    c.drawLine(RAND, y, BREITE - RAND, y, Paint(linie).apply { strokeWidth = 1.2f })
                    links(c, PlanPrint.TOTALS_LABEL, stift(9.5f, fett = true), RAND, NAME_BREITE, y + 14.5f)
                    r.cells.forEachIndexed { i, t ->
                        mitte(c, t, stift(9.5f, fett = true), RAND + NAME_BREITE + i * tagBreite, tagBreite, y + 14.5f)
                    }
                    mitte(c, r.total, stift(9.5f, fett = true), BREITE - RAND - SUMME_BREITE, SUMME_BREITE, y + 14.5f)
                    y += SUMMEN_HOEHE
                }
            }
            c.drawLine(RAND, y, BREITE - RAND, y, linie)
        }

        // Senkrechte Linien zwischen den Spalten, vom Kopf bis zur letzten Zeile.
        val tabelleOben = RAND + TITEL_HOEHE
        for (i in 0..7) {
            val x = RAND + NAME_BREITE + i * tagBreite
            c.drawLine(x, tabelleOben, x, y, linie)
        }
        c.drawRect(RectF(RAND, tabelleOben, BREITE - RAND, y), linie)

        val klein = stift(7.5f, farbe = GRAU)
        c.drawText("P = Pause · U = Urlaub · K = krank · Fr = frei (umrahmt: Frei-Wunsch) · Std. = bezahlte Stunden", RAND, HOEHE - RAND + 6f, klein)
        c.drawText(fuss, BREITE - RAND - klein.measureText(fuss), HOEHE - RAND + 6f, klein)
    }

    private fun zelle(c: Canvas, z: PrintCell, x: Float, y: Float, breite: Float, hoehe: Float, oben: Float, unten: Float) {
        val hintergrund = when {
            !z.inPlan -> AUSSERHALB
            z.kind == ShiftKind.EARLY -> FRUEH
            z.kind == ShiftKind.LATE -> SPAET
            z.wish -> WUNSCH
            else -> null
        }
        hintergrund?.let { c.drawRect(x + 0.5f, y + 0.5f, x + breite - 0.5f, y + hoehe - 0.5f, flaeche(it)) }
        if (z.wish) c.drawRect(x + 2f, y + 2f, x + breite - 2f, y + hoehe - 2f, wunschRahmen)
        if (z.bottom.isEmpty()) {
            // Nur ein Kürzel (U, K, Fr): groß in die Mitte. Normales "Fr" leise, damit Schichten und U/K auffallen.
            val leise = z.top == PlanPrint.FREE && !z.wish
            val farbe = when {
                z.wish -> WUNSCH_SCHRIFT
                leise -> GRAU
                else -> SCHWARZ
            }
            mitte(c, z.top, stift(oben + 1f, fett = !leise, farbe = farbe), x, breite, y + hoehe / 2f + oben / 2.8f)
        } else {
            mitte(c, z.top, stift(oben, fett = true), x, breite, y + hoehe * 0.45f)
            mitte(c, z.bottom, stift(unten, farbe = GRAU), x, breite, y + hoehe * 0.45f + unten + 2f)
        }
    }

    // ------------------------------------------------------------------ Teilen und Drucken

    private fun ordner(context: Context) = File(context.cacheDir, "pdf")

    /** PDF in den Cache schreiben (alte PDFs dort werden entfernt). Läuft im Hintergrund. */
    fun datei(context: Context, plan: ShiftPlan, leute: List<Employee>, abwesend: List<Absence>): File {
        val dir = ordner(context)
        dir.mkdirs()
        // Nur alte PDFs weg: Ein gerade geteiltes liest die Ziel-App (WhatsApp, Drive) vielleicht noch.
        val stunde = System.currentTimeMillis() - 60 * 60 * 1000L
        dir.listFiles()?.filter { it.lastModified() < stunde }?.forEach { it.delete() }
        val ziel = File(dir, PlanPrint.fileName(plan))
        FileOutputStream(ziel).use { schreiben(PlanPrint.weeks(plan, leute, abwesend), it) }
        return ziel
    }

    fun teilen(context: Context, pdf: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", pdf)
        val senden = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Schichtplan")
            clipData = ClipData.newRawUri("Schichtplan", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(senden, "Schichtplan teilen"))
    }

    /** Android-Druckdialog. Dort gibt es auch "Als PDF speichern". Braucht den Activity-Kontext. */
    fun drucken(context: Context, pdf: File, seiten: Int) {
        val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val name = pdf.nameWithoutExtension
        val adapter = object : PrintDocumentAdapter() {
            override fun onLayout(
                alt: PrintAttributes?,
                neu: PrintAttributes,
                abbruch: CancellationSignal?,
                rueckmeldung: LayoutResultCallback,
                extras: Bundle?,
            ) {
                if (abbruch?.isCanceled == true) {
                    rueckmeldung.onLayoutCancelled()
                    return
                }
                val info = PrintDocumentInfo.Builder("$name.pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(seiten)
                    .build()
                rueckmeldung.onLayoutFinished(info, alt != neu)
            }

            override fun onWrite(
                seitenAuswahl: Array<out PageRange>?,
                ziel: ParcelFileDescriptor,
                abbruch: CancellationSignal?,
                rueckmeldung: WriteResultCallback,
            ) {
                try {
                    FileInputStream(pdf).use { input -> FileOutputStream(ziel.fileDescriptor).use { input.copyTo(it) } }
                    rueckmeldung.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: IOException) {
                    rueckmeldung.onWriteFailed(e.message)
                }
            }
        }
        val attribute = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape())
            .build()
        manager.print(name, adapter, attribute)
    }
}

/** Knöpfe unter dem Wochenblatt: PDF teilen (WhatsApp, Mail, Drive) und Drucken. */
@Composable
internal fun PdfKnoepfe(
    jahresplan: YearPlan,
    montag: LocalDate,
    laden: ShopSettings,
    leute: List<Employee>,
    abwesend: List<Absence>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var arbeitet by remember { mutableStateOf(false) }
    var fehler by remember { mutableStateOf<String?>(null) }
    // Wie viele Wochen ab der gezeigten aufs PDF kommen. Bisher plante die Filialleitung 2 Wochen im Voraus.
    var wochen by rememberSaveable { mutableIntStateOf(2) }
    val plan = YearPlans.weeks(jahresplan, montag, wochen, laden)

    fun los(aktion: (File) -> Unit) {
        if (arbeitet) return
        arbeitet = true
        fehler = null
        scope.launch {
            try {
                val pdf = withContext(Dispatchers.IO) { PlanPdf.datei(context.applicationContext, plan, leute, abwesend) }
                aktion(pdf)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ActivityNotFoundException) {
                fehler = "Auf diesem Handy gibt es keine App dafür."
            } catch (e: IOException) {
                fehler = "Das PDF ließ sich nicht erstellen: ${e.message ?: "unbekannter Fehler"}. Ist der Speicher voll?"
            } catch (e: Exception) {
                fehler = "Das PDF ließ sich nicht erstellen: ${e::class.java.simpleName} ${e.message.orEmpty()}"
            } finally {
                arbeitet = false
            }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("PDF ab dieser Woche", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextMittel)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 2, 4).forEach { n ->
                Auswahl(
                    if (n == 1) "1 Woche" else "$n Wochen",
                    gewaehlt = wochen == n,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("pdf-wochen-$n"),
                ) { wochen = n }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { los { PlanPdf.teilen(context, it) } },
                enabled = !arbeitet,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
                    .testTag("pdf-teilen"),
            ) { Text("PDF teilen", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Akzent, textAlign = TextAlign.Center) }
            OutlinedButton(
                onClick = { los { PlanPdf.drucken(context, it, plan.weeks) } },
                enabled = !arbeitet,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
                    .testTag("pdf-drucken"),
            ) { Text("Drucken", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Akzent, textAlign = TextAlign.Center) }
        }
        fehler?.let { Hinweis(it) }
    }
}
