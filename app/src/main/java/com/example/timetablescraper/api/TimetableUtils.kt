package com.example.timetablescraper.api

import android.util.Log
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/**
 * Utilities for converting API events to UI models and date calculations.
 */
object TimetableUtils {

    private const val TAG = "TimetableUtils"

    private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /**
     * Dublin timezone resolved eagerly with a safe fallback.
     * If the IANA database is somehow incomplete, the system default is used
     * and a warning is logged.  This prevents [ZoneId.of] from throwing
     * [java.time.DateTimeException] on misconfigured runtimes.
     */
    @JvmStatic
    val DUBLIN_ZONE: ZoneId = try {
        ZoneId.of("Europe/Dublin")
    } catch (e: Exception) {
        Log.w(TAG, "Europe/Dublin timezone unavailable, falling back to system default", e)
        ZoneId.systemDefault()
    }

    /**
     * Convert an API event into a UI-friendly TimetableEvent.
     * Crash-proof — returns a fallback event if parsing fails.
     */
    fun toUiEvent(event: ApiEvent, weekStart: String): TimetableEvent {
        return try {
            val startTime = if (event.start.length >= 16) event.start.substring(11, 16)
            else if (event.start.length >= 5) event.start.substring(11.coerceAtMost(event.start.length))
            else "??:??"
            val endTime = if (event.end.length >= 16) event.end.substring(11, 16)
            else if (event.end.length >= 5) event.end.substring(11.coerceAtMost(event.end.length))
            else "??:??"

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
                id = event.id,
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
        } catch (_: Exception) {
            TimetableEvent(
                id = event.id,
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

    fun getCurrentMonday(today: LocalDate = LocalDate.now(DUBLIN_ZONE)): LocalDate {
        return today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }

    fun formatDayDate(monday: LocalDate, dayOffset: Int): String {
        val date = monday.plusDays(dayOffset.toLong())
        return date.format(DateTimeFormatter.ofPattern("MMM d"))
    }

    fun formatWeekRange(monday: LocalDate): String {
        val sunday = monday.plusDays(6)
        val startStr = monday.format(DateTimeFormatter.ofPattern("MMM d"))
        val endStr = sunday.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
        return "$startStr – $endStr"
    }

    fun generateAcademicWeeks(today: LocalDate = LocalDate.now(DUBLIN_ZONE)): List<LocalDate> {
        val academicYearStart = if (today.monthValue >= 9) {
            LocalDate.of(today.year, 9, 1)
        } else {
            LocalDate.of(today.year - 1, 9, 1)
        }
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
     * Deduplicate events.  O(n log n) single sort, no redundant passes.
     *
     * Key: normalised start | title | lecturer
     * Prefers the richest copy (group + lecturer metadata) per distinct entity.
     */
    fun deduplicateEvents(events: List<ApiEvent>): List<ApiEvent> {
        if (events.size <= 1) return events

        // Single sort: richest first → first occurrence wins in distinctBy
        return events
            .sortedWith(compareByDescending<ApiEvent> { it.group.length + it.lecturer.length }
                .thenBy { it.start })
            .distinctBy { e ->
                val s = e.start.trim().removeSuffix("Z").substringBefore(".000")
                "${s}|${e.title.trim()}|${e.lecturer.trim()}"
            }
    }

    /**
     * Current date in the Dublin timezone with a safe fallback.
     * Use this instead of [LocalDate.now] everywhere in the app to
     * guarantee timezone-consistent week boundaries.
     *
     * Crash-proof: returns the system-local date if the Dublin zone
     * cannot be resolved (should never happen on standard Android runtimes).
     */
    fun currentDublinDate(): LocalDate = LocalDate.now(DUBLIN_ZONE)

    /**
     * Classify academic weeks into active (has events) and empty (no events).
     *
     * @param events   All events for the course (e.g. from a full-year API fetch).
     * @param allWeeks The full list of academic Mondays from [generateAcademicWeeks].
     * @return Pair(activeKeys, emptyKeys) where each key is "yyyy-MM-dd".
     */
    fun classifyWeeks(
        events: List<ApiEvent>,
        allWeeks: List<LocalDate> = generateAcademicWeeks()
    ): Pair<Set<String>, Set<String>> {
        val activeKeys = events.mapNotNull { event ->
            try {
                LocalDate.parse(event.start.substring(0, 10))
            } catch (_: Exception) { null }
        }.mapNotNull { date ->
            allWeeks.firstOrNull { monday ->
                !date.isBefore(monday) && !date.isAfter(monday.plusDays(6))
            }
        }.map { it.format(DATE_FORMATTER) }.toSet()

        val allKeys = allWeeks.map { it.format(DATE_FORMATTER) }.toSet()
        return Pair(activeKeys, allKeys - activeKeys)
    }

    fun safeFormat(date: LocalDate, formatter: DateTimeFormatter): String {
        return try { date.format(formatter) } catch (_: Exception) { "?" }
    }
}
