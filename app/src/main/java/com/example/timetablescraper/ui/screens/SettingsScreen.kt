package com.example.timetablescraper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import com.example.timetablescraper.api.TimetableUtils
import java.time.LocalDate
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.timetablescraper.SyncPreferences
import com.example.timetablescraper.TimetableApplication
import com.example.timetablescraper.api.SearchResult
import com.example.timetablescraper.api.cache.SavedCourseEntity
import com.example.timetablescraper.worker.TimetableSyncWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSavedCourseSelected: (SearchResult, String?) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val app = context.applicationContext as TimetableApplication
    val coroutineScope = rememberCoroutineScope()

    // Intercept system back gesture / button
    BackHandler(onBack = onBack)

    // ── State ──────────────────────────────────────────────────────────
    var autoSyncEnabled by remember {
        mutableStateOf(SyncPreferences.isAutoSyncEnabled(context))
    }
    var syncIntervalHours by remember {
        mutableStateOf(SyncPreferences.getSyncIntervalHours(context))
    }
    var cacheEventCount by remember { mutableStateOf(0) }
    var cacheWeeksCount by remember { mutableStateOf(0) }
    var cacheCoursesCount by remember { mutableStateOf(0) }
    var newestCacheTime by remember { mutableStateOf<Long?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var savedCourses by remember { mutableStateOf<List<SavedCourseEntity>>(emptyList()) }

    // Load cache stats and saved courses
    LaunchedEffect(Unit) {
        val dao = app.database.timetableDao()
        cacheEventCount = dao.count()
        cacheWeeksCount = dao.countDistinctWeeks()
        val courses = dao.getDistinctCourseIdentities()
        cacheCoursesCount = courses.size
        newestCacheTime = dao.getNewestFetchedAt()
        savedCourses = dao.getSavedCourses()
    }

    // ── Helpers ────────────────────────────────────────────────────────
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy 'at' HH:mm", Locale.getDefault()) }

    fun formatTimestamp(millis: Long?): String {
        if (millis == null || millis == 0L) return "Never"
        return dateFormat.format(Date(millis))
    }

    // ── UI ─────────────────────────────────────────────────────────────
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync & Cache Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Auto-refresh section ────────────────────────────────────
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Background Refresh",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Auto-refresh timetable",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Switch(
                            checked = autoSyncEnabled,
                            onCheckedChange = { enabled ->
                                autoSyncEnabled = enabled
                                SyncPreferences.setAutoSyncEnabled(context, enabled)
                                if (enabled) {
                                    TimetableSyncWorker.schedule(context)
                                } else {
                                    TimetableSyncWorker.cancel(context)
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Keeps your cached timetables up to date in the background.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Interval selector (only when enabled)
                    if (autoSyncEnabled) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Refresh every",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(6, 12, 24).forEach { hours ->
                                FilterChip(
                                    selected = syncIntervalHours == hours,
                                    onClick = {
                                        syncIntervalHours = hours
                                        SyncPreferences.setSyncIntervalHours(context, hours)
                                        TimetableSyncWorker.schedule(context)
                                    },
                                    label = {
                                        Text(
                                            when (hours) {
                                                6 -> "6h"
                                                12 -> "12h"
                                                24 -> "Daily"
                                                else -> "${hours}h"
                                            }
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // ── Sync now button ────────────────────────────────────────
            Button(
                onClick = {
                    TimetableSyncWorker.syncNow(context)
                    // Give a moment for the one-off worker to start, then reload stats
                    coroutineScope.launch {
                        delay(500)
                        val dao = app.database.timetableDao()
                        cacheEventCount = dao.count()
                        cacheWeeksCount = dao.countDistinctWeeks()
                        newestCacheTime = dao.getNewestFetchedAt()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Sync, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sync Now")
            }

            // Last sync info
            val lastManual = SyncPreferences.getLastManualSync(context)
            Text(
                "Last manual sync: ${formatTimestamp(lastManual)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ── First academic week ──────────────────────────────────────
            var firstWeekExpanded by remember { mutableStateOf(false) }
            var sem2WeekExpanded by remember { mutableStateOf(false) }
            val allWeeks = remember { TimetableUtils.generateAcademicWeeks() }
            var firstWeekStr by remember { mutableStateOf(SyncPreferences.getFirstWeekMonday(context)) }
            var sem2WeekStr by remember { mutableStateOf(SyncPreferences.getSem2StartMonday(context)) }
            val firstWeek = remember(firstWeekStr) {
                firstWeekStr?.let { try { LocalDate.parse(it) } catch (_: Exception) { null } }
            }
            val sem2Week = remember(sem2WeekStr) {
                sem2WeekStr?.let { try { LocalDate.parse(it) } catch (_: Exception) { null } }
            }
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Academic Year Start",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Pick the first week of your academic year. Empty weeks before this are hidden.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    ExposedDropdownMenuBox(
                        expanded = firstWeekExpanded,
                        onExpandedChange = { firstWeekExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = firstWeek?.let { TimetableUtils.formatWeekRange(it) } ?: "Auto-detect",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = firstWeekExpanded) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = firstWeekExpanded,
                            onDismissRequest = { firstWeekExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Auto-detect") },
                                onClick = {
                                    SyncPreferences.setFirstWeekMonday(context, null)
                                    firstWeekStr = null
                                    firstWeekExpanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                            allWeeks.forEach { monday ->
                                DropdownMenuItem(
                                    text = { Text(TimetableUtils.formatWeekRange(monday)) },
                                    onClick = {
                                        val dateStr = monday.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                                        SyncPreferences.setFirstWeekMonday(context, dateStr)
                                        firstWeekStr = dateStr
                                        firstWeekExpanded = false
                                    },
                                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Semester 2 Start",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Pick the first week of Semester 2. Weeks between semesters are hidden.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    ExposedDropdownMenuBox(
                        expanded = sem2WeekExpanded,
                        onExpandedChange = { sem2WeekExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = sem2Week?.let { TimetableUtils.formatWeekRange(it) } ?: "Auto-detect",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sem2WeekExpanded) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = sem2WeekExpanded,
                            onDismissRequest = { sem2WeekExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Auto-detect") },
                                onClick = {
                                    SyncPreferences.setSem2StartMonday(context, null)
                                    sem2WeekStr = null
                                    sem2WeekExpanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                            allWeeks.forEach { monday ->
                                DropdownMenuItem(
                                    text = { Text(TimetableUtils.formatWeekRange(monday)) },
                                    onClick = {
                                        val dateStr = monday.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                                        SyncPreferences.setSem2StartMonday(context, dateStr)
                                        sem2WeekStr = dateStr
                                        sem2WeekExpanded = false
                                    },
                                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                                )
                            }
                        }
                    }
                }
            }

            // ── Cache statistics section ───────────────────────────────
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Cached Data",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    StatRow("Events stored", "$cacheEventCount")
                    StatRow("Weeks saved", "$cacheWeeksCount")
                    StatRow("Courses cached", "$cacheCoursesCount")
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    StatRow("Newest cache", formatTimestamp(newestCacheTime))

                    Spacer(modifier = Modifier.height(16.dp))

                    // Clear cache button
                    OutlinedButton(
                        onClick = { showClearConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            Icons.Default.DeleteForever,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Clear All Cached Data")
                    }
                }
            }

            // ── Saved Timetables ─────────────────────────────────────────
            if (savedCourses.isNotEmpty()) {
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Saved Timetables",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Tap a course to jump to its timetable.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        var filterText by remember { mutableStateOf("") }
                        OutlinedTextField(
                            value = filterText,
                            onValueChange = { filterText = it },
                            placeholder = { Text("Filter saved courses...") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (filterText.isNotEmpty()) {
                                    IconButton(onClick = { filterText = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear")
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        val filteredCourses = if (filterText.isBlank()) savedCourses
                            else savedCourses.filter { it.name.contains(filterText, ignoreCase = true) || it.programmeCode.contains(filterText, ignoreCase = true) }

                        filteredCourses.forEach { saved ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        saved.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        saved.programmeCode,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(
                                        onClick = {
                                            val searchResult = SearchResult(
                                                name = saved.name,
                                                programme_code = saved.programmeCode,
                                                identity = saved.identity,
                                                type = "Programme",
                                                selection_id = "",
                                                timetable_type_id = saved.timetableTypeId
                                            )
                                            onSavedCourseSelected(searchResult, saved.group)
                                        }
                                    ) {
                                        Text("Open")
                                    }
                                    TextButton(
                                        onClick = {
                                            coroutineScope.launch {
                                                app.repository.removeCourse(saved.identity)
                                                savedCourses = app.database.timetableDao().getSavedCourses()
                                            }
                                        },
                                        colors = ButtonDefaults.textButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error
                                        )
                                    ) {
                                        Icon(
                                            Icons.Default.DeleteForever,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Remove")
                                    }
                                }
                            }
                            if (saved != savedCourses.last()) {
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }

            // ── Help text ──────────────────────────────────────────────
            Text(
                "Cached timetables let you view your schedule even when offline. " +
                "Auto-refresh keeps them updated in the background.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }

    // ── Clear confirmation dialog ──────────────────────────────────────
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear all cached data?") },
            text = {
                Text("This will delete all saved timetables. You'll need an internet connection to view them again.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        coroutineScope.launch {
                            app.repository.clearAll()
                            cacheEventCount = 0
                            cacheWeeksCount = 0
                            cacheCoursesCount = 0
                            newestCacheTime = null
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}
