package com.example.timetablescraper.api

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/**
 * Utilities for converting API events to UI models and date calculations.
 * Extracted from TimetableScreen so they can be unit-tested independently.
 */
object TimetableUtils {

    private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /**
     * Convert an API event into a UI-friendly TimetableEvent.
     * Fully crash-proof — returns a fallback event if parsing fails.
     */
    fun toUiEvent(event: ApiEvent, weekStart: String): TimetableEvent {
        return try {
            val startTime = if (event.start.length >= 16) event.start.substring(11, 16)
            else if (event.start.length >= 5) event.start.substring(11.coerceAtMost(event.start.length))
            else "??:??"
            val endTime = if (event.end.length >= 16) event.end.substring(11, 16)
            else if (event.end.length >= 5) event.end.substring(11.coerceAtMost(event.end.length))
            else "??:??"

            // Extract day name from the ISO start date
            val dayName = if (event.start.length >= 10) {
                try {
                    val date = LocalDate.parse(event.start.substring(0, 10))
                    date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
                } catch (_: Exception) { "" }
            } else ""

            val dayIndex = when (dayName) {
                "Mon" -> 0; "Tue" -> 1; "Wed" -> 2; "Thu" -> 3; "Fri" -> 4
                else -> -1
            }

            TimetableEvent(
                moduleCode = event.module_code,
                title = event.title.ifBlank { "Untitled" },
                type = event.type,
                lecturer = event.lecturer.ifBlank { "Staff" },
                room = event.room.ifBlank { "TBA" },
                start = event.start,
                end = event.end,
                day = dayName,
                dayIndex = dayIndex,
                timeRange = "$startTime - $endTime",
                weekStart = weekStart,
                group = event.group
            )
        } catch (e: Exception) {
            // Should never reach here, but guard against any unforeseen parsing bug
            TimetableEvent(
                moduleCode = event.module_code.ifBlank { "?" },
                title = event.title.ifBlank { "Unknown event" },
                type = "",
                lecturer = "Staff",
                room = "TBA",
                start = "",
                end = "",
                day = "",
                dayIndex = -1,
                timeRange = "??:?? - ??:??",
                weekStart = weekStart,
                group = event.group
            )
        }
    }

    /** Get the Monday of the current week (or the closest past Monday). */
    fun getCurrentMonday(today: LocalDate = LocalDate.now()): LocalDate {
        return today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }

    /** Format a day's date for display: e.g. "Oct 6" */
    fun formatDayDate(monday: LocalDate, dayOffset: Int): String {
        val date = monday.plusDays(dayOffset.toLong())
        return date.format(DateTimeFormatter.ofPattern("MMM d"))
    }

    /** Format the week range for display: e.g. "Oct 6 – Oct 12, 2025" */
    fun formatWeekRange(monday: LocalDate): String {
        val sunday = monday.plusDays(6)
        val startStr = monday.format(DateTimeFormatter.ofPattern("MMM d"))
        val endStr = sunday.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
        return "$startStr – $endStr"
    }

    /**
     * Generate all Monday dates for the current academic year (Sep – Apr).
     * This drives the horizontal week picker.
     */
    fun generateAcademicWeeks(today: LocalDate = LocalDate.now()): List<LocalDate> {
        // Academic year starts in September of the current or previous calendar year
        val academicYearStart = if (today.monthValue >= 9) {
            LocalDate.of(today.year, 9, 1)
        } else {
            LocalDate.of(today.year - 1, 9, 1)
        }
        // End in April of the next calendar year
        val academicYearEnd = if (today.monthValue >= 9) {
            LocalDate.of(today.year + 1, 4, 30)
        } else {
            LocalDate.of(today.year, 4, 30)
        }

        val weeks = mutableListOf<LocalDate>()
        var monday = academicYearStart.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        while (!monday.isAfter(academicYearEnd)) {
            weeks.add(monday)
            monday = monday.plusWeeks(1)
        }
        return weeks
    }

    /**
     * Deduplicate a list of ApiEvents.  The same physical class can appear
     * multiple times in the API response (once per CategoryEvents view) with
     * varying metadata — different room strings, missing module_code, etc.
     *
     * Key:  normalised start time  |  title  |  lecturer
     * These three fields are the most stable across all API views.
     *
     * Prefers the copy with the richest group + lecturer metadata,
     * then sorts chronologically.
     */
    fun deduplicateEvents(events: List<ApiEvent>): List<ApiEvent> {
        return events
            .sortedByDescending { it.group.length + it.lecturer.length }
            .distinctBy {
                val s = it.start.trim().removeSuffix("Z")
                    .substringBeforeLast(".000")
                "${s}|${it.title.trim()}|${it.lecturer.trim()}"
            }
            .sortedBy { it.start }
    }

    /** Safe date formatting — returns "?" on any failure. */
    fun safeFormat(date: LocalDate, formatter: DateTimeFormatter): String {
        return try { date.format(formatter) } catch (_: Exception) { "?" }
    }
}
