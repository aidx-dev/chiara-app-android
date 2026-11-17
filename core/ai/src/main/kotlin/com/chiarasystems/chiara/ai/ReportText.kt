package com.chiarasystems.chiara.ai

/**
 * The clean-up the desktop app runs on every section the model writes (`reports_service.rs`), ported line by line so
 * both apps give the same text.
 */
object ReportText {
    /** `clean_section`: think tags, markdown, an echoed title, repeated sentences and "not recorded" sentences. */
    fun cleanSection(raw: String): String =
        dropAbsenceLines(dedupeSentences(stripLeadingTitle(stripMarkdown(stripThinkTags(raw)))))

    fun stripThinkTags(text: String): String {
        val out = StringBuilder()
        var rest = text
        while (true) {
            val open = listOf("<think>", "<thinking>").mapNotNull { tag -> rest.indexOf(tag).takeIf { it >= 0 }?.let { it to tag } }.minByOrNull { it.first }
            if (open == null) { out.append(rest); break }
            out.append(rest, 0, open.first)
            rest = rest.substring(open.first + open.second.length)
            val close = if (open.second == "<think>") "</think>" else "</thinking>"
            val end = rest.indexOf(close)
            if (end < 0) break
            rest = rest.substring(end + close.length)
        }
        return out.toString().trim()
    }

    fun stripMarkdown(text: String): String =
        text.split("\n").map { line ->
            val trimmed = line.dropWhile { it.isWhitespace() }
            when {
                trimmed.startsWith("#") -> ""
                trimmed.startsWith("**") && trimmed.trimEnd().endsWith("**") -> ""
                trimmed.startsWith("- ") -> trimmed.drop(2)
                else -> line
            }
        }.filter { it.isNotEmpty() }.joinToString("\n").trim()

    /** Drops a leading title line the model sometimes echoes (`**Title**`, `**Title:**`, `Title :`). */
    fun stripLeadingTitle(text: String): String {
        val lines = text.split("\n")
        val first = lines.firstOrNull() ?: return text
        val t = first.trim { it == ' ' || it == '\t' }
        val tail = t.trimEnd { it == ':' || it.isWhitespace() }
        val boldWrapped = t.startsWith("**") && tail.endsWith("**")
        val colonTitle = t.endsWith(":") && !t.contains(".")
        val isTitle = t.length <= 120 && (boldWrapped || colonTitle)
        return if (isTitle && lines.size > 1) lines.drop(1).joinToString("\n").trim() else text
    }

    /** Sentences keep their terminator and the whitespace after it; a period not followed by whitespace does not end one. */
    fun splitSentences(line: String): List<String> {
        val sentences = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < line.length) {
            val c = line[i]
            i++
            if (c != '.' && c != '!' && c != '?') continue
            if (i < line.length && !line[i].isWhitespace()) continue
            while (i < line.length && line[i].isWhitespace()) i++
            sentences += line.substring(start, i)
            start = i
        }
        if (start < line.length) sentences += line.substring(start)
        return sentences
    }

    private fun normalizeSentence(sentence: String): String =
        sentence.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").trimEnd('.', '!', '?').lowercase()

    /** Shortest sentence that counts as a repeat: short ones like "No consta." legitimately appear several times. */
    const val MIN_REPEATED_SENTENCE_CHARS = 25

    /** Drops sentences that already appeared earlier in the text (a greedy-decoding loop). */
    fun dedupeSentences(text: String): String {
        val seen = HashSet<String>()
        return text.split("\n").map { line ->
            splitSentences(line).filter { s ->
                val norm = normalizeSentence(s)
                norm.length < MIN_REPEATED_SENTENCE_CHARS || seen.add(norm)
            }.joinToString("").trimEnd()
        }.filter { it.trim { c -> c == ' ' || c == '\t' }.isNotEmpty() }.joinToString("\n")
    }

    private val absencePrefixes = listOf(
        "no consta ", "no constan ", "no se menciona", "no se especifica", "no hay información",
        "no se dispone de información", "not stated ", "no information ", "keine angabe ", "non renseigné ",
        "aucune information ", "aucun antécédent n'est mentionné",
    )

    /** A sentence that only says something is not recorded. A bare "No consta" is not one: it marks an empty section. */
    fun isAbsenceSentence(sentence: String): Boolean {
        val l = sentence.trim().lowercase()
        return absencePrefixes.any { l.startsWith(it) }
    }

    /** Removes sentences that only say something is not recorded, but never empties the section. */
    fun dropAbsenceLines(text: String): String {
        val kept = text.split("\n")
            .map { line -> splitSentences(line).filter { !isAbsenceSentence(it) }.joinToString("").trimEnd() }
            .filter { it.trim { c -> c == ' ' || c == '\t' }.isNotEmpty() }
        return if (kept.isEmpty()) text else kept.joinToString("\n")
    }

    /** Cuts the unfinished sentence a reply ends with when it hit `max_tokens`. */
    fun trimUnfinishedTail(text: String): String {
        val i = text.indexOfLast { it in ".!?\n" }
        if (i < 0) return text
        return text.substring(0, i + 1).trimEnd()
    }

    /** A yes/no reply in any report language; null when it is neither. */
    fun parseYesNo(reply: String): Boolean? {
        val word = reply.dropWhile { !it.isLetter() }.takeWhile { it.isLetter() }.lowercase()
        return when (word) {
            "sí", "si", "yes", "ja", "oui" -> true
            "no", "nein", "non" -> false
            else -> null
        }
    }
}
