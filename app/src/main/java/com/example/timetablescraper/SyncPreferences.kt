package com.example.timetablescraper

import android.content.Context
import android.content.SharedPreferences

/**
 * Simple preferences wrapper for sync and cache settings.
 * Uses SharedPreferences — lightweight enough for a student app.
 */
object SyncPreferences {

    private const val PREFS_NAME = "timetable_sync_prefs"
    private const val KEY_AUTO_SYNC = "auto_sync_enabled"
    private const val KEY_SYNC_INTERVAL_HOURS = "sync_interval_hours"
    private const val KEY_LAST_SYNC_MANUAL = "last_manual_sync"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Whether automatic background sync is enabled (default: true). */
    fun isAutoSyncEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_SYNC, true)

    fun setAutoSyncEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_SYNC, enabled).apply()
    }

    /** Sync interval in hours (6, 12, or 24). */
    fun getSyncIntervalHours(context: Context): Int =
        prefs(context).getInt(KEY_SYNC_INTERVAL_HOURS, 6)

    fun setSyncIntervalHours(context: Context, hours: Int) {
        prefs(context).edit().putInt(KEY_SYNC_INTERVAL_HOURS, hours).apply()
    }

    /** Timestamp of the last manually-triggered sync (epoch millis). */
    fun getLastManualSync(context: Context): Long =
        prefs(context).getLong(KEY_LAST_SYNC_MANUAL, 0L)

    fun setLastManualSync(context: Context, timestamp: Long) {
        prefs(context).edit().putLong(KEY_LAST_SYNC_MANUAL, timestamp).apply()
    }

    // ── First academic week ──────────────────────────────────────────

    private const val KEY_FIRST_WEEK = "first_week_monday"

    /** The Monday date (yyyy-MM-dd) the user has set as the first academic week.
     *  Defaults to null (auto-detect from current date). */
    fun getFirstWeekMonday(context: Context): String? =
        prefs(context).getString(KEY_FIRST_WEEK, null)

    fun setFirstWeekMonday(context: Context, monday: String?) {
        prefs(context).edit().putString(KEY_FIRST_WEEK, monday).apply()
    }

    // ── Semester 2 start ─────────────────────────────────────────────

    private const val KEY_SEM2_START = "sem2_start_monday"

    fun getSem2StartMonday(context: Context): String? =
        prefs(context).getString(KEY_SEM2_START, null)

    fun setSem2StartMonday(context: Context, monday: String?) {
        prefs(context).edit().putString(KEY_SEM2_START, monday).apply()
    }

    // ── Starred course (pinned to home) ──────────────────────────────

    private const val KEY_STARRED_ID = "starred_identity"
    private const val KEY_STARRED_NAME = "starred_name"
    private const val KEY_STARRED_TYPE_ID = "starred_type_id"

    fun getStarredCourse(context: Context): Triple<String, String, String>? {
        val id = prefs(context).getString(KEY_STARRED_ID, null) ?: return null
        val name = prefs(context).getString(KEY_STARRED_NAME, null) ?: return null
        val typeId = prefs(context).getString(KEY_STARRED_TYPE_ID, null) ?: return null
        return Triple(id, name, typeId)
    }

    // ── Last selected group per course ───────────────────────────────

    private const val KEY_LAST_GROUP_PREFIX = "last_group_"

    fun getLastGroup(context: Context, courseIdentity: String): String? =
        prefs(context).getString(KEY_LAST_GROUP_PREFIX + courseIdentity, null)

    fun setLastGroup(context: Context, courseIdentity: String, group: String) {
        prefs(context).edit().putString(KEY_LAST_GROUP_PREFIX + courseIdentity, group).apply()
    }

    fun setStarredCourse(context: Context, identity: String?, name: String?, typeId: String?) {
        prefs(context).edit().apply {
            if (identity != null) {
                putString(KEY_STARRED_ID, identity)
                putString(KEY_STARRED_NAME, name)
                putString(KEY_STARRED_TYPE_ID, typeId)
            } else {
                remove(KEY_STARRED_ID)
                remove(KEY_STARRED_NAME)
                remove(KEY_STARRED_TYPE_ID)
            }
            apply()
        }
    }
}
