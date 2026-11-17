package com.chiarasystems.chiara.data

import com.chiarasystems.chiara.domain.AssistantContext
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
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the bench harness and the routing with a scripted "model" that behaves like a good one: it calls the right tool
 * with the patient's name for questions, and says plain text otherwise. The same bench, run on the phone with the real
 * model (Settings, debug builds), is what measures the model.
 */
class AssistantBenchTest {
    private val today = LocalDate.of(2026, 10, 30)
    private val context = AssistantContext(today, "es", Locale("es"))
    private val days = object : DayFormatter {
        override fun dayChip(day: LocalDate) = day.toString()
        override fun longDay(day: LocalDate) = day.toString()
    }
    private val tr: (String) -> String = { it }

    /** Picks a tool from the last user question the way a competent model would. */
    private inner class ScriptedModel(private val patient: String) : LlmBackend {
        override val unavailableReason: String? = null
        override fun prepare() {}
        override suspend fun complete(messages: List<ChatMessage>, maxTokens: Int, onPartial: (String) -> Unit): String {
            val last = messages.last()
            // As the command reader: a good extractor (here, the keyword rules written out as the JSON the engine asks for).
            if (messages.first().content.contains("extract structured fields")) {
                val i = RuleBasedEngine().interpret(last.content, context)
                val json = buildString {
                    append("{\"action\":\"").append(i.kind.name.replaceFirstChar { it.lowercase() }).append("\"")
                    fun field(k: String, v: String?) { if (v != null) append(",\"$k\":\"").append(v.replace("\"", "")).append("\"") }
                    field("patientName", i.patientQuery); field("date", i.date); field("startTime", i.start); field("endTime", i.end)
                    field("existingDate", i.sourceDate); field("existingStartTime", i.sourceStart); field("note", i.note)
                    field("patientField", i.patientField); field("value", i.value); field("visitType", i.visitType); field("reason", i.reason)
                    append("}")
                }
                onPartial(json); return json
            }
            val reply = if (last.content.startsWith("Tool result")) "Según la ficha: dato correcto."
            else {
                val q = last.content.lowercase()
                when {
                    "cuántos pacientes" in q || "más de 60" in q -> """{"tool":"findPatients","arguments":{"minAge":61}}"""
                    "diabetes" in q -> """{"tool":"searchReports","arguments":{"text":"diabetes"}}"""
                    "citas tengo mañana" in q -> """{"tool":"getAgenda","arguments":{"date":"2026-10-31"}}"""
                    "resumen" in q || "medicación" in q || "última consulta" in q -> """{"tool":"getPatientSummary","arguments":{"patient":"$patient"}}"""
                    else -> "Hola, ¿en qué te ayudo?"
                }
            }
            onPartial(reply)
            return reply
        }
    }

    @Test fun aCompetentModelPassesTheRoutingCasesTheAppDoesNotDecideByItself() = runTest {
        val cacheDir = File.createTempFile("bench", "").also { it.delete() }
        val client = SupabaseClient(
            SupabaseConfig("http://demo.invalid/", "anon"), InMemorySecureStore(), FileResponseCache(cacheDir),
            OkHttpClient.Builder().addInterceptor(DemoBackend { today }).build(),
        )
        client.signIn("demo@chiara.test", "test-only")
        val patients = PatientsStore(client, this).also { it.load() }
        val appointments = AppointmentsStore(client)
        val reader = ClinicReader(patients, appointments, ReportsStore(client), days, tr, { today }, { 0 })
        val llm = ScriptedModel("Lucía Ferrer")
        val agent = ToolCallingAgent(llm, reader, days, { "Europe/Madrid" }, { "10:00" })
        val store = AssistantStore(FallbackEngine(LlmIntentEngine(llm), RuleBasedEngine()), agent, patients, appointments, days, { context }, { k, a -> if (a.isEmpty()) k else String.format(k, *a.toTypedArray()) }, { today }, { 0 })

        val results = AssistantBench.run(store, reader, "Lucía Ferrer", tr)

        assertEquals(19, results.size)
        results.forEach { println("BENCH ${if (it.passed) "ok  " else "FAIL"} ${it.case.turns} -> ${it.outcome}") }
        // Advice is always refused by the app, never by a model; orders are read by the keyword rules when the model says
        // nothing useful; navigation and questions go through the agent. A scripted model that only knows questions must
        // still get all of these right.
        fun passed(prefix: String) = results.first { it.case.turns.last().startsWith(prefix) }.passed
        assertTrue(passed("Cómo puedo tratarlo"))
        assertTrue(passed("Dame una sugerencia de diagnóstico"))
        assertTrue(passed("¿Cuántos pacientes"))
        assertTrue(passed("¿Qué medicación"))
        assertTrue(passed("¿Qué citas tengo mañana?"))
        assertTrue(passed("Hola"))
        assertTrue(passed("Abre la ficha"))
        assertTrue(passed("Cita para {p} el lunes de 11 a 12"))
        // Everything but the follow-ups that need the agent's memory of a card (a scripted model has none).
        assertTrue(results.count { !it.passed }.toString(), results.count { it.passed } >= 17)
    }

    @Test fun theBenchChecksWhatEachOutcomeMeans() {
        val b = AssistantBench
        assertTrue(b.accepts(AssistantBench.Expectation.Proposal("create"), AssistantBench.Outcome.Proposal("create")))
        assertTrue(!b.accepts(AssistantBench.Expectation.Proposal("move"), AssistantBench.Outcome.Proposal("create")))
        // A tool for somebody else does not count as being about the patient.
        val agent = AssistantBench.Outcome.Agent(listOf("getPatientSummary"))
        assertTrue(b.accepts(AssistantBench.Expectation.AgentAbout(b.PATIENT_TOOLS), agent, "Lucía Ferrer", listOf("getPatientSummary" to "Lucía Ferrer")))
        assertTrue(!b.accepts(AssistantBench.Expectation.AgentAbout(b.PATIENT_TOOLS), agent, "Lucía Ferrer", listOf("getPatientSummary" to "Marcos Alonso")))
        assertTrue(b.accepts(AssistantBench.Expectation.ReplyNaming, AssistantBench.Outcome.Reply("La próxima cita de Lucía Ferrer es el lunes."), "Lucía Ferrer"))
    }
}
