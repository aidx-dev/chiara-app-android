package com.chiarasystems.chiara.domain

import com.chiarasystems.chiara.model.Appointment
import com.chiarasystems.chiara.model.Patient
import com.chiarasystems.chiara.model.Report
import com.chiarasystems.chiara.model.VisitType
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports of DayBriefingTests, GroundingCheckTests, DictationVocabularyTests and the AudioLanguage rules. */
class BriefingAndDictationTest {
    private val monday = LocalDate.of(2026, 11, 2)
    private val ana = UUID.randomUUID().toString()
    private val luis = UUID.randomUUID().toString()
    private val eva = UUID.randomUUID().toString()

    private fun appt(patient: String, start: String, end: String, status: String, notes: String, name: String) = Appointment(
        id = UUID.randomUUID().toString(), patientId = patient, date = "2026-11-02",
        startTime = "$start:00", endTime = "$end:00", status = status, notes = notes,
        patients = Appointment.PatientName(name, "X"),
    )

    @Test fun countsFreeSlotsAndTheLastReportOfEachPatient() {
        val day = listOf(
            appt(luis, "10:00", "11:00", "Confirmed", "Revisión", "Luis"),
            appt(ana, "09:00", "09:30", "Scheduled", "Primera visita", "Ana"),
            appt(eva, "12:00", "12:30", "Canceled", "Seguimiento", "Eva"),
        )
        val report = Report(
            id = "r", patientId = luis, date = OffsetDateTime.parse("2026-10-12T00:00:00Z"),
            title = "Nevus benigno", codes = emptyList(), sections = emptyList(),
        )
        val b = DayBriefing.make(monday, day, mapOf(luis to report), today = LocalDate.of(2026, 10, 30), now = 0)

        // The cancelled one does not count, nor block its slot.
        assertEquals(2, b.total)
        assertEquals(1, b.firstVisits)
        assertEquals(1, b.unconfirmed)
        assertFalse(b.freeSlots.contains(9 * 60) || b.freeSlots.contains(10 * 60) || b.freeSlots.contains(10 * 60 + 30))
        assertTrue(b.freeSlots.contains(12 * 60))
        assertEquals(listOf("Ana X", "Luis X"), b.lines.map { it.patientName })
        assertTrue(b.lines[0].isFirstVisit)
        assertNull(b.lines[0].lastReport)
        assertEquals("Nevus benigno", b.lines[1].lastReport?.title)
    }

    @Test fun aPastDayHasNoFreeSlots() {
        val b = DayBriefing.make(monday, listOf(appt(ana, "09:00", "09:30", "Completed", "Revisión", "Ana")), emptyMap(),
            today = LocalDate.of(2026, 11, 10), now = 0)
        assertEquals(1, b.total)
        assertTrue(b.freeSlots.isEmpty())
    }

    @Test fun groundingPointsOutWhatTheRecordDoesNotHave() {
        val record = "Lucía Ferrer\nLast visit the patient came to: 2026-10-30 09:00.\n2026-10-12 · Nevus\n  Diagnósticos: D22.5 Nevus"
        val summary = "Nevus (D22.5) el 2026-10-12; última visita 2026-10-30. Posible L40.0, revisión el 2026-11-15."
        assertEquals(listOf("2026-11-15", "L40.0"), GroundingCheck.unsupported(summary, listOf(record)))
        assertTrue(GroundingCheck.unsupported("Sin novedades.", listOf(record)).isEmpty())
        assertEquals(listOf("2026-01-01"), GroundingCheck.unsupported("2026-01-01 y 2026-01-01", emptyList()))
    }

    @Test fun dictationVocabularyOrderAndNoRepeats() {
        val people = listOf(
            Patient(id = "1", firstName = "Pablo", lastName = "Bartolomé Gallardo"),
            Patient(id = "2", firstName = "Ana", lastName = "Bartolomé Gallardo"),
        )
        val labels = VisitType.entries.map { it.raw }
        val words = DictationVocabulary.forPatients(people, labels)
        assertEquals(labels, words.take(4))
        assertTrue(words.contains("Pablo Bartolomé Gallardo") && words.contains("Ana Bartolomé Gallardo"))
        assertEquals(1, words.count { it == "Bartolomé Gallardo" })
    }

    @Test fun dictationVocabularyIsCapped() {
        val many = (0 until 500).map { Patient(id = "$it", firstName = "P$it", lastName = "S$it") }
        assertEquals(300, DictationVocabulary.forPatients(many, emptyList(), limit = 300).size)
    }

    @Test fun audioLanguageResolution() {
        assertEquals("es", AudioLanguageResolver.codeFromPatientLanguage(" Español "))
        assertEquals("fr", AudioLanguageResolver.codeFromPatientLanguage("Français"))
        assertNull(AudioLanguageResolver.codeFromPatientLanguage("Italiano"))
        assertNull(AudioLanguageResolver.codeFromPatientLanguage(null))
        assertEquals(AudioLanguageResolver.Resolved("de", AudioLanguageResolver.Source.Patient), AudioLanguageResolver.resolve("german", "es"))
        assertEquals(AudioLanguageResolver.Resolved("en", AudioLanguageResolver.Source.LastUsed), AudioLanguageResolver.resolve(null, "en"))
    }
}
