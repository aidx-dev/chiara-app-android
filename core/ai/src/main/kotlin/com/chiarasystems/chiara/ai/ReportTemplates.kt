package com.chiarasystems.chiara.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A specialty's report template, exported from the desktop app (`ReportTemplates.json`, same file as iOS). The prompts
 * are the desktop's, byte for byte; only the code that assembles them is ported ([ReportPrompts]).
 */
@Serializable
data class ReportTemplate(
    val id: String,
    val version: Int,
    val fields: List<Field>,
    val reportSystemPrompt: String,
    val reviewSystemPrompt: String,
    val reviewIntro: String,
    val summarySystemPrompt: String,
    val summaryUserPrompt: String,
    val generationSections: List<Section>,
) {
    @Serializable
    data class Field(val id: String, /** Titles in Spanish, English, German and French. */ val titles: List<String>, val kind: String)

    @Serializable
    data class Section(
        val name: String,
        val prompt: String,
        val reviewBody: String,
        val maxTokens: Int,
        val fieldId: String,
        /** Written from another section's draft instead of the transcript. */
        val sourceFieldId: String? = null,
        /** What the field says when the section is empty, by language; null: the field is left out. */
        val emptyFallback: List<String>? = null,
        val evidenceQuestion: String? = null,
        /** "off", "observe" or "enforce". */
        val evidenceGate: String = "off",
    )

    fun field(id: String): Field? = fields.firstOrNull { it.id == id }
}

/** The texts every template shares (`templates/prompts.rs`). */
@Serializable
data class ReportCommon(
    val noRepeat: String,
    val noConsta: List<String>,
    val backgroundLabels: List<List<String>>,
    val sexRuleFemale: String,
    val sexRuleMale: String,
)

object ReportTemplates {
    @Serializable
    private data class File(val source: String, val common: ReportCommon, val templates: List<ReportTemplate>)

    const val DEFAULT_ID = "vascular"

    private val file: File by lazy {
        val text = ReportTemplates::class.java.getResourceAsStream("/ReportTemplates.json")?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("ReportTemplates.json is missing")
        Json { ignoreUnknownKeys = true }.decodeFromString<File>(text)
    }

    val all: List<ReportTemplate> get() = file.templates
    val common: ReportCommon get() = file.common
    /** The aidx commit the templates were exported from. */
    val source: String get() = file.source

    /** "plastic_surgery", "plasticSurgery" and "plasticsurgery" are the same specialty. */
    private fun normalized(id: String) = id.lowercase().filter { it.isLetter() }

    fun byId(id: String): ReportTemplate? = normalized(id).let { key -> all.firstOrNull { normalized(it.id) == key } }

    fun resolve(id: String?): ReportTemplate = id?.let(::byId) ?: byId(DEFAULT_ID)!!

    /** The templates of the doctor's specialties, in their order; the default one when none matches. */
    fun forSpecialties(specialties: List<String>): List<ReportTemplate> {
        val unique = specialties.mapNotNull(::byId).distinctBy { it.id }
        return unique.ifEmpty { listOf(resolve(null)) }
    }
}

/** How the desktop puts the prompts together (`templates/prompts.rs`). */
object ReportPrompts {
    /** Index of the report language in the four-language tables: Spanish unless it is English, German or French. */
    fun languageIndex(language: String): Int = when (language.trim().lowercase()) {
        "english", "en" -> 1
        "deutsch", "german", "de" -> 2
        "français", "francais", "french", "fr" -> 3
        else -> 0
    }

    fun reportSystemPrompt(t: ReportTemplate, language: String) = t.reportSystemPrompt.replace("{LANGUAGE}", language.uppercase())
    fun reviewSystemPrompt(t: ReportTemplate, language: String) = t.reviewSystemPrompt.replace("{LANGUAGE}", language.uppercase())

    /** So the patient is written about with the right grammatical gender; empty when the record has no sex. */
    fun sexRule(sex: String?): String = when (sex?.trim()?.lowercase()) {
        "female" -> ReportTemplates.common.sexRuleFemale
        "male" -> ReportTemplates.common.sexRuleMale
        else -> ""
    }

    fun reviewUserMessage(t: ReportTemplate, sectionName: String, reviewBody: String, priorBlock: String, draft: String, noRepeat: String): String {
        val intro = t.reviewIntro.replace("{SECTION}", sectionName)
        return "$priorBlock$intro\n\n$draft\n\n$reviewBody$noRepeat"
    }

    /** "No consta." in the report language. */
    fun noConsta(language: String): String = ReportTemplates.common.noConsta[languageIndex(language)] + "."

    fun localized(texts: List<String>, language: String): String {
        val i = languageIndex(language)
        return if (i < texts.size) texts[i] else texts.firstOrNull().orEmpty()
    }

    /** Whether the text is, in any supported language, the "no information" mark. */
    fun isNoConsta(text: String): Boolean {
        var t = text.trim()
        while (t.endsWith(".")) t = t.dropLast(1)
        t = t.trim()
        return ReportTemplates.common.noConsta.any { it.equals(t, ignoreCase = true) }
    }

    /** A section's text in the report language: fixed labels and "No consta", plus an explicit order. Spanish is untouched. */
    fun localizeSectionText(text: String, language: String): String {
        val i = languageIndex(language)
        if (i == 0) return text
        var out = text
        for (row in ReportTemplates.common.backgroundLabels) out = out.replace(row[0], row[i])
        out = out.replace("No consta", ReportTemplates.common.noConsta[i])
        return "$out Escribe TODO el contenido, incluidas las etiquetas de campo, exclusivamente en el idioma: ${language.trim().uppercase()}."
    }
}
