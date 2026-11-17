package com.chiarasystems.chiara.model

import java.time.OffsetDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Raw row of `list_consultations_with_latest_report`. Decoding is lenient, like iOS. */
@Serializable
data class ConsultationDto(
    val id: String,
    @SerialName("patient_id") val patientId: String,
    @SerialName("occurred_at") val occurredAt: String? = null,
    @SerialName("template_id") val templateId: String? = null,
    val status: String? = null,
    val content: JsonObject? = null,
) {
    fun toItem(): ConsultationItem = ConsultationItem(
        id = id,
        patientId = patientId,
        occurredAt = Dates.parseServerTimestamp(occurredAt),
        templateId = templateId,
        status = status,
        // Non-string values are dropped instead of failing the whole history.
        content = content?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { k to it } }?.toMap(),
    )
}

/** A consultation plus the content of its latest report revision (`[fieldId: HTML or JSON text]`). */
data class ConsultationItem(
    val id: String,
    val patientId: String,
    val occurredAt: OffsetDateTime?,
    val templateId: String?,
    val status: String?,
    val content: Map<String, String>?,
) {
    val hasReport: Boolean get() = content?.values?.any { it.trimmed.isNotEmpty() } ?: false
}

@Serializable
data class IcdCode(val code: String, val label: String) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** The value of an `icdMultiSelect` field is a JSON array serialized in a string. */
        fun parse(value: String?): List<IcdCode> =
            if (value == null) emptyList()
            else runCatching { json.decodeFromString<List<IcdCode>>(value) }.getOrDefault(emptyList())
    }
}

data class MedicationLine(val name: String, val dose: String)

data class ReportSection(val id: String, val title: String, val body: Body) {
    sealed interface Body {
        data class Text(val text: String) : Body
        data class Diagnoses(val text: String, val codes: List<IcdCode>) : Body
        data class Medication(val lines: List<MedicationLine>) : Body
    }
}

/** Presentation model of a report. [title] is null when there is no first sentence (UI shows "Informe de consulta"). */
data class Report(
    val id: String,
    val patientId: String,
    val date: OffsetDateTime?,
    val title: String?,
    val codes: List<IcdCode>,
    val sections: List<ReportSection>,
)

object ReportBuilder {
    private val order = listOf(
        "reasonForConsultation", "background", "reducedAnamnesis", "anamnesis", "personalHistory",
        "examination", "additionalTests", "tests", "clinicalAssessment", "diagnosis", "icdCodes", "icd",
        "medication", "treatmentPlan", "conclusions", "visitRecord", "fullReport",
    )

    // Spanish titles (source language). Other languages are looked up by field id in the UI layer.
    val titles: Map<String, String> = mapOf(
        "reasonForConsultation" to "Motivo de consulta",
        "background" to "Antecedentes",
        "reducedAnamnesis" to "Anamnesis reducida",
        "anamnesis" to "Anamnesis",
        "personalHistory" to "Historia personal",
        "examination" to "Exploración",
        "additionalTests" to "Pruebas complementarias",
        "tests" to "Pruebas complementarias",
        "clinicalAssessment" to "Evaluación clínica",
        "diagnosis" to "Diagnósticos",
        "icdCodes" to "Diagnósticos",
        "icd" to "Diagnósticos",
        "medication" to "Medicación",
        "treatmentPlan" to "Plan terapéutico",
        "conclusions" to "Conclusiones",
        "visitRecord" to "Registro de visita",
        "fullReport" to "Informe",
    )

    fun build(item: ConsultationItem): Report? {
        if (!item.hasReport) return null
        val content = item.content ?: return null

        val codes = IcdCode.parse(content["icdCodes"] ?: content["icd"])
        val sections = mutableListOf<ReportSection>()
        val consumed = mutableSetOf<String>()

        fun rank(k: String) = order.indexOf(k).let { if (it < 0) Int.MAX_VALUE else it }
        val keys = content.keys.sortedWith(Comparator { a, b ->
            val ia = rank(a)
            val ib = rank(b)
            if (ia == ib) a.compareTo(b) else ia.compareTo(ib)
        })

        for (key in keys) {
            if (key in consumed) continue
            val raw = content[key].orEmpty()
            if (raw.trimmed.isEmpty()) continue
            when (key) {
                "diagnosis", "icdCodes", "icd" -> {
                    // One DIAGNÓSTICOS block: free text and the CIE-10 chips.
                    if ("diagnosis" in consumed) continue
                    consumed += listOf("diagnosis", "icdCodes", "icd")
                    val text = HtmlText.plain(content["diagnosis"].orEmpty())
                    if (text.isNotEmpty() || codes.isNotEmpty()) {
                        sections += ReportSection("diagnoses", titles.getValue("diagnosis"), ReportSection.Body.Diagnoses(text, codes))
                    }
                }
                "medication" -> {
                    val meds = HtmlText.plain(raw).split("\n").filter { it.isNotEmpty() }
                        .map { it.replace("• ", "") }
                        .map { line ->
                            val parts = line.split(" — ")
                            MedicationLine(parts[0], parts.drop(1).joinToString(" — "))
                        }
                    if (meds.isNotEmpty()) sections += ReportSection(key, titles.getValue(key), ReportSection.Body.Medication(meds))
                }
                else -> {
                    val text = HtmlText.plain(raw)
                    if (text.isNotEmpty()) sections += ReportSection(key, titles[key] ?: key.humanized, ReportSection.Body.Text(text))
                }
            }
        }
        if (sections.isEmpty()) return null
        return Report(item.id, item.patientId, item.occurredAt, title(content), codes, sections)
    }

    /** First meaningful sentence of the report, as the row title. */
    private fun title(content: Map<String, String>): String? {
        for (key in listOf("diagnosis", "reasonForConsultation", "clinicalAssessment", "anamnesis", "reducedAnamnesis", "fullReport")) {
            val text = HtmlText.plain(content[key].orEmpty())
            val first = text.split("\n").firstOrNull { it.isNotEmpty() } ?: continue
            val sentence = first.split(".").firstOrNull { it.isNotEmpty() } ?: first
            return if (sentence.length > 70) sentence.take(67) + "…" else sentence
        }
        return null
    }
}

/** Report fields are TipTap HTML. This reduces them to plain paragraphs and bullets. */
object HtmlText {
    private val li = Regex("<li[^>]*>")
    private val breaks = Regex("<br\\s*/?>|</p>|</li>|</h[1-6]>|</ul>|</ol>")
    private val tags = Regex("<[^>]+>")
    private val invisible = Regex("[\\u200B\\u200C\\u200D\\uFEFF\\u00AD\\u2060]")
    private val trailingBlanks = Regex("[ \\t]+\\n")
    private val manyNewlines = Regex("\\n{3,}")

    fun plain(html: String): String {
        var s = html
        s = li.replace(s, "\n• ")
        s = breaks.replace(s, "\n")
        s = tags.replace(s, "")
        s = decodeEntities(s)
        s = invisible.replace(s, "")
        s = trailingBlanks.replace(s, "\n")
        s = manyNewlines.replace(s, "\n\n")
        return s.trimmed
    }

    private fun decodeEntities(s: String): String {
        var out = s
        for ((k, v) in listOf("&nbsp;" to " ", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'")) {
            out = out.replace(k, v)
        }
        return out.replace("&amp;", "&") // last, so "&amp;lt;" stays "&lt;"
    }
}
