package com.example.timetablescraper.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.timetablescraper.R
import com.example.timetablescraper.TimetableApplication
import com.example.timetablescraper.api.SearchResult
import com.example.timetablescraper.api.TimetableApiService
import com.example.timetablescraper.api.cache.SearchHistoryEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<SearchResult>,
    isLoading: Boolean,
    errorMessage: String?,
    hasSearched: Boolean,
    onStateChange: (query: String, results: List<SearchResult>, isLoading: Boolean, errorMessage: String?, hasSearched: Boolean) -> Unit,
    onCourseSelected: (SearchResult, group: String?) -> Unit,
    onSettingsClick: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as TimetableApplication
    val repository = app.repository
    val coroutineScope = rememberCoroutineScope()

    var showHistoryDialog by remember { mutableStateOf(false) }
    var historyEntries by remember { mutableStateOf<List<SearchHistoryEntity>>(emptyList()) }

    // Debounced search — uses the hoisted query from parent
    LaunchedEffect(query) {
        if (query.isBlank()) {
            onStateChange(query, emptyList(), false, null, false)
            return@LaunchedEffect
        }

        // Capture query at the moment this effect fired
        val capturedQuery = query

        // Debounce 300ms
        delay(300)

        // If query changed during debounce, abort — a newer LaunchedEffect handles it
        if (query != capturedQuery || query.isBlank()) return@LaunchedEffect

        onStateChange(query, emptyList(), true, null, true)

        try {
            val response = TimetableApiService.DEFAULT.searchCourses(query)
            // Double-check query still matches before updating state
            if (query == capturedQuery) {
                onStateChange(query, response.results, false, null, true)
                coroutineScope.launch { repository.recordSearch(query) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (query == capturedQuery) {
                onStateChange(query, emptyList(), false, "Connection error: ${e.message ?: "Search failed"}", true)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Image(
                        painter = painterResource(R.drawable.ic_tud_logo),
                        contentDescription = "TU Dublin Timetable",
                        modifier = Modifier.height(40.dp)
                    )
                },
                actions = {
                    IconButton(onClick = {
                        coroutineScope.launch {
                            historyEntries = repository.getRecentSearches()
                            showHistoryDialog = true
                        }
                    }) {
                        Icon(Icons.Default.History, contentDescription = "Search history")
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(horizontal = 16.dp)
        ) {
            // ── Search bar ──────────────────────────────────────────────
            OutlinedTextField(
                value = query,
                onValueChange = { onQueryChange(it) },
                placeholder = { Text("Type a course code, e.g. TU859") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = {
                            onStateChange("", emptyList(), false, null, false)
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ── State: Loading ──────────────────────────────────────────
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            // ── State: Error ────────────────────────────────────────────
            if (errorMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = errorMessage!!,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            // ── State: No results ───────────────────────────────────────
            if (hasSearched && !isLoading && results.isEmpty() && errorMessage == null) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No courses found for \"$query\"",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── State: Empty / idle ─────────────────────────────────────
            if (!hasSearched && query.isBlank()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Type a course code or name to search",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // ── Results list ────────────────────────────────────────────
            if (results.isNotEmpty() && !isLoading) {
                Text(
                    "${results.size} result(s) found",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Global set of expanded result identities — no artificial cap
                val expandedIdentities = remember { mutableStateOf(setOf<String>()) }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(results, key = { it.identity }) { result ->
                        val isExpanded = result.identity in expandedIdentities.value
                        var fetchedGroups by remember { mutableStateOf<List<String>>(emptyList()) }
                        var fetchingGroups by remember { mutableStateOf(false) }

                        Column {
                            CourseResultCard(
                                result = result,
                                onClick = {
                                    onCourseSelected(result, null)
                                }
                            )

                            // Compact expand arrow to fetch and show groups
                            IconButton(
                                onClick = {
                                    if (!isExpanded) {
                                        expandedIdentities.value = expandedIdentities.value + result.identity
                                        fetchingGroups = true
                                        coroutineScope.launch {
                                            try {
                                                // Try current week first; if empty (e.g. summer break),
                                                // fall back to a Monday mid-academic-year (October).
                                                val now = java.time.LocalDate.now()
                                                var monday = now.with(
                                                    java.time.temporal.TemporalAdjusters.previousOrSame(
                                                        java.time.DayOfWeek.MONDAY))
                                                var response = TimetableApiService.DEFAULT.fetchTimetable(
                                                    categoryTypeId = result.timetable_type_id,
                                                    identity = result.identity,
                                                    mondayDate = monday
                                                )
                                                if (response.events.isEmpty()) {
                                                    val academicYear = if (now.monthValue >= 9) now.year else now.year - 1
                                                    monday = java.time.LocalDate.of(academicYear, 10, 1)
                                                        .with(java.time.temporal.TemporalAdjusters.previousOrSame(
                                                            java.time.DayOfWeek.MONDAY))
                                                    response = TimetableApiService.DEFAULT.fetchTimetable(
                                                        categoryTypeId = result.timetable_type_id,
                                                        identity = result.identity,
                                                        mondayDate = monday
                                                    )
                                                }
                                                fetchedGroups = response.events
                                                    .flatMap { e ->
                                                        e.group.split("+").map { it.trim() }.filter { it.isNotBlank() }
                                                    }
                                                    .distinct()
                                                    .sorted()
                                            } catch (_: Exception) {
                                                fetchedGroups = emptyList()
                                            } finally {
                                                fetchingGroups = false
                                            }
                                        }
                                    } else {
                                        expandedIdentities.value = expandedIdentities.value - result.identity
                                        fetchedGroups = emptyList()
                                    }
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                if (fetchingGroups) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                } else {
                                    Text(if (isExpanded) "▾" else "▸", style = MaterialTheme.typography.titleSmall)
                                }
                            }

                            // Group chips
                            if (isExpanded && fetchedGroups.isNotEmpty()) {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                                ) {
                                    items(fetchedGroups.size) { i ->
                                        val grp = fetchedGroups[i]
                                        FilterChip(
                                            selected = false,
                                            onClick = { onCourseSelected(result, grp) },
                                            label = { Text(grp, style = MaterialTheme.typography.labelSmall) }
                                        )
                                    }
                                }
                            } else if (isExpanded && !fetchingGroups) {
                                Text(
                                    "No groups found for this course.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Search history dialog ───────────────────────────────────────────────
    if (showHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showHistoryDialog = false },
            title = { Text("Recent Searches") },
            text = {
                if (historyEntries.isEmpty()) {
                    Text("No recent searches yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column(
                        modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())
                    ) {
                        historyEntries.forEach { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showHistoryDialog = false
                                        onQueryChange(entry.query)
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.History, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(12.dp))
                                    Text(entry.query, style = MaterialTheme.typography.bodyLarge)
                                }
                                IconButton(onClick = {
                                    coroutineScope.launch {
                                        repository.clearSearchHistory()
                                        historyEntries = repository.getRecentSearches()
                                    }
                                }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Close, "Remove", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showHistoryDialog = false }) { Text("Close") } }
        )
    }
}

@Composable
private fun CourseResultCard(result: SearchResult, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = result.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AssistChip(
                    onClick = {},
                    label = { Text(result.programme_code, style = MaterialTheme.typography.labelSmall) }
                )
                AssistChip(
                    onClick = {},
                    label = { Text(result.type, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
    }
}
