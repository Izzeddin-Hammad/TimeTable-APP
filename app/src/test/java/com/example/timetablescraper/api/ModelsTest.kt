package com.example.timetablescraper.api

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for data model classes (pure JVM).
 * Ensures data class behaviour (equals, copy, etc.) works as expected.
 */
class ModelsTest {

    @Test
    fun `SearchResult equality`() {
        val a = SearchResult("TU859/CS", "TU859", "id-1", "Programme", "", "type-1")
        val b = SearchResult("TU859/CS", "TU859", "id-1", "Programme", "", "type-1")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `ApiEvent equality`() {
        val a = ApiEvent("TU859", "Maths", "Lec", "Dr. A", "A201",
            "2025-10-07T10:00:00", "2025-10-07T12:00:00", "A")
        val b = ApiEvent("TU859", "Maths", "Lec", "Dr. A", "A201",
            "2025-10-07T10:00:00", "2025-10-07T12:00:00", "A")
        assertEquals(a, b)
    }

    @Test
    fun `ApiEvent different group not equal`() {
        val a = ApiEvent("TU859", "Maths", "Lec", "Dr. A", "A201",
            "2025-10-07T10:00:00", "2025-10-07T12:00:00", "A")
        val b = ApiEvent("TU859", "Maths", "Lec", "Dr. A", "A201",
            "2025-10-07T10:00:00", "2025-10-07T12:00:00", "B")
        assertNotEquals(a, b)
    }

    @Test
    fun `TimetableEvent fields`() {
        val event = TimetableEvent(
            moduleCode = "TU859", title = "Algorithms", type = "Lec",
            lecturer = "Dr. Smith", room = "A201",
            start = "2025-10-07T10:00:00", end = "2025-10-07T12:00:00",
            day = "Tue", dayIndex = 1, timeRange = "10:00 - 12:00",
            weekStart = "2025-10-06", group = "A"
        )
        assertEquals("TU859", event.moduleCode)
        assertEquals("Algorithms", event.title)
        assertEquals("Lec", event.type)
        assertEquals("Dr. Smith", event.lecturer)
        assertEquals("A201", event.room)
        assertEquals("Tue", event.day)
        assertEquals(1, event.dayIndex)
        assertEquals("10:00 - 12:00", event.timeRange)
        assertEquals("2025-10-06", event.weekStart)
        assertEquals("A", event.group)
    }

    @Test
    fun `CacheResult with error`() {
        val events = listOf(
            ApiEvent("TU859", "Title", "Lec", "Staff", "Room", "", "", "")
        )
        val result = CacheResult(events, CacheSource.CACHE_STALE, "Connection timeout")
        assertEquals(CacheSource.CACHE_STALE, result.source)
        assertEquals("Connection timeout", result.error)
        assertEquals(1, result.events.size)
    }

    @Test
    fun `CacheResult without error`() {
        val result = CacheResult(emptyList(), CacheSource.NETWORK)
        assertEquals(CacheSource.NETWORK, result.source)
        assertNull(result.error)
    }

    @Test
    fun `SearchResponse count matches results`() {
        val results = listOf(
            SearchResult("A", "PA", "id-a", "Programme", "", "t1"),
            SearchResult("B", "PB", "id-b", "Programme", "", "t2")
        )
        val response = SearchResponse(results, results.size)
        assertEquals(2, response.count)
        assertEquals(2, response.results.size)
    }

    @Test
    fun `TimetableResponse count matches events`() {
        val events = listOf(
            ApiEvent("TU859", "Maths", "Lec", "Dr. A", "A201", "", "", "")
        )
        val response = TimetableResponse(events, "api", events.size)
        assertEquals(1, response.count)
        assertEquals("api", response.source)
    }

    @Test
    fun `ApiEvent empty group is empty string not null`() {
        val event = ApiEvent("TU859", "Title", "Lec", "Staff", "Room", "", "", "")
        assertEquals("", event.group)
        assertNotNull(event.group)
    }

    @Test
    fun `CacheSource enum values`() {
        assertEquals(3, CacheSource.entries.size)
        assertTrue(CacheSource.entries.contains(CacheSource.CACHE_FRESH))
        assertTrue(CacheSource.entries.contains(CacheSource.CACHE_STALE))
        assertTrue(CacheSource.entries.contains(CacheSource.NETWORK))
    }
}
