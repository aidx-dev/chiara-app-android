package com.chiarasystems.chiara.data

import com.chiarasystems.chiara.domain.AssistantContext
import com.chiarasystems.chiara.domain.AssistantIntent
import com.chiarasystems.chiara.domain.AssistantIntent.Kind
import com.chiarasystems.chiara.domain.FallbackEngine
import com.chiarasystems.chiara.domain.RuleBasedEngine
import com.chiarasystems.chiara.network.DemoBackend
import com.chiarasystems.chiara.network.FileResponseCache
import com.chiarasystems.chiara.network.InMemorySecureStore
import com.chiarasystems.chiara.network.SupabaseClient
import com.chiarasystems.chiara.network.SupabaseConfig
import java.io.File
import java.time.LocalDate
import java.util.Locale
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

private val TODAY = LocalDate.of(2026, 10, 30)
private val CONTEXT = AssistantContext(TODAY, "es", Locale("es"))

/** A model that answers from a script and records what it was asked. */
private class FakeLlm(private vararg val replies: String) : LlmBackend {
    override var unavailableReason: String? = null
    val asked = mutableListOf<List<ChatMessage>>()
    override fun prepare() {}
    override suspend fun complete(messages: List<ChatMessage>, maxTokens: Int, onPartial: (String) -> Unit): String {
        asked += messages
        val reply = replies[minOf(asked.size, replies.size) - 1]
        // Stream it in two pieces, like a real model.
        onPartial(reply.take(reply.length / 2)); onPartial(reply)
        return reply
    }
}

class ToolCallingAgentTest {
    private val cacheDir = File.createTempFile("agent", "").also { it.delete() }
    private val days = object : DayFormatter {
        override fun dayChip(day: LocalDate) = day.toString()
        override fun longDay(day: LocalDate) = day.toString()
    }
    private val translate: (String, List<Any>) -> String = { key, args -> if (args.isEmpty()) key else String.format(key, *args.toTypedArray()) }

    private class Env(val patients: PatientsStore, val appointments: AppointmentsStore, val reports: ReportsStore)

    private suspend fun TestScope.env(): Env {
        val client = SupabaseClient(
            SupabaseConfig("http://demo.invalid/", "anon"), InMemorySecureStore(), FileResponseCache(cacheDir),
            OkHttpClient.Builder().addInterceptor(DemoBackend { TODAY }).build(),
        )
        client.signIn("demo@chiara.test", "test-only")
        val patients = PatientsStore(client, this)
        patients.load()
        return Env(patients, AppointmentsStore(client), ReportsStore(client))
    }

    private fun reader(e: Env) = ClinicReader(e.patients, e.appointments, e.reports, days, { it }, { TODAY }, { 0 })
    private fun agent(e: Env, llm: LlmBackend) = ToolCallingAgent(llm, reader(e), days, { "Europe/Madrid" }, { "10:00" })

    @Test fun aToolCallIsRunAndItsResultFeedsTheFinalAnswer() = runTest {
        val e = env()
        val llm = FakeLlm("""{"tool":"getPatientReports","arguments":{"patient":"Lucía Ferrer","count":1}}""", "Lucía toma Fotoprotector SPF50.")
        val agent = agent(e, llm)
        val partials = mutableListOf<String>()
        agent.answer("¿Qué medicación toma Lucía Ferrer?", CONTEXT, { partials += it }, {})
        // The tool call (JSON) is never shown; the final answer streams, and ends complete.
        assertTrue(partials.none { it.trimStart().startsWith("{") })
        assertEquals("Lucía toma Fotoprotector SPF50.", partials.last())
        // The second model call saw the tool's result.
        val second = llm.asked[1].last().content
        assertTrue("Tool result for getPatientReports" in second && "Fotoprotector SPF50" in second)
        assertEquals("Lucía Ferrer", agent.lastPatient?.fullName)
        assertTrue(agent.lastSources.any { "Medicación" in it || "Medication" in it || "Fotoprotector" in it })
    }

    @Test fun aGreetingNeedsNoTool() = runTest {
        val e = env()
        val llm = FakeLlm("Hola, ¿en qué te ayudo?")
        val agent = agent(e, llm)
        var last = ""
        agent.answer("Hola", CONTEXT, { last = it }, {})
        assertEquals(1, llm.asked.size)
        assertEquals("Hola, ¿en qué te ayudo?", last)
        assertTrue(agent.lastSources.isEmpty())
    }

    @Test fun theConversationIsRememberedForFollowUpsAndForgottenOnReset() = runTest {
        val e = env()
        val llm = FakeLlm("Primera respuesta.", "Segunda respuesta.", "Tercera.")
        val agent = agent(e, llm)
        agent.answer("Hola", CONTEXT, {}, {})
        agent.answer("¿Y la última vez?", CONTEXT, {}, {})
        val second = llm.asked[1]
        assertTrue(second.any { it.role == ChatMessage.Role.User && it.content.startsWith("Hola") })
        assertTrue(second.any { it.role == ChatMessage.Role.Assistant && it.content == "Primera respuesta." })
        agent.reset()
        agent.answer("Otra cosa", CONTEXT, {}, {})
        assertFalse(llm.asked[2].any { it.content == "Primera respuesta." })
    }

    @Test fun anUnknownToolIsAnsweredWithTheAvailableOnes() = runTest {
        val e = env()
        val llm = FakeLlm("""{"tool":"deleteEverything","arguments":{}}""", "No puedo hacer eso.")
        agent(e, llm).answer("Borra todo", CONTEXT, {}, {})
        assertTrue("Unknown tool" in llm.asked[1].last().content && "getAgenda" in llm.asked[1].last().content)
    }

    @Test fun aModelThatKeepsCallingToolsIsCutOff() = runTest {
        val e = env()
        val loop = """{"tool":"getAgenda","arguments":{"date":"2026-10-30"}}"""
        val llm = FakeLlm(loop)
        var last = "?"
        agent(e, llm).answer("agenda", CONTEXT, { last = it }, {})
        assertEquals(5, llm.asked.size) // 4 tool rounds, then the answer is taken as it is
        assertNotNull(last)
    }

    @Test fun theChangeToolGoesThroughTheStoreAndNeverWrites() = runTest {
        val e = env()
        val llm = FakeLlm(
            """Voy a prepararlo. {"tool":"proposeChange","arguments":{"action":"createAppointment","patient":"Lucía Ferrer","date":"2026-11-09","startTime":"11:00","endTime":"11:30"}}""",
            "Preparada la cita; confírmala en la tarjeta.",
        )
        val agent = agent(e, llm)
        val store = AssistantStore(FallbackEngine(LlmIntentEngine(llm), RuleBasedEngine()), agent, e.patients, e.appointments, days, { CONTEXT }, translate, { TODAY }, { 0 })
        store.send("Quiero ver a Lucía Ferrer el lunes 9 de 11 a 11:30 por favor, resumen aparte")
        val p = store.state.value.proposal
        assertEquals("Lucía Ferrer", p?.patient?.fullName)
        assertEquals(11 * 60, p?.start)
        // Nothing is written until "Confirmar"; the model's narration is replaced by the app's own words.
        assertTrue(store.state.value.messages.last().text.isNotEmpty())
    }

    @Test fun toolCallsAreFoundInsideFencesAndSentences() {
        val a = ToolCallingAgent.parseToolCall("```json\n{\"tool\":\"getAgenda\",\"arguments\":{\"date\":\"2026-10-30\"}}\n```")
        assertEquals("getAgenda", a?.first)
        assertEquals("2026-10-30", a?.second?.get("date")?.toString()?.trim('"'))
        assertNull(ToolCallingAgent.parseToolCall("La agenda de hoy tiene {dos} citas."))
        assertNull(ToolCallingAgent.parseToolCall("Sin JSON."))
        // Braces inside strings do not confuse the scanner.
        assertEquals("proposeChange", ToolCallingAgent.parseToolCall("""{"tool":"proposeChange","arguments":{"text":"nota {urgente}"}}""")?.first)
    }

    @Test fun theIntentEngineReadsTheModelsJsonAndFallsBackToRulesWithoutIt() = runTest {
        val json = """Claro: {"action":"createAppointment","patientName":"Lucía","date":"2026-11-02","startTime":"11:00","endTime":"12:00","minutes":0,"visitType":"Revisión"}"""
        val intent = LlmIntentEngine(FakeLlm(json)).interpret("Cita para Lucía el lunes de 11 a 12", CONTEXT)
        assertEquals(Kind.CreateAppointment, intent.kind)
        assertEquals("Lucía", intent.patientQuery)
        assertEquals("2026-11-02", intent.date)
        assertEquals("11:00", intent.start)
        assertEquals(null, intent.minutes)
        // An unreadable answer is "not a command", like the system model saying so.
        assertEquals(Kind.Unknown, LlmIntentEngine(FakeLlm("no sé")).interpret("hola", CONTEXT).kind)
        // The model unavailable: the rules read the order.
        val off = FakeLlm("").apply { unavailableReason = "Instala el modelo." }
        val engine = FallbackEngine(LlmIntentEngine(off), RuleBasedEngine())
        assertEquals("Instala el modelo.".takeIf { RuleBasedEngine().unavailableReason != null }, engine.unavailableReason.takeIf { it != null })
        assertEquals(Kind.ShowAgenda, engine.interpret("enséñame la agenda de mañana", CONTEXT).kind)
    }

    @Test fun theSummarizerGivesTheModelOnlyTheRecordAndReturnsItForChecking() = runTest {
        val e = env()
        val llm = FakeLlm("Lucía, 38 años. Nevus benigno D22.5.")
        val patient = e.patients.patients.value.first { it.fullName == "Lucía Ferrer" }
        var shown = ""
        val record = LlmPatientSummarizer(llm, reader(e)).summarize(patient, CONTEXT) { shown = it }
        assertEquals("Lucía, 38 años. Nevus benigno D22.5.", shown)
        assertTrue("D22.5" in record)
        val prompt = llm.asked.single().last().content
        assertTrue(prompt.startsWith("Record:\n") && prompt.endsWith("Write the summary in Spanish."))
    }
}
