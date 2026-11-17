package com.chiarasystems.chiara.data

import com.chiarasystems.chiara.domain.AgendaRules
import com.chiarasystems.chiara.domain.UuidV7
import com.chiarasystems.chiara.model.Appointment
import com.chiarasystems.chiara.model.AppointmentStatus
import com.chiarasystems.chiara.model.Dates
import com.chiarasystems.chiara.model.Patient
import com.chiarasystems.chiara.model.PatientAppointment
import com.chiarasystems.chiara.model.VisitType
import com.chiarasystems.chiara.network.CachePolicy
import com.chiarasystems.chiara.network.SupabaseClient
import com.chiarasystems.chiara.network.rpcParams
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What a write changed, with enough to put it back. */
sealed interface AppointmentChange {
    data class Created(val appointment: Appointment) : AppointmentChange
    data class Updated(val before: Appointment) : AppointmentChange
}

/** Port of iOS `AppointmentsStore`. Appointments are cached by `yyyy-MM-dd`. */
class AppointmentsStore(private val client: SupabaseClient) {
    private val _byDay = MutableStateFlow<Map<String, List<Appointment>>>(emptyMap())
    val byDay: StateFlow<Map<String, List<Appointment>>> = _byDay.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadError = MutableStateFlow<Throwable?>(null)
    val loadError: StateFlow<Throwable?> = _loadError.asStateFlow()

    private val _revision = MutableStateFlow(0)

    /** Bumped on every successful write so open screens (patient file) can refresh. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun reset() {
        _byDay.value = emptyMap()
        _loadError.value = null
    }

    fun appointments(on: LocalDate): List<Appointment> =
        (_byDay.value[Dates.iso(on)] ?: emptyList()).sortedBy { it.startMinutes }

    fun hasAppointments(on: LocalDate): Boolean = !_byDay.value[Dates.iso(on)].isNullOrEmpty()

    /** Loads `[from, to]` and replaces the cache for exactly those days. */
    suspend fun load(from: LocalDate, to: LocalDate) {
        _isLoading.value = true
        try {
            val text = client.rpc(
                "list_appointments_in_range",
                rpcParams("p_start_date" to Dates.iso(from), "p_end_date" to Dates.iso(to)),
                CachePolicy.FallbackToDisk,
            )
            val rows = client.decode<List<Appointment>>(text)
            _byDay.update { current ->
                val next = current.toMutableMap()
                var d = from
                while (!d.isAfter(to)) { next[Dates.iso(d)] = emptyList(); d = d.plusDays(1) }
                for (row in rows) next[row.date.take(10)] = (next[row.date.take(10)] ?: emptyList()) + row
                next
            }
            _loadError.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _loadError.value = e
        } finally {
            _isLoading.value = false
        }
    }

    /** Slots taken on [day], ignoring cancelled appointments and, when moving one, the appointment itself. */
    fun busyIntervals(day: LocalDate, excluding: String? = null): List<AgendaRules.Busy> =
        appointments(day).filter { it.statusKey.occupiesSlot && it.id != excluding }
            .map { AgendaRules.Busy(it.startMinutes, it.endMinutes) }

    /** Cached appointments of [patientId] still to come (not cancelled or completed), earliest first. */
    fun upcoming(patientId: String, today: LocalDate = LocalDate.now(), nowMinutes: Int = Dates.minutesNow()): List<Appointment> =
        _byDay.value.values.flatten()
            .filter { a ->
                val day = a.day
                a.patientId == patientId && a.statusKey != AppointmentStatus.Canceled &&
                    a.statusKey != AppointmentStatus.Completed && day != null &&
                    (day.isAfter(today) || (day == today && a.endMinutes > nowMinutes))
            }
            .sortedWith(compareBy({ it.date }, { it.startMinutes }))

    suspend fun create(patient: Patient, day: LocalDate, start: Int, duration: Int, type: VisitType, reason: String): Appointment {
        val text = client.rpc(
            "create_appointment_with_patient",
            rpcParams(
                "p_appointment_id" to UuidV7.generate().toString(),
                "p_patient_id" to patient.id,
                "p_date" to Dates.iso(day),
                "p_start_time" to Dates.hhmm(start),
                "p_end_time" to Dates.hhmm(start + duration),
                "p_notes" to Appointment.composeNotes(type, reason),
                "p_patient" to null,
            ),
        )
        // The response has no embedded patient: attach the name.
        val created = client.decode<Appointment>(text).copy(
            patients = Appointment.PatientName(patient.firstName, patient.lastName),
        )
        add(created, Dates.iso(day))
        return created
    }

    /**
     * Moves or changes an appointment. The server overwrites every column, so [notes] must carry the current value when
     * it is not being edited.
     */
    suspend fun update(a: Appointment, day: LocalDate, start: Int, end: Int, status: String, notes: String?): Appointment {
        val text = client.rpc(
            "update_appointment",
            rpcParams(
                "p_id" to a.id,
                "p_patient_id" to a.patientId,
                "p_date" to Dates.iso(day),
                "p_start_time" to Dates.hhmm(start),
                "p_end_time" to Dates.hhmm(end),
                "p_status" to status,
                "p_notes" to notes,
            ),
        )
        val updated = client.decode<Appointment>(text).copy(patients = a.patients)
        drop(a.id)
        add(updated, Dates.iso(day))
        return updated
    }

    /** Archives (soft-deletes) an appointment; the agenda stops listing it. */
    suspend fun archive(a: Appointment) {
        client.rpc("archive_appointment", rpcParams("p_id" to a.id))
        drop(a.id)
        _revision.update { it + 1 }
    }

    /** Reverts a change made through [create] or [update]. */
    suspend fun undo(change: AppointmentChange) {
        when (change) {
            is AppointmentChange.Created -> archive(change.appointment)
            is AppointmentChange.Updated -> {
                val b = change.before
                update(b, b.day ?: LocalDate.now(), b.startMinutes, b.endMinutes, b.status, b.notes)
            }
        }
    }

    /** Appointment history of one patient (newest first), with the server-derived `NoShow`. Failures give an empty list. */
    suspend fun history(patientId: String): List<PatientAppointment> = try {
        client.decode<List<PatientAppointment>>(
            client.rpc("patient_appointment_history", rpcParams("p_patient_id" to patientId), CachePolicy.FallbackToDisk),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    private fun add(a: Appointment, key: String) {
        _byDay.update { it + (key to ((it[key] ?: emptyList()) + a)) }
        _revision.update { it + 1 }
    }

    private fun drop(id: String) {
        _byDay.update { map -> map.mapValues { (_, list) -> list.filterNot { it.id == id } } }
    }
}
