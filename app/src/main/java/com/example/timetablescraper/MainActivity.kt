package com.example.timetablescraper

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.example.timetablescraper.ui.screens.FatalErrorScreen
import com.example.timetablescraper.ui.screens.SearchScreen
import com.example.timetablescraper.ui.screens.SettingsScreen
import com.example.timetablescraper.ui.screens.TimetableScreen
import com.example.timetablescraper.ui.theme.TimetableScraperTheme
import com.example.timetablescraper.api.SearchResult
import com.example.timetablescraper.api.TimetableRepository
import com.example.timetablescraper.update.UpdateChecker
import com.example.timetablescraper.update.UpdateManager
import com.example.timetablescraper.update.UpdateReceiver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // ── Check for crash from previous session ───────────────
        val previousCrash = CrashHandler.getCrashInfo(this)
        if (previousCrash != null) {
            // Clear the crash flag immediately so "Try Again" works
            CrashHandler.clearCrashFlag(this)
        }

        // This state drives the FatalErrorScreen — it lives here
        // (outside setContent) so it survives composition restarts
        // during try-catch recovery within the same session.
        val crashState = mutableStateOf<CrashHandler.CrashInfo?>(previousCrash)

        setContent {
            TimetableScraperTheme {
                val currentCrash = crashState.value

                if (currentCrash != null) {
                    // ── Fatal recovery screen (from previous crash) ─
                    FatalErrorScreen(
                        crashInfo = currentCrash,
                        onClearAndRestart = {
                            val intent = packageManager
                                .getLaunchIntentForPackage(packageName)
                            if (intent != null) {
                                intent.addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TASK
                                )
                                finishAffinity()
                                startActivity(intent)
                                Runtime.getRuntime().exit(0)
                            }
                        },
                        onTryAgain = {
                            crashState.value = null
                        }
                    )
                } else {
                    // ── Normal application UI ────────────────────
                    MainApp()
                }
            }
        }
    }
}

// ── Star + Save helper ────────────────────────────────────────────
// Starring a course automatically saves it (bookmarks it) with a
// display name that includes the subgroup when known.
// Unstarring does NOT unsave — the course remains in saved/bookmarked.
private fun applyStarAndSave(
    context: Context,
    course: SearchResult,
    scope: CoroutineScope,
    repo: TimetableRepository,
    group: String? = null
) {
    SyncPreferences.setStarredCourse(context, course.identity, course.name, course.timetable_type_id)
    scope.launch {
        try {
            if (!repo.isCourseSaved(course.identity)) {
                val nameWithGroup = if (group != null) {
                    val short = group.split("/").drop(2).joinToString("/")
                    "${course.name} ($short)"
                } else course.name
                val courseToSave = course.copy(name = nameWithGroup)
                repo.saveCourse(courseToSave, group)
            }
        } catch (_: Exception) {
            // Save failure must not block the starring operation
        }
    }
}

@Composable
private fun MainApp() {
    val context = LocalContext.current

    // Protected coroutine scope with a fatal-error handler.
    // Catches unhandled exceptions from launched coroutines and persists
    // them so the next launch shows FatalErrorScreen.
    val coroutineScope = remember {
        CoroutineScope(
            Dispatchers.Main + SupervisorJob() +
            CoroutineExceptionHandler { _, throwable ->
                Log.e("MainApp", "Unhandled coroutine crash", throwable)
                // Persist crash info so the next launch shows FatalErrorScreen.
                context.getSharedPreferences("crash_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("crash_occurred", true)
                    .putString("crash_message", throwable.message ?: "Coroutine crash")
                    .putString("crash_stacktrace", throwable.stackTraceToString())
                    .putLong("crash_timestamp", System.currentTimeMillis())
                    .apply()
            }
        )
    }
    DisposableEffect(Unit) {
        onDispose { coroutineScope.cancel() }
    }

    val repository = TimetableApplication.instance.repository

    var starred by remember { mutableStateOf(SyncPreferences.getStarredCourse(context)) }
    val initialScreen = if (starred != null) "TIMETABLE" else "SEARCH"

    var currentScreen by remember { mutableStateOf(initialScreen) }
    var selectedCourse by remember {
        mutableStateOf(
            if (starred != null) SearchResult(
                name = starred!!.second, programme_code = "",
                identity = starred!!.first, type = "Programme",
                selection_id = "", timetable_type_id = starred!!.third
            ) else null
        )
    }
    var preselectedGroup by remember { mutableStateOf<String?>(null) }

    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var searchIsLoading by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var searchHasSearched by remember { mutableStateOf(false) }

    // ── Self-updating state ──────────────────────────────────────────────
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<UpdateChecker.UpdateResult?>(null) }
    var updateCheckDone by remember { mutableStateOf(false) }

    // Check for updates once on launch
    LaunchedEffect(Unit) {
        if (updateCheckDone) return@LaunchedEffect
        updateCheckDone = true
        try {
            val result = UpdateChecker.checkForUpdate()
            updateResult = result
            if (result.updateAvailable && result.downloadUrl != null) {
                showUpdateDialog = true
            }
        } catch (_: Exception) {
            // Update check failure is non-fatal — silently ignore
        }
    }

    // Register the download-complete receiver for the lifetime of this composable
    val updateReceiver = remember { UpdateReceiver() }
    DisposableEffect(Unit) {
        UpdateManager.registerReceiver(context, updateReceiver)
        onDispose {
            try { context.unregisterReceiver(updateReceiver) } catch (_: Exception) {}
        }
    }

    // ── Update available dialog ──────────────────────────────────────────
    if (showUpdateDialog && updateResult != null) {
        val remoteVersion = updateResult!!.remoteVersion ?: "latest"
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = { Text("Update Available") },
            text = {
                Text(
                    "A new version of TimeTable ($remoteVersion) is available. " +
                    "Would you like to download and install it now?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUpdateDialog = false
                        val downloadUrl = updateResult?.downloadUrl
                        if (downloadUrl != null) {
                            coroutineScope.launch {
                                UpdateManager.startDownload(context, downloadUrl)
                            }
                        }
                    }
                ) {
                    Text("Update Now")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showUpdateDialog = false }
                ) {
                    Text("Later")
                }
            }
        )
    }

    // ── Back navigation logic ───────────────────────────────────────
    fun goToStarredOrExit() {
        val s = starred
        if (s != null) {
            selectedCourse = SearchResult(
                name = s.second, programme_code = "",
                identity = s.first, type = "Programme",
                selection_id = "", timetable_type_id = s.third
            )
            preselectedGroup = null
            currentScreen = "TIMETABLE"
        } else {
            (context as? android.app.Activity)?.finish()
        }
    }

    fun goBackFromTimetable(isViewingStarred: Boolean) {
        if (isViewingStarred) {
            (context as? android.app.Activity)?.finish()
        } else if (starred != null) {
            goToStarredOrExit()
        } else {
            // No starred course — go to search, don't exit
            selectedCourse = null
            preselectedGroup = null
            currentScreen = "SEARCH"
        }
    }

    // ── System back handler ─────────────────────────────────────────
    BackHandler {
        when (currentScreen) {
            "SEARCH" -> goToStarredOrExit()
            "TIMETABLE" -> goBackFromTimetable(starred?.first == selectedCourse?.identity)
            "SETTINGS" -> goToStarredOrExit()
        }
    }

    when (currentScreen) {
        "SEARCH" -> {
            SearchScreen(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                results = searchResults,
                isLoading = searchIsLoading,
                errorMessage = searchError,
                hasSearched = searchHasSearched,
                onStateChange = { q, r, l, e, h ->
                    searchQuery = q; searchResults = r
                    searchIsLoading = l; searchError = e; searchHasSearched = h
                },
                onCourseSelected = { course, group ->
                    selectedCourse = course
                    preselectedGroup = group
                    currentScreen = "TIMETABLE"
                },
                onSettingsClick = { currentScreen = "SETTINGS" },
                hasStarredCourse = starred != null,
                onHomeClick = { goToStarredOrExit() }
            )
        }

        "TIMETABLE" -> {
            selectedCourse?.let { course ->
                val viewingStarred = starred?.first == course.identity
                var showReplaceStarDialog by remember { mutableStateOf(false) }
                var pendingStarCourse by remember { mutableStateOf<SearchResult?>(null) }

                // ── Replace-star confirmation dialog ─────────────────────────
                if (showReplaceStarDialog && pendingStarCourse != null) {
                    val currentStarredName = starred?.second ?: ""
                    val newName = pendingStarCourse!!.name
                    AlertDialog(
                        onDismissRequest = {
                            showReplaceStarDialog = false
                            pendingStarCourse = null
                        },
                        title = { Text("Replace Starred Course?") },
                        text = {
                            Text(
                                "\"$currentStarredName\" is currently your pinned course. " +
                                "Do you want to replace it with \"$newName\"?"
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val pc = pendingStarCourse!!
                                // Remove star from old course
                                SyncPreferences.setStarredCourse(context, null, null, null)
                                starred = null
                                // Remove old saved state before replacing
                                showReplaceStarDialog = false
                                pendingStarCourse = null
                                // Apply star + save for the new course
                                applyStarAndSave(context, pc, coroutineScope, repository)
                                starred = Triple(pc.identity, pc.name, pc.timetable_type_id)
                            }) {
                                Text("Replace")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showReplaceStarDialog = false
                                pendingStarCourse = null
                            }) {
                                Text("Cancel")
                            }
                        }
                    )
                }

                TimetableScreen(
                    selectedCourse = course,
                    preselectedGroup = preselectedGroup,
                    isStarred = viewingStarred,
                    onStarToggle = { star, group ->
                        if (star) {
                            // If another course is already starred, ask for confirmation
                            val existingStar = starred
                            if (existingStar != null && existingStar.first != course.identity) {
                                pendingStarCourse = course
                                showReplaceStarDialog = true
                            } else {
                                applyStarAndSave(context, course, coroutineScope, repository, group)
                                starred = Triple(course.identity, course.name, course.timetable_type_id)
                            }
                        } else {
                            // Unstar — remove star ONLY (keep saved/bookmark unchanged)
                            SyncPreferences.setStarredCourse(context, null, null, null)
                            starred = null
                        }
                    },
                    onSavedChanged = { isNowSaved, group ->
                        val currentStarredId = starred?.first
                        if (isNowSaved) {
                            // Saving a course → auto-star it (pin to home).
                            // If another course is already starred, ask for confirmation.
                            if (currentStarredId != null && currentStarredId != course.identity) {
                                pendingStarCourse = course
                                showReplaceStarDialog = true
                            } else if (currentStarredId == null) {
                                // No existing star → star this course directly
                                applyStarAndSave(context, course, coroutineScope, repository, group)
                                starred = Triple(course.identity, course.name, course.timetable_type_id)
                            }
                            // If currentStarredId == course.identity → already starred, nothing to do
                        } else {
                            // Unsaving a course → unstar it if it was the pinned one
                            if (currentStarredId == course.identity) {
                                SyncPreferences.setStarredCourse(context, null, null, null)
                                starred = null
                            }
                        }
                    },
                    onSearchClick = {
                        currentScreen = "SEARCH"
                        selectedCourse = null
                        preselectedGroup = null
                    },
                    onSettingsClick = { currentScreen = "SETTINGS" },
                    onBack = { goBackFromTimetable(viewingStarred) },
                    showBackArrow = !viewingStarred
                )
            }
        }

        "SETTINGS" -> {
            SettingsScreen(
                onBack = { goToStarredOrExit() },
                onSavedCourseSelected = { course, group ->
                    selectedCourse = course
                    preselectedGroup = group
                    currentScreen = "TIMETABLE"
                }
            )
        }
    }
}
