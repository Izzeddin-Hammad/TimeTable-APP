package com.example.timetablescraper

import android.content.Context
import android.content.SharedPreferences
import com.example.timetablescraper.api.SyncStrategy

/**
 * Preferences wrapper for sync and cache settings.
 *
 * Now supports the three-mode [SyncStrategy] pattern:
 * - [SyncStrategy.Daily]   → 24h TTL (token: "DAILY")
 * - [SyncStrategy.Weekly]  → 7d TTL  (token: "WEEKLY")
 * - [SyncStrategy.Custom]  → user-defined (token: "CUSTOM:VALUE:UNIT")
 *
 * Legacy [getSyncIntervalHours] / [setSyncIntervalHours] are retained for
 * backward compatibility but are superseded by the [syncStrategy] property.
 */
object SyncPreferences {

    private const val PREFS_NAME = "timetable_sync_prefs"

    // ── Legacy keys (deprecated but retained for backward compat) ──────
    private const val KEY_AUTO_SYNC = "auto_sync_enabled"
    private const val KEY_SYNC_INTERVAL_HOURS = "sync_interval_hours"
    private const val KEY_LAST_SYNC_MANUAL = "last_manual_sync"

    // ── New sync-strategy keys ────────────────────────────────────────
    private const val KEY_SYNC_STRATEGY_TOKEN = "sync_strategy_token"
    private const val KEY_CUSTOM_INTERVAL_VALUE = "custom_interval_value"
    private const val KEY_CUSTOM_INTERVAL_UNIT = "custom_interval_unit"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ═══════════════════════════════════════════════════════════════════
    // Sync Strategy (primary — supersedes the old interval-hours model)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Get the current [SyncStrategy].
     *
     * Migrates from the legacy [KEY_SYNC_INTERVAL_HOURS] if no strategy
     * token has been saved yet — a value of 24h becomes [SyncStrategy.Daily],
     * anything else becomes [SyncStrategy.Daily] as the safe default.
     */
    fun getSyncStrategy(context: Context): SyncStrategy {
        val token = prefs(context).getString(KEY_SYNC_STRATEGY_TOKEN, null)
        if (token != null) return SyncStrategy.fromToken(token)

        // Legacy migration: if we have an old interval value, use it
        val legacyHours = prefs(context).getInt(KEY_SYNC_INTERVAL_HOURS, 0)
        if (legacyHours > 0) {
            return when (legacyHours) {
                24  -> SyncStrategy.Daily
                168 -> SyncStrategy.Weekly   // 7 * 24
                else -> SyncStrategy.Custom(legacyHours, java.util.concurrent.TimeUnit.HOURS)
            }
        }

        return SyncStrategy.Daily  // safe default
    }

    /**
     * Persist a new [SyncStrategy].
     *
     * For [SyncStrategy.Custom], also stores the value and unit separately
     * for easy UI access without parsing the token.
     */
    fun setSyncStrategy(context: Context, strategy: SyncStrategy) {
        prefs(context).edit().apply {
            putString(KEY_SYNC_STRATEGY_TOKEN, strategy.toToken())
            if (strategy is SyncStrategy.Custom) {
                putInt(KEY_CUSTOM_INTERVAL_VALUE, strategy.value)
                putString(KEY_CUSTOM_INTERVAL_UNIT, strategy.unit.name)
            } else {
                remove(KEY_CUSTOM_INTERVAL_VALUE)
                remove(KEY_CUSTOM_INTERVAL_UNIT)
            }
            apply()
        }
    }

    /**
     * Get the custom interval value (for pre-filling the UI input field).
     * Returns 1 if no custom value has been stored.
     */
    fun getCustomIntervalValue(context: Context): Int =
        prefs(context).getInt(KEY_CUSTOM_INTERVAL_VALUE, 1)

    /**
     * Get the custom interval unit name (for pre-filling the UI dropdown).
     * Returns "HOURS" if not set.
     */
    fun getCustomIntervalUnit(context: Context): String =
        prefs(context).getString(KEY_CUSTOM_INTERVAL_UNIT, "HOURS") ?: "HOURS"

    // ═══════════════════════════════════════════════════════════════════
    // Legacy methods (retained for backward compatibility)
    // ═══════════════════════════════════════════════════════════════════

    /** Whether automatic background sync is enabled (default: true). */
    fun isAutoSyncEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_SYNC, true)

    fun setAutoSyncEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_SYNC, enabled).apply()
    }

    /**
     * Legacy sync interval in hours.
     *
     * @deprecated Use [getSyncStrategy] instead. This method reads the
     *   raw hours-storage for backward compatibility with the Settings UI
     *   but the new code should use the strategy model.
     */
    @Deprecated("Use getSyncStrategy() instead")
    fun getSyncIntervalHours(context: Context): Int {
        val strategy = getSyncStrategy(context)
        return when (strategy) {
            is SyncStrategy.Daily  -> 24
            is SyncStrategy.Weekly -> 168
            is SyncStrategy.Custom -> strategy.value
        }
    }

    @Deprecated("Use setSyncStrategy() instead")
    fun setSyncIntervalHours(context: Context, hours: Int) {
        val strategy = when (hours) {
            24  -> SyncStrategy.Daily
            168 -> SyncStrategy.Weekly
            else -> SyncStrategy.Custom(hours, java.util.concurrent.TimeUnit.HOURS)
        }
        setSyncStrategy(context, strategy)
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

    // ── Last selected group per course ───────────────────────────────

    private const val KEY_LAST_GROUP_PREFIX = "last_group_"

    fun getLastGroup(context: Context, courseIdentity: String): String? =
        prefs(context).getString(KEY_LAST_GROUP_PREFIX + courseIdentity, null)

    fun setLastGroup(context: Context, courseIdentity: String, group: String) {
        prefs(context).edit().putString(KEY_LAST_GROUP_PREFIX + courseIdentity, group).apply()
    }

    // ── Course view state (persists across app restarts) ─────────────

    private const val KEY_VIEW_SEMESTER_PREFIX = "view_semester_"
    private const val KEY_VIEW_WEEK_PREFIX = "view_week_"
    private const val KEY_VIEW_DAY_PREFIX = "view_day_"

    /** Save the view state for a course so it survives app restarts. */
    fun saveCourseViewState(
        context: Context,
        courseIdentity: String,
        semester: Int,
        weekStart: String,
        dayIndex: Int
    ) {
        prefs(context).edit().apply {
            putInt("$KEY_VIEW_SEMESTER_PREFIX$courseIdentity", semester)
            putString("$KEY_VIEW_WEEK_PREFIX$courseIdentity", weekStart)
            putInt("$KEY_VIEW_DAY_PREFIX$courseIdentity", dayIndex)
            apply()
        }
    }

    /** Restore the saved semester for a course, or 0 if never viewed. */
    fun getSavedSemester(context: Context, courseIdentity: String): Int =
        prefs(context).getInt("$KEY_VIEW_SEMESTER_PREFIX$courseIdentity", 0)

    /** Restore the saved week for a course, or null if never viewed. */
    fun getSavedWeek(context: Context, courseIdentity: String): String? =
        prefs(context).getString("$KEY_VIEW_WEEK_PREFIX$courseIdentity", null)

    /** Restore the saved day index for a course, or 0 (Monday) if never viewed. */
    fun getSavedDayIndex(context: Context, courseIdentity: String): Int =
        prefs(context).getInt("$KEY_VIEW_DAY_PREFIX$courseIdentity", 0)

    /** Delete all view state for a course (e.g. when a course is removed). */
    fun clearCourseViewState(context: Context, courseIdentity: String) {
        prefs(context).edit().apply {
            remove("$KEY_VIEW_SEMESTER_PREFIX$courseIdentity")
            remove("$KEY_VIEW_WEEK_PREFIX$courseIdentity")
            remove("$KEY_VIEW_DAY_PREFIX$courseIdentity")
            apply()
        }
    }

    // ── Pull-to-refresh rate limit (24h cooldown) ───────────────────

    private const val KEY_LAST_PULL_REFRESH = "last_pull_refresh"

    /**
     * Returns the epoch-millis timestamp of the last pull-to-refresh,
     * or 0 if never performed.
     */
    fun getLastPullRefreshTime(context: Context): Long =
        prefs(context).getLong(KEY_LAST_PULL_REFRESH, 0L)

    /** Record that a pull-to-refresh just completed. */
    fun setLastPullRefreshTime(context: Context, time: Long) {
        prefs(context).edit().putLong(KEY_LAST_PULL_REFRESH, time).apply()
    }

    /**
     * Returns true if a pull-to-refresh is allowed (≥ 24h since last one).
     * The "Sync Now" button in Settings bypasses this check entirely.
     */
    fun canPullRefresh(context: Context): Boolean {
        val last = getLastPullRefreshTime(context)
        if (last == 0L) return true  // never refreshed → allowed
        return (System.currentTimeMillis() - last) >= PULL_REFRESH_COOLDOWN_MS
    }

    private val PULL_REFRESH_COOLDOWN_MS = 24 * 60 * 60 * 1000L

    // ── Empty weeks visibility toggle ───────────────────────────────

    private const val KEY_HIDE_EMPTY_WEEKS = "hide_empty_weeks"

    /** Whether the user wants to hide weeks with no events from the week dropdown. */
    fun shouldHideEmptyWeeks(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HIDE_EMPTY_WEEKS, true)

    fun setHideEmptyWeeks(context: Context, hide: Boolean) {
        prefs(context).edit().putBoolean(KEY_HIDE_EMPTY_WEEKS, hide).apply()
    }

    // ── Full-year week classification cache ─────────────────────────

    private const val KEY_ACTIVE_WEEKS_PREFIX = "active_weeks_"

    /**
     * Load the set of active week keys (yyyy-MM-dd) previously classified
     * for this course, or null if never classified.
     *
     * Empty weeks are derived: allAcademicWeeks - activeWeeks.
     */
    fun getActiveWeeks(context: Context, courseIdentity: String): Set<String>? {
        val set = prefs(context).getStringSet(KEY_ACTIVE_WEEKS_PREFIX + courseIdentity, null)
        return if (set != null && set.isNotEmpty()) set else null
    }

    /** Persist the active-week classification so next launch is instant. */
    fun saveActiveWeeks(context: Context, courseIdentity: String, activeWeeks: Set<String>) {
        prefs(context).edit()
            .putStringSet(KEY_ACTIVE_WEEKS_PREFIX + courseIdentity, activeWeeks)
            .apply()
    }

    /** Remove classification for a course (e.g. when cache is cleared). */
    fun clearActiveWeeks(context: Context, courseIdentity: String) {
        prefs(context).edit()
            .remove(KEY_ACTIVE_WEEKS_PREFIX + courseIdentity)
            .apply()
    }

    // ── Week Cache Polling (lazy-loading) ───────────────────────────

    private const val KEY_CACHED_WEEK_PREFIX = "cached_week_"

    /** Mark a specific (course, weekStart) pair as having been fetched. */
    internal fun markWeekCached(context: Context, courseIdentity: String, weekStart: String) {
        prefs(context).edit()
            .putLong("$KEY_CACHED_WEEK_PREFIX$courseIdentity|$weekStart", System.currentTimeMillis())
            .apply()
    }

    /** Check if a specific (course, weekStart) pair has been previously fetched. */
    internal fun isWeekCached(context: Context, courseIdentity: String, weekStart: String): Boolean {
        return prefs(context).contains("$KEY_CACHED_WEEK_PREFIX$courseIdentity|$weekStart")
    }
}
