package com.chiarasystems.chiara.domain

import com.chiarasystems.chiara.domain.AssistantIntent.Kind
import com.chiarasystems.chiara.model.Dates
import com.chiarasystems.chiara.model.nilIfEmpty
import com.chiarasystems.chiara.model.trimmed
import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/**
 * Port of iOS `RuleBasedEngine`: keyword and pattern reader for Spanish and English requests. It needs no model, so it is
 * the safety net behind the on-device engine (see [FallbackEngine]).
 */
class RuleBasedEngine : AssistantEngine {
    override val unavailableReason: String? = null

    override suspend fun interpret(text: String, context: AssistantContext): AssistantIntent {
        val t = foldKeepingSpaces(text)
        val words = t.split(NON_LETTERS).filter { it.isNotEmpty() }
        val wordSet = words.toSet()
        fun has(vararg keys: String) = keys.any { t.contains(it) }
        val mentionsAppointment = has("cita", "appointment")
        val mentionsContact = has("telefono", "movil", "celular", "phone", "correo", "email", "e-mail", "mail")

        // A summary or the history of a patient is a question for the agent, not a command.
        if (has("resumen", "resume ", "summar", "historico", "que tiene", "what does")) return AssistantIntent()

        val kind = when {
            has("ficha", "expediente", "historial", "record") -> Kind.OpenPatient
            mentionsContact && wordSet.any { it in UPDATE_VERBS } -> Kind.UpdatePatient
            wordSet.any { it in CANCEL_VERBS } -> Kind.CancelAppointment
            mentionsAppointment && wordSet.any { it in CONFIRM_VERBS } -> Kind.ConfirmAppointment
            mentionsAppointment && wordSet.any { it in NOTE_VERBS } -> Kind.AddNote
            mentionsAppointment && wordSet.any { it in MOVE_VERBS } -> Kind.MoveAppointment
            has("hueco", "huecos", "disponibilidad", "cuando puede", "free slot", "availability", "first free") -> Kind.FindFreeSlot
            has("proxima cita", "siguiente cita", "next appointment", "cuando es la cita", "cuando tiene cita", "cuando vuelve") -> Kind.NextAppointment
            has("cuantas citas", "cuantas hay", "que citas", "citas tengo", "how many appointments", "tengo citas") -> Kind.AskAgenda
            has("cita", "appointment", "agendar", "reserva", "apunta", "book ") -> Kind.CreateAppointment
            has("agenda", "calendario", "schedule", "calendar") -> Kind.ShowAgenda
            else -> Kind.Unknown
        }
        if (kind == Kind.Unknown) return AssistantIntent()

        // What follows "nota: …" is free text, never part of the patient's name (11:30 is a time, not a colon).
        var nameSource = LEADING_BEFORE_COLON.find(t)?.groupValues?.get(1) ?: t
        if (kind == Kind.UpdatePatient) {
            // The new phone or email is not part of the name.
            nameSource = nameSource.replace(EMAIL, " ").replace(PHONE, " ")
        }
        var intent = AssistantIntent(kind = kind, patientQuery = patientName(nameSource))
        val mentions = dateMentions(words, t, context.today)
        val times = times(t)

        intent = when (kind) {
            Kind.CancelAppointment, Kind.ConfirmAppointment, Kind.AddNote -> intent.copy(
                sourceDate = mentions.firstOrNull()?.iso,
                sourceStart = times.start,
                note = if (kind == Kind.AddNote) noteText(text) else null,
            )
            Kind.AskAgenda -> intent.copy(date = mentions.firstOrNull()?.iso)
            Kind.NextAppointment -> intent
            Kind.FindFreeSlot -> intent.copy(
                date = mentions.firstOrNull()?.iso,
                minutes = durationMinutes(t),
                partOfDay = when {
                    t.contains("por la manana") || t.contains("morning") -> "morning"
                    t.contains("tarde") || t.contains("afternoon") -> "afternoon"
                    else -> null
                },
            )
            Kind.UpdatePatient -> {
                val field = if (t.contains("correo") || t.contains("email") || t.contains("mail")) "email" else "phone"
                val value = if (field == "email") EMAIL_CAPTURE.find(text)?.groupValues?.get(1)
                else PHONE_CAPTURE.find(t)?.groupValues?.get(1)
                intent.copy(patientField = field, value = value)
            }
            Kind.MoveAppointment -> {
                var i = intent.copy(start = times.start, end = times.end)
                if (mentions.size >= 2) {
                    i = i.copy(sourceDate = mentions[0].iso, date = mentions[1].iso)
                } else if (mentions.isNotEmpty()) {
                    // "la cita del lunes de Antonio": the day names the appointment, not where it goes.
                    val only = mentions[0]
                    i = if (only.after == "del" || only.after == "de") i.copy(sourceDate = only.iso) else i.copy(date = only.iso)
                }
                i
            }
            else -> intent.copy(date = mentions.firstOrNull()?.iso, start = times.start, end = times.end)
        }
        return intent
    }

    // --- Patient -------------------------------------------------------------------------------------------------

    private fun patientName(t: String): String? {
        val tokens = t.split(NON_ALNUM).filter { it.isNotEmpty() }
        val triggers = listOf(
            setOf("paciente", "patient"), setOf("para", "for"), setOf("ficha", "expediente", "historial", "record"),
            setOf("cita", "citas", "appointment"), setOf("venir", "vuelve"), setOf("a", "al"), setOf("de", "del"),
        )
        for (group in triggers) {
            for ((i, token) in tokens.withIndex()) {
                if (token !in group) continue
                val name = mutableListOf<String>()
                var j = i + 1
                while (j < tokens.size && name.isEmpty() && tokens[j] in FILLERS) j++
                while (j < tokens.size && name.size < 3 && tokens[j].toIntOrNull() == null && tokens[j] !in STOP_WORDS) {
                    name += tokens[j]
                    j++
                }
                if (name.isNotEmpty()) return name.joinToString(" ")
            }
        }
        return null
    }

    // --- Date ----------------------------------------------------------------------------------------------------

    private data class DateMention(val iso: String, /** The word right before the mention ("del", "al", "para"...). */ val after: String)

    /** Day references in the order they are written: "pasado mañana", "mañana", "hoy", weekdays and ISO dates. */
    private fun dateMentions(words: List<String>, t: String, today: LocalDate): List<DateMention> {
        fun iso(offset: Int) = Dates.iso(today.plusDays(offset.toLong()))
        val found = mutableListOf<DateMention>()
        var i = 0
        while (i < words.size) {
            val w = words[i]
            val before = if (i > 0) words[i - 1] else ""
            val ahead = words.drop(i + 1).take(2)
            if (w == "pasado" && ahead.firstOrNull() == "manana") {
                found += DateMention(iso(2), before)
                i += 2
                continue
            }
            if (w == "manana") {
                // "por la mañana" is a part of the day, not tomorrow.
                val partOfDay = before == "la" && i > 1 && words[i - 2] in setOf("por", "de", "en")
                if (!partOfDay) found += DateMention(iso(1), before)
            } else if (w == "tomorrow") {
                found += DateMention(iso(1), before)
            } else if (w == "hoy" || w == "today") {
                found += DateMention(iso(0), before)
            } else if (WEEKDAYS[w] != null) {
                val weekday = WEEKDAYS.getValue(w)
                val strictlyAfter = ahead.take(2) == listOf("que", "viene") || before == "proximo" || before == "proxima" || before == "next"
                for (offset in (if (strictlyAfter) 1 else 0) until 15) {
                    if (today.plusDays(offset.toLong()).dayOfWeek == weekday) {
                        found += DateMention(iso(offset), if (before == "proximo" || before == "next") "" else before)
                        break
                    }
                }
            }
            i++
        }

        if (found.isEmpty()) {
            val isoMatch = ISO_DATE.find(t)
            if (isoMatch != null) {
                found += DateMention(isoMatch.groupValues[1], "")
            } else {
                val m = EL_DAY.find(t)
                val day = m?.groupValues?.get(1)?.toIntOrNull()
                if (day != null && day in 1..31) {
                    // "el 5": the next day of the month with that number.
                    for (offset in 0 until 62) {
                        val candidate = today.plusDays(offset.toLong())
                        if (candidate.dayOfMonth == day) {
                            found += DateMention(Dates.iso(candidate), "")
                            break
                        }
                    }
                }
            }
        }
        return found
    }

    // --- Note ----------------------------------------------------------------------------------------------------

    /** The text after a colon, or after "nota"/"motivo". */
    private fun noteText(text: String): String? {
        COLON_TEXT.find(text)?.let { return it.groupValues[1].trimmed.nilIfEmpty }
        NOTE_TEXT.find(text)?.let { return it.groupValues[1].trimmed.nilIfEmpty }
        return null
    }

    // --- Time ----------------------------------------------------------------------------------------------------

    /** "media hora", "una hora", "45 minutos"... */
    private fun durationMinutes(t: String): Int? {
        if (t.contains("media hora") || t.contains("half an hour")) return 30
        MINUTES.find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        if (t.contains("una hora") || t.contains("1 hora") || t.contains("an hour") || t.contains("1 hour")) return 60
        return null
    }

    private fun clock(hour: String, minute: String?): Int? {
        val h = hour.toIntOrNull()?.takeIf { it in 0..23 } ?: return null
        val m = minute?.toIntOrNull() ?: 0
        return if (m in 0..59) h * 60 + m else null
    }

    /** The clinic works 08:30–18:30, so an hour from 1 to 7 is the afternoon. */
    private fun afternoon(minutes: Int): Int = if (minutes / 60 in 1..7) minutes + 12 * 60 else minutes

    private data class Times(val start: String?, val end: String?)

    private fun times(t: String): Times {
        var start: Int? = null
        var end: Int? = null
        val range = RANGE.find(t)
        if (range != null) {
            val g = range.groups
            start = clock(g[1]!!.value, g[2]?.value)
            end = clock(g[3]!!.value, g[4]?.value)
        } else {
            val single = SINGLE.find(t)
            if (single != null) start = clock(single.groups[1]!!.value, single.groups[2]?.value)
        }

        var s = start ?: return Times(null, null)
        s = afternoon(s)
        var e = end
        if (e != null) {
            e = afternoon(e)
            if (e <= s && e + 12 * 60 > s && e < 12 * 60) e += 12 * 60
        } else if (t.contains("media hora") || t.contains("half an hour")) {
            e = s + 30
        } else {
            val minutes = MINUTES.find(t)?.groupValues?.get(1)?.toIntOrNull()
            e = when {
                minutes != null -> s + minutes
                t.contains("una hora") || t.contains("1 hora") || t.contains("an hour") || t.contains("1 hour") -> s + 60
                else -> null
            }
        }
        return Times(Dates.hhmm(s), e?.let { Dates.hhmm(it) })
    }

    private companion object {
        val NON_LETTERS = Regex("[^\\p{L}]+")
        val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
        val LEADING_BEFORE_COLON = Regex("^(.*?):\\s")
        val EMAIL = Regex("[\\w.+-]+@[\\w-]+\\.[\\w.-]+")
        val EMAIL_CAPTURE = Regex("([\\w.+-]+@[\\w-]+\\.[\\w.-]+)")
        val PHONE = Regex("\\+?\\d[\\d ]{5,}\\d")
        val PHONE_CAPTURE = Regex("(\\+?\\d[\\d ]{5,}\\d)")
        val ISO_DATE = Regex("\\b(\\d{4}-\\d{2}-\\d{2})\\b")
        val EL_DAY = Regex("\\bel (\\d{1,2})\\b(?![:.h]\\d)")
        val COLON_TEXT = Regex(":\\s*(.+)$")
        val NOTE_TEXT = Regex("\\b(?:nota|motivo)\\b(?:\\s+(?:de|para))?\\s+(?:que\\s+)?(.+)$", RegexOption.IGNORE_CASE)
        val MINUTES = Regex("(\\d{1,3}) ?(?:min|minutos|minutes)")
        val RANGE = Regex(
            "(?:\\bde |\\bdesde |\\bentre |\\bfrom )(\\d{1,2})(?:[:.h](\\d{2}))?\\s*(?:a|hasta|y|to|-)\\s*(?:las )?(\\d{1,2})(?:[:.h](\\d{2}))?",
        )
        val SINGLE = Regex("(?:\\ba las? |\\bat |\\ba la |\\blas )(\\d{1,2})(?:[:.h](\\d{2}))?")

        val WEEKDAYS: Map<String, DayOfWeek> = mapOf(
            "domingo" to DayOfWeek.SUNDAY, "lunes" to DayOfWeek.MONDAY, "martes" to DayOfWeek.TUESDAY,
            "miercoles" to DayOfWeek.WEDNESDAY, "jueves" to DayOfWeek.THURSDAY, "viernes" to DayOfWeek.FRIDAY,
            "sabado" to DayOfWeek.SATURDAY,
            "sunday" to DayOfWeek.SUNDAY, "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY,
            "wednesday" to DayOfWeek.WEDNESDAY, "thursday" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY,
            "saturday" to DayOfWeek.SATURDAY,
        )

        /** Words that end a patient name. */
        val STOP_WORDS: Set<String> = setOf(
            "el", "la", "los", "las", "de", "del", "a", "al", "que", "para", "con", "y", "desde", "entre", "en",
            "hoy", "manana", "pasado", "proximo", "proxima", "viene", "este", "esta", "una", "un", "por", "hasta",
            "cita", "citas", "nota", "motivo", "incluyendo", "incluye", "sobre", "segun", "resumen",
            "the", "on", "from", "to", "at", "next", "this", "tomorrow", "today", "and", "for", "of",
        ) + WEEKDAYS.keys

        /** Words that may sit between the trigger and the name ("al paciente llamado Antonio"). */
        val FILLERS: Set<String> = setOf(
            "el", "la", "al", "a", "de", "del", "llamado", "llamada", "paciente", "patient", "called", "named", "mr", "mrs",
            "sr", "sra", "don", "dona",
        )

        val CANCEL_VERBS = setOf("cancela", "cancelar", "anula", "anular", "cancel")
        val CONFIRM_VERBS = setOf("confirma", "confirmar", "confirm")
        val NOTE_VERBS = setOf("nota", "anade", "anadir", "anota", "anotar", "motivo")
        val MOVE_VERBS = setOf(
            "mueve", "mover", "cambia", "cambiar", "pasa", "pasar", "reprograma", "reprogramar", "aplaza", "aplazar",
            "adelanta", "adelantar", "retrasa", "retrasar", "move", "reschedule",
        )
        val UPDATE_VERBS = setOf("cambia", "cambiar", "actualiza", "actualizar", "pon", "poner", "modifica", "modificar", "change", "update", "set")

        private val marks = Regex("\\p{M}+")

        /** Like iOS `folding(.diacriticInsensitive, .caseInsensitive)`: no trimming, so "resume " keeps its space. */
        fun foldKeepingSpaces(s: String): String =
            marks.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)
    }
}
