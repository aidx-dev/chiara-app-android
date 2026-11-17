package com.chiarasystems.chiara.domain

/**
 * Port of iOS `GroundingCheck`. Finds what a generated text states that its sources do not: dates (`yyyy-MM-dd`) and
 * ICD-10 codes, the details a reader acts on and the ones a small model has been seen to make up.
 */
object GroundingCheck {
    private val date = Regex("""\d{4}-\d{2}-\d{2}""")
    private val code = Regex("""\b[A-Z]\d{2}(?:\.\d{1,2})?\b""")

    /** Each unsupported date or code once, in the order they appear (all dates first, then all codes). */
    fun unsupported(text: String, sources: List<String>): List<String> {
        val found = date.findAll(text).map { it.value }.toList() + code.findAll(text).map { it.value }.toList()
        val seen = mutableSetOf<String>()
        return found.filter { item -> seen.add(item) && sources.none { it.contains(item) } }
    }
}
