package com.chiarasystems.chiara.domain

import com.chiarasystems.chiara.model.Dates
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * What the user asked for, as read from free text (port of iOS `AssistantIntent`). Values are raw strings: the assistant
 * store validates them and resolves them against the real data before anything is shown or written.
 */
data class AssistantIntent(
    val kind: Kind = Kind.Unknown,
    val patientQuery: String? = null,
    /** `yyyy-MM-dd`. For [Kind.MoveAppointment] this is the new day. */
    val date: String? = null,
    /** 24-hour `HH:mm`. */
    val start: String? = null,
    val end: String? = null,
    /** One of `VisitType.raw`. */
    val visitType: String? = null,
    val reason: String? = null,
    /** Which existing appointment is meant, when the patient has several. */
    val sourceDate: String? = null,
    val sourceStart: String? = null,
    /** Text to add to an appointment's notes ([Kind.AddNote]). */
    val note: String? = null,
    /** `phone` or `email` ([Kind.UpdatePatient]) and the new value. */
    val patientField: String? = null,
    val value: String? = null,
    /** Length wanted for [Kind.FindFreeSlot], and `morning` / `afternoon` when the user prefers one. */
    val minutes: Int? = null,
    val partOfDay: String? = null,
) {
    enum class Kind {
        CreateAppointment, ShowAgenda, OpenPatient,
        /** Change an existing appointment of a patient. */
        MoveAppointment, CancelAppointment, ConfirmAppointment, AddNote,
        /** Questions answered in the chat: a day's agenda, a patient's next appointment, free slots. */
        AskAgenda, NextAppointment, FindFreeSlot,
        /** Change a patient's phone or email. */
        UpdatePatient,
        Unknown,
    }
}

/** Everything an engine needs to resolve relative dates ("el lunes que viene"). */
data class AssistantContext(val today: LocalDate, val languageCode: String, val locale: Locale) {
    /**
     * The next [days] days written out ("- Monday (lunes) 2026-11-02 [today]"). A small model is poor at calendar
     * arithmetic, so it only has to pick from them.
     */
    fun calendarTable(days: Int): String = (0 until days).joinToString("\n") { offset ->
        val day = today.plusDays(offset.toLong())
        val name = day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        val local = day.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        val label = when (offset) { 0 -> "today"; 1 -> "tomorrow"; else -> "" }
        "- $name ($local) ${Dates.iso(day)}${if (label.isEmpty()) "" else " [$label]"}"
    }
}

/** Turns a sentence into an [AssistantIntent]. Engines only interpret: they never read or write clinic data. */
interface AssistantEngine {
    /** Null when the engine can be used, otherwise a message for the user. */
    val unavailableReason: String?

    suspend fun interpret(text: String, context: AssistantContext): AssistantIntent
}

/**
 * Uses [primary] (the on-device language model) and falls back to [fallback] when it is unavailable or fails. When the
 * model does answer, its answer stands, "this is not a command" included: the rules would only guess a command out of a
 * question ("resumen de Pablo con sus citas" is not "agendar una cita").
 */
class FallbackEngine(private val primary: AssistantEngine, private val fallback: AssistantEngine) : AssistantEngine {
    override val unavailableReason: String?
        get() = if (primary.unavailableReason == null) null else fallback.unavailableReason

    override suspend fun interpret(text: String, context: AssistantContext): AssistantIntent {
        if (primary.unavailableReason == null) {
            try {
                return primary.interpret(text, context)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (_: Exception) {
                // fall through to the rules
            }
        }
        return fallback.interpret(text, context)
    }
}
