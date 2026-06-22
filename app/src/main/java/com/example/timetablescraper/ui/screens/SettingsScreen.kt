package com.example.timetablescraper.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
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
import com.example.timetablescraper.api.SyncStrategy
import com.example.timetablescraper.api.cache.SavedCourseEntity
import com.example.timetablescraper.update.UpdateChecker
import com.example.timetablescraper.update.UpdateManager
import com.example.timetablescraper.worker.TimetableSyncWorker
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

    // ── State ──────────────────────────────────────────────────────────
    var autoSyncEnabled by remember {
        mutableStateOf(SyncPreferences.isAutoSyncEnabled(context))
    }
    var syncStrategy by remember {
        mutableStateOf(SyncPreferences.getSyncStrategy(context))
    }
    var customIntervalValue by remember {
        mutableStateOf(SyncPreferences.getCustomIntervalValue(context).toString())
    }
    var customIntervalUnit by remember {
        mutableStateOf(SyncPreferences.getCustomIntervalUnit(context))
    }
    var cacheEventCount by remember { mutableStateOf(0) }
    var cacheWeeksCount by remember { mutableStateOf(0) }
    var cacheCoursesCount by remember { mutableStateOf(0) }
    var newestCacheTime by remember { mutableStateOf<Long?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var savedCourses by remember { mutableStateOf<List<SavedCourseEntity>>(emptyList()) }
    var cachedCourseIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var cachedCourseNames by remember { mutableStateOf<List<com.example.timetablescraper.api.cache.TimetableDao.CourseNamePair>>(emptyList()) }
    var firstWeekExpanded by remember { mutableStateOf(false) }
    var sem2WeekExpanded by remember { mutableStateOf(false) }
    var uiTapKey by remember { mutableStateOf(0) }
    val uiRefresh = { uiTapKey++ }
    var updateCheckResult by remember { mutableStateOf<UpdateChecker.UpdateResult?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var isCheckingUpdate by remember { mutableStateOf(false) }

    /** Refresh the per-course cache list from the database. */
    fun refreshCachedCourses() {
        coroutineScope.launch {
            val dao = app.database.timetableDao()
            cachedCourseIds = dao.getDistinctCourseIdentities()
            cachedCourseNames = dao.getDistinctCourseNames()
        }
    }

    // ── Helper: refresh cache stats ──────────────────────────────────
    fun refreshCacheStats() {
        coroutineScope.launch {
            kotlinx.coroutines.delay(500)
            val dao = app.database.timetableDao()
            cacheEventCount = dao.count()
            cacheWeeksCount = dao.countDistinctWeeks()
            newestCacheTime = dao.getNewestFetchedAt()
        }
    }

    // ── Permission launcher for POST_NOTIFICATIONS (Android 13+) ───
    var showPermissionRationale by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Permission granted — trigger sync now
            TimetableSyncWorker.syncNow(context)
            refreshCacheStats()
        } else {
            showPermissionRationale = true
        }
    }

    // Load cache stats, saved courses, and cached course identities
    LaunchedEffect(Unit) {
        val dao = app.database.timetableDao()
        cacheEventCount = dao.count()
        cacheWeeksCount = dao.countDistinctWeeks()
        val courses = dao.getDistinctCourseIdentities()
        cacheCoursesCount = courses.size
        cachedCourseIds = courses
        cachedCourseNames = dao.getDistinctCourseNames()
        newestCacheTime = dao.getNewestFetchedAt()
        savedCourses = dao.getSavedCourses()
    }

    // ── Helpers ────────────────────────────────────────────────────────
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy 'at' HH:mm", Locale.getDefault()) }

    fun formatTimestamp(millis: Long?): String {
        if (millis == null || millis == 0L) return "Never"
        return dateFormat.format(Date(millis))
    }

    // ── Back handler: close dropdowns before navigating away ───────────
    BackHandler {
        when {
            firstWeekExpanded -> firstWeekExpanded = false
            sem2WeekExpanded -> sem2WeekExpanded = false
            else -> onBack()
        }
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

                    // ── Sync Strategy selector (Daily / Weekly / Custom) ──
                    if (autoSyncEnabled) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Sync Strategy",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Network calls are blocked while your cache is fresh.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        // Mode chips: Daily / Weekly / Custom
                        val modes = listOf(
                            SyncStrategy.Daily to "Daily (24h)",
                            SyncStrategy.Weekly to "Weekly (7d)",
                            SyncStrategy.Custom(1) to "Custom"
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            modes.forEach { (mode, label) ->
                                FilterChip(
                                    selected = when (syncStrategy) {
                                        is SyncStrategy.Daily -> mode is SyncStrategy.Daily
                                        is SyncStrategy.Weekly -> mode is SyncStrategy.Weekly
                                        is SyncStrategy.Custom -> mode is SyncStrategy.Custom
                                    },
                                    onClick = {
                                        syncStrategy = when (mode) {
                                            is SyncStrategy.Daily -> SyncStrategy.Daily
                                            is SyncStrategy.Weekly -> SyncStrategy.Weekly
                                            is SyncStrategy.Custom -> {
                                                val v = customIntervalValue.toIntOrNull() ?: 1
                                                val u = try {
                                                    java.util.concurrent.TimeUnit.valueOf(customIntervalUnit)
                                                } catch (_: Exception) {
                                                    java.util.concurrent.TimeUnit.HOURS
                                                }
                                                SyncStrategy.Custom(v.coerceAtLeast(1), u)
                                            }
                                            else -> SyncStrategy.Daily
                                        }
                                        SyncPreferences.setSyncStrategy(context, syncStrategy)
                                        TimetableSyncWorker.schedule(context)
                                    },
                                    label = { Text(label) }
                                )
                            }
                        }

                        // ── Custom interval fields (only when Custom is selected) ──
                        if (syncStrategy is SyncStrategy.Custom) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = customIntervalValue,
                                    onValueChange = { newVal ->
                                        // Only allow digits
                                        if (newVal.all { it.isDigit() } && newVal.length <= 5) {
                                            customIntervalValue = newVal
                                            val v = newVal.toIntOrNull()
                                            if (v != null && v > 0) {
                                                syncStrategy = SyncStrategy.Custom(v, java.util.concurrent.TimeUnit.DAYS)
                                                SyncPreferences.setSyncStrategy(context, syncStrategy)
                                                TimetableSyncWorker.schedule(context)
                                            }
                                        }
                                    },
                                    label = { Text("Days") },
                                    suffix = { Text("day(s)") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.width(100.dp)
                                )

                                // Always DAYS — no unit dropdown needed
                                Text(
                                    "= ${syncStrategy.ttlMillis() / 86_400_000} day(s) cache validity",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // ── Sync now button ────────────────────────────────────────
            Button(
                onClick = {
                    // On Android 13+, request POST_NOTIFICATIONS permission first
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context, Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        TimetableSyncWorker.syncNow(context)
                        refreshCacheStats()
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

                    // ── Hide empty weeks toggle ────────────────────────
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    var hideEmptyWeeks by remember {
                        mutableStateOf(SyncPreferences.shouldHideEmptyWeeks(context))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Hide empty weeks",
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (hideEmptyWeeks) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Weeks with no classes are removed from the week dropdown.",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (hideEmptyWeeks) MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                        Switch(
                            checked = hideEmptyWeeks,
                            onCheckedChange = { checked ->
                                hideEmptyWeeks = checked
                                SyncPreferences.setHideEmptyWeeks(context, checked)
                            }
                        )
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

            // ── Per-course cache management ────────────────────────────────
            if (cachedCourseIds.isNotEmpty()) {
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Cached Courses",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Delete individual course caches without wiping everything.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        cachedCourseIds.forEach { id ->
                            // Resolve course name: cached name → saved course → URL-decoded identity
                            val cachedPair = cachedCourseNames.firstOrNull { it.courseIdentity == id }
                            val saved = savedCourses.firstOrNull { it.identity == id }
                            val displayName = when {
                                cachedPair != null && cachedPair.courseName.isNotBlank() ->
                                    cachedPair.courseName.substringBefore("/")
                                saved != null ->
                                    saved.name.substringBefore("/")
                                else -> {
                                    try { java.net.URLDecoder.decode(id, "UTF-8").take(24) }
                                    catch (_: Exception) { id.take(24) }
                                }
                            }
                            val detail = saved?.programmeCode ?: ""

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    if (detail.isNotEmpty()) {
                                        Text(
                                            detail,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        coroutineScope.launch {
                                            app.repository.clearCacheForCourse(id)
                                            SyncPreferences.clearActiveWeeks(context, id)
                                            // Refresh stats and list
                                            val dao = app.database.timetableDao()
                                            cacheEventCount = dao.count()
                                            cacheWeeksCount = dao.countDistinctWeeks()
                                            cachedCourseIds = dao.getDistinctCourseIdentities()
                                            cachedCourseNames = dao.getDistinctCourseNames()
                                            cacheCoursesCount = cachedCourseIds.size
                                            newestCacheTime = dao.getNewestFetchedAt()
                                        }
                                    },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        Icons.Default.DeleteForever,
                                        contentDescription = "Delete cache for $displayName",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
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

            // ── Check for updates ───────────────────────────────────────
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Check for updates",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (isCheckingUpdate) "Checking…"
                                else "Current version: ${com.example.timetablescraper.BuildConfig.VERSION_NAME}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                isCheckingUpdate = true
                                coroutineScope.launch {
                                    val result = UpdateChecker.checkForUpdate()
                                    updateCheckResult = result
                                    isCheckingUpdate = false
                                    if (result.updateAvailable && result.downloadUrl != null) {
                                        showUpdateDialog = true
                                    }
                                }
                            },
                            enabled = !isCheckingUpdate
                        ) {
                            if (isCheckingUpdate) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(if (isCheckingUpdate) "Checking" else "Check")
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
                Text("This will delete all cached timetable data and refresh the per-course list. Your saved/bookmarked courses will not be affected.")
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
                            // Refresh the per-course cache list immediately
                            val dao = app.database.timetableDao()
                            cachedCourseIds = dao.getDistinctCourseIdentities()
                            cachedCourseNames = dao.getDistinctCourseNames()
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

    // ── Update available dialog ──────────────────────────────────────────
    if (showUpdateDialog && updateCheckResult != null) {
        val remoteVersion = updateCheckResult!!.remoteVersion ?: "latest"
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
                        val downloadUrl = updateCheckResult?.downloadUrl
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
                TextButton(onClick = { showUpdateDialog = false }) {
                    Text("Later")
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
