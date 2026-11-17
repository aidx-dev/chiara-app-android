package com.chiarasystems.chiara.domain

import com.chiarasystems.chiara.model.Appointment
import com.chiarasystems.chiara.model.AppointmentStatus
import com.chiarasystems.chiara.model.Dates
import com.chiarasystems.chiara.model.Report
import com.chiarasystems.chiara.model.VisitType
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Port of iOS `DayBriefing`: what a day of the agenda holds at a glance. Computed from the data, without a language
 * model, so it is instant and cannot say anything the agenda does not.
 */
data class DayBriefing(
    /** Appointments that still count (not cancelled). */
    val total: Int,
    val firstVisits: Int,
    /** Scheduled but not confirmed yet. */
    val unconfirmed: Int,
    /** Free 30-minute starts still ahead (none for a past day or a Sunday). */
    val freeSlots: List<Int>,
    val lines: List<Line>,
) {
    data class Line(
        val id: String,
        val start: Int,
        val patientName: String,
        val isFirstVisit: Boolean,
        val lastReport: LastReport?,
    )

    /** [title] null means "no first sentence": the UI shows its localised "Informe de consulta". */
    data class LastReport(val title: String?, val date: OffsetDateTime?)

    companion object {
        /** [latestReports]: each patient's newest report (by patient id), when already known. */
        fun make(
            day: LocalDate,
            appointments: List<Appointment>,
            latestReports: Map<String, Report>,
            today: LocalDate = LocalDate.now(),
            now: Int = Dates.minutesNow(),
        ): DayBriefing {
            val active = appointments.filter { it.statusKey != AppointmentStatus.Canceled }.sortedBy { it.startMinutes }
            val busy = active.map { AgendaRules.Busy(it.startMinutes, it.endMinutes) }
            val free = AgendaRules.slots.filter { AgendaRules.violation(day, it, 30, busy, today, now) == null }
            return DayBriefing(
                total = active.size,
                firstVisits = active.count { it.visit.type == VisitType.First },
                unconfirmed = active.count { it.statusKey == AppointmentStatus.Scheduled },
                freeSlots = free,
                lines = active.map { a ->
                    Line(
                        id = a.id,
                        start = a.startMinutes,
                        patientName = a.patientName,
                        isFirstVisit = a.visit.type == VisitType.First,
                        lastReport = latestReports[a.patientId]?.let { LastReport(it.title, it.date) },
                    )
                },
            )
        }
    }
}
