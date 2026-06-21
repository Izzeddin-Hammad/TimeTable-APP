package com.example.timetablescraper.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.timetablescraper.SyncPreferences
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
 * Periodic background worker that fetches the current week's timetable
 * for previously-viewed courses and caches the results.
 *
 * This runs even when the app is in the background (WorkManager handles
 * Doze / battery optimisation).
 */
class TimetableSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "TimetableSyncWorker"
        const val UNIQUE_WORK_NAME = "timetable_periodic_sync"
        private const val PROGRAMME_TYPE_ID = "241e4d36-93f2-4938-9e15-d4536fe3b2eb"

        /**
         * Schedule periodic background sync based on user preferences.
         * If auto-sync is disabled, cancels existing work instead.
         *
         * Existing work with the same [UNIQUE_WORK_NAME] is replaced.
         */
        fun schedule(context: Context) {
            if (!SyncPreferences.isAutoSyncEnabled(context)) {
                cancel(context)
                return
            }

            val intervalHours = SyncPreferences.getSyncIntervalHours(context)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<TimetableSyncWorker>(
                intervalHours.toLong(), TimeUnit.HOURS,
                15, TimeUnit.MINUTES  // flex interval
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

            Log.d(TAG, "Scheduled periodic sync every $intervalHours hours")
        }

        /** Cancel all scheduled sync work. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context)
                .cancelUniqueWork(UNIQUE_WORK_NAME)
            Log.d(TAG, "Cancelled periodic sync")
        }

        /** Run a one-off sync immediately (for the "Sync Now" button). */
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

            // Get distinct course identities that have been cached before
            val courseIdentities = dao.getDistinctCourseIdentities()
            if (courseIdentities.isEmpty()) {
                Log.d(TAG, "No courses cached yet — nothing to sync")
                return@withContext Result.success()
            }

            val currentMonday = LocalDate.now()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val weekStart = currentMonday.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val now = System.currentTimeMillis()
            var syncedCount = 0

            for (courseIdentity in courseIdentities) {
                try {
                    // Fetch current week for each known course
                    val response = TimetableApiService.fetchTimetable(
                        categoryTypeId = PROGRAMME_TYPE_ID,
                        identity = courseIdentity,
                        mondayDate = currentMonday
                    )

                    if (response.events.isNotEmpty()) {
                        // Replace cache
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
                                    group = event.group
                                )
                            }
                        dao.insertAll(entities)
                        syncedCount++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Sync failed for course $courseIdentity: ${e.message}")
                    // Continue with other courses
                }
            }

            Log.d(TAG, "Sync complete — updated $syncedCount courses")
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Sync worker failed", e)
            Result.retry()
        }
    }

}
