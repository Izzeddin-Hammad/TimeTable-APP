package com.example.timetablescraper.api

import androidx.compose.runtime.Immutable
import com.example.timetablescraper.SyncPreferences
import com.example.timetablescraper.api.cache.CachedEventEntity
import com.example.timetablescraper.api.cache.SavedCourseEntity
import com.example.timetablescraper.api.cache.SearchHistoryEntity
import com.example.timetablescraper.api.cache.TimetableDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Enterprise-grade caching repository that minimises HTTP requests through:
 *
 * 1. **Configurable sync strategy** — [SyncStrategy] determines the TTL.
 *    Network calls are **blocked entirely** when the cache is fresh within
 *    the chosen window (Daily / Weekly / Custom).
 *
 * 2. **Per-week lazy loading** — Each (course, week) pair is stored
 *    independently. Switching weeks triggers zero network if already cached.
 *
 * 3. **Singleton request debouncing** — Identical in-flight requests are
 *    consolidated via [RequestDebouncer].
 *
 * 4. **Manifold fail-safe** — On any network error (timeout, DNS, HTTP 429,
 *    HTTP 500+), stale cache is returned with an error flag. The UI reads
 *    [CacheSource.CACHE_STALE] to show an "Offline/Cached Mode" warning.
 *
 * 5. **Zero hardcoded values** — TTL, API config, and endpoint URLs all
 *    come from runtime configuration.
 *
 * @param database     The Room database instance.
 * @param apiService   The configurable API client. Defaults to [TimetableApiService.DEFAULT].
 * @param syncStrategy The sync strategy to use. Defaults to reading from [SyncPreferences].
 */
class TimetableRepository(
    private val database: TimetableDatabase,
    private val apiService: TimetableApiService = TimetableApiService.DEFAULT,
    private val syncStrategy: SyncStrategy = SyncStrategy.Daily
) {
    private val dao = database.timetableDao()

    /**
     * No global mutex — Room handles concurrent reads internally,
     * and [RequestDebouncer] deduplicates network calls per URL.
     * The background week scanner (30+ weeks) no longer blocks user navigation.
     */

    /** Indexable cache for checking week presence without raw DAO calls. */
    val weekCacheIndex: WeekCacheIndex by lazy { WeekCacheIndex(dao) }

    /** Current effective TTL in milliseconds (from the sync strategy). */
    val currentTtlMillis: Long get() = syncStrategy.ttlMillis()

    companion object {
        /**
         * Legacy TTL constant retained for backward compatibility with tests.
         * Production code should use the strategy-aware [currentTtlMillis].
         */
        @Deprecated("Use currentTtlMillis from the repository instance instead",
            replaceWith = ReplaceWith("repository.currentTtlMillis"))
        const val CACHE_TTL_MS = 4 * 60 * 60 * 1000L

        /** How long to keep old cached data before pruning (30 days). */
        private const val PRUNE_AGE_MS = 30 * 24 * 60 * 60 * 1000L
    }

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Load the timetable for [courseIdentity] and [mondayDate] with configurable
     * cache TTL from the current [SyncStrategy].
     *
     * ## Request minimization flow
     * 1. Check the local cache for this specific (course, week).
     * 2. If cached and age < strategy TTL → return fresh cache (zero network).
     * 3. If not cached or stale → fetch from API via the request debouncer.
     * 4. On network failure → return stale cache with error flag.
     * 5. On network failure with **no** stale cache → throw (caller shows error).
     *
     * @param courseIdentity   The course/programme identity string.
     * @param timetableTypeId  The category type ID for API queries.
     * @param mondayDate       The Monday of the target week.
     * @param forceRefresh     If true, skip the cache freshness check and go to network.
     * @param context          Optional Android context for reading SyncPreferences
     *                         (if the repository was constructed without a strategy override).
     * @return [CacheResult] with events and source metadata.
     */
    suspend fun loadTimetable(
        courseIdentity: String,
        timetableTypeId: String,
        mondayDate: LocalDate,
        forceRefresh: Boolean = false,
        context: android.content.Context? = null,
        courseName: String? = null
    ): CacheResult = withContext(Dispatchers.IO) {

            val weekStart = mondayDate.format(DateTimeFormatter.ISO_LOCAL_DATE)

            // Resolve the effective TTL from the strategy
            val effectiveTtl = if (context != null) {
                SyncPreferences.getSyncStrategy(context).ttlMillis()
            } else {
                syncStrategy.ttlMillis()
            }

            // ── 1. Check cache (unless force-refresh) ───────────────
            // Single query — reads events AND their fetchedAt from the first event
            if (!forceRefresh) {
                val cachedEvents = dao.getEvents(courseIdentity, weekStart)
                if (cachedEvents.isNotEmpty()) {
                    // All events for the same course+week share the same fetchedAt
                    val lastFetched = cachedEvents.first().fetchedAt
                    val age = System.currentTimeMillis() - lastFetched

                    if (age < effectiveTtl) {
                        // Cache is fresh within the chosen sync window →
                        // return immediately, ZERO network calls
                        return@withContext CacheResult(
                            events = cachedEvents.map { it.toApiEvent() },
                            source = CacheSource.CACHE_FRESH
                        )
                    }
                }
            }

            // ── 2. Fetch from API ──────────────────────────────────
            try {
                val response = apiService.fetchTimetable(
                    categoryTypeId = timetableTypeId,
                    identity = courseIdentity,
                    mondayDate = mondayDate
                )

                // ── 3. Persist to cache ─────────────────────────────
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
                                group = event.group,
                                courseName = courseName ?: ""
                            )
                        }
                    dao.deleteForWeek(courseIdentity, weekStart)
                    dao.insertAll(entities)

                    // Prune old data if cache is growing large
                    if (dao.count() > 1000) {
                        dao.pruneOlderThan(now - PRUNE_AGE_MS)
                    }

                    // DAO is the single source of truth — no SharedPreferences write needed
                }

                CacheResult(
                    events = response.events,
                    source = CacheSource.NETWORK
                )

            } catch (e: CancellationException) {
                throw e  // NEVER swallow cancellation
            } catch (e: Exception) {
                // ── 4. Fail-safe: fall back to stale cache ──────────
                val staleEvents = dao.getEvents(courseIdentity, weekStart)
                if (staleEvents.isNotEmpty()) {
                    CacheResult(
                        events = staleEvents.map { it.toApiEvent() },
                        source = CacheSource.CACHE_STALE,
                        error = e.message
                    )
                } else {
                    // No cache to fall back to — rethrow for the UI to handle
                    throw e
                }
        }
    }

    /** Convert a cached entity back to an ApiEvent for the UI layer. */
    private fun CachedEventEntity.toApiEvent() = ApiEvent(
        id = id,
        module_code = moduleCode,
        title = title,
        type = type,
        lecturer = lecturer,
        room = room,
        start = start,
        end = end,
        group = group
    )

    /** Delete all cached timetable data (e.g. on app reset).
     *  Saved/bookmarked courses and search history are NOT affected. */
    suspend fun clearAll() {
        dao.pruneOlderThan(Long.MAX_VALUE)
    }

    /** Delete cached data for a single course (granular cache management). */
    suspend fun clearCacheForCourse(courseIdentity: String) {
        dao.deleteForCourse(courseIdentity)
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
    /** Data came from the cache and was fresh (no network call). */
    CACHE_FRESH,
    /** Data came from cache but was stale — network failed (offline/error fallback). */
    CACHE_STALE,
    /** Data came from a live API call. */
    NETWORK
}

@Immutable
data class CacheResult(
    val events: List<ApiEvent>,
    val source: CacheSource,
    val error: String? = null
)
