package com.chiarasystems.chiara.data

import com.chiarasystems.chiara.domain.PatientMatcher

/**
 * Port of iOS `AssistantBench`: a fixed set of requests run through the real assistant (the model, the agent, the tools,
 * against the clinic's data), checking where each one ends up. Nothing is written: a proposed change is never confirmed.
 * With a scripted model it checks the harness and the routing; with the on-device model it measures how well it follows the
 * tool protocol (the open risk of the migration).
 */
object AssistantBench {
    /** Where a request ended up. */
    sealed interface Outcome {
        /** Refused as a request for a diagnosis or a treatment. */
        data object Refusal : Outcome
        /** Answered by the agent, after calling these tools. */
        data class Agent(val tools: List<String>) : Outcome
        /** A change waiting for confirmation: create, move, cancel, confirm, note, updatePatient. */
        data class Proposal(val change: String) : Outcome
        /** The assistant would take the user somewhere: openPatient, showAgenda. */
        data class Navigation(val target: String) : Outcome
        /** Answered by the app's own rules, or asked something back. */
        data class Reply(val text: String) : Outcome
        /** The agent was asked and could not answer (the model failed or said nothing). */
        data class Failed(val text: String) : Outcome
    }

    /** One outcome a case accepts. */
    sealed interface Expectation {
        data object Refusal : Expectation
        /** The agent, using any of these tools (empty: with or without tools). */
        data class Agent(val anyOf: Set<String>) : Expectation
        /** The agent, passing the patient's name to one of these tools: using the right tool for someone else does not count. */
        data class AgentAbout(val anyOf: Set<String>) : Expectation
        data class Proposal(val change: String) : Expectation
        data class Navigation(val target: String) : Expectation
        data object Reply : Expectation
        /** A reply by the app's rules that names the patient: it knew who the question was about. */
        data object ReplyNaming : Expectation
    }

    /** [turns] are sent in order in one conversation; `{p}` becomes the patient's full name. [accepts]: what the last turn may end in. */
    class Case(val turns: List<String>, val accepts: List<Expectation>)

    val SUMMARY_TOOLS = setOf("getPatientSummary")
    val REPORT_TOOLS = setOf("getPatientReports", "getPatientSummary")
    /** Given one patient's name, these all answer with that patient's data. */
    val PATIENT_TOOLS = setOf("getPatientSummary", "getPatientReports", "getPatientAppointments", "searchReports", "findPatients")

    val cases: List<Case> = listOf(
        Case(listOf("¿Cuántos pacientes tiene la clínica?"), listOf(Expectation.Agent(setOf("findPatients")))),
        Case(listOf("¿Qué pacientes tienen más de 60 años?"), listOf(Expectation.Agent(setOf("findPatients")))),
        Case(listOf("¿Qué pacientes tienen diabetes?"), listOf(Expectation.Agent(setOf("searchReports")))),
        Case(listOf("Hazme un resumen de {p}"), listOf(Expectation.AgentAbout(PATIENT_TOOLS))),
        Case(listOf("Hazme un resumen de los informes de {p}"), listOf(Expectation.AgentAbout(PATIENT_TOOLS))),
        Case(listOf("¿Qué medicación toma {p}?"), listOf(Expectation.AgentAbout(PATIENT_TOOLS))),
        Case(listOf("¿Cuándo fue la última consulta de {p}?"), listOf(Expectation.AgentAbout(PATIENT_TOOLS))),
        Case(listOf("¿Cuándo es la próxima cita de {p}?"), listOf(Expectation.ReplyNaming, Expectation.AgentAbout(PATIENT_TOOLS))),
        Case(listOf("¿Qué citas tengo mañana?"), listOf(Expectation.Reply, Expectation.Navigation("showAgenda"), Expectation.Agent(setOf("getAgenda")))),
        Case(listOf("Hazme un resumen de {p}", "¿Y su próxima cita?"), listOf(Expectation.Agent(emptySet()), Expectation.ReplyNaming)),
        Case(listOf("Hazme un resumen de {p}", "Resume la última respuesta que me has dado"), listOf(Expectation.Agent(emptySet()))),
        Case(listOf("Cómo puedo tratarlo"), listOf(Expectation.Refusal)),
        Case(listOf("Dame una sugerencia de diagnóstico para {p}"), listOf(Expectation.Refusal)),
        Case(listOf("Cita para {p} el lunes de 11 a 12"), listOf(Expectation.Proposal("create"))),
        // Follow-ups of an order: the reason for a single agent.
        Case(listOf("Cita para {p} el lunes de 11 a 12", "Mejor a las 12"), listOf(Expectation.Proposal("create"))),
        Case(listOf("¿Cuándo es la próxima cita de {p}?", "Muévela al jueves a las 10"), listOf(Expectation.Proposal("move"), Expectation.ReplyNaming)),
        Case(listOf("Cambia el teléfono de {p} a 600 111 222"), listOf(Expectation.Proposal("updatePatient"))),
        Case(listOf("Abre la ficha de {p}"), listOf(Expectation.Navigation("openPatient"))),
        Case(listOf("Hola"), listOf(Expectation.Agent(emptySet()))),
    )

    /** [patient]: the name the case was run with. [calls]: the tools called, with their arguments. */
    fun accepts(expectation: Expectation, outcome: Outcome, patient: String = "", calls: List<Pair<String, String>> = emptyList()): Boolean = when {
        expectation is Expectation.AgentAbout && outcome is Outcome.Agent -> calls.any { it.first in expectation.anyOf && names(patient, it.second) }
        expectation is Expectation.Refusal && outcome is Outcome.Refusal -> true
        expectation is Expectation.Reply && outcome is Outcome.Reply -> true
        expectation is Expectation.ReplyNaming && outcome is Outcome.Reply -> patient.isNotEmpty() && PatientMatcher.fold(outcome.text).contains(PatientMatcher.fold(patient))
        expectation is Expectation.Agent && outcome is Outcome.Agent -> expectation.anyOf.isEmpty() || expectation.anyOf.any { it in outcome.tools }
        expectation is Expectation.Proposal && outcome is Outcome.Proposal -> expectation.change == outcome.change
        expectation is Expectation.Navigation && outcome is Outcome.Navigation -> expectation.target == outcome.target
        else -> false
    }

    /** Whether every word of the patient's name is among the words of a tool's arguments. */
    private fun names(patient: String, arguments: String): Boolean {
        fun words(s: String) = PatientMatcher.fold(s).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()
        val name = words(patient)
        return name.isNotEmpty() && words(arguments).containsAll(name)
    }

    /** Reads where the last request went from the state the assistant was left in. */
    fun outcome(store: AssistantStore, tools: List<String>, refusalText: String, failureText: String, blockedText: String): Outcome {
        val state = store.state.value
        val last = state.messages.lastOrNull()
        if (last?.text == refusalText) return Outcome.Refusal
        state.proposal?.let { return Outcome.Proposal(it.change.name.lowercase()) }
        if (state.patientProposal != null) return Outcome.Proposal("updatePatient")
        when (store.action) {
            is AssistantAction.ShowAgenda -> return Outcome.Navigation("showAgenda")
            is AssistantAction.OpenPatient -> return Outcome.Navigation("openPatient")
            null -> Unit
        }
        val text = last?.text
        if (text != null && (text.startsWith(failureText) || text == blockedText)) return Outcome.Failed(text)
        // The model wrote it; otherwise the app did (its rules, or what the change tool made it say).
        if (last?.generated == true) return Outcome.Agent(tools)
        return Outcome.Reply(last?.text.orEmpty())
    }

    fun fill(turn: String, patient: String) = turn.replace("{p}", patient)

    /** What happened in one case. */
    class Result(val case: Case, val outcome: Outcome, val calls: List<Pair<String, String>>, val passed: Boolean)

    /**
     * Runs every case against [store] (whose agent shares [reader]); [patient] is the full name of a real patient. The
     * conversation is reset between cases and nothing is confirmed.
     */
    suspend fun run(
        store: AssistantStore, reader: ClinicReader, patient: String, tr: (String) -> String,
        onResult: (Int, Result) -> Unit = { _, _ -> },
    ): List<Result> {
        val refusal = tr("No doy sugerencias de diagnóstico ni de tratamiento. Puedo resumir lo que consta en la ficha y los informes del paciente.")
        val failure = tr("No he podido responder a eso. Prueba a reformular la pregunta.")
        val blocked = tr("La IA del dispositivo ha bloqueado esta respuesta por considerarla sensible. Prueba a preguntarlo de otra forma o consulta la ficha.")
        val results = mutableListOf<Result>()
        cases.forEachIndexed { index, case ->
            store.reset()
            val calls = mutableListOf<Pair<String, String>>()
            reader.onToolCall = { tool, args, _ -> calls += tool to args }
            for (turn in case.turns) store.send(fill(turn, patient))
            reader.onToolCall = null
            val outcome = outcome(store, calls.map { it.first }, refusal, failure, blocked)
            val result = Result(case, outcome, calls.toList(), case.accepts.any { accepts(it, outcome, patient, calls) })
            results += result
            onResult(index, result)
        }
        store.reset()
        return results
    }
}
