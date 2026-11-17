package com.chiarasystems.chiara.domain

import com.chiarasystems.chiara.domain.AgendaRules.Busy
import com.chiarasystems.chiara.domain.AgendaRules.Violation
import com.chiarasystems.chiara.domain.PatientMatcher.Match
import com.chiarasystems.chiara.model.Patient
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

private fun patient(first: String, last: String, doc: String? = null) =
    Patient(id = UUID.randomUUID().toString(), firstName = first, lastName = last, documentId = doc)

/** Ports of PatientMatcherTests, AgendaRulesTests and ClinicalAdviceGuardTests (+ GroundingCheck). 2026-10-30 is a Friday. */
class AssistantLogicTest {
    private val people = listOf(
        patient("Pablo", "Bartolomé Gallardo"), patient("Pablo", "Soler"),
        patient("María", "Núñez"), patient("Mariano", "Ferrer"),
    )

    private fun one(m: Match) = (m as Match.One).patient
    private fun many(m: Match) = (m as Match.Many).patients

    // --- PatientMatcher ------------------------------------------------------------------------------------------

    @Test fun matchesIgnoringAccentsAndCase() = assertEquals("Núñez", one(PatientMatcher.match("maria nunez", people)).lastName)

    @Test fun everyWordMustStartAWordOfTheName() =
        assertEquals("Bartolomé Gallardo", one(PatientMatcher.match("pablo bart", people)).lastName)

    @Test fun severalMatchesAreAmbiguous() {
        assertEquals(2, many(PatientMatcher.match("pablo", people)).size)
        assertEquals(2, many(PatientMatcher.match("mari", people)).size)
    }

    @Test fun noMatchAndEmptyQuery() {
        assertTrue(PatientMatcher.match("laura", people) is Match.None)
        assertTrue(PatientMatcher.match("   ", people) is Match.None)
    }

    @Test fun wordsThatComeWithANameAreIgnored() =
        assertEquals("Bartolomé Gallardo", one(PatientMatcher.match("los informes del paciente Pablo Bartolomé", people)).lastName)

    @Test fun theIdDocumentFindsThePatient() {
        val withId = people + patient("Iván", "Soler", "45678901C")
        assertEquals("Iván", one(PatientMatcher.match("45678901c", withId)).firstName)
    }

    @Test fun closestNeedsTwoWords() {
        assertTrue(PatientMatcher.match("Pablo Bartolomé García", people) is Match.None)
        assertEquals("Bartolomé Gallardo", one(PatientMatcher.closest("Pablo Bartolomé García", people)).lastName)
        assertTrue(PatientMatcher.closest("Pablo Pérez", people) is Match.None)
    }

    @Test fun closestStillAsksWhenTwoAreEquallyClose() {
        val twins = listOf(patient("Pablo", "Soler Ruiz"), patient("Pablo", "Soler Gil"))
        assertEquals(2, many(PatientMatcher.closest("Pablo Soler García", twins)).size)
    }

    @Test fun aCutNameIsCompletedWithTheWordsThatFollowIt() {
        val tests = listOf(patient("Prueba", "asdf"), patient("Prueba", "qwer"))
        assertEquals("asdf", one(PatientMatcher.widened("Prueba", "Hazme un resumen de Prueba asdf", tests)).lastName)
        assertTrue(PatientMatcher.widened("Prueba", "Cita para Prueba el lunes", tests) is Match.Many)
    }

    @Test fun thePatientASentenceNamesInFull() {
        val tests = listOf(patient("Prueba", "asdf"), patient("Prueba1_20261007", "Test"))
        assertEquals("asdf", PatientMatcher.named("¿Qué medicación toma Prueba asdf?", tests)?.lastName)
        assertEquals("Bartolomé Gallardo", PatientMatcher.named("Hazme un resumen de Pablo Bartolomé", people)?.lastName)
        assertNull(PatientMatcher.named("¿Qué medicación toma Pablo?", people))
        assertNull(PatientMatcher.named("¿Cuántos pacientes hay?", people))
    }

    @Test fun aWordNotInTheNameRulesItOut() =
        assertTrue(PatientMatcher.match("pablo bartolome incluyendo", people) is Match.None)

    // --- AgendaRules (iOS AgendaRulesTests) ----------------------------------------------------------------------

    private val today = LocalDate.of(2026, 10, 30)
    private val monday = LocalDate.of(2026, 11, 2)
    private val sunday = LocalDate.of(2026, 11, 1)

    private fun v(day: LocalDate, start: Int, dur: Int = 30, now: Int = 540, busy: List<Busy> = emptyList()) =
        AgendaRules.violation(day, start, dur, busy, today, now)

    @Test fun aFreeSlotIsAccepted() = assertNull(v(monday, 9 * 60))

    @Test fun slotsSkipLunchAndKeepTheGrid() {
        assertEquals(18, AgendaRules.slots.size)
        assertFalse(AgendaRules.slots.contains(14 * 60 + 30))
    }

    @Test fun rulesAreCheckedInOrder() {
        assertEquals(Violation.Sunday, v(sunday, 9 * 60))
        assertEquals(Violation.OutsideHours, v(monday, 8 * 60))
        assertEquals(Violation.OutsideHours, v(monday, 19 * 60))
        assertEquals(Violation.OutsideHours, v(monday, 9 * 60 + 15))
        assertEquals(Violation.LunchBreak, v(monday, 14 * 60 + 30))
        assertEquals(Violation.InvalidDuration, v(monday, 9 * 60, 20))
        assertEquals(Violation.Past, v(LocalDate.of(2026, 10, 29), 9 * 60))
    }

    @Test fun todayIsPastOnlyBeforeNow() {
        assertEquals(Violation.Past, v(today, 9 * 60, now = 10 * 60))
        assertNull(v(today, 11 * 60, now = 10 * 60))
    }

    @Test fun overlapWithABusyIntervalIsRejected() {
        val busy = listOf(Busy(10 * 60, 11 * 60))
        assertEquals(Violation.Overlap, v(monday, 10 * 60 + 30, busy = busy))
        assertEquals(Violation.Overlap, v(monday, 9 * 60 + 30, 60, busy = busy))
        assertNull(v(monday, 11 * 60, busy = busy))
        assertNull(v(monday, 9 * 60 + 30, busy = busy))
    }

    @Test fun movingKeepsItsOwnOddStartAndDuration() {
        val r = AgendaRules.violation(monday, 9 * 60 + 10, 25, emptyList(), today, 0, enforceDuration = false, enforceGrid = false)
        assertNull(r)
    }

    @Test fun freeStartsAreTheNearestOnesInOrder() {
        val busy = listOf(Busy(10 * 60, 11 * 60))
        val free = AgendaRules.freeStarts(monday, 30, busy, today, 0, near = 10 * 60, limit = 3)
        assertEquals(listOf(9 * 60, 9 * 60 + 30, 11 * 60), free)
    }

    // --- ClinicalAdviceGuard -------------------------------------------------------------------------------------

    private val advice = listOf(
        "Dame una sugerencia de diagnóstico para Pablo Bartolomé", "Cómo puedo tratarlo", "¿Qué tratamiento le doy a Pablo?",
        "Recomiéndame un tratamiento para Lucía", "Posible diagnóstico de Lucía", "Suggest a diagnosis for Pablo",
        "How should I treat him?", "Diagnosevorschlag für Pablo", "Comment puis-je le traiter ?",
    )
    private val recordQuestions = listOf(
        "¿Cuál es el diagnóstico de Pablo?", "Dame el plan de tratamiento de Lucía", "¿Qué tratamiento tiene Carmen?",
        "¿Qué medicación toma María?", "Hazme un resumen del paciente Pablo Bartolomé",
        "Cita para Antonio el lunes de 11 a 12", "What is Pablo's diagnosis?",
    )

    @Test fun adviceIsRefused() = advice.forEach { assertTrue(it, ClinicalAdviceGuard.isAskingForAdvice(it)) }

    @Test fun recordQuestionsPass() = recordQuestions.forEach { assertFalse(it, ClinicalAdviceGuard.isAskingForAdvice(it)) }

    // --- GroundingCheck ------------------------------------------------------------------------------------------

    @Test fun unsupportedDatesAndCodesAreFoundOnceInOrder() {
        val text = "Última visita 2026-10-31 y 2026-10-12; dx L82, L82 y D22.5."
        val sources = listOf("visita 2026-10-12", "D22.5 Nevus")
        assertEquals(listOf("2026-10-31", "L82"), GroundingCheck.unsupported(text, sources))
        assertTrue(GroundingCheck.unsupported("sin datos", emptyList()).isEmpty())
    }
}
