package com.chiarasystems.chiara.data

import com.chiarasystems.chiara.domain.AssistantContext
import com.chiarasystems.chiara.domain.AssistantEngine
import com.chiarasystems.chiara.domain.AssistantIntent
import com.chiarasystems.chiara.domain.AssistantIntent.Kind
import com.chiarasystems.chiara.model.Patient
import com.chiarasystems.chiara.model.VisitType
import com.chiarasystems.chiara.network.DemoBackend
import com.chiarasystems.chiara.network.FileResponseCache
import com.chiarasystems.chiara.network.InMemorySecureStore
import com.chiarasystems.chiara.network.SupabaseClient
import com.chiarasystems.chiara.network.SupabaseConfig
import java.io.File
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Friday 2026-10-30, the day the demo data and most expectations are built around. */
private val TODAY = LocalDate.of(2026, 10, 30)
private val CONTEXT = AssistantContext(TODAY, "es", Locale("es"))

private class StubEngine(override val unavailableReason: String? = null, val handler: (String) -> AssistantIntent) : AssistantEngine {
    constructor(always: AssistantIntent) : this(null, { always })
    override suspend fun interpret(text: String, context: AssistantContext) = handler(text)
}

private class StubFailure : Exception()

/** An agent that streams what the test tells it to and records what it was asked. */
private class StubAgent : AssistantAgent {
    override var unavailableReason: String? = null
    var partials = listOf<String>()
    var activity: String? = null
    var error: Throwable? = null
    override var lastPatient: Patient? = null
    override var proposeChange: (suspend (AssistantIntent) -> String)? = null
    override var lastSources: List<String> = emptyList()
    /** The change its "model" asks for, given the question: run through [proposeChange] like the real tool. */
    var act: ((String) -> AssistantIntent?)? = null
    val toolReplies = mutableListOf<String>()
    val prompts = mutableListOf<String>()
    var resets = 0

    override suspend fun answer(prompt: String, context: AssistantContext, onPartial: (String) -> Unit, onActivity: (String?) -> Unit) {
        prompts += prompt
        activity?.let(onActivity)
        act?.invoke(prompt)?.let { intent -> proposeChange?.let { toolReplies += it(intent) } }
        partials.forEach(onPartial)
        error?.let { throw it }
    }

    override fun prepare(context: AssistantContext) {}
    override fun reset() { resets++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantStoreTest {
    private val failureText = "No he podido responder a eso. Prueba a reformular la pregunta."
    private val lucia = Patient(id = "00000000-0000-7000-8000-000000000001", firstName = "Lucía", lastName = "Ferrer")
    private val cacheDir = File.createTempFile("assistant", "").also { it.delete() }

    private val days = object : DayFormatter {
        override fun dayChip(day: LocalDate) = day.toString()
        override fun longDay(day: LocalDate) = day.toString()
    }
    private val translate: (String, List<Any>) -> String = { key, args -> if (args.isEmpty()) key else String.format(key, *args.toTypedArray()) }

    /** Not signed in: nothing here reaches a server. */
    private fun TestScope.offline(engine: AssistantEngine, agent: AssistantAgent?): AssistantStore {
        val client = SupabaseClient(SupabaseConfig("http://unused.invalid/", "anon"), InMemorySecureStore(), FileResponseCache(cacheDir))
        return AssistantStore(engine, agent, PatientsStore(client, this), AppointmentsStore(client), days, { CONTEXT }, translate, { TODAY }, { 0 })
    }

    private suspend fun TestScope.demo(engine: AssistantEngine, agent: AssistantAgent?): AssistantStore {
        val client = SupabaseClient(
            SupabaseConfig("http://demo.invalid/", "anon"), InMemorySecureStore(), FileResponseCache(cacheDir),
            OkHttpClient.Builder().addInterceptor(DemoBackend { TODAY }).build(),
        )
        client.signIn("demo@chiara.test", "test-only")
        val patients = PatientsStore(client, this)
        patients.load()
        return AssistantStore(engine, agent, patients, AppointmentsStore(client), days, { CONTEXT }, translate, { TODAY }, { 0 })
    }

    private fun AssistantStore.texts() = state.value.messages.map { it.text }
    private fun AssistantStore.last() = state.value.messages.last()

    @Test fun aQuestionGoesToTheAgentAndStreamsIntoOneMessage() = runTest {
        val agent = StubAgent().apply { partials = listOf("Lucía", "Lucía toma", "Lucía toma fotoprotector."); activity = "Consultando los informes…" }
        val store = offline(StubEngine(always = AssistantIntent()), agent)
        store.send("¿Qué medicación toma Lucía?")
        val m = store.state.value.messages
        assertEquals(2, m.size)
        assertEquals(AssistantMessage.Role.User, m[0].role)
        assertEquals("Lucía toma fotoprotector.", m[1].text)
        assertTrue(m[1].generated)
        assertEquals(listOf("¿Qué medicación toma Lucía?"), agent.prompts)
        assertNull(store.state.value.activity)
    }

    @Test fun withoutTheModelTheAnswerSaysWhy() = runTest {
        val agent = StubAgent().apply { unavailableReason = "Activa la IA del dispositivo." }
        val store = offline(StubEngine(always = AssistantIntent()), agent)
        store.send("¿Qué medicación toma Lucía?")
        assertTrue(store.last().text.startsWith("Activa la IA del dispositivo."))
        assertFalse(store.last().generated)
        assertTrue(agent.prompts.isEmpty())
    }

    @Test fun anAgentThatFailsLeavesAFriendlyMessage() = runTest {
        val agent = StubAgent().apply { error = StubFailure() }
        val store = offline(StubEngine(always = AssistantIntent()), agent)
        store.send("¿Qué medicación toma Lucía?")
        assertTrue(store.last().text.startsWith(failureText))
        assertFalse(store.last().generated)
        assertNull(store.state.value.activity)
    }

    @Test fun whatWasStreamedBeforeAFailureIsKept() = runTest {
        val agent = StubAgent().apply { partials = listOf("Lucía toma"); error = StubFailure() }
        val store = offline(StubEngine(always = AssistantIntent()), agent)
        store.send("¿Qué medicación toma Lucía?")
        assertEquals("Lucía toma", store.last().text)
        assertTrue(store.last().generated)
    }

    @Test fun aContactChangeThatInventsItsValueIsAnsweredAsAQuestion() = runTest {
        // What the model did with "resumen del paciente Pablo Bartolomé".
        val invented = AssistantIntent(kind = Kind.UpdatePatient, patientQuery = "Pablo", patientField = "phone", value = "600111222")
        val agent = StubAgent().apply { partials = listOf("Resumen.") }
        val store = offline(StubEngine(always = invented), agent)
        store.send("Hazme un resumen del paciente Pablo Bartolomé")
        assertEquals(1, agent.prompts.size)
        assertNull(store.state.value.patientProposal)
        assertEquals("Resumen.", store.last().text)
    }

    @Test fun aFollowUpTheReaderTakesForABookingStillReachesTheAgent() = runTest {
        // The reader has no memory: it turned "resume lo anterior" into "agendar una cita".
        val agent = StubAgent().apply { partials = listOf("Resumen de lo anterior.") }
        val store = offline(StubEngine(always = AssistantIntent(kind = Kind.CreateAppointment)), agent)
        store.send("Resume la última respuesta que me has dado")
        assertEquals(listOf("Resume la última respuesta que me has dado"), agent.prompts)
        assertEquals("Resumen de lo anterior.", store.last().text)
        assertFalse("¿Para qué paciente quieres la cita?" in store.texts())
    }

    @Test fun aRequestForAdviceIsRefusedWithoutAnyModel() = runTest {
        var readerCalls = 0
        val agent = StubAgent().apply { partials = listOf("Podría ser una infección.") }
        val store = offline(StubEngine { readerCalls++; AssistantIntent() }, agent)
        store.send("Dame una sugerencia de diagnóstico para Pablo Bartolomé")
        store.send("Cómo puedo tratarlo")
        assertEquals(0, readerCalls)
        assertTrue(agent.prompts.isEmpty())
        val refusal = "No doy sugerencias de diagnóstico ni de tratamiento. Puedo resumir lo que consta en la ficha y los informes del paciente."
        assertEquals(listOf(refusal, refusal), store.state.value.messages.filter { it.role == AssistantMessage.Role.Assistant }.map { it.text })
        assertTrue(store.state.value.messages.none { it.generated })
    }

    // --- The patient of the conversation

    @Test fun aFollowUpCommandIsAboutThePatientTheAgentLookedUp() = runTest {
        val agent = StubAgent().apply {
            partials = listOf("Lucía Ferrer, 38 años…"); lastPatient = lucia
            act = { if (it.endsWith("¿Y su próxima cita?")) AssistantIntent(kind = Kind.NextAppointment) else null }
        }
        val store = demo(StubEngine(always = AssistantIntent()), agent)
        store.send("Hazme un resumen de Lucía Ferrer")
        store.send("¿Y su próxima cita?")
        assertTrue("Lucía Ferrer" in store.last().text)
        assertFalse("¿De qué paciente?" in store.texts())
    }

    @Test fun aChangeThatNamesNobodyIsProposedForThePatientOfTheConversation() = runTest {
        val agent = StubAgent().apply {
            partials = listOf("Resumen."); lastPatient = lucia
            val booking = AssistantIntent(kind = Kind.CreateAppointment, date = "2026-11-02", start = "11:00", end = "11:30")
            act = { if (it.endsWith("Dale cita el lunes de 11 a 11:30")) booking else null }
        }
        val store = demo(StubEngine(always = AssistantIntent()), agent)
        store.send("Hazme un resumen de Lucía Ferrer")
        store.send("Dale cita el lunes de 11 a 11:30")
        // Nothing is written: the card shows who it is for and waits for "Confirmar".
        assertEquals(lucia.id, store.state.value.proposal?.patient?.id)
    }

    @Test fun resetForgetsThePatientOfTheConversation() = runTest {
        val agent = StubAgent().apply {
            partials = listOf("Resumen."); lastPatient = lucia
            act = { if (it.endsWith("¿Y su próxima cita?")) AssistantIntent(kind = Kind.NextAppointment) else null }
        }
        val store = offline(StubEngine(always = AssistantIntent()), agent)
        store.send("Hazme un resumen de Lucía Ferrer")
        store.reset()
        agent.lastPatient = null
        store.send("¿Y su próxima cita?")
        assertEquals("¿De qué paciente?", store.last().text)
    }

    @Test fun theAgentIsToldWhichPatientACommandWasAbout() = runTest {
        val agent = StubAgent().apply {
            partials = listOf("Fotoprotector.")
            act = { if (it.endsWith("Abre la ficha de Lucía")) AssistantIntent(kind = Kind.OpenPatient, patientQuery = "Lucía") else null }
        }
        val store = demo(StubEngine(always = AssistantIntent()), agent)
        store.send("Abre la ficha de Lucía")
        store.send("¿Qué medicación toma?")
        assertTrue("Patient being talked about: Lucía Ferrer." in agent.prompts.last())
        assertTrue(agent.prompts.last().endsWith("Question: ¿Qué medicación toma?"))
    }

    // --- One entry: the agent and its change tool

    @Test fun anOrderGoesThroughTheChangeToolAndWaitsOnTheCard() = runTest {
        // What the model narrates is not what the user reads: "ya está" would be false.
        val agent = StubAgent().apply {
            partials = listOf("¡Hecho! Cita agendada.")
            act = { AssistantIntent(kind = Kind.CreateAppointment, patientQuery = "Lucía Ferrer", date = "2026-11-09", start = "11:00", end = "11:30") }
        }
        val store = demo(StubEngine { error("the command reader is not used while the agent runs") }, agent)
        store.send("Quiero ver a Lucía Ferrer el lunes 9 de 11 a 11:30")
        val p = store.state.value.proposal
        assertEquals("Lucía Ferrer", p?.patient?.fullName)
        assertEquals(11 * 60, p?.start)
        assertFalse(store.last().generated)
        assertFalse("Cita agendada" in store.last().text)
        assertTrue("Nothing is done until they tap Confirmar" in agent.toolReplies.first())
    }

    @Test fun aFollowUpChangeIsAboutThePatientBeingTalkedAbout() = runTest {
        val agent = StubAgent().apply {
            act = { q ->
                if (q.endsWith("a las 12")) AssistantIntent(kind = Kind.CreateAppointment, date = "2026-11-09", start = "12:00", end = "12:30")
                else AssistantIntent(kind = Kind.CreateAppointment, patientQuery = "Lucía Ferrer", date = "2026-11-09", start = "11:00", end = "11:30")
            }
        }
        val store = demo(StubEngine { error("not used") }, agent)
        store.send("Quiero ver a Lucía Ferrer el lunes 9 de 11 a 11:30")
        store.send("Mejor el lunes 9 a las 12")
        assertEquals("Lucía Ferrer", store.state.value.proposal?.patient?.fullName)
        assertEquals(12 * 60, store.state.value.proposal?.start)
    }

    @Test fun aDayOrHourTheUserDidNotSayIsNotBooked() = runTest {
        val agent = StubAgent().apply { act = { AssistantIntent(kind = Kind.CreateAppointment, patientQuery = "Lucía Ferrer", date = "2026-11-09", start = "17:00") } }
        val store = demo(StubEngine { error("not used") }, agent)
        store.send("Quiero ver a Lucía Ferrer")
        assertNull(store.state.value.proposal)
        assertEquals("¿Para qué día?", store.last().text)
    }

    @Test fun oneChangeAtATime() = runTest {
        val booking = AssistantIntent(kind = Kind.CreateAppointment, patientQuery = "Lucía Ferrer", date = "2026-11-09", start = "11:00", end = "11:30")
        val agent = StubAgent().apply { act = { booking } }
        val store = demo(StubEngine { error("not used") }, agent)
        store.send("Quiero ver a Lucía Ferrer el lunes 9 de 11 a 11:30")
        val first = store.state.value.proposal
        // The model asks for a second change in the same turn: the first card stays.
        val reply = agent.proposeChange!!(booking)
        assertTrue(reply.startsWith("A change is already waiting"))
        assertEquals(first, store.state.value.proposal)
    }

    @Test fun whenTheModelFailsAnOrderStillWorks() = runTest {
        val agent = StubAgent().apply { error = StubFailure() }
        val store = demo(StubEngine(always = AssistantIntent(kind = Kind.CreateAppointment, patientQuery = "Lucía", date = "2026-11-09", start = "11:00", end = "11:30")), agent)
        store.send("Quiero ver a Lucía el lunes 9 de 11 a 11:30")
        assertEquals("Lucía Ferrer", store.state.value.proposal?.patient?.fullName)
    }

    @Test fun aCompleteOrderTakesTheCommandReaderWithoutTheAgent() = runTest {
        val agent = StubAgent()
        val store = demo(StubEngine(always = AssistantIntent(kind = Kind.CreateAppointment, patientQuery = "Lucía Ferrer", date = "2026-11-09", start = "11:00", end = "12:00")), agent)
        store.send("Cita para Lucía Ferrer el lunes 9 de 11 a 12")
        assertTrue(agent.prompts.isEmpty())
        assertEquals("Lucía Ferrer", store.state.value.proposal?.patient?.fullName)
    }

    @Test fun aBetterTimeForAnUnconfirmedCardIsTheSameBooking() = runTest {
        // On the phone "Mejor a las 12" after a new booking became a move of an appointment that did not exist.
        val agent = StubAgent().apply { act = { AssistantIntent(kind = Kind.MoveAppointment, date = "2026-11-09", start = "12:00") } }
        val store = demo(StubEngine(always = AssistantIntent(kind = Kind.CreateAppointment, patientQuery = "Lucía Ferrer", date = "2026-11-09", start = "11:00", end = "12:00", visitType = "Seguimiento")), agent)
        store.send("Cita para Lucía Ferrer el lunes 9 de 11 a 12")
        store.send("Mejor a las 12")
        val p = store.state.value.proposal
        assertEquals(AssistantProposal.Change.Create, p?.change)
        assertEquals("Lucía Ferrer", p?.patient?.fullName)
        assertEquals(12 * 60, p?.start)
        assertEquals(60, p?.duration)
        assertEquals(VisitType.FollowUp, p?.type)
    }

    @Test fun aDateNoToolReturnedIsPointedOutInTheAnswer() = runTest {
        val agent = StubAgent().apply {
            partials = listOf("La última consulta fue el 2026-10-31 (D22.5).")
            lastSources = listOf("Last visit the patient came to: none recorded.\n2026-10-12 · Nevus D22.5")
        }
        val store = offline(StubEngine(always = AssistantIntent()), agent)
        store.send("¿Cuándo fue la última consulta de Lucía?")
        assertEquals(listOf("2026-10-31"), store.last().unsupported)
    }

    @Test fun aGreetingReadAsACommandForAPatientIsAnswered() = runTest {
        val agent = StubAgent().apply { partials = listOf("Hola, ¿en qué te ayudo?") }
        val store = offline(StubEngine(always = AssistantIntent(kind = Kind.OpenPatient, patientQuery = "Hola")), agent)
        store.send("Hola")
        assertEquals(listOf("Hola"), agent.prompts)
        assertEquals("Hola, ¿en qué te ayudo?", store.last().text)
    }

    @Test fun anOrderForSomeoneWhoDoesNotExistStillSaysSo() = runTest {
        val agent = StubAgent().apply { act = { AssistantIntent(kind = Kind.CreateAppointment, patientQuery = "Nadie", date = "2026-11-02", start = "11:00") } }
        val store = offline(StubEngine(always = AssistantIntent()), agent)
        store.send("Cita para Nadie el lunes a las 11")
        assertEquals("No encuentro ningún paciente llamado «Nadie».", store.last().text)
        assertTrue(store.state.value.canCreatePatient)
    }

    @Test fun anAnswerTheSystemBlocksSaysWhy() = runTest {
        val agent = StubAgent().apply { partials = listOf("Carmen Beltrán, 64"); error = AssistantAgentError(AssistantAgentError.Kind.BlockedBySystem) }
        val store = offline(StubEngine(always = AssistantIntent()), agent)
        store.send("¿Qué pacientes tienen más de 60 años?")
        assertEquals(2, store.state.value.messages.size)
        assertTrue(store.last().text.startsWith("La IA del dispositivo ha bloqueado esta respuesta"))
        assertFalse(store.last().generated)
    }

    @Test fun theAgentIsToldWhichPatientTheQuestionNames() = runTest {
        val agent = StubAgent().apply { partials = listOf("Fotoprotector.") }
        val store = demo(StubEngine(always = AssistantIntent()), agent)
        store.send("¿Qué medicación toma Lucía Ferrer?")
        assertTrue(agent.prompts.first().startsWith("The question is about the patient Lucía Ferrer."))
    }

    @Test fun withoutAnAgentAQuestionGetsTheOldHint() = runTest {
        val store = offline(StubEngine(always = AssistantIntent()), null)
        store.send("¿Qué medicación toma Lucía?")
        assertTrue(store.last().text.startsWith("No te he entendido."))
    }

    // --- The command lane: confirm and undo against the demo backend

    @Test fun aBookingIsConfirmedAndCanBeUndone() = runTest {
        val store = demo(StubEngine(always = AssistantIntent(kind = Kind.CreateAppointment, patientQuery = "Lucía Ferrer", date = "2026-11-09", start = "11:00", end = "11:30")), null)
        store.send("Cita para Lucía Ferrer el lunes 9 de 11 a 11:30")
        assertTrue(store.state.value.proposal?.isValid == true)
        val outcome = store.confirm()
        assertNotNull(outcome)
        assertEquals("Cita agendada · 2026-11-09, 11:00", outcome!!.message)
        assertNull(store.state.value.proposal)
        outcome.undo() // archives the appointment again
    }

    @Test fun aSundayOrAPastHourCannotBeConfirmed() = runTest {
        val sunday = demo(StubEngine(always = AssistantIntent(kind = Kind.CreateAppointment, patientQuery = "Lucía Ferrer", date = "2026-11-08", start = "11:00", end = "11:30")), null)
        sunday.send("Cita para Lucía Ferrer el domingo a las 11")
        assertEquals(com.chiarasystems.chiara.domain.AgendaRules.Violation.Sunday, sunday.state.value.proposal?.issue)
        assertNull(sunday.confirm())
    }

    @Test fun contactChangesAreValidatedBeforeTheCard() = runTest {
        fun store(value: String, field: String) = StubEngine(always = AssistantIntent(kind = Kind.UpdatePatient, patientQuery = "Lucía Ferrer", patientField = field, value = value))
        val bad = demo(store("12", "phone"), null)
        bad.send("cambia el teléfono de Lucía Ferrer a 12")
        assertEquals("Ese teléfono no parece válido.", bad.last().text)
        val good = demo(store("600111222", "phone"), null)
        good.send("cambia el teléfono de Lucía Ferrer a 600111222")
        assertEquals("600111222", good.state.value.patientProposal?.newValue)
    }

    @Test fun freeSlotsAreOfferedAndPickingOneBooks() = runTest {
        val store = demo(StubEngine(always = AssistantIntent(kind = Kind.FindFreeSlot, minutes = 30, patientQuery = "Lucía Ferrer")), null)
        store.send("busca huecos libres para Lucía Ferrer")
        assertEquals(5, store.state.value.slotChoices.size)
        store.choose(store.state.value.slotChoices.first())
        assertEquals("Lucía Ferrer", store.state.value.proposal?.patient?.fullName)
    }
}
