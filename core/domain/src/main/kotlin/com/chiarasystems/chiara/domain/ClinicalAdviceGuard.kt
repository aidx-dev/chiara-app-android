package com.chiarasystems.chiara.domain

/**
 * Port of iOS `ClinicalAdviceGuard`. Spots requests for a diagnosis or a treatment ("sugiéreme un diagnóstico",
 * "cómo puedo tratarlo"). The assistant only reports what the record says and never advises; a small model ignores that
 * when it is only in its instructions, so these requests are answered here, before any model sees them.
 * Asking what the record CONTAINS ("¿cuál es el diagnóstico de Pablo?") is not advice and passes.
 */
object ClinicalAdviceGuard {
    /** Matched against the request without accents or capitals (Spanish, English, German, French). */
    private val patterns: List<Regex> = listOf(
        // Spanish
        "sugerencia de (diagnostico|tratamiento)", "sugiere(me)? (un|el|algun)", "sugiereme", "recomiend",
        "como (puedo|debo|debemos|podria|podemos|se) (trat|cur|medic)", "como (trato|curo|trata|curar|tratar)",
        "que (le )?(receto|pauto|prescribo|debo dar|le doy)", "que tratamiento (le )?(doy|pauto|debo|puedo|deberia)",
        "que (deberia|debo|puedo) (hacer|recetar|prescribir|darle)",
        "posible(s)? diagnostico", "diagnostico (diferencial|probable|presuntivo)",
        // English
        "suggest(ion)? (a |an |the )?(diagnos|treat)", "how (can|should|do|could) (i|we) (treat|cure|manage)",
        "what should i (prescribe|give)", "recommend", "differential diagnos", "possible diagnos",
        // German
        "diagnosevorschlag", "wie (kann|soll) ich .{0,20}behandeln", "empfiehl", "behandlungsvorschlag",
        // French
        "suggere", "comment (puis-je|dois-je|peut-on) (le |la )?(traiter|soigner)", "que (dois|puis)-je prescrire", "diagnostic differentiel",
    ).map { Regex(it) }

    fun isAskingForAdvice(text: String): Boolean {
        val folded = PatientMatcher.fold(text)
        return patterns.any { it.containsMatchIn(folded) }
    }
}
