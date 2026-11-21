package com.nuvio.app.features.usenet

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Reads the dates indexers give releases. */
internal object NewznabDates {
    /**
     * [value] in epoch milliseconds: an RFC 1123 date (pubDate, usenetdate), an ISO 8601 instant, or
     * a count of epoch seconds or milliseconds. Null when it is none of them.
     */
    @OptIn(ExperimentalTime::class)
    fun parse(value: String): Long? = rfc1123(value)
        ?: runCatching { Instant.parse(value).toEpochMilliseconds() }.getOrNull()
        ?: value.toLongOrNull()?.takeIf { it > 0 && it < Long.MAX_VALUE / 1000 }?.let { if (it < 100000000000L) it * 1000 else it }

    /** As java.time's RFC_1123_DATE_TIME reads it: an optional day name, GMT or a +HHMM offset. */
    fun rfc1123(value: String): Long? {
        val match = RFC_1123.matchEntire(value.trim()) ?: return null
        val (day, monthName, yearText, hourText, minuteText, secondText, zone) = match.destructured
        val month = MONTHS.indexOf(monthName.lowercase()) + 1
        val year = yearText.toInt()
        val hour = hourText.toInt()
        val minute = minuteText.toInt()
        val second = secondText.ifEmpty { "0" }.toInt()
        if (month == 0 || hour > 23 || minute > 59 || second > 59) return null
        if (day.toInt() !in 1..daysIn(year, month)) return null
        val offsetMinutes = if (zone.equals("GMT", ignoreCase = true)) 0 else {
            val sign = if (zone[0] == '-') -1 else 1
            val hours = zone.substring(1, 3).toInt()
            val minutes = zone.substring(3, 5).toInt()
            if (hours > 18 || minutes > 59) return null
            sign * (hours * 60 + minutes)
        }
        val days = daysFromCivil(year, month, day.toInt())
        return ((days * 86400 + hour * 3600 + minute * 60 + second) - offsetMinutes * 60L) * 1000
    }

    private val RFC_1123 = Regex(
        "(?i)(?:[a-z]{3},\\s*)?(\\d{1,2})\\s+([a-z]{3})\\s+(\\d{4})\\s+(\\d{2}):(\\d{2})(?::(\\d{2}))?\\s+(GMT|[+-]\\d{4})",
    )
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    private fun daysIn(year: Int, month: Int) = when (month) {
        2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /** Days since 1970-01-01 of a proleptic Gregorian date (Howard Hinnant's days_from_civil). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yearOfEra = y - era * 400
        val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146097 + dayOfEra - 719468
    }
}
