package com.example.timetablescraper

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Global uncaught exception handler that persists crash info for recovery
 * on next launch. Registered in [TimetableApplication.onCreate].
 *
 * ## Architecture
 *
 * ```
 * ┌─ Thread.uncaughtException ─────────────────────────────────────┐
 * │  1. Write crash info → SharedPreferences + marker file         │
 * │  2. Chain to previous handler (OS kills the process)           │
 * └───────────────────────────────────────────────────────────────┘
 *                          ↓  (process restarts)
 * ┌─ MainActivity.onCreate ────────────────────────────────────────┐
 * │  CrashHandler.hasCrashOccurred() == true                       │
 * │    → FatalErrorScreen("Something went wrong")                  │
 * │      → "Clear Cache & Restart" button wipes data & restarts    │
 * └───────────────────────────────────────────────────────────────┘
 * ```
 *
 * This is the outermost safety net.  Inner layers (coroutine exception
 * handlers, try-catch in LaunchedEffect, runCatching at scope roots)
 * prevent most crashes from reaching this handler at all.
 */
class CrashHandler private constructor(
    private val context: Context
) : Thread.UncaughtExceptionHandler {

    private val previousHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    init {
        if (previousHandler is CrashHandler) {
            throw IllegalStateException("CrashHandler already registered — call register() once")
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            Log.e(TAG, "Uncaught exception on thread: ${thread.name}", throwable)
            persistCrash(throwable)
        } catch (_: Exception) {
            // If the persister itself fails, there is nothing more we can do
        }
        // Chain to the OS default handler (which kills the process)
        previousHandler?.uncaughtException(thread, throwable)
    }

    private fun persistCrash(throwable: Throwable) {
        val timestamp = System.currentTimeMillis()
        val message = throwable.message ?: "Unknown error"
        val stacktrace = throwable.stackTraceToString()

        // Primary: SharedPreferences
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_CRASH_OCCURRED, true)
            .putString(KEY_CRASH_MESSAGE, message)
            .putString(KEY_CRASH_STACKTRACE, stacktrace)
            .putLong(KEY_CRASH_TIMESTAMP, timestamp)
            .apply()

        // Secondary: marker file (survives SharedPreferences corruption)
        try {
            File(context.filesDir, CRASH_MARKER_FILE).bufferedWriter().use { writer ->
                writer.write("Crash at: $timestamp\n")
                writer.write("Message: $message\n\n")
                writer.write(stacktrace)
            }
        } catch (_: Exception) { /* secondary persistence is best-effort */ }
    }

    companion object {
        private const val TAG = "CrashHandler"
        private const val PREFS_NAME = "crash_prefs"
        private const val KEY_CRASH_OCCURRED = "crash_occurred"
        private const val KEY_CRASH_MESSAGE = "crash_message"
        private const val KEY_CRASH_STACKTRACE = "crash_stacktrace"
        private const val KEY_CRASH_TIMESTAMP = "crash_timestamp"
        private const val CRASH_MARKER_FILE = ".crash_marker"

        /** Register the global handler. Call once from [TimetableApplication.onCreate]. */
        @JvmStatic
        fun register(context: Context) {
            val handler = CrashHandler(context.applicationContext)
            Thread.setDefaultUncaughtExceptionHandler(handler)
            Log.i(TAG, "Global uncaught exception handler registered")
        }

        /** True if a crash was recorded in a previous session. */
        @JvmStatic
        fun hasCrashOccurred(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_CRASH_OCCURRED, false)) return true
            return File(context.filesDir, CRASH_MARKER_FILE).exists()
        }

        /** Read the persisted crash info, or null if no crash is recorded. */
        @JvmStatic
        fun getCrashInfo(context: Context): CrashInfo? {
            if (!hasCrashOccurred(context)) return null
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return CrashInfo(
                message = prefs.getString(KEY_CRASH_MESSAGE, "Unknown error"),
                stacktrace = prefs.getString(KEY_CRASH_STACKTRACE, null),
                timestamp = prefs.getLong(KEY_CRASH_TIMESTAMP, 0L)
            )
        }

        /** Clear the crash flags after successful recovery or restart. */
        @JvmStatic
        fun clearCrashFlag(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
            try { File(context.filesDir, CRASH_MARKER_FILE).delete() } catch (_: Exception) { }
        }
    }

    data class CrashInfo(
        val message: String?,
        val stacktrace: String?,
        val timestamp: Long
    )
}
