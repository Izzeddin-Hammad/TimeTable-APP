package com.example.timetablescraper.api

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for group filtering logic (pure JVM).
 * Tests the group extraction and filtering that happens in the UI layer.
 */
class GroupFilteringTest {

    // ── Group extraction from event list ───────────────────────────────

    @Test
    fun `extracts distinct sorted groups from events`() {
        val events = listOf(
            ApiEvent("TU859", "Maths", "Lec", "Staff", "Room", "", "", "A"),
            ApiEvent("TU859", "Physics", "Lab", "Staff", "Room", "", "", "B"),
            ApiEvent("TU859", "CS", "Lec", "Staff", "Room", "", "", "A"),
            ApiEvent("TU859", "DB", "Tut", "Staff", "Room", "", "", "G1"),
        )
        val groups = events.map { it.group }.distinct().sorted()
        assertEquals(listOf("A", "B", "G1"), groups)
    }

    @Test
    fun `extracts groups from compound group strings`() {
        val events = listOf(
            ApiEvent("TU859", "Maths", "Lec", "Staff", "Room", "", "", "A + B"),
            ApiEvent("TU859", "Physics", "Lab", "Staff", "Room", "", "", "G2 + G1"),
        )
        // Split each group on "+" and collect
        val allGroups = events.flatMap { event ->
            event.group.split("+").map { it.trim() }.filter { it.isNotBlank() }
        }.distinct().sorted()
        assertEquals(listOf("A", "B", "G1", "G2"), allGroups)
    }

    @Test
    fun `handles empty group strings`() {
        val events = listOf(
            ApiEvent("TU859", "Lec", "Lec", "Staff", "Room", "", "", ""),
            ApiEvent("TU859", "Lab", "Lab", "Staff", "Room", "", "", ""),
        )
        val groups = events.flatMap { event ->
            event.group.split("+").map { it.trim() }.filter { it.isNotBlank() }
        }.distinct().sorted()
        assertTrue(groups.isEmpty())
    }

    // ── Event filtering by group ───────────────────────────────────────

    @Test
    fun `filters events by exact group match`() {
        val events = listOf(
            ApiEvent("TU859", "Maths", "Lec", "Staff", "Room", "", "", "A"),
            ApiEvent("TU859", "Physics", "Lab", "Staff", "Room", "", "", "B"),
            ApiEvent("TU859", "CS", "Lec", "Staff", "Room", "", "", "A"),
        )
        val filtered = events.filter { event ->
            event.group.split("+").any { g -> g.trim() == "A" }
        }
        assertEquals(2, filtered.size)
    }

    @Test
    fun `filter with null group returns all events`() {
        val events = listOf(
            ApiEvent("TU859", "Maths", "Lec", "Staff", "Room", "", "", "A"),
            ApiEvent("TU859", "Physics", "Lab", "Staff", "Room", "", "", "B"),
        )
        // When selectedGroup is null, show all
        val selectedGroup: String? = null
        val displayEvents = if (selectedGroup != null) {
            events.filter { event ->
                event.group.split("+").any { g -> g.trim() == selectedGroup }
            }
        } else events
        assertEquals(2, displayEvents.size)
    }

    @Test
    fun `filter matches compound group events`() {
        val events = listOf(
            ApiEvent("TU859", "Joint", "Lec", "Staff", "Room", "", "", "G1 + G2"),
        )
        val filtered = events.filter { event ->
            event.group.split("+").any { g -> g.trim() == "G1" }
        }
        assertEquals(1, filtered.size)
    }

    // ── Group normalisation ────────────────────────────────────────────

    @Test
    fun `normalise sorts compound groups alphabetically`() {
        val raw = "G2 + G1"
        val normalised = raw.split("+").map { it.trim() }.filter { it.isNotEmpty() }.sorted().joinToString(" + ")
        assertEquals("G1 + G2", normalised)
    }

    @Test
    fun `normalise handles single group`() {
        val raw = "A"
        val normalised = raw.split("+").map { it.trim() }.filter { it.isNotEmpty() }.sorted().joinToString(" + ")
        assertEquals("A", normalised)
    }

    @Test
    fun `normalise handles three groups`() {
        val raw = "C + A + B"
        val normalised = raw.split("+").map { it.trim() }.filter { it.isNotEmpty() }.sorted().joinToString(" + ")
        assertEquals("A + B + C", normalised)
    }
}
