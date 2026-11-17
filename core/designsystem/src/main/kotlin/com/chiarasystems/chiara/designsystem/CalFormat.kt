package com.chiarasystems.chiara.designsystem

import android.icu.text.DateFormat
import android.icu.util.TimeZone
import com.chiarasystems.chiara.model.capitalizedFirst
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * Port of the display half of iOS `Cal`: Spanish keeps the design's exact layouts, other languages use their locale's own
 * arrangement of the same fields (ICU skeletons). Wire parsing and arithmetic live in `Dates` (core:model).
 */
class CalFormat(val code: String) {
    private val locale = Locale.forLanguageTag(code)
    private val zone = ZoneId.systemDefault()
    private val cache = HashMap<String, DateFormat>()

    private fun format(day: LocalDate, spanishPattern: String, skeleton: String): String {
        val key = if (code == "es") "p:$spanishPattern" else "s:$skeleton"
        val f = cache.getOrPut(key) {
            val df = if (code == "es") android.icu.text.SimpleDateFormat(spanishPattern, locale)
            else DateFormat.getInstanceForSkeleton(skeleton, locale)
            df.apply { timeZone = TimeZone.getTimeZone(zone.id) }
        }
        return f.format(Date.from(day.atStartOfDay(zone).toInstant()))
    }

    fun longDay(d: LocalDate) = format(d, "EEEE, d 'de' MMMM", "EEEEdMMMM").capitalizedFirst
    fun longDayYear(d: LocalDate) = format(d, "EEEE, d 'de' MMMM 'de' yyyy", "EEEEdMMMMyyyy").capitalizedFirst
    fun monthYear(d: LocalDate) = format(d, "LLLL yyyy", "LLLLyyyy").capitalizedFirst
    fun shortDate(d: LocalDate) = format(d, "d MMM", "dMMM").replace(".", "")
    /** "Vie 30 oct" */
    fun dayChip(d: LocalDate) = format(d, "EEE d MMM", "EEEdMMM").replace(".", "").capitalizedFirst
    fun weekdayShort(d: LocalDate) = format(d, "EEE", "EEE").replace(".", "").capitalizedFirst
    fun monthShort(d: LocalDate) = format(d, "MMM", "MMM").replace(".", "")
    fun birthDate(d: LocalDate) = format(d, "d 'de' MMMM 'de' yyyy", "dMMMMyyyy")

    fun shortDate(t: OffsetDateTime?): String = t?.atZoneSameInstant(zone)?.toLocalDate()?.let(::shortDate).orEmpty()
}
