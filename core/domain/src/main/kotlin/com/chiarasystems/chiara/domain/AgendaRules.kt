package com.chiarasystems.chiara.domain

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs

/**
 * Port of iOS `AgendaRules` (Core/AgendaRules.swift). Times are minutes since midnight, days are [LocalDate]
 * (appointments are wall-clock: never convert through Instant).
 */
object AgendaRules {
    const val FIRST_SLOT = 510   // 08:30
    const val LAST_SLOT = 1110   // 18:30
    const val SLOT_STEP = 30
    const val LUNCH_START = 840  // 14:00
    const val LUNCH_END = 930    // 15:30 (exclusive)
    val DURATIONS = listOf(15, 30, 45, 60)

    /** 08:30..18:30 every 30 min, minus starts in [14:00, 15:30). */
    val slots: List<Int> =
        (FIRST_SLOT..LAST_SLOT step SLOT_STEP).filterNot { it >= LUNCH_START && it < LUNCH_END }

    enum class Violation { Past, Sunday, OutsideHours, LunchBreak, InvalidDuration, Overlap }

    data class Busy(val start: Int, val end: Int)

    fun isBookable(day: LocalDate, today: LocalDate): Boolean =
        !day.isBefore(today) && day.dayOfWeek != DayOfWeek.SUNDAY

    /** True if [minute] is already gone (today) or if the interval [minute, minute+duration) overlaps a busy one. */
    fun isTaken(day: LocalDate, minute: Int, duration: Int, busy: List<Busy>, today: LocalDate, nowMinutes: Int): Boolean {
        if (day == today && minute < nowMinutes) return true
        return busy.any { minute < it.end && minute + duration > it.start }
    }

    /** First rule that fails, in the same order as iOS; only the START is checked against 18:30 and lunch. */
    fun violation(
        day: LocalDate, start: Int, duration: Int, busy: List<Busy>, today: LocalDate, nowMinutes: Int,
        enforceDuration: Boolean = true, enforceGrid: Boolean = true,
    ): Violation? = when {
        day.isBefore(today) -> Violation.Past
        day.dayOfWeek == DayOfWeek.SUNDAY -> Violation.Sunday
        enforceDuration && duration !in DURATIONS -> Violation.InvalidDuration
        start < FIRST_SLOT || start > LAST_SLOT || (enforceGrid && (start - FIRST_SLOT) % SLOT_STEP != 0) -> Violation.OutsideHours
        start >= LUNCH_START && start < LUNCH_END -> Violation.LunchBreak
        day == today && start < nowMinutes -> Violation.Past
        isTaken(day, start, duration, busy, today, nowMinutes) -> Violation.Overlap
        else -> null
    }

    /** Free slots closest to [near], limited and returned chronologically (stable tie-break: earlier slot first). */
    fun freeStarts(
        day: LocalDate, duration: Int, busy: List<Busy>, today: LocalDate, nowMinutes: Int,
        near: Int = FIRST_SLOT, limit: Int = 4,
    ): List<Int> = slots
        .filter { violation(day, it, duration, busy, today, nowMinutes) == null }
        .sortedWith(compareBy<Int> { abs(it - near) }.thenBy { it })
        .take(limit)
        .sorted()
}
