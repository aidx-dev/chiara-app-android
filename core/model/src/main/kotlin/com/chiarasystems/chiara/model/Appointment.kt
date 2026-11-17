package com.chiarasystems.chiara.model

import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `rawValue` is what is stored in the appointment notes (always Spanish). */
enum class VisitType(val raw: String) {
    First("Primera visita"), Review("Revisión"), FollowUp("Seguimiento"), Results("Resultados");
}

/** A row of `list_appointments_in_range`; [patients] is the embedded name (absent on create/update responses). */
@Serializable
data class Appointment(
    val id: String,
    @SerialName("patient_id") val patientId: String,
    val date: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
    val status: String = "Scheduled",
    val notes: String? = null,
    val patients: PatientName? = null,
) {
    @Serializable
    data class PatientName(
        @SerialName("first_name") val firstName: String? = null,
        @SerialName("last_name") val lastName: String? = null,
    )

    val statusKey: AppointmentStatus get() = AppointmentStatus.normalize(status)
    val day: LocalDate? get() = Dates.parseIso(date)
    val startMinutes: Int get() = Dates.minutesOfDay(startTime)
    val endMinutes: Int get() = Dates.minutesOfDay(endTime)
    val durationMinutes: Int get() = maxOf(endMinutes - startMinutes, 0)
    val timeLabel: String get() = Dates.hhmm(startMinutes)

    val patientName: String
        get() = listOf(patients?.firstName, patients?.lastName)
            .mapNotNull { it?.trimmed?.nilIfEmpty }.joinToString(" ")

    /** The visit type travels as the prefix of `notes`: "Revisión — traer analítica". */
    val visit: ParsedNotes get() = parseNotes(notes)

    companion object {
        private const val SEPARATOR = " — " // U+2014 with a space on both sides

        data class ParsedNotes(val type: VisitType?, val reason: String)

        fun composeNotes(type: VisitType, reason: String): String {
            val r = reason.trimmed
            return if (r.isEmpty()) type.raw else "${type.raw}$SEPARATOR$r"
        }

        fun parseNotes(notes: String?): ParsedNotes {
            val n = (notes ?: "").trimmed
            for (t in VisitType.entries) {
                if (n == t.raw || n.startsWith(t.raw + SEPARATOR)) {
                    return ParsedNotes(t, n.drop(t.raw.length).trim { it == ' ' || it == '—' })
                }
            }
            return ParsedNotes(null, n)
        }
    }
}

/** A row of `patient_appointment_history` (adds `effective_status`, where the server-derived NoShow comes from). */
@Serializable
data class PatientAppointment(
    val id: String,
    val date: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
    val status: String = "Scheduled",
    @SerialName("effective_status") val effectiveStatus: String = status,
) {
    val statusKey: AppointmentStatus get() = AppointmentStatus.normalize(effectiveStatus)
    val day: LocalDate? get() = Dates.parseIso(date)
    val startMinutes: Int get() = Dates.minutesOfDay(startTime)
    val durationMinutes: Int get() = maxOf(Dates.minutesOfDay(endTime) - startMinutes, 0)
}
