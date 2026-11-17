package com.chiarasystems.chiara.data

import com.chiarasystems.chiara.model.Appointment
import com.chiarasystems.chiara.model.Patient
import com.chiarasystems.chiara.model.PatientDraft
import com.chiarasystems.chiara.model.VisitType
import com.chiarasystems.chiara.network.ApiError
import com.chiarasystems.chiara.network.SecureStore
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StoresTest {
    private lateinit var env: TestEnv
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun setUp() { env = TestEnv() }
    @After fun tearDown() { env.close() }

    private fun patientsJson(vararg names: Pair<String, String>) = names.mapIndexed { i, (f, l) ->
        """{"id":"p$i","first_name":"$f","last_name":"$l","document_id":"DNI$i"}"""
    }.joinToString(",", "[", "]")

    // --- AuthStore ------------------------------------------------------------------------------------------------

    @Test fun signInLoadsTheProfileBrandingAndClinicName() = runBlocking {
        env.json("/auth/v1/token", env.tokenBody())
        env.json("/rest/v1/profiles", """[{"clinic_id":"clinic-1","role":"doctor","full_name":"Ana García","specialties":["dermatology"]}]""")
        env.json("/rest/v1/rpc/get_clinic_branding", """{"doctor_name":"Dra. García","doctor_license":"28/1234"}""")
        env.json("/rest/v1/clinics", """[{"name":"Clínica Sol"}]""")
        val auth = AuthStore(env.client, env.store)
        auth.signIn(" doc@clinic.es ", "pw")

        assertEquals(AuthPhase.SignedIn, auth.phase.value)
        val u = auth.user.value!!
        assertEquals("Ana García", u.fullName)
        assertEquals("Clínica Sol", u.clinicName)
        assertEquals(listOf("dermatology"), u.specialties)
        assertEquals("28/1234", auth.branding.value.doctorLicense)
        assertEquals("doc@clinic.es", auth.lastEmail)
        assertTrue(auth.hasStoredSession)
        assertTrue(env.callsTo("/rest/v1/profiles").single().path.contains("user_id=eq.u1"))
    }

    @Test fun signInValidatesBeforeTouchingTheNetwork() = runBlocking {
        val auth = AuthStore(env.client, env.store)
        auth.signIn("", "pw")
        assertEquals(AuthMessage.EnterCredentials, auth.message.value)
        auth.signIn("not-an-email", "pw")
        assertEquals(AuthMessage.InvalidEmail, auth.message.value)
        assertTrue(env.calls.isEmpty())
    }

    @Test fun wrongPasswordShowsInvalidCredentials() = runBlocking {
        env.json("/auth/v1/token", """{"error_description":"Invalid login"}""", 400)
        val auth = AuthStore(env.client, env.store)
        auth.signIn("a@b.co", "bad")
        assertEquals(AuthMessage.Failure(ApiError.InvalidCredentials), auth.message.value)
        assertEquals(AuthPhase.SignedOut, auth.phase.value)
    }

    @Test fun anAccountWithoutClinicIsRejectedAndSignedOut() = runBlocking {
        env.json("/auth/v1/token", env.tokenBody(clinic = null))
        env.json("/rest/v1/profiles", "[]")
        env.json("/auth/v1/logout", "", 204)
        val auth = AuthStore(env.client, env.store)
        auth.signIn("a@b.co", "pw")
        val failure = (auth.message.value as AuthMessage.Failure).error as ApiError.Server
        assertEquals(403, failure.status)
        assertEquals(AuthPhase.SignedOut, auth.phase.value)
        assertFalse(auth.hasStoredSession)
    }

    @Test fun biometricsRestoreTheStoredSessionAndDropARevokedOne() = runBlocking {
        val auth = AuthStore(env.client, env.store)
        auth.signInWithBiometrics { BiometricOutcome.Success }
        assertEquals(AuthMessage.EnableBiometricsFirst, auth.message.value)

        env.store.set("old-refresh", SecureStore.REFRESH_TOKEN)
        auth.signInWithBiometrics { BiometricOutcome.Cancelled }
        assertNull("cancel is silent", auth.message.value)
        auth.signInWithBiometrics { BiometricOutcome.Failed }
        assertEquals(AuthMessage.BiometricFailed, auth.message.value)

        env.json("/auth/v1/token", """{"error_description":"revoked"}""", 400)
        auth.signInWithBiometrics { BiometricOutcome.Success }
        assertEquals(AuthMessage.StoredSessionExpired, auth.message.value)
        assertFalse(auth.hasStoredSession)
    }

    @Test fun losingTheSessionSignsTheUserOut() = runBlocking {
        env.json("/rest/v1/profiles", """[{"clinic_id":"clinic-1"}]""")
        env.json("/rest/v1/rpc/get_clinic_branding", "null")
        env.json("/rest/v1/clinics", "[]")
        env.json("/rest/v1/rpc/x", "{}", 401)
        val auth = AuthStore(env.client, env.store)
        env.json("/auth/v1/token", env.tokenBody())
        auth.signIn("a@b.co", "pw")
        assertEquals(AuthPhase.SignedIn, auth.phase.value)
        // Every refresh is rejected: the session is lost.
        env.json("/auth/v1/token", "{}", 400)
        try { env.client.rpc("x") } catch (e: ApiError.SessionExpired) { /* expected */ }
        assertEquals(AuthPhase.SignedOut, auth.phase.value)
        assertEquals(AuthMessage.SessionLost, auth.message.value)
    }

    // --- PatientsStore ----------------------------------------------------------------------------------------------

    @Test fun loadFetchesEveryPage() = runBlocking {
        env.signedIn()
        env.route("/rest/v1/rpc/list_patients") { body ->
            val page = Regex("\"p_page\":(\\d+)").find(body)!!.groupValues[1].toInt()
            val items = (1..(if (page == 1) 100 else 50)).joinToString(",", "[", "]") { """{"id":"p$page-$it"}""" }
            MockResponse().setBody("""{"items":$items,"total":150}""")
        }
        val store = PatientsStore(env.client, scope)
        store.load()
        assertEquals(150, store.patients.value.size)
        assertEquals(2, env.callsTo("/rest/v1/rpc/list_patients").size)
        assertNull(store.loadError.value)
    }

    @Test fun searchIsDebouncedAndFallsBackToTheLoadedListOffline() = runBlocking {
        env.signedIn()
        env.json("/rest/v1/rpc/list_patients", """{"items":${patientsJson("María" to "Núñez", "Pablo" to "Soler")},"total":2}""")
        val store = PatientsStore(env.client, scope)
        store.load()

        store.search("m")
        store.search("maria") // cancels the first one: only one search request is made
        withTimeout(5000) { store.isSearching.first { !it } }
        assertEquals(1, env.callsTo("/rest/v1/rpc/list_patients").count { it.body.contains("\"p_search\":\"maria\"") })
        assertEquals(0, env.callsTo("/rest/v1/rpc/list_patients").count { it.body.contains("\"p_search\":\"m\"") })

        env.offline = true
        store.search("nunez")
        withTimeout(5000) { store.isSearching.first { !it } }
        assertEquals(listOf("Núñez"), store.searchResults.value.map { it.lastName })
    }

    @Test fun createGeneratesAV7IdTrimsValuesAndRejectsADuplicateDocument() = runBlocking {
        env.signedIn()
        env.json("/rest/v1/rpc/list_patients", """{"items":[{"id":"x","document_id":"12345678Z"}],"total":1}""")
        env.route("/rest/v1/rpc/create_patient") { MockResponse().setBody("""{"id":"new","first_name":"Ana","last_name":"Ruiz"}""") }
        val store = PatientsStore(env.client, scope)
        store.load()

        try {
            store.create(PatientDraft(firstName = "A", lastName = "B", documentId = " 12345678z "))
            throw AssertionError("expected duplicate")
        } catch (e: DuplicateDocumentException) { /* expected */ }
        assertTrue(env.callsTo("/rest/v1/rpc/create_patient").isEmpty())

        val created = store.create(
            PatientDraft(firstName = " Ana ", lastName = "Ruiz", documentId = "99999999r", phone = "  ", sex = PatientDraft.Sex.Other),
        )
        assertEquals("new", created.id)
        val body = env.callsTo("/rest/v1/rpc/create_patient").single().body
        assertTrue(body.contains("\"p_first_name\":\"Ana\""))
        assertTrue(body.contains("\"p_document_id\":\"99999999R\""))
        assertTrue(body.contains("\"p_phone\":null"))
        assertTrue(body.contains("\"p_sex\":\"Other\""))
        assertTrue(Regex("\"p_id\":\"[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\"").containsMatchIn(body))
        assertEquals(2, store.patients.value.size)
    }

    // --- AppointmentsStore ------------------------------------------------------------------------------------------

    private val day = LocalDate.of(2026, 11, 2)
    private val ana = Patient(id = "pa", firstName = "Ana", lastName = "Ruiz")

    private fun apptJson(id: String, start: String, end: String, status: String = "Scheduled", notes: String = "Revisión") =
        """{"id":"$id","patient_id":"pa","date":"2026-11-02","start_time":"$start:00","end_time":"$end:00","status":"$status","notes":"$notes"}"""

    @Test fun loadReplacesExactlyTheRequestedDays() = runBlocking {
        env.signedIn()
        env.json("/rest/v1/rpc/list_appointments_in_range", "[${apptJson("a1", "10:00", "10:30")}]")
        val store = AppointmentsStore(env.client)
        store.load(day.minusDays(1), day.plusDays(1))
        assertEquals(listOf("a1"), store.appointments(day).map { it.id })
        assertTrue(store.byDay.value.containsKey("2026-11-01") && store.appointments(day.minusDays(1)).isEmpty())
        assertTrue(store.hasAppointments(day))
    }

    @Test fun createAttachesThePatientNameAndSendsTheContract() = runBlocking {
        env.signedIn()
        env.json("/rest/v1/rpc/create_appointment_with_patient", apptJson("n1", "09:00", "09:30"))
        val store = AppointmentsStore(env.client)
        val created = store.create(ana, day, 9 * 60, 30, VisitType.Review, " traer analítica ")
        assertEquals("Ana Ruiz", created.patientName)
        val body = env.callsTo("/rest/v1/rpc/create_appointment_with_patient").single().body
        assertTrue(body.contains("\"p_start_time\":\"09:00\"") && body.contains("\"p_end_time\":\"09:30\""))
        assertTrue(body.contains("\"p_notes\":\"Revisión — traer analítica\""))
        assertTrue(body.contains("\"p_patient\":null"))
        assertEquals(1, store.revision.value)
    }

    @Test fun updateMovesTheRowAndUndoPutsItBack() = runBlocking {
        env.signedIn()
        env.json("/rest/v1/rpc/list_appointments_in_range", "[${apptJson("a1", "10:00", "10:30")}]")
        var next = apptJson("a1", "11:00", "11:30", "Confirmed")
        env.route("/rest/v1/rpc/update_appointment") { MockResponse().setBody(next) }
        val store = AppointmentsStore(env.client)
        store.load(day, day)
        val before = store.appointments(day).single()

        val updated = store.update(before, day, 11 * 60, 11 * 60 + 30, "Confirmed", before.notes)
        assertEquals(11 * 60, store.appointments(day).single().startMinutes)
        assertEquals(updated.id, store.appointments(day).single().id)

        next = apptJson("a1", "10:00", "10:30")
        store.undo(AppointmentChange.Updated(before))
        val last = env.callsTo("/rest/v1/rpc/update_appointment").last().body
        assertTrue(last.contains("\"p_start_time\":\"10:00\"") && last.contains("\"p_status\":\"Scheduled\""))
        assertEquals(10 * 60, store.appointments(day).single().startMinutes)
    }

    @Test fun archiveAndUndoOfACreate() = runBlocking {
        env.signedIn()
        env.json("/rest/v1/rpc/create_appointment_with_patient", apptJson("n1", "09:00", "09:30"))
        env.json("/rest/v1/rpc/archive_appointment", "null")
        val store = AppointmentsStore(env.client)
        val created = store.create(ana, day, 540, 30, VisitType.First, "")
        store.undo(AppointmentChange.Created(created))
        assertTrue(store.appointments(day).isEmpty())
        assertTrue(env.callsTo("/rest/v1/rpc/archive_appointment").single().body.contains("\"p_id\":\"n1\""))
    }

    @Test fun busyIntervalsSkipCancelledAndTheExcludedOne() = runBlocking {
        env.signedIn()
        env.json(
            "/rest/v1/rpc/list_appointments_in_range",
            "[${apptJson("a1", "09:00", "09:30")},${apptJson("a2", "10:00", "11:00", "Canceled")},${apptJson("a3", "12:00", "12:30", "NoShow")}]",
        )
        val store = AppointmentsStore(env.client)
        store.load(day, day)
        assertEquals(listOf(540 to 570, 720 to 750), store.busyIntervals(day).map { it.start to it.end })
        assertEquals(listOf(720 to 750), store.busyIntervals(day, excluding = "a1").map { it.start to it.end })
    }

    @Test fun upcomingExcludesCancelledCompletedAndPast() = runBlocking {
        env.signedIn()
        env.json(
            "/rest/v1/rpc/list_appointments_in_range",
            "[${apptJson("a1", "09:00", "09:30")},${apptJson("a2", "10:00", "10:30", "Completed")},${apptJson("a3", "11:00", "11:30", "NoShow")},${apptJson("a4", "12:00", "12:30", "Canceled")}]",
        )
        val store = AppointmentsStore(env.client)
        store.load(day, day)
        // On the day itself, with the clock at 09:15: a1 is still running, a2/a4 are excluded, a3 (NoShow) still counts.
        assertEquals(listOf("a1", "a3"), store.upcoming("pa", today = day, nowMinutes = 9 * 60 + 15).map { it.id })
        assertEquals(listOf("a3"), store.upcoming("pa", today = day, nowMinutes = 9 * 60 + 40).map { it.id })
        assertTrue(store.upcoming("pa", today = day.plusDays(1), nowMinutes = 0).isEmpty())
    }

    @Test fun historyFailuresGiveAnEmptyList() = runBlocking {
        env.signedIn()
        env.json("/rest/v1/rpc/patient_appointment_history", "boom", 500)
        assertTrue(AppointmentsStore(env.client).history("pa").isEmpty())
    }

    // --- ReportsStore -----------------------------------------------------------------------------------------------

    @Test fun loadAllBuildsReportsFromEveryPatientNewestFirstAndRunsOnce() = runBlocking {
        env.signedIn()
        env.route("/rest/v1/rpc/list_consultations_with_latest_report") { body ->
            val patient = Regex("\"p_patient_id\":\"(\\w+)\"").find(body)!!.groupValues[1]
            val date = if (patient == "p1") "2026-10-01" else "2026-10-20"
            MockResponse().setBody(
                """[{"id":"c-$patient","patient_id":"$patient","occurred_at":"${date}T10:00:00+00:00","status":"Finalized","content":{"anamnesis":"<p>Hola</p>"}},
                    {"id":"empty-$patient","patient_id":"$patient","occurred_at":"${date}T11:00:00+00:00","content":{}}]""",
            )
        }
        val store = ReportsStore(env.client)
        val patients = (1..8).map { Patient(id = "p$it") }
        store.loadAll(patients)
        assertEquals(8, store.reports.value.size)
        assertEquals("c-p1", store.reports.value.last().id) // oldest last
        assertEquals(8, env.callsTo("/rest/v1/rpc/list_consultations_with_latest_report").size)
        store.loadAll(patients)
        assertEquals("only once unless forced", 8, env.callsTo("/rest/v1/rpc/list_consultations_with_latest_report").size)
        store.loadAll(patients, force = true)
        assertEquals(16, env.callsTo("/rest/v1/rpc/list_consultations_with_latest_report").size)
        assertEquals(1, store.reports("p3").size)
    }

    @Test fun aFailedFetchKeepsWhatWasLoadedBefore() = runBlocking {
        env.signedIn()
        env.json("/rest/v1/rpc/list_consultations_with_latest_report",
            """[{"id":"c1","patient_id":"p1","occurred_at":"2026-10-01T10:00:00+00:00","content":{"anamnesis":"x"}}]""")
        val store = ReportsStore(env.client)
        assertEquals(1, store.fetch("p1").size)
        env.json("/rest/v1/rpc/list_consultations_with_latest_report", "boom", 500)
        assertEquals(1, store.fetch("p1").size)
    }
}
