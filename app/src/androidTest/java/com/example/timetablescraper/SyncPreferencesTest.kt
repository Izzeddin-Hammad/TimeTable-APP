package com.example.timetablescraper

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Instrumentation tests for SyncPreferences.
 */
class SyncPreferencesTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        // Reset prefs before each test
        val prefs = context.getSharedPreferences("timetable_sync_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun default_auto_sync_is_true() {
        assertTrue(SyncPreferences.isAutoSyncEnabled(context))
    }

    @Test
    fun set_and_get_auto_sync() {
        SyncPreferences.setAutoSyncEnabled(context, false)
        assertFalse(SyncPreferences.isAutoSyncEnabled(context))

        SyncPreferences.setAutoSyncEnabled(context, true)
        assertTrue(SyncPreferences.isAutoSyncEnabled(context))
    }

    @Test
    fun default_sync_interval_is_6_hours() {
        assertEquals(6, SyncPreferences.getSyncIntervalHours(context))
    }

    @Test
    fun set_and_get_sync_interval() {
        SyncPreferences.setSyncIntervalHours(context, 12)
        assertEquals(12, SyncPreferences.getSyncIntervalHours(context))

        SyncPreferences.setSyncIntervalHours(context, 24)
        assertEquals(24, SyncPreferences.getSyncIntervalHours(context))
    }

    @Test
    fun default_last_manual_sync_is_0() {
        assertEquals(0L, SyncPreferences.getLastManualSync(context))
    }

    @Test
    fun set_and_get_last_manual_sync() {
        val timestamp = System.currentTimeMillis()
        SyncPreferences.setLastManualSync(context, timestamp)
        assertEquals(timestamp, SyncPreferences.getLastManualSync(context))
    }

    @Test
    fun values_persist_across_multiple_accesses() {
        SyncPreferences.setAutoSyncEnabled(context, false)
        SyncPreferences.setSyncIntervalHours(context, 24)
        val ts = 1234567890L
        SyncPreferences.setLastManualSync(context, ts)

        // Re-read — ensure they persist
        assertFalse(SyncPreferences.isAutoSyncEnabled(context))
        assertEquals(24, SyncPreferences.getSyncIntervalHours(context))
        assertEquals(ts, SyncPreferences.getLastManualSync(context))
    }
}
