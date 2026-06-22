package com.example.timetablescraper.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3Api

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.timetablescraper.SyncPreferences
import com.example.timetablescraper.TimetableApplication
import com.example.timetablescraper.worker.SyncNotificationManager
import com.example.timetablescraper.api.CacheSource
import com.example.timetablescraper.api.SearchResult
import com.example.timetablescraper.api.TimetableEvent
import com.example.timetablescraper.api.TimetableUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")

// ── Colour palette for event types ──────────────────────────────────────────

private val typeColors = mapOf(
    "Lab" to Color(0xFF2196F3),
    "Practical" to Color(0xFF2196F3),
    "Tutorial" to Color(0xFF4CAF50),
    "Tut" to Color(0xFF4CAF50),
    "Lecture" to Color(0xFF673AB7),
    "Lec" to Color(0xFF673AB7)
)

private fun colorForType(type: String): Color {
    return typeColors.entries.firstOrNull { (key, _) ->
        type.contains(key, ignoreCase = true)
    }?.value ?: Color(0xFF607D8B)
}

// ── Screen ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen(
    selectedCourse: SearchResult,
    onBack: () -> Unit,
    preselectedGroup: String? = null,
    isStarred: Boolean = false,
    onStarToggle: (Boolean) -> Unit = {},
    onSearchClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    showBackArrow: Boolean = true
) {
    // ── Get repository from Application ──────────────────────────────────
    val context = LocalContext.current
    val app = context.applicationContext as TimetableApplication
    val repository = app.repository

    // Intercept system back gesture / button
    BackHandler(onBack = onBack)

    // ── State (persisted across app restarts via SharedPreferences) ─────
    // Restore saved view state for this course (or use defaults for new courses)
    val savedWeekStr = SyncPreferences.getSavedWeek(context, selectedCourse.identity)
    val savedDayIdx = SyncPreferences.getSavedDayIndex(context, selectedCourse.identity)
    val savedSemester = SyncPreferences.getSavedSemester(context, selectedCourse.identity)
    val savedGroup = remember(selectedCourse.identity) {
        SyncPreferences.getLastGroup(context, selectedCourse.identity)
    }

    val hasSavedState = savedWeekStr != null

    // currentMonday: restored from saved prefs → Week 4 for new/uncached courses → overridden below
    var currentMonday by remember {
        mutableStateOf(
            savedWeekStr?.let { try { java.time.LocalDate.parse(it) } catch (_: Exception) { null } }
                ?: if (!hasSavedState) {
                    // For new/uncached courses: use the fallback semester start (October Monday)
                    // instead of the raw academic year start (September) which is often summer break.
                    // The LaunchedEffect(visibleWeeks) below will override this to the real
                    // first week once the background scanner finishes.
                    val allW = TimetableUtils.generateAcademicWeeks()
                    allW.firstOrNull { it.monthValue == 10 && it.dayOfMonth <= 7 }
                        ?: allW.firstOrNull()
                        ?: TimetableUtils.getCurrentMonday()
                } else {
                    TimetableUtils.getCurrentMonday()
                }
        )
    }
    var selectedDayIndex by remember { mutableStateOf(savedDayIdx) }
    var isLoading by remember { mutableStateOf(true) }
    var events by remember { mutableStateOf<List<TimetableEvent>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var cacheSource by remember { mutableStateOf<CacheSource?>(null) }
    var isSaved by remember { mutableStateOf(false) }
    var selectedGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var groupInitialised by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(selectedCourse.identity) {
        groupInitialised = false
    }
    LaunchedEffect(preselectedGroup, savedGroup, groupInitialised) {
        if (!groupInitialised) {
            selectedGroup = preselectedGroup ?: savedGroup
            groupInitialised = true
        }
    }
    var weekMenuExpanded by remember { mutableStateOf(false) }
    var emptyWeeks by rememberSaveable { mutableStateOf(setOf<String>()) }
    var activeWeeks by rememberSaveable { mutableStateOf(setOf<String>()) }
    var failedWeeks by remember { mutableStateOf(setOf<String>()) }
    var scanningWeeks by remember { mutableStateOf(false) }
    var scannedCount by remember { mutableStateOf(0) }
    var totalToScan by remember { mutableStateOf(0) }
    var userPickedWeek by remember { mutableStateOf(false) }

    // Incremented to trigger a force-refresh
    var refreshTrigger by remember { mutableStateOf(0) }

    val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri")
    val coroutineScope = rememberCoroutineScope()

    // Apply group filter — splits group string to match individual groups exactly
    val displayEvents = if (selectedGroup != null) {
        events.filter { event ->
            event.group.split("+").any { g -> g.trim() == selectedGroup }
        }
    } else events

    // Check if this course is saved/bookmarked
    LaunchedEffect(selectedCourse.identity) {
        isSaved = repository.isCourseSaved(selectedCourse.identity)
    }

    // Remember selected group so returning to this course restores it
    LaunchedEffect(selectedGroup) {
        if (selectedGroup != null) {
            SyncPreferences.setLastGroup(context, selectedCourse.identity, selectedGroup!!)
            android.util.Log.d("Timetable", "Group saved: course=${selectedCourse.identity} group=$selectedGroup")
        }
    }

    // ── Background week scanner (serial) ────────────────────────────
    // After the first successful load, scan all remaining weeks in the
    // background so empty ones disappear from the dropdown automatically.
    // Serial (1 worker): the API rate limiter allows 5 req/10s, so
    // parallelism would exhaust the bucket instantly on any course
    // with more than 5 weeks — leaving most weeks as "failed."
    LaunchedEffect(events.isNotEmpty(), selectedCourse.identity) {
        if (!events.isNotEmpty() || scanningWeeks) return@LaunchedEffect
        scanningWeeks = true
        val allWeeks = TimetableUtils.generateAcademicWeeks()
        val alreadyKnown = emptyWeeks.toSet() +
            setOf(currentMonday.format(DATE_FORMATTER))
        val toScan = allWeeks.filter {
            it.format(DATE_FORMATTER) !in alreadyKnown
        }
        totalToScan = toScan.size
        scannedCount = 0

        // Serial scan: the API rate limiter allows 5 requests per 10 seconds,
        // so a single worker stays safely within quota while a parallel scan
        // (4 workers) exhausts the bucket instantly on any course with >5 weeks.
        val parallelism = 1
        val semaphore = Semaphore(parallelism)
        coroutineScope {
            toScan.map { monday ->
                async {
                    semaphore.acquire()
                    try {
                        val result = repository.loadTimetable(
                            courseIdentity = selectedCourse.identity,
                            timetableTypeId = selectedCourse.timetable_type_id,
                            mondayDate = monday,
                            forceRefresh = false,
                            context = context,
                            courseName = selectedCourse.name
                        )
                        val key = monday.format(DATE_FORMATTER)
                        if (result.events.isEmpty()) {
                            emptyWeeks = emptyWeeks + key
                        } else {
                            activeWeeks = activeWeeks + key
                        }
                    } catch (_: Exception) {
                        // Network error — track as failed so it's hidden from
                        // the dropdown without being permanently marked empty
                        failedWeeks = failedWeeks + monday.format(DATE_FORMATTER)
                    } finally {
                        scannedCount++
                        semaphore.release()
                    }
                }
            }.awaitAll()
        }
        scanningWeeks = false
    }

    // Fetch timetable when course or week changes (or force-refresh triggered)
    LaunchedEffect(currentMonday, selectedCourse.identity, refreshTrigger) {
        isLoading = true
        errorMessage = null
        // Keep previous cacheSource visible during reload to avoid layout jump

        try {
            val forceRefresh = refreshTrigger > 0
            val result = repository.loadTimetable(
                courseIdentity = selectedCourse.identity,
                timetableTypeId = selectedCourse.timetable_type_id,
                mondayDate = currentMonday,
                forceRefresh = forceRefresh,
                context = context,
                courseName = selectedCourse.name
            )
            cacheSource = result.source

            // Post notification for pull-to-refresh — runs on Main dispatcher
            // (outside the IO block), so no IO contention.
            if (forceRefresh && result.source == CacheSource.NETWORK) {
                val eventCount = result.events.size
                val courseName = selectedCourse.name.substringBefore("/")
                SyncNotificationManager.postCompletionNotification(
                    context,
                    success = true,
                    summary = "Refreshed $eventCount event(s) for $courseName"
                )
            }

            val mondayStr = currentMonday.format(DATE_FORMATTER)
            // Events already deduplicated by repository — no second pass needed
            events = result.events
                .map { TimetableUtils.toUiEvent(it, mondayStr) }

            // Mark empty weeks so they're removed from the dropdown.
            // We do NOT mutate currentMonday here — let the user navigate
            // with the week picker. The displayMonday logic will jump the
            // visual selection to the first valid semester week if
            // currentMonday falls outside semester bounds.
            if (events.isNotEmpty()) {
                // Add the current week to activeWeeks so it shows in the dropdown immediately
                activeWeeks = activeWeeks + currentMonday.format(DATE_FORMATTER)
            }

            // Mark empty weeks so the visibleWeeks filter removes them later
            if (events.isEmpty() && !forceRefresh && !userPickedWeek) {
                val weekStr = currentMonday.format(DATE_FORMATTER)
                emptyWeeks = emptyWeeks + weekStr

                // In summer (Jun–Aug), bulk-mark all summer weeks as empty
                // so they're hidden from the dropdown, but keep currentMonday
                // as the actual current week. The displayMonday logic below
                // handles showing the first semester week in the picker.
                if (currentMonday.monthValue in 6..8) {
                    val prefMonday = SyncPreferences.getFirstWeekMonday(context)
                        ?.let { try { LocalDate.parse(it) } catch (_: Exception) { null } }
                    val targetMonday = prefMonday
                        ?: run {
                            val now = java.time.LocalDate.now()
                            val academicYear = if (now.monthValue >= 9) now.year else now.year - 1
                            java.time.LocalDate.of(academicYear, 10, 1)
                                .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                        }
                    // Mark summer weeks as empty so the hide-empty-weeks filter
                    // removes them — but don't jump currentMonday.
                    var m = currentMonday
                    while (m.isBefore(targetMonday)) {
                        emptyWeeks = emptyWeeks + m.format(DATE_FORMATTER)
                        m = m.plusWeeks(1)
                    }
                }
                // Don't advance currentMonday — user picks their week
            }

            // If we loaded stale cache and there's an error, show a warning
            if (result.source == CacheSource.CACHE_STALE && result.error != null) {
                errorMessage = "⚠️ Showing cached data (offline). Last update may be outdated."
            }
        } catch (e: CancellationException) {
            throw e  // Don't swallow compose navigation cancellation
        } catch (e: Exception) {
            val msg = e.message ?: ""
            errorMessage = when {
                msg.contains("Unable to resolve host", ignoreCase = true) ||
                msg.contains("timeout", ignoreCase = true) ||
                msg.contains("connect", ignoreCase = true) ->
                    "No internet connection. Check your network and try again."
                msg.contains("API error", ignoreCase = true) ->
                    "Server error: $msg"
                else ->
                    "Failed to load timetable. Try refreshing or check back later.\n($msg)"
            }
            events = emptyList()
            cacheSource = null
        } finally {
            isLoading = false
            userPickedWeek = false
        }
    }

            // When events change (new week loaded), keep the current day selected
            // if it still has events; otherwise jump to the first available day.
    LaunchedEffect(displayEvents) {
        if (displayEvents.isNotEmpty()) {
            val stillHasEvents = displayEvents.any { it.dayIndex == selectedDayIndex }
            if (!stillHasEvents) {
                val firstDay = displayEvents.minByOrNull { it.dayIndex }?.dayIndex ?: 0
                selectedDayIndex = firstDay.coerceIn(0, 4)
            }
        }
    }

    // ── UI ──────────────────────────────────────────────────────────────
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            selectedCourse.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1
                        )
                        Text(
                            selectedCourse.programme_code,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    if (showBackArrow) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    // ── Star (pin to home) ────────────────────────────
                    IconButton(onClick = { onStarToggle(!isStarred) }) {
                        Icon(
                            if (isStarred) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = if (isStarred) "Unpin from home" else "Pin to home",
                            tint = if (isStarred) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // ── Search ─────────────────────────────────────────
                    IconButton(onClick = onSearchClick) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = "Search courses"
                        )
                    }

                    // ── Bookmark button ───────────────────────────────────
                    IconButton(
                        onClick = {
                            // Optimistic UI update — toggle instantly, DB write follows
                            val newSaved = !isSaved
                            isSaved = newSaved
                            coroutineScope.launch {
                                val group = selectedGroup
                                val nameWithGroup = if (group != null) {
                                    val short = group.split("/")
                                        .drop(2).joinToString("/")
                                    "${selectedCourse.name} ($short)"
                                } else selectedCourse.name
                                val courseToSave = selectedCourse.copy(name = nameWithGroup)
                                if (newSaved) {
                                    repository.saveCourse(courseToSave, group)
                                } else {
                                    repository.removeCourse(selectedCourse.identity)
                                }
                            }
                        }
                    ) {
                        Icon(
                            if (isSaved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                            contentDescription = if (isSaved) "Remove from saved" else "Save timetable"
                        )
                    }

                    // ── Settings button ─────────────────────────────────────
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── Cache-status indicator ───────────────────────────────────
            if (cacheSource != null && events.isNotEmpty()) {
                CacheStatusBar(source = cacheSource!!)
            }

            // ── Week scanning progress (fixed height to avoid layout jump) ─
            Box(modifier = Modifier.fillMaxWidth().height(4.dp)) {
                if (scanningWeeks && totalToScan > 0) {
                    LinearProgressIndicator(
                        progress = { scannedCount.toFloat() / totalToScan },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
            }

            // ── Week picker (dropdown menu) ────────────────────────────
            val allAcademicWeeks = remember { TimetableUtils.generateAcademicWeeks() }

            // Auto-detect first week and semester boundary from scan results.
            // Treats both emptyWeeks (confirmed empty) and unscanned weeks as "empty"
            // for gap detection — only activeWeeks count as having classes.
            val autoFirstWeek = remember(activeWeeks) {
                allAcademicWeeks.firstOrNull { it.format(DATE_FORMATTER) in activeWeeks }
            }
            val autoSem2Start = remember(activeWeeks, emptyWeeks, failedWeeks, scanningWeeks) {
                // Only compute after scan finishes — partial data causes false gaps
                if (scanningWeeks) return@remember null
                val ordered = allAcademicWeeks.map { it.format(DATE_FORMATTER) }
                var gapLen = 0
                for (i in ordered.indices) {
                    val key = ordered[i]
                    // Only count a week as "gap" if it's confirmed empty (in emptyWeeks).
                    // Weeks not yet scanned (in neither set) are ignored to avoid
                    // false detection from partial scan data.
                    if (key in emptyWeeks) {
                        gapLen++
                    } else if (key in activeWeeks) {
                        if (gapLen >= 3) {
                            // Found 3+ confirmed-empty weeks followed by an active week
                            return@remember allAcademicWeeks[i]
                        }
                        gapLen = 0
                    }
                    // else: unknown week (not scanned yet) — ignore, don't break the gap
                }
                null
            }

            // Use manual config as override, fall back to auto-detection
            val manualFirst = remember {
                SyncPreferences.getFirstWeekMonday(context)
                    ?.let { try { LocalDate.parse(it) } catch (_: Exception) { null } }
            }
            val manualSem2 = remember {
                SyncPreferences.getSem2StartMonday(context)
                    ?.let { try { LocalDate.parse(it) } catch (_: Exception) { null } }
            }
            // Fallback when scan hasn't completed yet: use October for Sem1, late Jan for Sem2
            val fallbackFirst = allAcademicWeeks.firstOrNull {
                it.monthValue == 10 && it.dayOfMonth <= 7
            }
            val fallbackSem2 = allAcademicWeeks.firstOrNull {
                it.monthValue == 1 && it.dayOfMonth >= 20
            }
            val firstWeekDate = manualFirst ?: autoFirstWeek ?: fallbackFirst
            val sem2WeekDate = manualSem2 ?: autoSem2Start ?: fallbackSem2

            // Active semester: 0 = Sem 1, 1 = Sem 2 (restored from saved state)
            var activeSemester by remember { mutableStateOf(savedSemester) }

            // Filter weeks by semester
            val semesterWeeks = remember(allAcademicWeeks, firstWeekDate, sem2WeekDate, activeSemester) {
                val from = if (activeSemester == 0) firstWeekDate else sem2WeekDate
                val to = if (activeSemester == 0) sem2WeekDate?.minusWeeks(1) else null
                allAcademicWeeks.filter { w ->
                    (from == null || !w.isBefore(from)) && (to == null || !w.isAfter(to))
                }
            }
            val visibleWeeks = remember(
                allAcademicWeeks, emptyWeeks, failedWeeks, activeSemester,
                firstWeekDate, sem2WeekDate
            ) {
                val semStart = if (activeSemester == 0) firstWeekDate else sem2WeekDate
                val semEnd = if (activeSemester == 0) sem2WeekDate?.minusWeeks(1) else null
                // Show all weeks within the semester, progressively hiding
                // empty weeks (confirmed no events) and failed weeks
                // (rate-limited / network error, need a retry).
                allAcademicWeeks.filter { w ->
                    val key = w.format(DATE_FORMATTER)
                    (semStart == null || !w.isBefore(semStart)) &&
                    (semEnd == null || !w.isAfter(semEnd)) &&
                    key !in emptyWeeks &&
                    key !in failedWeeks
                }
            }

            // Always jump to the first visible week if currentMonday is not visible
            val displayMonday = if (visibleWeeks.isNotEmpty() && currentMonday !in visibleWeeks)
                visibleWeeks.firstOrNull() ?: currentMonday
            else currentMonday

            // For new courses (no saved state), jump to the first visible (non-empty) week.
            // Re-fires whenever visibleWeeks updates (e.g. scan finishes populating activeWeeks)
            // so the user always lands on Week 1 after the scan completes.
            LaunchedEffect(visibleWeeks) {
                if (!hasSavedState && !userPickedWeek && visibleWeeks.isNotEmpty()) {
                    currentMonday = visibleWeeks.first()
                }
            }

            // When user manually switches semester tab, reset to that semester's first non-empty week
            LaunchedEffect(activeSemester) {
                if (visibleWeeks.isNotEmpty()) {
                    if (!hasSavedState || currentMonday !in visibleWeeks) {
                        currentMonday = visibleWeeks.first()
                    }
                }
            }

            // ── Persist view state on any change ───────────────────────────
            LaunchedEffect(
                currentMonday, selectedDayIndex,
                activeSemester, selectedCourse.identity
            ) {
                SyncPreferences.saveCourseViewState(
                    context,
                    selectedCourse.identity,
                    semester = activeSemester,
                    weekStart = currentMonday.format(DATE_FORMATTER),
                    dayIndex = selectedDayIndex
                )
            }

            // ── Semester tabs (wide, at top) ──────────────────────────────
            if (sem2WeekDate != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = activeSemester == 0,
                        onClick = { activeSemester = 0 },
                        label = { Text("Semester 1") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = activeSemester == 1,
                        onClick = { activeSemester = 1 },
                        label = { Text("Semester 2") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ExposedDropdownMenuBox(
                    expanded = weekMenuExpanded,
                    onExpandedChange = { weekMenuExpanded = it },
                    modifier = Modifier.weight(1f)
                ) {
                    val weekNum = visibleWeeks.indexOf(displayMonday).let { if (it >= 0) it + 1 else null }
                    val label = if (weekNum != null) "W$weekNum · ${TimetableUtils.formatWeekRange(displayMonday)}"
                                else TimetableUtils.formatWeekRange(displayMonday)
                    OutlinedTextField(
                        value = label,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(
                                expanded = weekMenuExpanded,
                                modifier = Modifier.size(48.dp)
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = weekMenuExpanded,
                        onDismissRequest = { weekMenuExpanded = false }
                    ) {
                        visibleWeeks.forEachIndexed { index, monday ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Week ${index + 1} · ${TimetableUtils.formatWeekRange(monday)}",
                                        fontWeight = if (monday == currentMonday) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                            onClick = {
                                weekMenuExpanded = false
                                currentMonday = monday
                                userPickedWeek = true
                            },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                        }
                    }
                }
            }

            // ── Group filter (only show if there are multiple groups) ──────
            val availableGroups = remember(events) {
                events.flatMap { it.group.split("+").map { g -> g.trim() } }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted()
            }
            if (availableGroups.size > 1) {
                var groupMenuExpanded by remember { mutableStateOf(false) }

                ExposedDropdownMenuBox(
                    expanded = groupMenuExpanded,
                    onExpandedChange = { groupMenuExpanded = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    OutlinedTextField(
                        value = selectedGroup ?: "All groups",
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(
                                expanded = groupMenuExpanded,
                                modifier = Modifier.size(48.dp)
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = groupMenuExpanded,
                        onDismissRequest = { groupMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("All groups", fontWeight = if (selectedGroup == null) FontWeight.Bold else FontWeight.Normal) },
                            onClick = {
                                groupMenuExpanded = false
                                selectedGroup = null
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                        )
                        availableGroups.forEach { group ->
                            val isPinned = selectedGroup == group
                            DropdownMenuItem(
                                text = { Text(group, fontWeight = if (isPinned) FontWeight.Bold else FontWeight.Normal) },
                                onClick = {
                                    groupMenuExpanded = false
                                    selectedGroup = group
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                                trailingIcon = {
                                    IconButton(onClick = {
                                        SyncPreferences.setLastGroup(context, selectedCourse.identity, group)
                                    }) {
                                        Icon(
                                            if (isPinned) Icons.Filled.Star else Icons.Filled.StarBorder,
                                            "Set as default",
                                            Modifier.size(20.dp),
                                            tint = if (isPinned) Color(0xFFFFD700) else LocalContentColor.current
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // ── Day tabs ────────────────────────────────────────────────
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(days.size) { index ->
                    val dayName = days[index]
                    val isSelected = selectedDayIndex == index
                    val dateStr = TimetableUtils.formatDayDate(currentMonday, index)

                    DayTabItem(
                        day = dayName,
                        date = dateStr,
                        isSelected = isSelected,
                        hasEvents = displayEvents.any { it.dayIndex == index },
                        onClick = { selectedDayIndex = index }
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(top = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            // ── Content area ────────────────────────────────────────────
            val contentState = when {
                isLoading -> "loading"
                errorMessage != null && displayEvents.isEmpty() -> "error"
                displayEvents.isEmpty() -> "empty"
                else -> "events"
            }

            Crossfade(
                targetState = contentState,
                animationSpec = tween(300),
                label = "content"
            ) { state ->
                when (state) {
                    "loading" -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                "error" -> {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = errorMessage ?: "An unexpected error occurred",
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                OutlinedButton(onClick = {
                                    refreshTrigger++
                                }) {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Retry")
                                }
                            }
                        }
                    }
                }

                "events" -> {
                    val filteredEvents = displayEvents.filter { it.dayIndex == selectedDayIndex }
                    EventsContent(days, selectedDayIndex, filteredEvents)
                }

                "empty" -> {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "No classes this week",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Try navigating to an academic term week.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
    }
}

// ── Cache status bar / Offline warning ──────────────────────────────────────

/**
 * Enhanced cache status indicator that doubles as an offline warning banner.
 *
 * - [CacheSource.CACHE_FRESH]: green, compact — shows "Loaded from cache"
 * - [CacheSource.CACHE_STALE]: orange/amber, **prominent** — shows
 *   "⚠️ Offline / Cached Mode" with explanation text. Indicates the app
 *   could not reach the server and is showing potentially outdated data.
 * - [CacheSource.NETWORK]: blue, compact — shows "Updated from server"
 */
@Composable
private fun CacheStatusBar(source: CacheSource) {
    val (text, color) = when (source) {
        CacheSource.CACHE_FRESH -> "📦 Loaded from cache" to Color(0xFF4CAF50)
        CacheSource.CACHE_STALE -> "⚠️ Offline / Cached Mode — data may be outdated" to Color(0xFFFF9800)
        CacheSource.NETWORK -> "🌐 Updated from server" to Color(0xFF2196F3)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = when (source) {
            CacheSource.CACHE_STALE -> Color(0xFFFF9800).copy(alpha = 0.15f)
            else -> color.copy(alpha = 0.1f)
        }
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Medium
            )
            if (source == CacheSource.CACHE_STALE) {
                Text(
                    text = "Network request failed — showing last cached version. " +
                            "Your sync interval may still be active.",
                    style = MaterialTheme.typography.labelSmall,
                    color = color.copy(alpha = 0.7f),
                    fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.85f
                )
            }
        }
    }
}

// ── Events content (shared between states) ──────────────────────────────────

@Composable
private fun EventsContent(
    days: List<String>,
    selectedDayIndex: Int,
    filteredEvents: List<TimetableEvent>
) {
    if (filteredEvents.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "No classes on ${days[selectedDayIndex]}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(filteredEvents, key = { "${it.start}-${it.moduleCode}-${it.type}-${it.room}" }) { event ->
                TimetableEventCard(event = event)
            }
        }
    }
}

// ── Day tab ─────────────────────────────────────────────────────────────────

@Composable
private fun DayTabItem(
    day: String,
    date: String,
    isSelected: Boolean,
    hasEvents: Boolean,
    onClick: () -> Unit
) {
    val bgColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }

    val textColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Text(
            day,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = textColor
        )
        Text(
            date,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = textColor
        )
        if (hasEvents && !isSelected) {
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
            )
        }
    }
}

// ── Event card ──────────────────────────────────────────────────────────────

@Composable
private fun TimetableEventCard(event: TimetableEvent) {
    val accentColor = colorForType(event.type)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(IntrinsicSize.Min)
    ) {
        // Accent bar
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(accentColor)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Module code + type badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        event.moduleCode,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = accentColor
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = accentColor.copy(alpha = 0.12f)
                    ) {
                        Text(
                            event.type,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = accentColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Title
                Text(
                    event.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Time
                Text(
                    event.timeRange,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Lecturer
                Text(
                    "\uD83D\uDC68\u200D\uD83C\uDFEB ${event.lecturer}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Room
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = accentColor
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        event.room,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Group (if present)
                if (event.group.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        event.group,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}
