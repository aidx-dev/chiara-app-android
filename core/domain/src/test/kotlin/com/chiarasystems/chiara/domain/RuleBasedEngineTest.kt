package com.chiarasystems.chiara.domain

import com.chiarasystems.chiara.domain.AssistantIntent.Kind
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of RuleBasedEngineTests + FallbackEngine. Today is Friday 2026-10-30: "el lunes" is 2026-11-02, "el jueves" 2026-11-05. */
class RuleBasedEngineTest {
    private val context = AssistantContext(LocalDate.of(2026, 10, 30), "es", Locale.forLanguageTag("es"))

    private data class Case(
        val text: String, val kind: Kind, val patient: String? = null, val date: String? = null,
        val start: String? = null, val end: String? = null, val minutes: Int? = null,
        val value: String? = null, val note: String? = null,
    )

    private val commands = listOf(
        Case("Cita para Antonio el lunes de 11 a 12", Kind.CreateAppointment, "antonio", "2026-11-02", "11:00", "12:00"),
        Case("Book an appointment for Antonio on Monday from 11 to 12", Kind.CreateAppointment, "antonio", "2026-11-02", "11:00", "12:00"),
        Case("Apunta una cita para Lucía mañana a las 4", Kind.CreateAppointment, "lucia", "2026-10-31", "16:00"),
        Case("Enséñame la agenda de mañana", Kind.ShowAgenda, date = "2026-10-31"),
        Case("Abre la ficha de María", Kind.OpenPatient, "maria"),
        Case("Cancela la cita de Pablo del lunes", Kind.CancelAppointment, "pablo"),
        Case("Confirma la cita de Lucía", Kind.ConfirmAppointment, "lucia"),
        Case("Mueve la cita de Antonio al jueves a las 12", Kind.MoveAppointment, "antonio", "2026-11-05", "12:00"),
        Case("Anota en la cita de Marcos: traer analítica", Kind.AddNote, "marcos", note = "traer analítica"),
        Case("¿Cuándo es la próxima cita de Irene?", Kind.NextAppointment, "irene"),
        Case("¿Cuántas citas tengo mañana?", Kind.AskAgenda, date = "2026-10-31"),
        Case("Primer hueco de 30 minutos esta semana", Kind.FindFreeSlot, minutes = 30),
        Case("Cambia el teléfono de Pablo a 600 111 222", Kind.UpdatePatient, "pablo", value = "600 111 222"),
    )

    /** Questions that were once read as commands: they belong to the agent. */
    private val questions = listOf(
        "Hazme un resumen del paciente Pablo Bartolomé incluyendo los informes y el histórico de citas",
        "¿Qué medicación toma María?", "Dame el histórico de Pablo", "¿Qué pacientes tienen psoriasis?",
        "¿Qué le diagnosticaron a Lucía en la última consulta?", "Hola", "Gracias",
    )

    @Test fun readsCommands() = runBlocking {
        for (c in commands) {
            val intent = RuleBasedEngine().interpret(c.text, context)
            assertEquals(c.text, c.kind, intent.kind)
            c.patient?.let { assertEquals(c.text, it, intent.patientQuery) }
            c.date?.let { assertEquals(c.text, it, intent.date) }
            c.start?.let { assertEquals(c.text, it, intent.start) }
            c.end?.let { assertEquals(c.text, it, intent.end) }
            c.minutes?.let { assertEquals(c.text, it, intent.minutes) }
            c.value?.let { assertEquals(c.text, it, intent.value) }
            c.note?.let { assertEquals(c.text, it, intent.note) }
        }
    }

    @Test fun leavesQuestionsAlone() = runBlocking {
        for (q in questions) assertEquals(q, Kind.Unknown, RuleBasedEngine().interpret(q, context).kind)
    }

    @Test fun nameStopsAtWordsThatAreNotPartOfIt() = runBlocking {
        val intent = RuleBasedEngine().interpret("Cita para Pablo Bartolomé incluyendo el motivo el lunes a las 10", context)
        assertEquals("pablo bartolome", intent.patientQuery)
    }

    @Test fun calendarTableWritesOutTheNextDays() {
        val table = context.calendarTable(3).lines()
        assertEquals("- Friday (viernes) 2026-10-30 [today]", table[0])
        assertEquals("- Saturday (sábado) 2026-10-31 [tomorrow]", table[1])
        assertEquals("- Sunday (domingo) 2026-11-01", table[2])
    }

    @Test fun fallbackUsesRulesOnlyWhenThePrimaryIsUnavailableOrFails() = runBlocking {
        class Stub(override val unavailableReason: String? = null, val result: () -> AssistantIntent) : AssistantEngine {
            override suspend fun interpret(text: String, context: AssistantContext) = result()
        }
        val rules = RuleBasedEngine()
        val modelSaysUnknown = FallbackEngine(Stub { AssistantIntent() }, rules)
        // The model answered: its "not a command" stands even though the rules would guess one.
        assertEquals(Kind.Unknown, modelSaysUnknown.interpret("Cita para Antonio el lunes", context).kind)
        val unavailable = FallbackEngine(Stub("no model") { AssistantIntent() }, rules)
        assertEquals(Kind.CreateAppointment, unavailable.interpret("Cita para Antonio el lunes", context).kind)
        assertEquals(null, unavailable.unavailableReason)
        val failing = FallbackEngine(Stub { error("boom") }, rules)
        assertEquals(Kind.CreateAppointment, failing.interpret("Cita para Antonio el lunes", context).kind)
        assertTrue(true)
    }
}
