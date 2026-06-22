package com.example.timetablescraper.api.cache

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CachedEventEntity::class, SavedCourseEntity::class, SearchHistoryEntity::class],
    version = 7,
    exportSchema = false
)
abstract class TimetableDatabase : RoomDatabase() {

    abstract fun timetableDao(): TimetableDao

    companion object {
        private const val TAG = "TimetableDatabase"

        @Volatile
        private var INSTANCE: TimetableDatabase? = null

        /**
         * Migration 6 → 7: Safe schema reconciliation.
         *
         * Uses CREATE TABLE IF NOT EXISTS to preserve existing data while ensuring
         * all current columns exist.  ALTER TABLE ADD COLUMN statements for columns
         * that may have been added since v6 are wrapped in try/catch so they are
         * silently skipped if already present.
         *
         * This eliminates the need for [fallbackToDestructiveMigration] for the
         * common upgrade path (6 → 7).  Older migration paths still fall back to
         * destructive as a safety net, with a log warning for observability.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ── cached_events ─────────────────────────────────────
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `cached_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT,
                        `courseIdentity` TEXT NOT NULL,
                        `weekStart` TEXT NOT NULL,
                        `fetchedAt` INTEGER NOT NULL,
                        `moduleCode` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `lecturer` TEXT NOT NULL,
                        `room` TEXT NOT NULL,
                        `start` TEXT NOT NULL,
                        `end` TEXT NOT NULL,
                        `group` TEXT NOT NULL DEFAULT '',
                        `courseName` TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cached_events_courseIdentity_weekStart` ON `cached_events` (`courseIdentity`, `weekStart`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cached_events_fetchedAt` ON `cached_events` (`fetchedAt`)")

                // Columns possibly absent in v6 — add silently if missing
                try { db.execSQL("ALTER TABLE `cached_events` ADD COLUMN `group` TEXT NOT NULL DEFAULT ''") } catch (_: Exception) { /* already present */ }
                try { db.execSQL("ALTER TABLE `cached_events` ADD COLUMN `courseName` TEXT NOT NULL DEFAULT ''") } catch (_: Exception) { /* already present */ }

                // ── saved_courses ─────────────────────────────────────
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `saved_courses` (
                        `identity` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `programmeCode` TEXT NOT NULL,
                        `timetableTypeId` TEXT NOT NULL,
                        `savedAt` INTEGER NOT NULL,
                        `group` TEXT,
                        PRIMARY KEY(`identity`)
                    )
                """.trimIndent())

                // ── search_history ────────────────────────────────────
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `search_history` (
                        `query` TEXT NOT NULL,
                        `searchedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`query`)
                    )
                """.trimIndent())
            }
        }

        fun getInstance(context: Context): TimetableDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TimetableDatabase::class.java,
                    "timetable_cache.db"
                )
                    .addMigrations(MIGRATION_6_7)
                    .fallbackToDestructiveMigration()
                    .addCallback(object : Callback() {
                        override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                            super.onDestructiveMigration(db)
                            Log.w(TAG, "Destructive database migration triggered — " +
                                    "user cache was cleared. This should not happen for " +
                                    "normal 6→7 upgrades.")
                        }
                    })
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
