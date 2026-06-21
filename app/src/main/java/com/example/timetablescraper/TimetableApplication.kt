package com.example.timetablescraper

import android.app.Application
import com.example.timetablescraper.api.cache.TimetableDatabase
import com.example.timetablescraper.api.TimetableRepository
import com.example.timetablescraper.worker.TimetableSyncWorker

/**
 * Application-level singleton that owns the database, repository,
 * and schedules the background sync worker.
 *
 * This is the simplest form of "dependency injection" —
 * no Hilt/Dagger needed for an app this size.
 */
class TimetableApplication : Application() {

    /** Lazily-initialized Room database (thread-safe singleton). */
    val database: TimetableDatabase by lazy {
        TimetableDatabase.getInstance(this)
    }

    /** Repository that wraps cache + API. */
    val repository: TimetableRepository by lazy {
        TimetableRepository(database)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Schedule periodic background sync based on user preferences.
        // Idempotent — calling again updates the existing schedule.
        TimetableSyncWorker.schedule(this)
    }

    companion object {
        lateinit var instance: TimetableApplication
            private set
    }
}
