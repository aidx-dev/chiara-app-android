package com.chiarasystems.chiara

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import com.chiarasystems.chiara.designsystem.CalFormat
import com.chiarasystems.chiara.designsystem.Translator
import com.chiarasystems.chiara.model.ClinicBranding
import com.chiarasystems.chiara.model.Dates
import com.chiarasystems.chiara.model.Patient
import com.chiarasystems.chiara.model.Report
import com.chiarasystems.chiara.model.ReportSection
import com.chiarasystems.chiara.model.UserProfile
import com.chiarasystems.chiara.model.nilIfEmpty
import com.chiarasystems.chiara.model.trimmed
import java.io.File

/**
 * Port of iOS `ReportPDF`: the report as a paginated A4 PDF (595.2 × 841.8 pt, 48 pt margins) in the cache directory,
 * ready to share through the FileProvider. Layout: one styled text flowed over as many pages as needed.
 */
object ReportPdf {
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 48
    private val ink = Color.rgb(29, 29, 31)
    private val blue = Color.rgb(0, 91, 182)
    private val muted = Color.rgb(94, 94, 99)

    fun make(
        context: Context, report: Report, patient: Patient?, doctor: UserProfile?, branding: ClinicBranding,
        tr: Translator, cal: CalFormat,
    ): File? {
        val text = SpannableStringBuilder()
        // Sizes are pt on a 72 dpi page, so 1 unit = 1 px for the StaticLayout.
        fun add(s: String, size: Int, bold: Boolean = false, color: Int = ink, before: Int = 0, after: Int = 0) {
            repeat(before / (size + 2)) { text.append("\n") }
            val start = text.length
            text.append(s).append("\n")
            text.setSpan(AbsoluteSizeSpan(size, false), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(ForegroundColorSpan(color), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (bold) text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            repeat(after / (size + 2)) { text.append("\n") }
        }

        val clinic = branding.clinicName?.nilIfEmpty ?: doctor?.clinicName
        if (clinic != null) add(clinic, 15, bold = true, color = blue)
        val contact = listOf(branding.clinicAddress, branding.clinicPhone, branding.clinicEmail)
            .mapNotNull { it?.trimmed?.nilIfEmpty }.joinToString(" · ")
        if (contact.isNotEmpty()) add(contact, 9, color = muted, after = 18)

        val title = report.title ?: tr.tr("Informe de consulta")
        add(tr.tr("INFORME DE CONSULTA"), 9, bold = true, color = muted, before = 6)
        add(title, 20, bold = true, after = 6)
        val date = report.date?.toLocalDate()
        val meta = mutableListOf(date?.let(cal::longDayYear).orEmpty())
        if (patient != null) {
            meta.add(0, patient.fullName)
            patient.documentId?.nilIfEmpty?.let { meta.add(1, "${tr.tr("DNI")} $it") }
        }
        add(meta.joinToString(" · "), 11, color = muted, after = 10)

        for (section in report.sections) {
            add(tr.tr(section.title).uppercase(), 9, bold = true, color = blue, before = 14, after = 3)
            when (val b = section.body) {
                is ReportSection.Body.Text -> add(b.text, 11)
                is ReportSection.Body.Diagnoses -> {
                    if (b.text.isNotEmpty()) add(b.text, 11)
                    b.codes.forEach { add("${it.code} — ${it.label}", 11) }
                }
                is ReportSection.Body.Medication -> b.lines.forEach { add(if (it.dose.isEmpty()) it.name else "${it.name} — ${it.dose}", 11) }
            }
        }
        val doctorName = branding.doctorName?.nilIfEmpty ?: doctor?.fullName
        val license = branding.doctorLicense?.nilIfEmpty
        if (doctorName != null || license != null) {
            add(doctorName.orEmpty(), 11, bold = true, before = 28)
            license?.let { add(tr.tr("Colegiado %s", it), 9, color = muted) }
        }

        val width = PAGE_W - 2 * MARGIN
        val height = PAGE_H - 2 * MARGIN
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, TextPaint().apply { isAntiAlias = true; textSize = 11f }, width)
            .setLineSpacing(0f, 1.25f).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()

        val doc = PdfDocument()
        try {
            var line = 0
            var pageNo = 1
            while (line < layout.lineCount || pageNo == 1) {
                val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
                val top = layout.getLineTop(line)
                var last = line
                while (last < layout.lineCount && layout.getLineBottom(last) - top <= height) last++
                if (last == line) last = line + 1 // a line taller than the page still has to move on
                val bottom = layout.getLineTop(last)
                page.canvas.save()
                page.canvas.translate(MARGIN.toFloat(), MARGIN.toFloat() - top)
                page.canvas.clipRect(0f, top.toFloat(), width.toFloat(), bottom.toFloat())
                layout.draw(page.canvas)
                page.canvas.restore()
                doc.finishPage(page)
                line = last
                pageNo++
                if (line >= layout.lineCount) break
            }
            val name = "${tr.tr("Informe")}-${patient?.lastName?.nilIfEmpty ?: "paciente"}-${date?.let(Dates::iso) ?: "sin-fecha"}.pdf".replace("/", "-")
            val dir = File(context.cacheDir, "reports").apply { mkdirs() }
            val file = File(dir, name)
            file.outputStream().use { doc.writeTo(it) }
            return file
        } catch (e: Exception) {
            return null
        } finally {
            doc.close()
        }
    }
}
