package com.chiarasystems.chiara.model

import java.time.LocalDate
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports of ReportTests, PatientAgeTests and the Appointment/Patient behaviour specified in the iOS models. */
class ModelsTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val today = LocalDate.of(2026, 10, 30)

    private fun item(raw: String) = json.decodeFromString<ConsultationDto>(raw).toItem()

    // --- ReportTests ---------------------------------------------------------------------------------------------

    @Test fun htmlBecomesPlainTextWithBullets() {
        val text = HtmlText.plain("<p>Hola&nbsp;<strong>mundo</strong> &amp; todos</p><ul><li>uno</li><li>dos</li></ul>")
        assertTrue(text.contains("Hola mundo & todos"))
        assertTrue(text.contains("• uno"))
        assertTrue(text.contains("• dos"))
        assertFalse(text.contains("<"))
    }

    @Test fun htmlEntitiesDecodeAmpersandLast() {
        assertEquals("&lt;", HtmlText.plain("&amp;lt;"))
    }

    @Test fun icdCodesAreReadFromTheJsonStoredInTheField() {
        val codes = IcdCode.parse("""[{"code":"D22.5","label":"Nevus"},{"code":"L82","label":"Queratosis"}]""")
        assertEquals(listOf("D22.5", "L82"), codes.map { it.code })
        assertTrue(IcdCode.parse("not json").isEmpty())
        assertTrue(IcdCode.parse(null).isEmpty())
    }

    @Test fun aReportIsBuiltInReadingOrder() {
        val consultation = item(
            """
            {"id":"00000000-0000-7000-8000-000000000200","patient_id":"00000000-0000-7000-8000-000000000001",
             "occurred_at":"2026-10-12T09:30:00.123456+00:00","template_id":"derm","status":"Finalized",
             "content":{
               "medication":"<p>Fotoprotector SPF50 — cada 3 horas</p>",
               "treatmentPlan":"<ul><li>Revisión en 6 meses</li></ul>",
               "diagnosis":"<p>Nevus benigno. Sin cambios.</p>",
               "icdCodes":"[{\"code\":\"D22.5\",\"label\":\"Nevus del tronco\"}]",
               "anamnesis":"<p>Lesión de 6 meses.</p>"}}
            """.trimIndent(),
        )
        val report = ReportBuilder.build(consultation)!!
        assertEquals(listOf("anamnesis", "diagnoses", "medication", "treatmentPlan"), report.sections.map { it.id })
        assertEquals("Nevus benigno", report.title)
        assertEquals(listOf("D22.5"), report.codes.map { it.code })
        assertNotNull(report.date)
        val med = report.sections[2].body as ReportSection.Body.Medication
        assertEquals(listOf(MedicationLine("Fotoprotector SPF50", "cada 3 horas")), med.lines)
    }

    @Test fun aDraftWithNoContentIsNotAReport() {
        val draft = item(
            """
            {"id":"00000000-0000-7000-8000-000000000202","patient_id":"00000000-0000-7000-8000-000000000002",
             "occurred_at":"2026-10-20T10:00:00+00:00","status":"Draft","content":{}}
            """.trimIndent(),
        )
        assertNull(ReportBuilder.build(draft))
    }

    @Test fun fieldsOfOtherTemplatesGetAReadableTitle() {
        val consultation = item(
            """
            {"id":"00000000-0000-7000-8000-000000000203","patient_id":"00000000-0000-7000-8000-000000000003",
             "occurred_at":"2026-10-20T10:00:00+00:00","status":"Finalized","content":{"skinCondition":"<p>Seca</p>"}}
            """.trimIndent(),
        )
        assertEquals("Skin condition", ReportBuilder.build(consultation)!!.sections.first().title)
    }

    @Test fun nonStringContentValuesAreDroppedNotFatal() {
        val c = item(
            """{"id":"a","patient_id":"b","content":{"anamnesis":"<p>Hola</p>","weird":42,"obj":{"x":1}}}""",
        )
        assertEquals(setOf("anamnesis"), c.content!!.keys)
    }

    // --- PatientAgeTests -----------------------------------------------------------------------------------------

    @Test fun anImpossibleBirthDateIsNoAge() {
        assertNull(Patient(id = "x", dateOfBirth = "2221-03-01").age(today))
        assertNull(Patient(id = "x", dateOfBirth = "1826-03-01").age(today))
        assertNull(Patient(id = "x", dateOfBirth = null).age(today))
        assertEquals(64, Patient(id = "x", dateOfBirth = "1962-02-03").age(today))
    }

    @Test fun patientNamesAndInitials() {
        val p = Patient(id = "x", firstName = " Ana ", lastName = "Ruiz")
        assertEquals("Ana Ruiz", p.fullName)
        assertEquals("AR", p.initials)
        assertEquals("?", Patient(id = "x").initials)
    }

    @Test fun avatarTintUsesTheLastThreeUuidBytes() {
        // bytes 13..15 = 0xFF 0xFF 0xFF: sum 765, wraps to 253 as UInt8 -> 253 % 4 == 1
        assertEquals(1, Patient(id = "00000000-0000-7000-8000-ffffffffffff").tintIndex)
        // bytes 13..15 = 0x00 0x00 0x02 -> 2
        assertEquals(2, Patient(id = "00000000-0000-7000-8000-000000000002").tintIndex)
    }

    @Test fun sexKinds() {
        assertEquals(Patient.SexKind.Female, Patient(id = "x", sex = "Mujer").sexKind)
        assertEquals(Patient.SexKind.Male, Patient(id = "x", sex = "male").sexKind)
        assertEquals(Patient.SexKind.Other, Patient(id = "x", sex = "Other").sexKind)
        assertNull(Patient(id = "x", sex = "").sexKind)
    }

    @Test fun emailValidation() {
        assertTrue(PatientDraft(email = "").emailIsValid)
        assertTrue(PatientDraft(email = "a@b.co").emailIsValid)
        assertFalse(PatientDraft(email = "a@b").emailIsValid)
        assertFalse(PatientDraft().canSave)
        assertTrue(PatientDraft(firstName = "A", lastName = "B").canSave)
    }

    // --- Appointments --------------------------------------------------------------------------------------------

    @Test fun notesCarryTheVisitTypeAsPrefix() {
        assertEquals("Revisión — traer analítica", Appointment.composeNotes(VisitType.Review, " traer analítica "))
        assertEquals("Primera visita", Appointment.composeNotes(VisitType.First, "  "))
        val parsed = Appointment.parseNotes("Revisión — traer analítica")
        assertEquals(VisitType.Review, parsed.type)
        assertEquals("traer analítica", parsed.reason)
        assertEquals(VisitType.Results, Appointment.parseNotes("Resultados").type)
        val legacy = Appointment.parseNotes("  llamada previa ")
        assertNull(legacy.type)
        assertEquals("llamada previa", legacy.reason)
        assertNull(Appointment.parseNotes("Revisiones — x").type)
    }

    @Test fun appointmentDecodesAndDerivesTimes() {
        val a = json.decodeFromString<Appointment>(
            """{"id":"1","patient_id":"2","date":"2026-10-30","start_time":"09:30:00","end_time":"10:15:00",
               "status":"no_show","notes":null,"patients":{"first_name":" Ana ","last_name":""}}""",
        )
        assertEquals(AppointmentStatus.NoShow, a.statusKey)
        assertEquals(570, a.startMinutes)
        assertEquals(45, a.durationMinutes)
        assertEquals("09:30", a.timeLabel)
        assertEquals("Ana", a.patientName)
        assertEquals(LocalDate.of(2026, 10, 30), a.day)
    }

    @Test fun datesHelpers() {
        assertEquals(LocalDate.of(2026, 10, 26), Dates.startOfWeek(LocalDate.of(2026, 11, 1))) // Sunday -> previous Monday
        assertEquals(0, Dates.minutesOfDay("garbage"))
        assertEquals("25:00", Dates.hhmm(1500))
        assertNotNull(Dates.parseServerTimestamp("2026-10-12T09:30:00.123456+00:00"))
        assertNull(Dates.parseServerTimestamp("nope"))
        assertEquals(LocalDate.of(2026, 10, 30), Dates.parseIso("2026-10-30T00:00:00"))
    }

    // --- UserProfile ---------------------------------------------------------------------------------------------

    @Test fun userProfileInitialsSkipTitles() {
        assertEquals("AG", UserProfile(id = "1", email = "x@y.z", fullName = "Dra. Ana García", role = "doctor").initials)
        assertEquals("MP", UserProfile(id = "1", email = "maria.perez@clinic.es", role = "staff").initials)
        val u = UserProfile(id = "1", email = "x@y.z", role = "doctor", specialties = listOf("plastic_surgery"))
        assertEquals("plasticsurgery", u.specialtyKey)
        assertEquals(UserProfile.RoleKind.Doctor, u.roleKind)
    }
}
