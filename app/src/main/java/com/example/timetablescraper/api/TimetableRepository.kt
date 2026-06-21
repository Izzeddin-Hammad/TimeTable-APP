package com.example.timetablescraper.api

import com.example.timetablescraper.api.cache.CachedEventEntity
import com.example.timetablescraper.api.cache.SavedCourseEntity
import com.example.timetablescraper.api.cache.SearchHistoryEntity
import com.example.timetablescraper.api.cache.TimetableDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Coordinates between the local Room cache and the remote API.
 *
 * Strategy:
 * - On every request, check the cache first.
 * - If the cache is fresh enough (< [CACHE_TTL_MS]), return cached data.
 * - Otherwise, fetch from the API, persist to cache, and return.
 * - A manual "force refresh" always bypasses the cache.
 *
 * Cache staleness is per (courseIdentity + weekStart), so navigating to a
 * different week or course that was already fetched hits the cache instantly.
 */
class TimetableRepository(private val database: TimetableDatabase) {

    private val dao = database.timetableDao()

    /** Serializes access so only one load operation runs at a time. */
    private val loadMutex = Mutex()

    companion object {
        /** How long cached data is considered fresh (4 hours). */
        const val CACHE_TTL_MS = 4 * 60 * 60 * 1000L

        /** How long to keep old cached data before pruning (30 days). */
        private const val PRUNE_AGE_MS = 30 * 24 * 60 * 60 * 1000L
    }

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Load the timetable for [course] and [mondayDate], preferring the cache
     * when available and fresh. Set [forceRefresh] = true to skip the cache.
     *
     * @return Pair of (events, isFromCache)
     */
    suspend fun loadTimetable(
        courseIdentity: String,
        timetableTypeId: String,
        mondayDate: java.time.LocalDate,
        forceRefresh: Boolean = false
    ): CacheResult = loadMutex.withLock {
        withContext(Dispatchers.IO) {

            val weekStart = mondayDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)

            // 1. Check cache (unless force-refresh)
            if (!forceRefresh) {
                val cachedEvents = dao.getEvents(courseIdentity, weekStart)
                if (cachedEvents.isNotEmpty()) {
                    val lastFetched = dao.getLastFetchedAt(courseIdentity, weekStart) ?: 0L
                    val now = System.currentTimeMillis()
                    val age = now - lastFetched

                    if (age < CACHE_TTL_MS) {
                        // Cache is fresh — return immediately
                        return@withContext CacheResult(
                            events = cachedEvents.map { it.toApiEvent() },
                            source = CacheSource.CACHE_FRESH
                        )
                    }
                }
            }

            // 2. Fetch from API
            try {
                val response = TimetableApiService.fetchTimetable(
                    categoryTypeId = timetableTypeId,
                    identity = courseIdentity,
                    mondayDate = mondayDate
                )

                // 3. Persist to cache (deduplicated — the API can return the same
                //    event in multiple CategoryEvents blocks)
                if (response.events.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    val entities = TimetableUtils.deduplicateEvents(response.events)
                        .map { event ->
                            CachedEventEntity(
                                courseIdentity = courseIdentity,
                                weekStart = weekStart,
                                fetchedAt = now,
                                moduleCode = event.module_code,
                                title = event.title,
                                type = event.type,
                                lecturer = event.lecturer,
                                room = event.room,
                                start = event.start,
                                end = event.end,
                                group = event.group
                            )
                        }
                    dao.deleteForWeek(courseIdentity, weekStart)
                    dao.insertAll(entities)

                    if (dao.count() > 1000) {
                        dao.pruneOlderThan(now - PRUNE_AGE_MS)
                    }
                }

                CacheResult(
                    events = response.events,
                    source = CacheSource.NETWORK
                )
            } catch (e: CancellationException) {
                throw e // NEVER swallow cancellation — let it propagate
            } catch (e: Exception) {
                // 4. On failure, fall back to stale cache if available
                val staleEvents = dao.getEvents(courseIdentity, weekStart)
                if (staleEvents.isNotEmpty()) {
                    CacheResult(
                        events = staleEvents.map { it.toApiEvent() },
                        source = CacheSource.CACHE_STALE,
                        error = e.message
                    )
                } else {
                    throw e
                }
            }
        }
    }

    /** Convert a cached entity back to an ApiEvent for the UI layer. */
    private fun CachedEventEntity.toApiEvent() = ApiEvent(
        module_code = moduleCode,
        title = title,
        type = type,
        lecturer = lecturer,
        room = room,
        start = start,
        end = end,
        group = group
    )

    /** Delete all cached data (e.g. on app reset). */
    suspend fun clearAll() {
        dao.pruneOlderThan(Long.MAX_VALUE)
    }

    // ── Saved courses ──────────────────────────────────────────────────

    /** Get all saved courses, newest first. */
    suspend fun getSavedCourses(): List<SavedCourseEntity> {
        return dao.getSavedCourses()
    }

    /** Check if a course is bookmarked. */
    suspend fun isCourseSaved(identity: String): Boolean {
        return dao.isCourseSaved(identity)
    }

    /** Save (bookmark) a course for quick access, optionally with a pre-selected group. */
    suspend fun saveCourse(course: SearchResult, group: String? = null) {
        dao.saveCourse(
            SavedCourseEntity(
                identity = course.identity,
                name = course.name,
                programmeCode = course.programme_code,
                timetableTypeId = course.timetable_type_id,
                savedAt = System.currentTimeMillis(),
                group = group
            )
        )
    }

    /** Remove a course from saved/bookmarks. */
    suspend fun removeCourse(identity: String) {
        dao.removeCourse(identity)
    }

    /** Toggle: save if not saved, remove if saved. Returns the new state. */
    suspend fun toggleSavedCourse(course: SearchResult, group: String? = null): Boolean {
        return if (dao.isCourseSaved(course.identity)) {
            dao.removeCourse(course.identity)
            false
        } else {
            saveCourse(course, group)
            true
        }
    }

    // ── Search history ─────────────────────────────────────────────────

    /** Get recent search queries (newest first, up to 10). */
    suspend fun getRecentSearches(): List<SearchHistoryEntity> {
        return dao.getRecentSearches(10)
    }

    /** Save a search query to history (deduplicates by query text). */
    suspend fun recordSearch(query: String) {
        if (query.isNotBlank()) {
            dao.recordSearch(
                SearchHistoryEntity(
                    query = query.trim(),
                    searchedAt = System.currentTimeMillis()
                )
            )
        }
    }

    /** Clear all search history. */
    suspend fun clearSearchHistory() {
        dao.clearSearchHistory()
    }
}

// ── Result types ──────────────────────────────────────────────────────

enum class CacheSource {
    /** Data came from the cache and was fresh. */
    CACHE_FRESH,
    /** Data came from cache but was stale — network failed. */
    CACHE_STALE,
    /** Data came from a live API call. */
    NETWORK
}

data class CacheResult(
    val events: List<ApiEvent>,
    val source: CacheSource,
    val error: String? = null
)
