package com.chiarasystems.chiara.ai

import java.time.Instant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable

/** A report written from a consultation's transcript, kept on the device next to the recording. */
@Serializable
data class GeneratedReport(
    val templateId: String,
    val templateVersion: Int,
    /** The language it is written in ("Español", "English"…), the patient's. */
    val language: String,
    val generatedAtMillis: Long,
    /** The models that answered, in order of first use. */
    val models: List<String>,
    val sections: List<Section>,
) {
    @Serializable
    data class Section(val fieldId: String, val title: String, val text: String)

    val plainText: String get() = sections.joinToString("\n\n") { "${it.title}\n${it.text}" }
}

sealed class ReportGeneratorError : Exception() {
    /** No section came out with text of its own (`SIN_CONTENIDO_REAL` in the desktop). */
    data object NoContent : ReportGeneratorError()
}

/** Where a report is, for the progress line. */
sealed interface ReportProgress {
    /** 0..1 over the whole report. */
    val fraction: Double

    data class Summarizing(val block: Int, val of: Int) : ReportProgress { override val fraction get() = 0.2 * (block - 1) / maxOf(of, 1) }
    data class Writing(val section: Int, val of: Int) : ReportProgress { override val fraction get() = 0.2 + 0.5 * (section - 1) / maxOf(of, 1) }
    data class Reviewing(val section: Int, val of: Int) : ReportProgress { override val fraction get() = 0.7 + 0.3 * (section - 1) / maxOf(of, 1) }
}

/**
 * The transcript in blocks for the summary that comes before the report (`rolling_summary.rs`): a block closes once it
 * reaches about 3000 recogniser tokens, and the next starts with the last 200 words of the previous as context.
 */
object RollingSummary {
    const val THRESHOLD = 3000
    const val OVERLAP_WORDS = 200

    data class Block(/** What is sent to the summarizer: the previous block's tail, then this block. */ val input: String, /** This block alone. */ val text: String)

    /** Recogniser tokens of a line, estimated from the words (Spanish speech is about 1.3 tokens a word). */
    fun tokens(line: String): Int = Math.ceil(words(line).size * 1.3).toInt()

    private fun words(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun blocks(lines: List<String>): List<Block> {
        val out = mutableListOf<Block>()
        var pending = ""
        var pendingTokens = 0
        var prevTail = ""
        fun flush() {
            if (pending.isEmpty()) return
            out += Block(if (prevTail.isEmpty()) pending else "$prevTail\n$pending", pending)
            prevTail = lastWords(pending, OVERLAP_WORDS)
            pending = ""
            pendingTokens = 0
        }
        for (line in lines) {
            val text = line.trim()
            if (text.isEmpty()) continue
            pending += if (pending.isEmpty()) text else "\n$text"
            pendingTokens += tokens(text)
            if (pendingTokens >= THRESHOLD) flush()
        }
        flush()
        return out
    }

    fun lastWords(text: String, count: Int): String = words(text).takeLast(count).joinToString(" ")
}

/**
 * Writes a consultation report from its transcript the way the desktop app does (`reports_service.rs`, sectioned layout):
 * a summary of the transcript first, then each section of the specialty's template from it (pass 1), then each section
 * reviewed against the ones before (pass 2), every answer cleaned like the desktop's.
 */
class ReportGenerator(
    /** One chat completion: system prompt, user message, max tokens. */
    private val complete: suspend (String, String, Int) -> OpenRouterChat.Completion,
    private val now: () -> Long = { Instant.now().toEpochMilli() },
) {
    suspend fun generate(
        lines: List<String>, template: ReportTemplate, language: String, patientSex: String?,
        progress: (ReportProgress) -> Unit = {},
    ): GeneratedReport {
        val models = mutableListOf<String>()
        suspend fun call(system: String, user: String, maxTokens: Int): String {
            val c = complete(system, user, maxTokens)
            if (c.model !in models) models += c.model
            return c.text
        }

        // ── Summary of the transcript, block by block
        val blocks = RollingSummary.blocks(lines)
        val summaries = mutableListOf<String>()
        var anySummary = false
        blocks.forEachIndexed { i, block ->
            currentCoroutineContext().ensureActive()
            progress(ReportProgress.Summarizing(i + 1, blocks.size))
            val user = template.summaryUserPrompt.replace("{TEXT}", block.input)
            try {
                val summary = ReportText.dedupeSentences(call(template.summarySystemPrompt, user, 3000).trim()).trim()
                if (summary.isEmpty()) throw ChatError.Unreadable
                summaries += summary
                anySummary = true
            } catch (e: ChatError) {
                // A missing key or a key problem stops the report; anything else leaves the block as spoken.
                if (e is ChatError.NotAvailable || isKeyProblem(e)) throw e
                summaries += block.text
            }
        }
        val transcript = if (anySummary) summaries.joinToString("\n\n") else lines.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

        val sexRule = ReportPrompts.sexRule(patientSex)
        val sections = template.generationSections

        // ── Pass 1: each section on its own
        val pass1 = mutableListOf<String>()
        sections.forEachIndexed { i, section ->
            currentCoroutineContext().ensureActive()
            progress(ReportProgress.Writing(i + 1, sections.size))
            val sourceIndex = section.sourceFieldId?.let { s -> sections.indexOfFirst { it.fieldId == s } } ?: -1
            val input = if (sourceIndex >= 0 && sourceIndex < pass1.size) pass1[sourceIndex] else transcript
            val system = ReportPrompts.reportSystemPrompt(template, language) + sexRule

            // Only an enforced gate changes the report; an observed one is only logged by the desktop.
            var retryOnNoConsta = false
            if (section.evidenceGate == "enforce" && section.evidenceQuestion != null && section.sourceFieldId == null) {
                val answer = runCatching { call(system, "$input\n\n---\n${section.evidenceQuestion} Responde solo SÍ o NO.", 3) }.getOrNull()
                val parsed = answer?.let(ReportText::parseYesNo)
                if (parsed == false) { pass1 += ReportPrompts.noConsta(language); return@forEachIndexed }
                retryOnNoConsta = parsed == true
            }

            val user = "$input\n\n${ReportPrompts.localizeSectionText(section.prompt, language)}"
            var cleaned = ReportText.cleanSection(call(system, user, section.maxTokens))
            if (retryOnNoConsta && ReportPrompts.isNoConsta(cleaned)) {
                cleaned = ReportText.cleanSection(call(system, "$user $EVIDENCE_RETRY_HINT", section.maxTokens))
            }
            pass1 += cleaned
        }

        // ── Pass 2: each section reviewed against the ones before
        val pass2 = mutableListOf<String>()
        sections.forEachIndexed { i, section ->
            currentCoroutineContext().ensureActive()
            progress(ReportProgress.Reviewing(i + 1, sections.size))
            // Nothing to polish: the review would only write content of its own.
            if (pass1[i].trim().isEmpty() || ReportPrompts.isNoConsta(pass1[i])) { pass2 += ReportPrompts.noConsta(language); return@forEachIndexed }
            val prior = if (i > 0) {
                "Las siguientes secciones del informe ya han sido redactadas:\n\n" +
                    (0 until i).joinToString("\n\n") { "## ${sections[it].name}\n${pass2[it]}" } + "\n\n---\n\n"
            } else ""
            val user = ReportPrompts.reviewUserMessage(
                template, section.name, ReportPrompts.localizeSectionText(section.reviewBody, language), prior, pass1[i],
                if (i > 0) ReportTemplates.common.noRepeat else "",
            )
            val system = ReportPrompts.reviewSystemPrompt(template, language) + sexRule
            pass2 += ReportText.cleanSection(call(system, user, section.maxTokens))
        }

        return assemble(template, pass2, language, models, now())
    }

    companion object {
        /** Said when the transcript has the information a gated section needs but the model still wrote "No consta". */
        const val EVIDENCE_RETRY_HINT = "La transcripción sí contiene esta información: escríbela tal como aparece, sin añadir nada."

        /** Each section's final text in its field (`assemble_content`), in the template's field order. */
        fun assemble(template: ReportTemplate, texts: List<String>, language: String, models: List<String>, generatedAtMillis: Long = Instant.now().toEpochMilli()): GeneratedReport {
            val content = LinkedHashMap<String, String>()
            var hasGeneratedText = false
            template.generationSections.zip(texts).forEach { (section, raw) ->
                val text = raw.trim()
                if (text.isEmpty() || ReportPrompts.isNoConsta(text)) {
                    section.emptyFallback?.let { content[section.fieldId] = ReportPrompts.localized(it, language) }
                } else {
                    hasGeneratedText = true
                    content[section.fieldId] = text
                }
            }
            if (!hasGeneratedText) throw ReportGeneratorError.NoContent
            val index = ReportPrompts.languageIndex(language)
            val names = template.generationSections.associate { it.fieldId to it.name }
            val order = (template.fields.map { it.id } + template.generationSections.map { it.fieldId }).distinct()
            val sections = order.mapNotNull { id ->
                val text = content[id] ?: return@mapNotNull null
                val title = template.field(id)?.let { f -> f.titles.getOrNull(index) ?: f.titles[0] } ?: names[id] ?: id
                GeneratedReport.Section(id, title, text)
            }
            return GeneratedReport(template.id, template.version, language, generatedAtMillis, models, sections)
        }

        private fun isKeyProblem(e: ChatError) = e is ChatError.Rejected && e.status in setOf(401, 402, 403)
    }
}
