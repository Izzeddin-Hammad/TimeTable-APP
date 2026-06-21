package com.example.timetablescraper.api.cache

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TimetableDao {

    /** Get all cached events for a specific course + week. */
    @Query("""
        SELECT * FROM cached_events
        WHERE courseIdentity = :courseIdentity AND weekStart = :weekStart
        ORDER BY start ASC
    """)
    suspend fun getEvents(courseIdentity: String, weekStart: String): List<CachedEventEntity>

    /** Observe cached events reactively (used for cache-status UI). */
    @Query("""
        SELECT * FROM cached_events
        WHERE courseIdentity = :courseIdentity AND weekStart = :weekStart
        ORDER BY start ASC
    """)
    fun observeEvents(courseIdentity: String, weekStart: String): Flow<List<CachedEventEntity>>

    /** Insert or replace events – replaces the entire week's data atomically. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<CachedEventEntity>)

    /** Delete all events for a given course + week (used before re-caching). */
    @Query("""
        DELETE FROM cached_events
        WHERE courseIdentity = :courseIdentity AND weekStart = :weekStart
    """)
    suspend fun deleteForWeek(courseIdentity: String, weekStart: String)

    /** Get the newest fetchedAt timestamp for a course+week, or null if not cached. */
    @Query("""
        SELECT MAX(fetchedAt) FROM cached_events
        WHERE courseIdentity = :courseIdentity AND weekStart = :weekStart
    """)
    suspend fun getLastFetchedAt(courseIdentity: String, weekStart: String): Long?

    /** Prune events older than the given cutoff (epoch millis). */
    @Query("DELETE FROM cached_events WHERE fetchedAt < :olderThan")
    suspend fun pruneOlderThan(olderThan: Long)

    /** Count all cached rows (for diagnostics). */
    @Query("SELECT COUNT(*) FROM cached_events")
    suspend fun count(): Int

    /** Get all distinct course identities that have been cached (for background sync). */
    @Query("SELECT DISTINCT courseIdentity FROM cached_events")
    suspend fun getDistinctCourseIdentities(): List<String>

    /** Get the newest fetchedAt timestamp across all cached data, or null. */
    @Query("SELECT MAX(fetchedAt) FROM cached_events")
    suspend fun getNewestFetchedAt(): Long?

    /** Get the oldest fetchedAt timestamp across all cached data, or null. */
    @Query("SELECT MIN(fetchedAt) FROM cached_events")
    suspend fun getOldestFetchedAt(): Long?

    /** Count distinct weekStart + courseIdentity combinations (how many weeks are cached). */
    @Query("SELECT COUNT(DISTINCT weekStart || '|' || courseIdentity) FROM cached_events")
    suspend fun countDistinctWeeks(): Int

    // ── Saved courses ──────────────────────────────────────────────────

    /** Get all saved courses, newest first. */
    @Query("SELECT * FROM saved_courses ORDER BY savedAt DESC")
    suspend fun getSavedCourses(): List<SavedCourseEntity>

    /** Check if a course is saved. */
    @Query("SELECT EXISTS(SELECT 1 FROM saved_courses WHERE identity = :identity)")
    suspend fun isCourseSaved(identity: String): Boolean

    /** Save a course (insert or replace). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCourse(course: SavedCourseEntity)

    /** Remove a saved course by identity. */
    @Query("DELETE FROM saved_courses WHERE identity = :identity")
    suspend fun removeCourse(identity: String)

    // ── Search history ─────────────────────────────────────────────────

    /** Get recent search queries, newest first. */
    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC LIMIT :limit")
    suspend fun getRecentSearches(limit: Int = 10): List<SearchHistoryEntity>

    /** Insert or update a search query's timestamp. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun recordSearch(entry: SearchHistoryEntity)

    /** Clear all search history. */
    @Query("DELETE FROM search_history")
    suspend fun clearSearchHistory()
}
