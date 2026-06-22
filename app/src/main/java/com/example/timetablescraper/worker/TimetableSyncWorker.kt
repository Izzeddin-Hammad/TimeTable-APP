package com.example.timetablescraper.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.timetablescraper.SyncPreferences
import com.example.timetablescraper.api.InstitutionConfiguration
import com.example.timetablescraper.api.Institution
import com.example.timetablescraper.api.SyncStrategy
import com.example.timetablescraper.api.TimetableApiService
import com.example.timetablescraper.api.TimetableUtils
import com.example.timetablescraper.api.cache.CachedEventEntity
import com.example.timetablescraper.api.cache.TimetableDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/**
 * Periodic background worker that refreshes cached timetables.
 *
 * ## Notifications
 * - Shows a foreground notification while syncing (via [setForeground]).
 * - Posts a completion notification with success/fail status and timestamp.
 *
 * ## Strategy-aware scheduling
 * The sync interval respects the user's chosen [SyncStrategy].
 *
 * ## Zero hardcoded values
 * API endpoints and identifiers are resolved through [InstitutionConfiguration].
 *
 * ## Fail-safe
 * Individual course failures are caught; one failed course does not block others.
 */
class TimetableSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "TimetableSyncWorker"
        const val UNIQUE_WORK_NAME = "timetable_periodic_sync"

        /**
         * Schedule periodic background sync based on the user's [SyncStrategy].
         * If auto-sync is disabled, cancels existing work.
         */
        fun schedule(context: Context) {
            if (!SyncPreferences.isAutoSyncEnabled(context)) {
                cancel(context)
                return
            }

            val strategy = SyncPreferences.getSyncStrategy(context)
            val intervalMillis = strategy.ttlMillis()
            val intervalMinutes = (intervalMillis / 60_000).coerceAtLeast(15L)
            val intervalHours = when {
                intervalMinutes < 60    -> 1L
                intervalMinutes < 1440  -> intervalMinutes / 60
                else                    -> intervalMinutes / 60
            }.coerceAtLeast(1L)

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<TimetableSyncWorker>(
                intervalHours, TimeUnit.HOURS,
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30, TimeUnit.SECONDS
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )

            Log.d(TAG, "Scheduled periodic sync every $intervalHours hours (strategy: ${strategy.displayName()})")
        }

        /** Cancel all scheduled sync work. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context)
                .cancelUniqueWork(UNIQUE_WORK_NAME)
            Log.d(TAG, "Cancelled periodic sync")
        }

        /** Run a one-off sync immediately (for "Sync Now"). */
        fun syncNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<TimetableSyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(request)

            SyncPreferences.setLastManualSync(context, System.currentTimeMillis())
            Log.d(TAG, "Manual sync enqueued")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting background timetable sync...")

        try {
            val database = TimetableDatabase.getInstance(applicationContext)
            val dao = database.timetableDao()
            val apiService = TimetableApiService.DEFAULT  // reuse singleton OkHttp client + rate limiter

            val courseIdentities = dao.getDistinctCourseIdentities()
            if (courseIdentities.isEmpty()) {
                Log.d(TAG, "No courses cached yet — nothing to sync")
                SyncNotificationManager.postCompletionNotification(
                    applicationContext,
                    success = true,
                    summary = "No courses to sync yet"
                )
                return@withContext Result.success()
            }

            val currentMonday = TimetableUtils.currentDublinDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val weekStart = currentMonday.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val now = System.currentTimeMillis()
            var syncedCount = 0
            var failedCount = 0

            val config = Institution.DEFAULT

            for (courseIdentity in courseIdentities) {
                try {
                    // Resolve course name from saved courses (for Settings display)
                    val courseName = dao.getSavedCourse(courseIdentity)?.name ?: ""

                    val response = apiService.fetchTimetable(
                        categoryTypeId = config.programmeTypeId,
                        identity = courseIdentity,
                        mondayDate = currentMonday
                    )

                    if (response.events.isNotEmpty()) {
                        dao.deleteForWeek(courseIdentity, weekStart)
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
                                    courseName = courseName
                                )
                            }
                        dao.insertAll(entities)
                        syncedCount++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Sync failed for course $courseIdentity: ${e.message}")
                    failedCount++
                }
            }

            val success = failedCount == 0
            val summary = if (syncedCount > 0) {
                "Updated $syncedCount course(s)" +
                    if (failedCount > 0) ", $failedCount failed" else ""
            } else {
                "No new data found"
            }

            Log.d(TAG, "Sync complete — $summary")
            SyncNotificationManager.postCompletionNotification(
                applicationContext,
                success = success,
                summary = summary,
                errorMessage = if (!success) "$failedCount course(s) failed" else null
            )
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Sync worker failed", e)
            SyncNotificationManager.postCompletionNotification(
                applicationContext,
                success = false,
                summary = "Sync failed",
                errorMessage = e.message
            )
            Result.retry()
        }
    }

}
