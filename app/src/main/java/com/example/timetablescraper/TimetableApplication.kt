package com.example.timetablescraper

import android.app.Application
import com.example.timetablescraper.api.InstitutionConfiguration
import com.example.timetablescraper.api.Institution
import com.example.timetablescraper.api.SyncStrategy
import com.example.timetablescraper.api.TimetableApiService
import com.example.timetablescraper.api.TimetableRepository
import com.example.timetablescraper.api.cache.TimetableDatabase
import com.example.timetablescraper.worker.SyncNotificationManager
import com.example.timetablescraper.worker.TimetableSyncWorker

/**
 * Application-level singleton that owns the database, repository,
 * and schedules the background sync worker.
 *
 * All configuration is injected dynamically — zero hardcoded values.
 * The [InstitutionConfiguration] can be swapped out to support any
 * university running Scientia Publish.
 */
class TimetableApplication : Application() {

    /** Lazily-initialized Room database (thread-safe singleton). */
    val database: TimetableDatabase by lazy {
        TimetableDatabase.getInstance(this)
    }

    /** The active institution configuration (injectable). */
    val institutionConfig: InstitutionConfiguration by lazy {
        Institution.DEFAULT
    }

    /** The API service, configured with the chosen institution config. */
    val apiService: TimetableApiService by lazy {
        TimetableApiService(config = institutionConfig)
    }

    /**
     * Repository that wraps cache + API with a sync strategy.
     *
     * The strategy is read from [SyncPreferences] on first access so
     * user preferences are respected. If the user hasn't configured one,
     * [SyncStrategy.Daily] is the safe default.
     */
    val repository: TimetableRepository by lazy {
        val strategy = runCatching {
            SyncPreferences.getSyncStrategy(this)
        }.getOrDefault(SyncStrategy.Daily)

        TimetableRepository(
            database = database,
            apiService = apiService,
            syncStrategy = strategy
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // ── Register the outermost crash safety net ─────────────────────
        CrashHandler.register(this)

        // Create notification channel for sync progress/completion
        SyncNotificationManager.createChannel(this)

        // Schedule periodic background sync based on user preferences.
        // Idempotent — calling again updates the existing schedule.
        TimetableSyncWorker.schedule(this)
    }

    companion object {
        lateinit var instance: TimetableApplication
            private set
    }
}
