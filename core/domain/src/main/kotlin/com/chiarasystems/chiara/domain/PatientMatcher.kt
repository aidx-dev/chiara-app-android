package com.chiarasystems.chiara.domain

import com.chiarasystems.chiara.model.Patient
import com.chiarasystems.chiara.model.trimmed
import java.text.Normalizer
import java.util.Locale

/**
 * Port of iOS `PatientMatcher`. Finds the patient a name refers to; shared by the assistant's commands and the read-only
 * lookups of its tools, so both resolve a name the same way.
 */
object PatientMatcher {
    sealed interface Match {
        data object None : Match
        data class One(val patient: Patient) : Match
        data class Many(val patients: List<Patient>) : Match
    }

    /** Words that come with a name but are not part of it ("el paciente Pablo", "Dr. Soler"). */
    private val fillers: Set<String> = setOf(
        "paciente", "pacientes", "el", "la", "los", "las", "de", "del", "al", "a", "y", "sr", "sra", "senor", "senora",
        "don", "dona", "dr", "dra", "doctor", "doctora", "informe", "informes", "ficha", "historial", "citas", "cita",
        "patient", "patients", "the", "of", "mr", "mrs", "ms", "report", "reports", "record",
        "patientin", "herr", "frau", "von", "der", "die", "bericht", "berichte", "akte",
        "patiente", "le", "les", "des", "du", "m", "mme", "rapport", "rapports", "dossier",
    )

    private val combiningMarks = Regex("\\p{M}+")

    /** Trim, drop accents (NFD + remove marks) and case. "Núñez" -> "nunez". */
    fun fold(s: String): String =
        combiningMarks.replace(Normalizer.normalize(s.trimmed, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

    /** Every word typed has to start a word of the name (or be the ID document). */
    fun match(query: String, patients: List<Patient>): Match {
        val tokens = nameTokens(query)
        if (tokens.isEmpty()) return Match.None
        return result(patients.filter { matches(tokens, it) })
    }

    /**
     * [match], and when nobody has every word, the patients with the most of them, provided at least two words agree.
     * Only for reading: a change is never made on a near match.
     */
    fun closest(query: String, patients: List<Patient>): Match {
        val exact = match(query, patients)
        if (exact !is Match.None) return exact
        val tokens = nameTokens(query)
        val scored = patients.map { p -> p to tokens.count { t -> wordsOf(p).any { it.startsWith(t) } } }
        val best = scored.maxOfOrNull { it.second } ?: return Match.None
        if (best < 2) return Match.None
        return result(scored.filter { it.second == best }.map { it.first })
    }

    /** [match], and when several patients fit, the words that follow the name in [request] may tell them apart. */
    fun widened(query: String, request: String, patients: List<Patient>): Match {
        val result = match(query, patients)
        if (result !is Match.Many) return result
        val tokens = nameTokens(query)
        val words = split(fold(request))
        val last = tokens.lastOrNull() ?: return result
        val at = words.indexOf(last)
        if (at < 0) return result
        val extended = tokens.toMutableList()
        for (word in words.drop(at + 1).take(3)) {
            if (word in fillers) continue
            extended += word
            val found = patients.filter { matches(extended, it) }
            if (found.size == 1) return Match.One(found[0])
            if (found.isEmpty()) break
        }
        return result
    }

    /** The one patient whose first name and at least one surname appear in [request], or null. */
    fun named(request: String, patients: List<Patient>): Patient? {
        val words = split(fold(request)).toSet()
        val scored = patients.mapNotNull { p ->
            val first = split(fold(p.firstName.orEmpty()))
            val last = split(fold(p.lastName.orEmpty()))
            if (first.isEmpty() || !first.all { it in words }) return@mapNotNull null
            val surnames = last.count { it in words }
            if (surnames > 0) p to (first.size + surnames) else null
        }
        val best = scored.maxOfOrNull { it.second } ?: return null
        val top = scored.filter { it.second == best }
        return if (top.size == 1) top[0].first else null
    }

    /** Whether every word of [query] is in the patient's name. */
    fun matches(query: String, patient: Patient): Boolean {
        val tokens = nameTokens(query)
        return tokens.isNotEmpty() && matches(tokens, patient)
    }

    private fun matches(tokens: List<String>, patient: Patient): Boolean {
        val words = wordsOf(patient)
        return tokens.all { token -> words.any { it.startsWith(token) } }
    }

    private fun result(found: List<Patient>): Match = when (found.size) {
        0 -> Match.None
        1 -> Match.One(found[0])
        else -> Match.Many(found)
    }

    /** The query's words without the ones that only come with a name; if nothing is left, all of them. */
    private fun nameTokens(query: String): List<String> {
        val all = split(fold(query))
        val kept = all.filter { it !in fillers }
        return kept.ifEmpty { all }
    }

    private fun wordsOf(patient: Patient): List<String> =
        split(fold(patient.fullName)) + listOf(fold(patient.documentId.orEmpty())).filter { it.isNotEmpty() }

    private fun split(s: String): List<String> =
        s.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
}
