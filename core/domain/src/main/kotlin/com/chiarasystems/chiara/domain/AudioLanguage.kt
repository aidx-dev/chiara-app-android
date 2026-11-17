package com.chiarasystems.chiara.domain

import com.chiarasystems.chiara.model.Patient
import com.chiarasystems.chiara.model.trimmed
import java.util.Locale

/** Port of iOS `AudioLanguageResolver`: which language a consultation is spoken in, decided without asking anyone. */
object AudioLanguageResolver {
    /**
     * What the clinic wrote as the patient's language ("Español", "English", "Deutsch", "Français", or a code) as one of
     * the app's language codes; null when empty or a language the app does not support.
     */
    fun codeFromPatientLanguage(raw: String?): String? = when (raw?.trimmed?.lowercase(Locale.ROOT)) {
        "español", "espanol", "spanish", "castellano", "es" -> "es"
        "english", "inglés", "ingles", "en" -> "en"
        "deutsch", "german", "alemán", "aleman", "de" -> "de"
        "français", "francais", "french", "francés", "frances", "fr" -> "fr"
        else -> null
    }

    enum class Source { Patient, LastUsed }

    data class Resolved(val code: String, val source: Source)

    /** The patient's language if there is one; otherwise the one used last (or the app's). */
    fun resolve(patientLanguage: String?, lastUsed: String): Resolved =
        codeFromPatientLanguage(patientLanguage)?.let { Resolved(it, Source.Patient) } ?: Resolved(lastUsed, Source.LastUsed)
}

/** Port of `DictationService.vocabulary`: phrases that bias on-device speech recognition towards names and visit types. */
object DictationVocabulary {
    /** Visit-type labels first (localised by the caller), then full names, then surnames, without repeats. */
    fun forPatients(patients: List<Patient>, visitTypeLabels: List<String>, limit: Int = 300): List<String> {
        val seen = mutableSetOf<String>()
        val phrases = mutableListOf<String>()
        fun add(phrase: String?) {
            val p = phrase?.trimmed ?: return
            if (p.isEmpty() || !seen.add(p.lowercase())) return
            phrases += p
        }
        visitTypeLabels.forEach(::add)
        patients.forEach { add(it.fullName) }
        patients.forEach { add(it.lastName) }
        return phrases.take(limit)
    }
}
