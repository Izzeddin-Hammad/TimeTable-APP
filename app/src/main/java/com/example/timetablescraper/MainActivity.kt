package com.example.timetablescraper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.example.timetablescraper.ui.screens.SearchScreen
import com.example.timetablescraper.ui.screens.SettingsScreen
import com.example.timetablescraper.ui.screens.TimetableScreen
import com.example.timetablescraper.ui.theme.TimetableScraperTheme
import com.example.timetablescraper.api.SearchResult

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TimetableScraperTheme {
                MainApp()
            }
        }
    }
}

@Composable
private fun MainApp() {
    val context = LocalContext.current

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
                onSettingsClick = { currentScreen = "SETTINGS" }
            )
        }

        "TIMETABLE" -> {
            selectedCourse?.let { course ->
                val viewingStarred = starred?.first == course.identity
                TimetableScreen(
                    selectedCourse = course,
                    preselectedGroup = preselectedGroup,
                    isStarred = viewingStarred,
                    onStarToggle = { star ->
                        if (star) {
                            SyncPreferences.setStarredCourse(
                                context, course.identity, course.name, course.timetable_type_id)
                            starred = Triple(course.identity, course.name, course.timetable_type_id)
                        } else {
                            SyncPreferences.setStarredCourse(context, null, null, null)
                            starred = null
                        }
                    },
                    onSearchClick = {
                        currentScreen = "SEARCH"
                        selectedCourse = null
                        preselectedGroup = null
                    },
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
