package com.example.timetablescraper.api.cache

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Instrumentation tests for TimetableDao using an in-memory Room database.
 */
class TimetableDaoTest {

    private lateinit var database: TimetableDatabase
    private lateinit var dao: TimetableDao

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, TimetableDatabase::class.java)
            .build()
        dao = database.timetableDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ── Event insert & query ───────────────────────────────────────────

    @Test
    fun `insert and query events`() = runBlocking {
        val now = System.currentTimeMillis()
        val events = listOf(
            CachedEventEntity(
                courseIdentity = "course-a", weekStart = "2025-10-06",
                fetchedAt = now, moduleCode = "TU859", title = "Maths",
                type = "Lec", lecturer = "Dr. A", room = "A201",
                start = "2025-10-06T10:00:00", end = "2025-10-06T12:00:00"
            ),
            CachedEventEntity(
                courseIdentity = "course-a", weekStart = "2025-10-06",
                fetchedAt = now, moduleCode = "TU859", title = "Physics",
                type = "Lab", lecturer = "Dr. B", room = "B305",
                start = "2025-10-07T10:00:00", end = "2025-10-07T12:00:00"
            )
        )
        dao.insertAll(events)

        val result = dao.getEvents("course-a", "2025-10-06")
        assertEquals(2, result.size)
        assertEquals("Maths", result[0].title)
        assertEquals("Physics", result[1].title)
    }

    @Test
    fun `query returns empty for non-existent course`() = runBlocking {
        val result = dao.getEvents("no-course", "2025-10-06")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `query returns empty for non-existent week`() = runBlocking {
        val events = listOf(
            CachedEventEntity(
                courseIdentity = "course-a", weekStart = "2025-10-06",
                fetchedAt = System.currentTimeMillis(), moduleCode = "TU859",
                title = "Maths", type = "Lec", lecturer = "Dr. A", room = "A201",
                start = "2025-10-06T10:00:00", end = "2025-10-06T12:00:00"
            )
        )
        dao.insertAll(events)

        val result = dao.getEvents("course-a", "2025-10-13")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `events ordered by start time`() = runBlocking {
        val now = System.currentTimeMillis()
        val events = listOf(
            CachedEventEntity(
                courseIdentity = "course-a", weekStart = "2025-10-06",
                fetchedAt = now, moduleCode = "TU859", title = "Late",
                type = "Lec", lecturer = "Dr. A", room = "A201",
                start = "2025-10-06T15:00:00", end = "2025-10-06T17:00:00"
            ),
            CachedEventEntity(
                courseIdentity = "course-a", weekStart = "2025-10-06",
                fetchedAt = now, moduleCode = "TU859", title = "Early",
                type = "Lec", lecturer = "Dr. B", room = "B305",
                start = "2025-10-06T09:00:00", end = "2025-10-06T11:00:00"
            )
        )
        dao.insertAll(events)

        val result = dao.getEvents("course-a", "2025-10-06")
        assertEquals("Early", result[0].title)
        assertEquals("Late", result[1].title)
    }

    @Test
    fun `insert replaces existing events`() = runBlocking {
        val now = System.currentTimeMillis()
        val first = listOf(
            CachedEventEntity(
                courseIdentity = "course-a", weekStart = "2025-10-06",
                fetchedAt = now - 10000, moduleCode = "OLD", title = "Old",
                type = "Lec", lecturer = "Dr. A", room = "A201",
                start = "", end = ""
            )
        )
        dao.insertAll(first)
        assertEquals(1, dao.count())

        val second = listOf(
            CachedEventEntity(
                courseIdentity = "course-a", weekStart = "2025-10-06",
                fetchedAt = now, moduleCode = "NEW", title = "New",
                type = "Lab", lecturer = "Dr. B", room = "B305",
                start = "", end = ""
            )
        )
        dao.insertAll(second)
        // OnConflictStrategy.REPLACE creates new rows with new IDs
        // So count becomes 2 (old + new)
        assertEquals(2, dao.count())
    }

    // ── deleteForWeek ──────────────────────────────────────────────────

    @Test
    fun `deleteForWeek removes only matching course and week`() = runBlocking {
        val now = System.currentTimeMillis()
        // Insert for course-a, week-1
        dao.insertAll(listOf(
            CachedEventEntity(
                courseIdentity = "course-a", weekStart = "2025-10-06",
                fetchedAt = now, moduleCode = "M1", title = "T1",
                type = "Lec", lecturer = "L", room = "R", start = "", end = ""
            )
        ))
        // Insert for course-a, week-2
        dao.insertAll(listOf(
            CachedEventEntity(
                courseIdentity = "course-a", weekStart = "2025-10-13",
                fetchedAt = now, moduleCode = "M2", title = "T2",
                type = "Lab", lecturer = "L", room = "R", start = "", end = ""
            )
        ))

        dao.deleteForWeek("course-a", "2025-10-06")

        assertEquals(0, dao.getEvents("course-a", "2025-10-06").size)
        assertEquals(1, dao.getEvents("course-a", "2025-10-13").size)
    }

    @Test
    fun `deleteForWeek does not affect other courses`() = runBlocking {
        val now = System.currentTimeMillis()
        dao.insertAll(listOf(
            CachedEventEntity(
                courseIdentity = "course-a", weekStart = "2025-10-06",
                fetchedAt = now, moduleCode = "MA", title = "TA",
                type = "Lec", lecturer = "L", room = "R", start = "", end = ""
            )
        ))
        dao.insertAll(listOf(
            CachedEventEntity(
                courseIdentity = "course-b", weekStart = "2025-10-06",
                fetchedAt = now, moduleCode = "MB", title = "TB",
                type = "Lec", lecturer = "L", room = "R", start = "", end = ""
            )
        ))

        dao.deleteForWeek("course-a", "2025-10-06")

        assertEquals(0, dao.getEvents("course-a", "2025-10-06").size)
        assertEquals(1, dao.getEvents("course-b", "2025-10-06").size)
    }

    // ── Statistics ─────────────────────────────────────────────────────

    @Test
    fun `count returns total events`() = runBlocking {
        val now = System.currentTimeMillis()
        dao.insertAll(listOf(
            CachedEventEntity("course-a", "2025-10-06", now, "M1", "T1", "Lec", "L", "R", "", ""),
            CachedEventEntity("course-a", "2025-10-06", now, "M2", "T2", "Lab", "L", "R", "", ""),
            CachedEventEntity("course-b", "2025-10-06", now, "M3", "T3", "Lec", "L", "R", "", "")
        ))
        assertEquals(3, dao.count())
    }

    @Test
    fun `countDistinctWeeks returns correct distinct weeks`() = runBlocking {
        val now = System.currentTimeMillis()
        dao.insertAll(listOf(
            // course-a, week1
            CachedEventEntity("course-a", "2025-10-06", now, "M1", "T1", "Lec", "L", "R", "", ""),
            CachedEventEntity("course-a", "2025-10-06", now, "M2", "T2", "Lab", "L", "R", "", ""),
            // course-a, week2
            CachedEventEntity("course-a", "2025-10-13", now, "M3", "T3", "Lec", "L", "R", "", ""),
            // course-b, week1
            CachedEventEntity("course-b", "2025-10-06", now, "M4", "T4", "Lec", "L", "R", "", "")
        ))
        // Distinct (weekStart || courseIdentity) combos:
        // 2025-10-06|course-a, 2025-10-13|course-a, 2025-10-06|course-b = 3
        assertEquals(3, dao.countDistinctWeeks())
    }

    @Test
    fun `getDistinctCourseIdentities returns all cached courses`() = runBlocking {
        val now = System.currentTimeMillis()
        dao.insertAll(listOf(
            CachedEventEntity("course-a", "2025-10-06", now, "M1", "T1", "Lec", "L", "R", "", ""),
            CachedEventEntity("course-b", "2025-10-06", now, "M2", "T2", "Lab", "L", "R", "", ""),
            CachedEventEntity("course-a", "2025-10-13", now, "M3", "T3", "Lec", "L", "R", "", "")
        ))
        val identities = dao.getDistinctCourseIdentities()
        assertEquals(setOf("course-a", "course-b"), identities.toSet())
    }

    @Test
    fun `getNewestFetchedAt returns max timestamp`() = runBlocking {
        val t1 = 1000L
        val t2 = 2000L
        val t3 = 3000L
        dao.insertAll(listOf(
            CachedEventEntity("course-a", "2025-10-06", t1, "M1", "T1", "Lec", "L", "R", "", ""),
            CachedEventEntity("course-b", "2025-10-06", t3, "M2", "T2", "Lab", "L", "R", "", ""),
            CachedEventEntity("course-a", "2025-10-13", t2, "M3", "T3", "Lec", "L", "R", "", "")
        ))
        assertEquals(t3, dao.getNewestFetchedAt())
    }

    @Test
    fun `getNewestFetchedAt returns null when no events`() = runBlocking {
        assertNull(dao.getNewestFetchedAt())
    }

    @Test
    fun `getOldestFetchedAt returns min timestamp`() = runBlocking {
        val t1 = 1000L
        val t2 = 2000L
        dao.insertAll(listOf(
            CachedEventEntity("course-a", "2025-10-06", t2, "M1", "T1", "Lec", "L", "R", "", ""),
            CachedEventEntity("course-b", "2025-10-06", t1, "M2", "T2", "Lab", "L", "R", "", "")
        ))
        assertEquals(t1, dao.getOldestFetchedAt())
    }

    @Test
    fun `getLastFetchedAt returns max for specific course week`() = runBlocking {
        dao.insertAll(listOf(
            CachedEventEntity("course-a", "2025-10-06", 1000L, "M1", "T1", "Lec", "L", "R", "", ""),
            CachedEventEntity("course-a", "2025-10-06", 2000L, "M2", "T2", "Lab", "L", "R", "", "")
        ))
        assertEquals(2000L, dao.getLastFetchedAt("course-a", "2025-10-06"))
    }

    @Test
    fun `getLastFetchedAt returns null for non-existent`() = runBlocking {
        assertNull(dao.getLastFetchedAt("no", "2025-10-06"))
    }

    // ── pruneOlderThan ─────────────────────────────────────────────────

    @Test
    fun `pruneOlderThan removes old events`() = runBlocking {
        val now = System.currentTimeMillis()
        dao.insertAll(listOf(
            CachedEventEntity("course-a", "2025-10-06", now - 100000, "M1", "T1", "Lec", "L", "R", "", ""),
            CachedEventEntity("course-b", "2025-10-06", now, "M2", "T2", "Lab", "L", "R", "", "")
        ))
        dao.pruneOlderThan(now - 50000)
        assertEquals(1, dao.count())
        val remaining = dao.getEvents("course-b", "2025-10-06")
        assertEquals(1, remaining.size)
    }

    @Test
    fun `pruneOlderThan removes nothing when all are new`() = runBlocking {
        val now = System.currentTimeMillis()
        dao.insertAll(listOf(
            CachedEventEntity("course-a", "2025-10-06", now, "M1", "T1", "Lec", "L", "R", "", "")
        ))
        dao.pruneOlderThan(0)
        assertEquals(1, dao.count())
    }

    // ── Saved courses ──────────────────────────────────────────────────

    @Test
    fun `save and query saved courses`() = runBlocking {
        dao.saveCourse(SavedCourseEntity("course-a", "TU859/CS", "TU859", "type-id", 1000L))
        dao.saveCourse(SavedCourseEntity("course-b", "TU858/EE", "TU858", "type-id", 2000L))

        val saved = dao.getSavedCourses()
        assertEquals(2, saved.size)
        // Newest first
        assertEquals("course-b", saved[0].identity)
        assertEquals("course-a", saved[1].identity)
    }

    @Test
    fun `isCourseSaved returns true for saved course`() = runBlocking {
        dao.saveCourse(SavedCourseEntity("course-a", "TU859/CS", "TU859", "type-id", 1000L))
        assertTrue(dao.isCourseSaved("course-a"))
        assertFalse(dao.isCourseSaved("course-x"))
    }

    @Test
    fun `removeCourse deletes saved course`() = runBlocking {
        dao.saveCourse(SavedCourseEntity("course-a", "TU859/CS", "TU859", "type-id", 1000L))
        assertTrue(dao.isCourseSaved("course-a"))

        dao.removeCourse("course-a")
        assertFalse(dao.isCourseSaved("course-a"))
        assertTrue(dao.getSavedCourses().isEmpty())
    }

    @Test
    fun `save course replaces existing`() = runBlocking {
        dao.saveCourse(SavedCourseEntity("course-a", "TU859/CS", "TU859", "type-id", 1000L))
        dao.saveCourse(SavedCourseEntity("course-a", "TU859/CS Updated", "TU859", "type-id", 2000L))

        val saved = dao.getSavedCourses()
        assertEquals(1, saved.size)
        assertEquals("TU859/CS Updated", saved[0].name)
    }

    // ── Search history ─────────────────────────────────────────────────

    @Test
    fun `record and query search history`() = runBlocking {
        dao.recordSearch(SearchHistoryEntity("TU859", 1000L))
        dao.recordSearch(SearchHistoryEntity("TU858", 2000L))

        val history = dao.getRecentSearches(10)
        assertEquals(2, history.size)
        // Newest first
        assertEquals("TU858", history[0].query)
        assertEquals("TU859", history[1].query)
    }

    @Test
    fun `recordSearch replaces existing query`() = runBlocking {
        dao.recordSearch(SearchHistoryEntity("TU859", 1000L))
        dao.recordSearch(SearchHistoryEntity("TU859", 5000L))

        val history = dao.getRecentSearches(10)
        assertEquals(1, history.size)
        assertEquals(5000L, history[0].searchedAt)
    }

    @Test
    fun `getRecentSearches respects limit`() = runBlocking {
        for (i in 1..15) {
            dao.recordSearch(SearchHistoryEntity("query$i", i.toLong() * 1000))
        }

        val history = dao.getRecentSearches(5)
        assertEquals(5, history.size)
        assertEquals("query15", history[0].query)
    }

    @Test
    fun `clearSearchHistory removes all`() = runBlocking {
        dao.recordSearch(SearchHistoryEntity("TU859", 1000L))
        dao.clearSearchHistory()
        assertTrue(dao.getRecentSearches(10).isEmpty())
    }

    // ── Helper: bridge suspend functions ───────────────────────────────

    private fun runBlocking(block: suspend () -> Unit) {
        kotlinx.coroutines.runBlocking { block() }
    }
}
