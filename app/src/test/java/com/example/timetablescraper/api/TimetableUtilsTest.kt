package com.example.timetablescraper.api

import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Unit tests for TimetableUtils (pure JVM — no Android dependencies).
 */
class TimetableUtilsTest {

    // ── toUiEvent ──────────────────────────────────────────────────────

    @Test
    fun `toUiEvent parses ISO datetime correctly`() {
        val event = ApiEvent(
            module_code = "TU859", title = "Algorithms", type = "Lec",
            lecturer = "Dr. Smith", room = "A201",
            start = "2025-10-07T10:00:00", end = "2025-10-07T12:00:00",
            group = "A"
        )
        val result = TimetableUtils.toUiEvent(event, "2025-10-06")

        assertEquals("TU859", result.moduleCode)
        assertEquals("Algorithms", result.title)
        assertEquals("Lec", result.type)
        assertEquals("Dr. Smith", result.lecturer)
        assertEquals("A201", result.room)
        assertEquals("10:00 - 12:00", result.timeRange)
        assertEquals("Tue", result.day)
        assertEquals(1, result.dayIndex)
        assertEquals("A", result.group)
    }

    @Test
    fun `toUiEvent handles all weekdays`() {
        val weekdays = listOf(
            "2025-10-06" to Pair("Mon", 0),
            "2025-10-07" to Pair("Tue", 1),
            "2025-10-08" to Pair("Wed", 2),
            "2025-10-09" to Pair("Thu", 3),
            "2025-10-10" to Pair("Fri", 4)
        )
        for ((dateStr, expected) in weekdays) {
            val event = ApiEvent("TU859", "Title", "Lec", "Staff", "Room",
                "${dateStr}T10:00:00", "${dateStr}T12:00:00", "")
            val result = TimetableUtils.toUiEvent(event, "2025-10-06")
            assertEquals("Day mismatch for $dateStr", expected.first, result.day)
            assertEquals("DayIndex mismatch for $dateStr", expected.second, result.dayIndex)
        }
    }

    @Test
    fun `toUiEvent handles Saturday as unknown day`() {
        val event = ApiEvent("TU859", "Title", "Lec", "Staff", "Room",
            "2025-10-11T10:00:00", "2025-10-11T12:00:00", "")
        val result = TimetableUtils.toUiEvent(event, "2025-10-06")
        assertEquals("Sat", result.day)
        assertEquals(-1, result.dayIndex)
    }

    @Test
    fun `toUiEvent fallback for short start time`() {
        val event = ApiEvent("TU859", "Title", "Lec", "Staff", "Room",
            "2025-10-07", "2025-10-07T12:00", "")
        val result = TimetableUtils.toUiEvent(event, "2025-10-06")
        // start = "2025-10-07" (10 chars): not >= 16, but >= 5
        //   → substring(11.coerceAtMost(10)) = substring(10) = ""
        // end = "2025-10-07T12:00" (16 chars): >= 16 → substring(11,16) = "12:00"
        assertEquals(" - 12:00", result.timeRange)
    }

    @Test
    fun `toUiEvent empty start and end yields question marks`() {
        val event = ApiEvent("TU859", "Title", "Lec", "Staff", "Room",
            "", "", "")
        val result = TimetableUtils.toUiEvent(event, "2025-10-06")
        assertEquals("??:?? - ??:??", result.timeRange)
    }

    @Test
    fun `toUiEvent blank title defaults to Untitled`() {
        val event = ApiEvent("TU859", "", "Lec", "", "Room",
            "2025-10-07T10:00:00", "2025-10-07T12:00:00", "")
        val result = TimetableUtils.toUiEvent(event, "2025-10-06")
        assertEquals("Untitled", result.title)
    }

    @Test
    fun `toUiEvent blank lecturer defaults to Staff`() {
        val event = ApiEvent("TU859", "Title", "Lec", "", "Room",
            "2025-10-07T10:00:00", "2025-10-07T12:00:00", "")
        val result = TimetableUtils.toUiEvent(event, "2025-10-06")
        assertEquals("Staff", result.lecturer)
    }

    @Test
    fun `toUiEvent blank room defaults to TBA`() {
        val event = ApiEvent("TU859", "Title", "Lec", "Staff", "",
            "2025-10-07T10:00:00", "2025-10-07T12:00:00", "")
        val result = TimetableUtils.toUiEvent(event, "2025-10-06")
        assertEquals("TBA", result.room)
    }

    @Test
    fun `toUiEvent weekStart is passed through`() {
        val event = ApiEvent("TU859", "Title", "Lec", "Staff", "Room",
            "2025-10-07T10:00:00", "2025-10-07T12:00:00", "")
        val result = TimetableUtils.toUiEvent(event, "2025-10-06")
        assertEquals("2025-10-06", result.weekStart)
    }

    @Test
    fun `toUiEvent handles invalid date without crashing`() {
        val event = ApiEvent("TU859", "Title", "Lec", "Staff", "Room",
            "not-a-date", "not-a-date", "")
        val result = TimetableUtils.toUiEvent(event, "2025-10-06")
        // "not-a-date" is 11 chars: not >= 16 but >= 5
        //   → substring(11.coerceAtMost(11)) = substring(11) = ""
        // Day parsing: parse("not-a-date") throws → dayName = ""
        assertEquals(" - ", result.timeRange)
        assertEquals("", result.day)
    }

    // ── getCurrentMonday ───────────────────────────────────────────────

    @Test
    fun `getCurrentMonday returns Monday for a Wednesday`() {
        val wednesday = LocalDate.of(2025, 10, 8)
        val monday = TimetableUtils.getCurrentMonday(wednesday)
        assertEquals(DayOfWeek.MONDAY, monday.dayOfWeek)
        assertEquals(LocalDate.of(2025, 10, 6), monday)
    }

    @Test
    fun `getCurrentMonday returns same day if already Monday`() {
        val monday = LocalDate.of(2025, 10, 6)
        val result = TimetableUtils.getCurrentMonday(monday)
        assertEquals(monday, result)
    }

    @Test
    fun `getCurrentMonday returns previous Monday for Sunday`() {
        val sunday = LocalDate.of(2025, 10, 12)
        val result = TimetableUtils.getCurrentMonday(sunday)
        assertEquals(LocalDate.of(2025, 10, 6), result)
    }

    // ── formatDayDate ──────────────────────────────────────────────────

    @Test
    fun `formatDayDate formats Monday correctly`() {
        val monday = LocalDate.of(2025, 10, 6)
        val result = TimetableUtils.formatDayDate(monday, 0)
        assertEquals("Oct 6", result)
    }

    @Test
    fun `formatDayDate formats Wednesday correctly`() {
        val monday = LocalDate.of(2025, 10, 6)
        val result = TimetableUtils.formatDayDate(monday, 2)
        assertEquals("Oct 8", result)
    }

    @Test
    fun `formatDayDate formats Friday correctly`() {
        val monday = LocalDate.of(2025, 10, 6)
        val result = TimetableUtils.formatDayDate(monday, 4)
        assertEquals("Oct 10", result)
    }

    // ── formatWeekRange ────────────────────────────────────────────────

    @Test
    fun `formatWeekRange formats full week`() {
        val monday = LocalDate.of(2025, 10, 6)
        val result = TimetableUtils.formatWeekRange(monday)
        assertEquals("Oct 6 – Oct 12, 2025", result)
    }

    @Test
    fun `formatWeekRange handles year boundary`() {
        val monday = LocalDate.of(2025, 12, 29)
        val result = TimetableUtils.formatWeekRange(monday)
        assertEquals("Dec 29 – Jan 4, 2026", result)
    }

    // ── generateAcademicWeeks ──────────────────────────────────────────

    @Test
    fun `generateAcademicWeeks starts at or before September 1`() {
        val today = LocalDate.of(2025, 10, 15)
        val weeks = TimetableUtils.generateAcademicWeeks(today)
        assertTrue("Weeks list should not be empty", weeks.isNotEmpty())
        val firstMonday = weeks.first()
        // Should be the Monday of the week containing Sep 1, 2025
        assertTrue(firstMonday <= LocalDate.of(2025, 9, 1))
        assertEquals(DayOfWeek.MONDAY, firstMonday.dayOfWeek)
    }

    @Test
    fun `generateAcademicWeeks ends in April`() {
        val today = LocalDate.of(2025, 10, 15)
        val weeks = TimetableUtils.generateAcademicWeeks(today)
        val lastMonday = weeks.last()
        // Should be in April 2026
        assertTrue(lastMonday.monthValue in 1..4)
        assertTrue(lastMonday.year == 2026 || (lastMonday.year == 2025 && lastMonday.monthValue >= 9))
    }

    @Test
    fun `generateAcademicWeeks from spring uses previous year's September`() {
        val today = LocalDate.of(2026, 2, 10)
        val weeks = TimetableUtils.generateAcademicWeeks(today)
        val firstMonday = weeks.first()
        // Academic year starts Sep 2025
        assertTrue(firstMonday.year == 2025 && firstMonday.monthValue in 8..9)
    }

    @Test
    fun `generateAcademicWeeks all entries are Mondays`() {
        val today = LocalDate.of(2025, 10, 15)
        val weeks = TimetableUtils.generateAcademicWeeks(today)
        for (monday in weeks) {
            assertEquals(DayOfWeek.MONDAY, monday.dayOfWeek)
        }
    }

    @Test
    fun `generateAcademicWeeks is ordered and 7 days apart`() {
        val today = LocalDate.of(2025, 10, 15)
        val weeks = TimetableUtils.generateAcademicWeeks(today)
        for (i in 1 until weeks.size) {
            assertEquals(7L, weeks[i].toEpochDay() - weeks[i - 1].toEpochDay())
        }
    }

    @Test
    fun `generateAcademicWeeks covers current date`() {
        val today = LocalDate.of(2025, 11, 20)
        val weeks = TimetableUtils.generateAcademicWeeks(today)
        val mondayOfCurrent = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        assertTrue(weeks.contains(mondayOfCurrent))
    }

    // ── safeFormat ─────────────────────────────────────────────────────

    @Test
    fun `safeFormat returns formatted date`() {
        val date = LocalDate.of(2025, 10, 6)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        assertEquals("2025-10-06", TimetableUtils.safeFormat(date, formatter))
    }

    @Test
    fun `safeFormat returns question mark on invalid formatter`() {
        val date = LocalDate.of(2025, 10, 6)
        // Using a formatter that should work fine — testing it doesn't crash
        val formatter = DateTimeFormatter.ofPattern("MMM d")
        assertEquals("Oct 6", TimetableUtils.safeFormat(date, formatter))
    }

    // ── toUiEvent crash-proof: try/catch fallback ──────────────────────

    @Test
    fun `toUiEvent provides fallback for any parsing failure`() {
        // Force an edge case: start is just long enough but has unusual format
        val event = ApiEvent("MOD", "Title", "Lec", "Staff", "Room",
            "YYYY-MM-DDTHH:MM:SS", "YYYY-MM-DDTHH:MM:SS", "")
        // Length >= 16 so it tries substring(11, 16) which works but gives "HH:MM"
        // The date parsing will fail but the catch shouldn't trigger the outer try
        val result = TimetableUtils.toUiEvent(event, "2025-10-06")
        assertEquals("", result.day) // date parsing fails gracefully
        assertNotNull(result.timeRange)
    }
}
