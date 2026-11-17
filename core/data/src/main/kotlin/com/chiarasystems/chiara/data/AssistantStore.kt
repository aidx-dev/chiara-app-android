package com.chiarasystems.chiara.data

import com.chiarasystems.chiara.domain.AgendaRules
import com.chiarasystems.chiara.domain.AssistantContext
import com.chiarasystems.chiara.domain.AssistantEngine
import com.chiarasystems.chiara.domain.AssistantIntent
import com.chiarasystems.chiara.domain.AssistantIntent.Kind
import com.chiarasystems.chiara.domain.ClinicalAdviceGuard
import com.chiarasystems.chiara.domain.GroundingCheck
import com.chiarasystems.chiara.domain.PatientMatcher
import com.chiarasystems.chiara.domain.RuleBasedEngine
import com.chiarasystems.chiara.model.Appointment
import com.chiarasystems.chiara.model.AppointmentStatus
import com.chiarasystems.chiara.model.Dates
import com.chiarasystems.chiara.model.Patient
import com.chiarasystems.chiara.model.VisitType
import com.chiarasystems.chiara.model.nilIfEmpty
import com.chiarasystems.chiara.model.trimmed
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AssistantMessage(
    val id: Long,
    val role: Role,
    /** Grows while an answer is being streamed. */
    val text: String,
    /** Written by the language model rather than by the app's own rules. */
    val generated: Boolean = false,
    /** Dates or ICD-10 codes in a generated answer that no tool returned: shown as a warning. */
    val unsupported: List<String> = emptyList(),
) {
    enum class Role { User, Assistant }

    /** What the bubble shows: the model writes Markdown lists and bold although it is asked not to. */
    val displayText: String get() = if (generated) tidy(text) else text

    companion object {
        fun tidy(text: String): String = text.split("\n").joinToString("\n") { raw ->
            val line = raw.replace("**", "")
            val m = Regex("^(\\s*)[*\\-] ").find(line)
            if (m != null) m.groupValues[1] + "• " + line.substring(m.range.last + 1) else line
        }
    }
}

/** A change the assistant is about to make to the agenda. Nothing is written until the user confirms. */
data class AssistantProposal(
    val change: Change,
    /** The appointment being changed (null when creating one). */
    val original: Appointment?,
    val patient: Patient,
    val day: LocalDate,
    val start: Int,
    val duration: Int,
    val type: VisitType,
    /** The reason of a new appointment, or the text to add for [Change.Note]. */
    val reason: String,
    val issue: AgendaRules.Violation?,
    /** Free start times the same day, offered when [issue] is about the hour. */
    val suggestions: List<Int> = emptyList(),
) {
    enum class Change { Create, Move, Cancel, Confirm, Note }
    val isValid: Boolean get() = issue == null
}

/** A change of one contact detail of a patient, shown before it is written. */
data class PatientProposal(val patient: Patient, val field: Field, val oldValue: String, val newValue: String) {
    enum class Field(val raw: String) { Phone("phone"), Email("email") }
}

/** A free start time offered as an answer to "find me a slot". */
data class AssistantSlot(val day: LocalDate, val start: Int)

/** What a confirmed change did: where to look (if it concerns the agenda), what to tell the user, and how to take it back. */
class AssistantOutcome(val day: LocalDate?, val message: String, val undo: suspend () -> Unit)

/** Something the sheet has to do on the user's behalf once the assistant is done. */
sealed interface AssistantAction {
    data class ShowAgenda(val day: LocalDate) : AssistantAction
    data class OpenPatient(val id: String) : AssistantAction
}

/** The model-driven side of the assistant (Phase 6): answers questions from the record with tools. Absent → rules only. */
interface AssistantAgent {
    /** Null when the agent can be used, otherwise a message for the user. */
    val unavailableReason: String?
    /** Set by the store: prepares a change (never writes it) and returns what the app said, for the model. */
    var proposeChange: (suspend (AssistantIntent) -> String)?
    val lastPatient: Patient?
    val lastSources: List<String>
    fun prepare(context: AssistantContext)
    fun reset()
    /** Streams the answer through [onPartial]; [onActivity] tells what is being looked up. */
    suspend fun answer(prompt: String, context: AssistantContext, onPartial: (String) -> Unit, onActivity: (String?) -> Unit)
}

class AssistantAgentError(val kind: Kind, message: String = kind.name) : Exception(message) {
    enum class Kind { BlockedBySystem, Failed }
}

/** Formats days for chat text (the display half of iOS `Cal`). */
interface DayFormatter {
    fun dayChip(day: LocalDate): String
    fun longDay(day: LocalDate): String
}

/** Everything the sheet draws. */
data class AssistantUiState(
    val messages: List<AssistantMessage> = emptyList(),
    val proposal: AssistantProposal? = null,
    /** Patients that matched an ambiguous name, to pick from. */
    val candidates: List<Patient> = emptyList(),
    val isWorking: Boolean = false,
    /** Offered when no patient matched the name that was said. */
    val canCreatePatient: Boolean = false,
    /** Appointments of one patient to pick from when a request could mean several. */
    val appointmentChoices: List<Appointment> = emptyList(),
    /** Free slots found for the user, to pick one and book it. */
    val slotChoices: List<AssistantSlot> = emptyList(),
    val slotDuration: Int = 30,
    val patientProposal: PatientProposal? = null,
    /** What the agent is looking up while it works ("Consultando la agenda…"). */
    val activity: String? = null,
)

/**
 * Port of iOS `AssistantStore`. The engine only interprets text; this store resolves patients, applies the agenda rules and
 * performs the write, and only after the user has confirmed. Texts are catalog keys translated by [tr].
 */
class AssistantStore(
    private val engine: AssistantEngine,
    private val agent: AssistantAgent?,
    private val patients: PatientsStore,
    private val appointments: AppointmentsStore,
    private val days: DayFormatter,
    private val context: () -> AssistantContext,
    private val tr: (String, List<Any>) -> String,
    private val today: () -> LocalDate = { LocalDate.now() },
    private val nowMinutes: () -> Int = { Dates.minutesNow() },
) {
    private val _state = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()
    private var nextId = 1L

    /** Set when the answer is a navigation; the sheet consumes it with [takeAction]. */
    var action: AssistantAction? = null
        private set

    private var pendingIntent: AssistantIntent? = null
    private var slotPatientQuery: String? = null
    private var pendingPatient: Patient? = null
    private var incompleteRequest: String? = null
    private var agentSeen = 0
    private var contextPatient: Patient? = null
    private var agentQuestion = ""
    private val toolReplies = mutableListOf<String>()
    private var insideChangeTool = false
    private var agentFailure: String? = null
    private var unconfirmedCard: AssistantProposal? = null

    init {
        agent?.proposeChange = { intent -> runChange(intent) }
    }

    private val s get() = _state.value
    private fun update(block: AssistantUiState.() -> AssistantUiState) { _state.value = _state.value.block() }
    private fun t(key: String, vararg args: Any) = tr(key, args.toList())

    val unavailableReason: String? get() = engine.unavailableReason

    fun reset() {
        _state.value = AssistantUiState()
        clearPending()
        action = null
        agentSeen = 0
        contextPatient = null
        agent?.reset()
    }

    /** Called when the sheet opens, so the first answer does not wait for the model to load. */
    fun prepare() { agent?.prepare(context()) }

    fun takeAction(): AssistantAction? { val a = action; action = null; return a }

    private fun add(role: AssistantMessage.Role, text: String, generated: Boolean = false): AssistantMessage {
        val m = AssistantMessage(nextId++, role, text, generated)
        update { copy(messages = messages + m) }
        return m
    }

    private fun say(text: String) { add(AssistantMessage.Role.Assistant, text) }

    private fun ask(question: String, request: String?) { incompleteRequest = request; say(question) }

    private fun clearPending() {
        update { copy(proposal = null, patientProposal = null, candidates = emptyList(), appointmentChoices = emptyList(), slotChoices = emptyList(), canCreatePatient = false) }
        slotPatientQuery = null; pendingIntent = null; pendingPatient = null; incompleteRequest = null
    }

    // --- Conversation

    suspend fun send(raw: String) {
        val text = raw.trimmed
        if (text.isEmpty() || s.isWorking) return
        add(AssistantMessage.Role.User, text)
        val request = incompleteRequest?.let { "$it. $text" } ?: text
        unconfirmedCard = s.proposal
        clearPending()
        update { copy(isWorking = true) }
        try {
            // The assistant reports what the record says; it does not advise. Decided here, not by a model.
            if (ClinicalAdviceGuard.isAskingForAdvice(text)) {
                say(t("No doy sugerencias de diagnóstico ni de tratamiento. Puedo resumir lo que consta en la ficha y los informes del paciente."))
                return
            }
            if (patients.patients.value.isEmpty()) patients.load()
            // When the model runs, the agent answers, and prepares changes with its tool. A complete order takes the faster,
            // surer lane of the command reader instead.
            if (agent != null && agent.unavailableReason == null && !isCompleteOrder(text)) {
                val failure = askAgent(request) ?: return
                agentFailure = failure // the model failed: an order still works through the command reader, which must not ask it again
            }
            try { readCommand(request) } finally { agentFailure = null }
        } finally { update { copy(isWorking = false) } }
    }

    private val fastLane = setOf(Kind.CreateAppointment, Kind.MoveAppointment, Kind.CancelAppointment, Kind.ConfirmAppointment, Kind.AddNote, Kind.UpdatePatient, Kind.OpenPatient, Kind.NextAppointment)

    /** Orders the command reader takes on its own when they name someone who exists. */
    private suspend fun isCompleteOrder(text: String): Boolean {
        val intent = runCatching { RuleBasedEngine().interpret(text, context()) }.getOrNull() ?: return false
        val query = intent.patientQuery ?: return false
        return intent.kind in fastLane && PatientMatcher.widened(query, text, patients.patients.value) is PatientMatcher.Match.One
    }

    /** The command reader (the model's extraction, or the keyword rules without it). */
    private suspend fun readCommand(request: String) {
        try {
            handle(grounded(engine.interpret(request, context()), request), request)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            say(t("No he podido procesar la petición. Inténtalo de nuevo."))
        }
    }

    suspend fun choose(patient: Patient) {
        val intent = pendingIntent ?: return
        if (s.isWorking) return
        add(AssistantMessage.Role.User, patient.fullName)
        val request = incompleteRequest
        clearPending()
        update { copy(isWorking = true) }
        try { contextPatient = patient; continueWith(patient, intent, request) } finally { update { copy(isWorking = false) } }
    }

    /** One place that knows what to do with a patient once the name is settled. */
    private suspend fun continueWith(patient: Patient, intent: AssistantIntent, request: String?) {
        when (intent.kind) {
            Kind.OpenPatient -> action = AssistantAction.OpenPatient(patient.id)
            Kind.CreateAppointment -> prepareAppointment(intent, patient, request)
            Kind.NextAppointment -> answerNextAppointment(patient)
            Kind.UpdatePatient -> prepareContactChange(intent, patient)
            else -> selectAppointment(intent, patient, request)
        }
    }

    /** The user picked one of the offered free slots: book it for the patient named (or ask who). */
    suspend fun choose(slot: AssistantSlot) {
        if (s.isWorking) return
        add(AssistantMessage.Role.User, label(slot, s.slotDuration))
        val query = slotPatientQuery
        val duration = s.slotDuration
        clearPending()
        val intent = AssistantIntent(
            kind = Kind.CreateAppointment, patientQuery = query, date = Dates.iso(slot.day),
            start = Dates.hhmm(slot.start), end = Dates.hhmm(slot.start + duration),
        )
        val request = "Appointment on ${Dates.iso(slot.day)} from ${Dates.hhmm(slot.start)} to ${Dates.hhmm(slot.start + duration)}"
        update { copy(isWorking = true) }
        try { handle(intent, request) } finally { update { copy(isWorking = false) } }
    }

    /** The user picked one of several appointments of the same patient. */
    suspend fun choose(appointment: Appointment) {
        val intent = pendingIntent ?: return
        val patient = pendingPatient ?: return
        if (s.isWorking) return
        add(AssistantMessage.Role.User, label(appointment))
        val request = incompleteRequest
        clearPending()
        update { copy(isWorking = true) }
        try { prepareChange(intent, patient, appointment, request) } finally { update { copy(isWorking = false) } }
    }

    fun dismissProposal() {
        val change = s.proposal?.change
        update { copy(proposal = null, patientProposal = null) }
        say(if (change == AssistantProposal.Change.Create) t("Vale, no agendo nada.") else t("Vale, no cambio nada."))
    }

    /** Writes a phone or email change. */
    suspend fun confirmPatientChange(): AssistantOutcome? {
        val p = s.patientProposal ?: return null
        if (s.isWorking) return null
        update { copy(isWorking = true) }
        try {
            patients.update(p.patient.id, mapOf(p.field.raw to p.newValue))
            update { copy(patientProposal = null) }
            val store = patients
            return AssistantOutcome(null, t("Datos actualizados")) { store.update(p.patient.id, mapOf(p.field.raw to p.oldValue)) }
        } finally { update { copy(isWorking = false) } }
    }

    /** Writes the change. Rules are checked again right before, since the agenda may have changed while the card was open. */
    suspend fun confirm(): AssistantOutcome? {
        val p = s.proposal ?: return null
        if (!p.isValid || s.isWorking) return null
        update { copy(isWorking = true) }
        try {
            if (p.change == AssistantProposal.Change.Create || p.change == AssistantProposal.Change.Move) {
                appointments.load(p.day, p.day)
                val checked = makeProposal(p.patient, p.day, p.start, p.duration, p.type, p.reason, p.change, p.original)
                if (checked.issue != null) {
                    update { copy(proposal = checked) }
                    say(message(checked.issue))
                    return null
                }
            }
            val outcome: AssistantOutcome
            when (p.change) {
                AssistantProposal.Change.Create -> {
                    val created = appointments.create(p.patient, p.day, p.start, p.duration, p.type, p.reason)
                    outcome = outcomeOf(created.day ?: p.day, t("Cita agendada · %s, %s", days.dayChip(p.day), Dates.hhmm(p.start)), AppointmentChange.Created(created))
                }
                AssistantProposal.Change.Move -> {
                    val o = p.original ?: return null
                    appointments.update(o, p.day, p.start, p.start + p.duration, o.status, o.notes)
                    outcome = outcomeOf(p.day, t("Cita movida · %s, %s", days.dayChip(p.day), Dates.hhmm(p.start)), AppointmentChange.Updated(o))
                }
                AssistantProposal.Change.Cancel -> {
                    val o = p.original ?: return null
                    appointments.update(o, p.day, o.startMinutes, o.endMinutes, AppointmentStatus.Canceled.serverValue, o.notes)
                    outcome = outcomeOf(p.day, t("Cita cancelada"), AppointmentChange.Updated(o))
                }
                AssistantProposal.Change.Confirm -> {
                    val o = p.original ?: return null
                    appointments.update(o, p.day, o.startMinutes, o.endMinutes, AppointmentStatus.Confirmed.serverValue, o.notes)
                    outcome = outcomeOf(p.day, t("Cita confirmada"), AppointmentChange.Updated(o))
                }
                AssistantProposal.Change.Note -> {
                    val o = p.original ?: return null
                    appointments.update(o, p.day, o.startMinutes, o.endMinutes, o.status, appendedNotes(o, p.reason))
                    outcome = outcomeOf(p.day, t("Nota añadida"), AppointmentChange.Updated(o))
                }
            }
            update { copy(proposal = null) }
            return outcome
        } finally { update { copy(isWorking = false) } }
    }

    private fun outcomeOf(day: LocalDate, message: String, change: AppointmentChange): AssistantOutcome {
        val store = appointments
        return AssistantOutcome(day, message) { store.undo(change) }
    }

    /** Notes keep the visit type as a prefix ("Revisión — traer analítica"); an extra note is appended to the reason. */
    private fun appendedNotes(a: Appointment, extra: String): String {
        val v = a.visit
        val reason = listOf(v.reason, extra.trimmed).filter { it.isNotEmpty() }.joinToString("; ")
        return v.type?.let { Appointment.composeNotes(it, reason) } ?: reason
    }

    fun label(slot: AssistantSlot, duration: Int) = "${days.dayChip(slot.day)} · ${Dates.hhmm(slot.start)}–${Dates.hhmm(slot.start + duration)}"
    fun label(a: Appointment): String {
        val day = a.day?.let(days::dayChip) ?: a.date
        return "$day · ${Dates.hhmm(a.startMinutes)}–${Dates.hhmm(a.endMinutes)}"
    }

    // --- Interpretation

    private val aboutAPatient = setOf(Kind.CreateAppointment, Kind.MoveAppointment, Kind.CancelAppointment, Kind.ConfirmAppointment, Kind.AddNote, Kind.OpenPatient, Kind.NextAppointment, Kind.UpdatePatient)

    private suspend fun handle(intent: AssistantIntent, request: String) {
        // Nobody named: it is about the patient of the conversation. A change still shows the name on the card.
        val ctxPatient = contextPatient
        if (intent.patientQuery == null && ctxPatient != null && intent.kind in aboutAPatient) {
            continueWith(ctxPatient, intent, request); return
        }
        // A name that is nobody's, in a sentence with no word of an order, is something to answer, not a patient to look for.
        val q0 = intent.patientQuery
        if (q0 != null && intent.kind in aboutAPatient && PatientMatcher.widened(q0, request, patients.patients.value) is PatientMatcher.Match.None &&
            !soundsLikeAnOrder(request) && !insideChangeTool
        ) { answerWithAgent(request); return }

        suspend fun withPatient(keepRequest: Boolean, onOne: suspend (Patient) -> Unit, onNone: () -> Unit = {}) {
            val query = intent.patientQuery ?: return
            when (val m = resolvePatient(query, request)) {
                PatientMatcher.Match.None -> { onNone(); say(t("No encuentro ningún paciente llamado «%s».", query)) }
                is PatientMatcher.Match.One -> onOne(m.patient)
                is PatientMatcher.Match.Many -> {
                    pendingIntent = intent
                    if (keepRequest) incompleteRequest = request
                    update { copy(candidates = m.patients.take(6)) }
                    say(t("Hay varios pacientes que coinciden con «%s». ¿Cuál es?", query))
                }
            }
        }

        when (intent.kind) {
            Kind.CreateAppointment -> {
                if (intent.patientQuery == null) { ask(t("¿Para qué paciente quieres la cita?"), request); return }
                withPatient(true, { prepareAppointment(intent, it, request) }, { update { copy(canCreatePatient = true) } })
            }
            Kind.ShowAgenda -> {
                val day = intent.date?.let(Dates::parseIso)
                if (day == null) { ask(t("¿Para qué día?"), request); return }
                action = AssistantAction.ShowAgenda(day)
            }
            Kind.OpenPatient -> {
                if (intent.patientQuery == null) { ask(t("¿De qué paciente?"), request); return }
                withPatient(false, { action = AssistantAction.OpenPatient(it.id) }) // opening a record is harmless: ambiguity is a tap
            }
            Kind.MoveAppointment, Kind.CancelAppointment, Kind.ConfirmAppointment, Kind.AddNote -> {
                if (intent.patientQuery == null) { ask(t("¿De qué paciente es la cita?"), request); return }
                withPatient(true, { selectAppointment(intent, it, request) })
            }
            Kind.AskAgenda -> answerAgenda(intent.date?.let(Dates::parseIso) ?: today())
            Kind.NextAppointment -> {
                if (intent.patientQuery == null) { ask(t("¿De qué paciente?"), request); return }
                withPatient(false, { answerNextAppointment(it) })
            }
            Kind.FindFreeSlot -> answerFreeSlots(intent)
            Kind.UpdatePatient -> {
                if (intent.patientQuery == null) { ask(t("¿De qué paciente?"), request); return }
                withPatient(true, { prepareContactChange(intent, it) })
            }
            Kind.Unknown -> answerWithAgent(request)
        }
    }

    // --- Agent

    private fun soundsLikeAnOrder(request: String): Boolean {
        val said = PatientMatcher.fold(request)
        val words = said.split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }.toSet()
        return said.any { it.isDigit() } || words.any { it in DAY_WORDS } || ORDER_WORDS.any { said.contains(it) }
    }

    /** Something the command reader could not take as an order: the agent answers it, or says why it can't. */
    private suspend fun answerWithAgent(request: String) {
        agentFailure?.let { say(failureText()); return }
        if (agent == null) { say(t("No te he entendido. Prueba con algo como «cita para Antonio el lunes de 11 a 12».")); return }
        // Without the model the commands still work (rules), but questions can't: say why.
        agent.unavailableReason?.let { say(it + " " + t("Mientras tanto puedo agendar, mover o cancelar citas si me lo pides directamente.")); return }
        askAgent(request)?.let { say(failureText()) }
    }

    private fun failureText() = t("No he podido responder a eso. Prueba a reformular la pregunta.")

    /** Asks the agent; returns null when it answered, or the failure when it gave nothing at all. */
    private suspend fun askAgent(request: String): String? {
        val ag = agent ?: return "no agent"
        // A patient named in full is who the question is about, whatever tool the model picks.
        val named = PatientMatcher.named(request, patients.patients.value)
        if (named != null) contextPatient = named
        val prompt = agentPrompt(request, named)
        agentQuestion = prompt
        toolReplies.clear()
        val reply = add(AssistantMessage.Role.Assistant, "", generated = true)
        var gaveNothing = false
        fun edit(change: (AssistantMessage) -> AssistantMessage) =
            update { copy(messages = messages.map { if (it.id == reply.id) change(it) else it }) }
        var failure: String? = null
        try {
            ag.answer(prompt, context(), { text -> update { copy(activity = null) }; edit { it.copy(text = text) } }, { label -> update { copy(activity = label) } })
            ag.lastPatient?.let { contextPatient = it }
            // Dates and codes the answer states that no tool returned (nor the user said) are pointed out.
            val sources = ag.lastSources + prompt
            edit { it.copy(unsupported = GroundingCheck.unsupported(it.text, sources)) }
        } catch (e: CancellationException) {
            return null
        } catch (e: AssistantAgentError) {
            if (e.kind == AssistantAgentError.Kind.BlockedBySystem) {
                edit { it.copy(text = t("La IA del dispositivo ha bloqueado esta respuesta por considerarla sensible. Prueba a preguntarlo de otra forma o consulta la ficha."), generated = false) }
                return null
            }
            failure = e.toString()
        } catch (e: Exception) {
            failure = e.toString()
        } finally {
            update { copy(activity = null) }
            if (!gaveNothing) agentSeen = s.messages.size
        }
        // A change was prepared: the app's own words go with the card, not the model's ("ya está agendada").
        if (toolReplies.isNotEmpty()) {
            edit { it.copy(text = toolReplies.joinToString("\n"), generated = false, unsupported = emptyList()) }
            return null
        }
        if (failure != null && (s.messages.firstOrNull { it.id == reply.id }?.text?.trimmed.orEmpty()).isEmpty()) {
            update { copy(messages = messages.filter { it.id != reply.id }) }
            gaveNothing = true
            agentSeen = minOf(agentSeen, s.messages.size)
            return failure
        }
        return null
    }

    /** The agent's change tool: the same path as a typed order, except that what the app says goes back to the model. */
    private suspend fun runChange(proposed: AssistantIntent): String {
        if (s.proposal != null || s.patientProposal != null) return "A change is already waiting for the user's confirmation. Prepare the next one after that."
        var intent = grounded(proposed, agentQuestion)
        // "Mejor a las 12" while the card of a new appointment was still waiting: that booking at another time.
        val card = unconfirmedCard
        if (intent.kind == Kind.MoveAppointment && card != null && card.change == AssistantProposal.Change.Create &&
            (intent.patientQuery?.let { PatientMatcher.matches(it, card.patient) } ?: true)
        ) {
            intent = intent.copy(
                kind = Kind.CreateAppointment, patientQuery = card.patient.fullName,
                date = intent.date ?: Dates.iso(card.day),
                end = intent.end ?: intent.start?.let { Dates.hhmm(Dates.minutesOfDay(it) + card.duration) },
                visitType = intent.visitType ?: card.type.raw, reason = intent.reason ?: card.reason.nilIfEmpty,
                sourceDate = null, sourceStart = null,
            )
        }
        if (intent.kind == Kind.Unknown) return "Nothing was prepared: the user's words do not say enough for that (who, which day, what time). Ask them."
        val before = s.messages.size
        insideChangeTool = true
        try { handle(intent, agentQuestion) } finally { insideChangeTool = false }
        val said = s.messages.drop(before).filter { it.role == AssistantMessage.Role.Assistant }.map { it.text }
        if (s.messages.size > before) update { copy(messages = messages.take(before)) }
        // The agent carries the conversation; the reader's half-finished request is not kept.
        incompleteRequest = null
        toolReplies += said
        var text = said.joinToString(" ")
        if (s.proposal != null || s.patientProposal != null) text += " A card shows it to the user. Nothing is done until they tap Confirmar: do not say it is done."
        else if (action != null) text += " The app opens it."
        return text.ifEmpty { "Done." }
    }

    /** The question, preceded by what was said since the agent last answered: "¿y el de Pablo?" only makes sense with it. */
    private fun agentPrompt(request: String, named: Patient?): String {
        val context = mutableListOf<String>()
        val unseen = s.messages.dropLast(1).drop(agentSeen).takeLast(4).map { m ->
            val who = if (m.role == AssistantMessage.Role.User) "User" else "Assistant"
            val text = if (m.text.length > 160) m.text.take(159) + "…" else m.text
            "$who: ${text.replace("\n", " ")}"
        }
        if (unseen.isNotEmpty()) context += "Earlier in this chat:\n" + unseen.joinToString("\n")
        val ctxPatient = contextPatient
        if (named != null) context += "The question is about the patient ${named.fullName}. Pass exactly this name to the tools."
        else if (ctxPatient != null && ctxPatient.id != agent?.lastPatient?.id) context += "Patient being talked about: ${ctxPatient.fullName}."
        return if (context.isEmpty()) request else context.joinToString("\n") + "\n\nQuestion: " + request
    }

    // --- Questions

    private suspend fun answerAgenda(day: LocalDate) {
        appointments.load(day, day)
        val list = appointments.appointments(day).filter { it.statusKey != AppointmentStatus.Canceled }
        val title = days.longDay(day)
        if (list.isEmpty()) { say(t("%s: no hay citas.", title)); return }
        val lines = list.joinToString("\n") { "${Dates.hhmm(it.startMinutes)} · ${it.patientName}" }
        var text = (if (list.size == 1) t("%s: 1 cita.", title) else t("%s: %d citas.", title, list.size)) + "\n" + lines
        val free = AgendaRules.slots.filter { AgendaRules.violation(day, it, 30, appointments.busyIntervals(day), today(), nowMinutes()) == null }
        if (free.isNotEmpty() && day.dayOfWeek != DayOfWeek.SUNDAY) text += "\n" + t("Huecos libres: %s", free.take(8).joinToString(", ") { Dates.hhmm(it) })
        say(text)
    }

    private suspend fun answerNextAppointment(patient: Patient) {
        val t0 = today()
        appointments.load(t0, t0.plusDays(180))
        val next = appointments.upcoming(patient.id).firstOrNull()
        if (next == null) { say(t("%s no tiene citas próximas.", patient.fullName)); return }
        say(t("La próxima cita de %s es el %s.", patient.fullName, label(next)))
    }

    /** First free slots from the day asked (or today) over the next two weeks. */
    private suspend fun answerFreeSlots(intent: AssistantIntent) {
        val minutes = intent.minutes ?: intent.start?.let { st -> intent.end?.let { Dates.minutesOfDay(it) - Dates.minutesOfDay(st) } } ?: 30
        if (minutes !in AgendaRules.DURATIONS) { say(message(AgendaRules.Violation.InvalidDuration)); return }
        val t0 = today()
        val first = maxOf(intent.date?.let(Dates::parseIso) ?: t0, t0)
        appointments.load(first, first.plusDays(13))
        val now = nowMinutes()
        val found = mutableListOf<AssistantSlot>()
        for (offset in 0 until 14) {
            if (found.size >= 5) break
            val day = first.plusDays(offset.toLong())
            if (day.dayOfWeek == DayOfWeek.SUNDAY) continue
            val busy = appointments.busyIntervals(day)
            for (start in AgendaRules.slots) {
                if (found.size >= 5) break
                if (intent.partOfDay == "morning" && start >= 12 * 60) continue
                if (intent.partOfDay == "afternoon" && start < 15 * 60) continue
                if (AgendaRules.violation(day, start, minutes, busy, t0, now) == null) found += AssistantSlot(day, start)
            }
        }
        if (found.isEmpty()) { say(t("No he encontrado huecos de %d min en los próximos días.", minutes)); return }
        slotPatientQuery = intent.patientQuery
        update { copy(slotChoices = found, slotDuration = minutes) }
        say(t("Estos son los primeros huecos de %d min:", minutes))
    }

    // --- Patient details

    private fun prepareContactChange(intent: AssistantIntent, patient: Patient) {
        val field = PatientProposal.Field.entries.firstOrNull { it.raw == intent.patientField }
        val raw = intent.value?.trimmed?.nilIfEmpty
        if (field == null || raw == null) {
            say(t("Dime qué dato quieres cambiar y el nuevo valor, por ejemplo «cambia el teléfono de Pablo a 600 111 222»."))
            return
        }
        val value: String
        when (field) {
            PatientProposal.Field.Phone -> {
                value = raw.filter { it.isDigit() || it == '+' || it == ' ' }.trimmed
                if (value.count { it.isDigit() } < 7) { say(t("Ese teléfono no parece válido.")); return }
            }
            PatientProposal.Field.Email -> {
                value = raw.lowercase()
                if (!Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(value)) { say(t("Ese correo no parece válido.")); return }
            }
        }
        val current = (if (field == PatientProposal.Field.Phone) patient.phone else patient.email).orEmpty()
        if (current == value) { say(t("Ya tiene ese dato.")); return }
        update { copy(patientProposal = PatientProposal(patient, field, current, value)) }
        say(t("Voy a cambiar este dato. ¿Lo confirmas?"))
    }

    /** Finds the appointment a change refers to among the patient's upcoming ones. */
    private suspend fun selectAppointment(intent: AssistantIntent, patient: Patient, request: String?) {
        val t0 = today()
        appointments.load(t0, t0.plusDays(90))
        var list = appointments.upcoming(patient.id)
        intent.sourceDate?.let(Dates::parseIso)?.let { d -> list = list.filter { it.day == d } }
        intent.sourceStart?.takeIf { it.contains(":") }?.let { st -> list = list.filter { it.startMinutes == Dates.minutesOfDay(st) } }
        when (list.size) {
            0 -> {
                val narrowed = intent.sourceDate != null || intent.sourceStart != null
                say(if (narrowed) t("%s no tiene ninguna cita en esa fecha.", patient.fullName) else t("%s no tiene citas próximas.", patient.fullName))
            }
            1 -> prepareChange(intent, patient, list[0], request)
            else -> {
                pendingIntent = intent; pendingPatient = patient; incompleteRequest = request
                update { copy(appointmentChoices = list.take(6)) }
                say(t("%s tiene varias citas. ¿Cuál?", patient.fullName))
            }
        }
    }

    private suspend fun prepareChange(intent: AssistantIntent, patient: Patient, o: Appointment, request: String?) {
        val known = request?.let { "$it. Patient: ${patient.fullName}" }
        val day = o.day ?: today()
        val visit = o.visit
        val type = visit.type ?: VisitType.Review

        fun propose(change: AssistantProposal.Change, reason: String) {
            val made = AssistantProposal(change, o, patient, day, o.startMinutes, o.durationMinutes, type, reason, null)
            update { copy(proposal = made) }
            say(reply(made))
        }

        when (intent.kind) {
            Kind.CancelAppointment -> propose(AssistantProposal.Change.Cancel, visit.reason)
            Kind.ConfirmAppointment -> if (o.statusKey == AppointmentStatus.Confirmed) say(t("Esa cita ya está confirmada.")) else propose(AssistantProposal.Change.Confirm, visit.reason)
            Kind.AddNote -> {
                val note = intent.note?.trimmed?.nilIfEmpty
                if (note == null) { ask(t("¿Qué nota quieres añadir?"), known); return }
                propose(AssistantProposal.Change.Note, note)
            }
            Kind.MoveAppointment -> {
                val newDay = intent.date?.let(Dates::parseIso)
                val startText = intent.start?.takeIf { it.contains(":") }
                if (newDay == null && startText == null) { ask(t("¿Para cuándo quieres moverla?"), known); return }
                val target = newDay ?: day
                val start = startText?.let(Dates::minutesOfDay) ?: o.startMinutes
                val end = intent.end?.takeIf { it.contains(":") }?.let(Dates::minutesOfDay)
                val duration = end?.let { it - start } ?: o.durationMinutes
                appointments.load(target, target)
                val made = makeProposal(patient, target, start, duration, type, visit.reason, AssistantProposal.Change.Move, o)
                update { copy(proposal = made) }
                say(reply(made))
            }
            else -> Unit
        }
    }

    private suspend fun prepareAppointment(intent: AssistantIntent, patient: Patient, request: String?) {
        // The patient is settled, so the follow-up question keeps their name in the request.
        val known = request?.let { "$it. Patient: ${patient.fullName}" }
        val day = intent.date?.let(Dates::parseIso)
        if (day == null) { ask(t("¿Para qué día?"), known); return }
        val startText = intent.start?.takeIf { it.contains(":") }
        if (startText == null) { ask(t("¿A qué hora?"), known); return }
        val start = Dates.minutesOfDay(startText)
        val end = intent.end?.takeIf { it.contains(":") }?.let(Dates::minutesOfDay)
        val duration = end?.let { it - start } ?: 30
        appointments.load(day, day)
        val type = VisitType.entries.firstOrNull { it.raw == intent.visitType } ?: VisitType.Review
        val made = makeProposal(patient, day, start, duration, type, intent.reason.orEmpty())
        update { copy(proposal = made) }
        say(reply(made))
    }

    /** The user picked one of the suggested free hours. */
    fun pick(start: Int) {
        val p = s.proposal ?: return
        if (s.isWorking) return
        add(AssistantMessage.Role.User, Dates.hhmm(start))
        val made = makeProposal(p.patient, p.day, start, p.duration, p.type, p.reason, p.change, p.original)
        update { copy(proposal = made) }
        say(reply(made))
    }

    private fun makeProposal(
        patient: Patient, day: LocalDate, start: Int, duration: Int, type: VisitType, reason: String,
        change: AssistantProposal.Change = AssistantProposal.Change.Create, original: Appointment? = null,
    ): AssistantProposal {
        val t0 = today()
        val now = nowMinutes()
        val busy = appointments.busyIntervals(day, original?.id)
        // A moved appointment keeps the duration and start it already had, even off the usual grid.
        val issue = AgendaRules.violation(
            day, start, duration, busy, t0, now,
            enforceDuration = original?.let { it.durationMinutes != duration } ?: true,
            enforceGrid = original?.let { it.startMinutes != start } ?: true,
        )
        // A different day or a bad duration can't be fixed by moving the hour.
        val hourIssue = issue == AgendaRules.Violation.Overlap || issue == AgendaRules.Violation.LunchBreak || issue == AgendaRules.Violation.OutsideHours || issue == AgendaRules.Violation.Past
        val free = if (hourIssue && !day.isBefore(t0) && day.dayOfWeek != DayOfWeek.SUNDAY) AgendaRules.freeStarts(day, duration, busy, t0, now, start) else emptyList()
        return AssistantProposal(change, original, patient, day, start, duration, type, reason, issue, free)
    }

    private fun reply(p: AssistantProposal): String {
        val issue = p.issue
        if (issue == null) return when (p.change) {
            AssistantProposal.Change.Create -> t("Esto es lo que voy a agendar. ¿Lo confirmas?")
            AssistantProposal.Change.Move -> t("Voy a mover la cita. ¿Lo confirmas?")
            AssistantProposal.Change.Cancel -> t("Voy a cancelar esta cita. ¿Lo confirmas?")
            AssistantProposal.Change.Confirm -> t("Voy a marcar la cita como confirmada. ¿Lo confirmas?")
            AssistantProposal.Change.Note -> t("Voy a añadir esta nota a la cita. ¿Lo confirmas?")
        }
        if (p.suggestions.isEmpty()) {
            val hourIssue = issue == AgendaRules.Violation.Overlap || issue == AgendaRules.Violation.LunchBreak || issue == AgendaRules.Violation.OutsideHours || issue == AgendaRules.Violation.Past
            return if (hourIssue && p.day.dayOfWeek != DayOfWeek.SUNDAY) message(issue) + " " + t("No quedan huecos libres de %d min ese día.", p.duration) else message(issue)
        }
        return message(issue) + " " + t("Estas horas están libres ese día:")
    }

    // --- Patient matching

    /** Finds who [query] names (completed with the words after it in [request] when ambiguous); a single match becomes the conversation's patient. */
    private fun resolvePatient(query: String, request: String): PatientMatcher.Match {
        val m = PatientMatcher.widened(query, request, patients.patients.value)
        if (m is PatientMatcher.Match.One) contextPatient = m.patient
        return m
    }

    fun message(v: AgendaRules.Violation): String = when (v) {
        AgendaRules.Violation.Past -> t("Esa hora ya ha pasado.")
        AgendaRules.Violation.Sunday -> t("No se agenda los domingos.")
        AgendaRules.Violation.OutsideHours -> t("Esa hora queda fuera del horario (08:30–18:30).")
        AgendaRules.Violation.LunchBreak -> t("Esa hora cae en la pausa de comida (14:00–15:30).")
        AgendaRules.Violation.InvalidDuration -> t("La duración debe ser de 15, 30, 45 o 60 minutos.")
        AgendaRules.Violation.Overlap -> t("Esa franja ya está ocupada.")
    }

    companion object {
        /** Words that say when, in the app's four languages (without accents). */
        val DAY_WORDS = setOf(
            "hoy", "manana", "pasado", "lunes", "martes", "miercoles", "jueves", "viernes", "sabado", "domingo", "semana", "mediodia",
            "today", "tomorrow", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "week", "noon",
            "heute", "morgen", "montag", "dienstag", "mittwoch", "donnerstag", "freitag", "samstag", "sonntag", "woche", "mittag",
            "aujourd", "demain", "lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche", "semaine", "midi",
        )

        /** Pieces of words that only come with an order: an appointment, a record to open, a contact detail. */
        val ORDER_WORDS = listOf(
            "cita", "appointment", "termin", "rendez", "rdv", "agend", "reserv", "book", "ficha", "record", "akte", "dossier",
            "abre", "abrir", "open", "offne", "ouvre", "expediente", "telefono", "movil", "phone", "correo", "email", "mail",
        )

        /** Pieces of words about the agenda itself. */
        val AGENDA_WORDS = listOf("schedule", "calendar", "calendario", "kalender", "hueco", "libre", "free", "slot", "disponib", "verfugbar", "dispon")

        /** Starts of words that ask about the record rather than give an order. */
        val RECORD_WORDS = listOf(
            "resumen", "resume", "resumir", "summar", "medicacion", "medication", "medikament", "medicament",
            "diagnost", "historico", "informes", "berichte", "zusammenfass", "rapports",
        )

        private val COMMANDS = setOf(Kind.CreateAppointment, Kind.MoveAppointment, Kind.CancelAppointment, Kind.ConfirmAppointment, Kind.AddNote, Kind.OpenPatient, Kind.NextAppointment, Kind.UpdatePatient)

        /**
         * The small model sometimes fills a field from its own instructions or makes it up. What the user did not say is
         * dropped: an invented name asks "¿de qué paciente?", and a contact change that does not bring its new value is a
         * question, not a change.
         */
        fun grounded(original: AssistantIntent, request: String): AssistantIntent {
            var intent = original
            val said = PatientMatcher.fold(request)
            val q = intent.patientQuery
            if (q != null && !PatientMatcher.fold(q).split(" ").filter { it.isNotEmpty() }.all { said.contains(it) }) intent = intent.copy(patientQuery = null)
            // The model also fills a day or an hour from its calendar when none was said. Without a number or a day word, there is none.
            val words = said.split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }.toSet()
            val hasNumber = said.any { it.isDigit() }
            val hasDayWord = words.any { it in DAY_WORDS }
            if (!hasNumber && !hasDayWord) intent = intent.copy(date = null, start = null, end = null, sourceDate = null, sourceStart = null)
            // Nor is a question with no day and nothing about the agenda a question about the agenda.
            if (intent.kind in setOf(Kind.AskAgenda, Kind.ShowAgenda, Kind.FindFreeSlot) && !hasNumber && !hasDayWord &&
                (ORDER_WORDS + AGENDA_WORDS).none { said.contains(it) }
            ) intent = intent.copy(kind = Kind.Unknown)
            // A summary or a question about the record is for the agent, even when the model saw a command in it.
            val asksAboutRecord = RECORD_WORDS.any { w -> words.any { it.startsWith(w) } }
            if (asksAboutRecord && intent.date == null && intent.start == null && intent.kind !in setOf(Kind.AddNote, Kind.UpdatePatient, Kind.FindFreeSlot, Kind.Unknown)) intent = intent.copy(kind = Kind.Unknown)
            // A command that names nobody, no day, no time and no appointment is a question: the stateless reader has no memory.
            if (intent.kind in COMMANDS && intent.patientQuery == null && intent.date == null && intent.start == null &&
                intent.sourceDate == null && intent.sourceStart == null && ORDER_WORDS.none { said.contains(it) }
            ) intent = intent.copy(kind = Kind.Unknown)
            if (intent.kind == Kind.UpdatePatient) {
                val value = intent.value.orEmpty()
                val digits = value.filter { it.isDigit() }
                val spoken = if (value.contains("@")) request.lowercase().contains(value.lowercase()) else digits.isNotEmpty() && request.filter { it.isDigit() }.contains(digits)
                if (!spoken) intent = intent.copy(kind = Kind.Unknown)
            }
            return intent
        }
    }
}
