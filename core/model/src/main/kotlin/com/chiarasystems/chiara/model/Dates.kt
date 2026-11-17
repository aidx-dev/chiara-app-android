package com.chiarasystems.chiara.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.Period
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Port of the pure parts of iOS `Cal` / `ServerTimestamp` (Core/Dates.swift).
 * Appointment days and times are wall-clock values with no timezone: they are [LocalDate] + minutes since midnight and
 * must never go through an Instant. Only `occurred_at` is a real timestamp.
 * Display formatting (long day, month label...) depends on the app language and lives in the UI layer.
 */
object Dates {
    /** `yyyy-MM-dd`, parsed from the first 10 characters (the server may append a time). Null when unparsable. */
    fun parseIso(raw: String?): LocalDate? {
        if (raw == null || raw.length < 10) return null
        return runCatching { LocalDate.parse(raw.substring(0, 10)) }.getOrNull()
    }

    fun iso(day: LocalDate): String = day.toString()

    /** Monday-first (ISO) week start. */
    fun startOfWeek(day: LocalDate): LocalDate = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun startOfMonth(day: LocalDate): LocalDate = day.withDayOfMonth(1)

    fun isSunday(day: LocalDate): Boolean = day.dayOfWeek == DayOfWeek.SUNDAY

    /** Full years between [birth] and [today]. */
    fun age(birth: LocalDate, today: LocalDate = LocalDate.now()): Int = Period.between(birth, today).years

    fun minutesNow(zone: ZoneId = ZoneId.systemDefault()): Int =
        ZonedDateTime.now(zone).let { it.hour * 60 + it.minute }

    /** "09:30:00" / "09:30" -> 570; 0 on bad input (like iOS). */
    fun minutesOfDay(time: String?): Int {
        val parts = time?.split(":") ?: return 0
        if (parts.size < 2) return 0
        val h = parts[0].toIntOrNull() ?: return 0
        val m = parts[1].toIntOrNull() ?: return 0
        return h * 60 + m
    }

    /** `%02d:%02d`, no wrap at 24h. */
    fun hhmm(minutes: Int): String = String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60)

    /** Postgres `timestamptz` such as `2026-10-12T09:30:00.123456+00:00`. Null when unparsable (iOS: distantPast). */
    fun parseServerTimestamp(raw: String?): OffsetDateTime? {
        if (raw == null) return null
        return runCatching { OffsetDateTime.parse(raw) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(raw.replace(' ', 'T')) }.getOrNull()
    }
}
