package com.chiarasystems.chiara.model

/** Port of iOS `AppointmentStatus` (Models/Appointment.swift). Server values are PascalCase. */
enum class AppointmentStatus(val serverValue: String) {
    Scheduled("Scheduled"), Confirmed("Confirmed"), Completed("Completed"), Canceled("Canceled"), NoShow("NoShow");

    /** Cancelled appointments free their slot; every other status keeps it occupied. */
    val occupiesSlot: Boolean get() = this != Canceled

    companion object {
        /** Lowercase, drop `_`, whitespace and `-`; unknown / null / empty falls back to [Scheduled]. */
        fun normalize(raw: String?): AppointmentStatus {
            val key = raw.orEmpty().lowercase().filterNot { it == '_' || it == '-' || it.isWhitespace() }
            return when (key) {
                "confirmed" -> Confirmed
                "completed" -> Completed
                "canceled", "cancelled" -> Canceled
                "noshow" -> NoShow
                else -> Scheduled
            }
        }
    }
}
