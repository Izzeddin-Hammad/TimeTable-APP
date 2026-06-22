# TUD-TimeTable-APP — Technical Architecture & Feature Specification

**Codebase:** `com.example.timetablescraper`  
**Version:** 1.14 (target SDK 36, min SDK 26)  
**App Name:** TimeTable  
**Repository:** `Izzeddin-Hammad/TUD-TimeTable-APP`

---

## Section 1: Core Navigation & State Architecture

### 1.1 `MainActivity.kt` — The Root Orchestrator

**File:** `app/src/main/java/com/example/timetablescraper/MainActivity.kt`

`MainActivity` extends `ComponentActivity` and, in `onCreate`, calls `enableEdgeToEdge()` and invokes `setContent { TimetableScraperTheme { MainApp() } }`. It is declared in `AndroidManifest.xml` as the launcher activity (`android.intent.action.MAIN` / `android.intent.category.LAUNCHER`).

`MainActivity` is **not** a navigation host in the Jetpack Navigation or Compose Navigation sense. Instead, it implements a **custom single-activity state machine** via a `when(currentScreen)` conditional composable, with all screen-level state hoisted into `MainApp()`.

### 1.2 `MainApp()` — The State Machine Root Composable

`MainApp()` is a private `@Composable` function defined inside `MainActivity.kt`. It owns every hoisted state variable that controls navigation and the cross-cutting data shared between screens.

#### 1.2.1 Hoisted State Variables (Complete Catalog)

| Variable | Type | Default | Purpose | Written By | Read By |
|---|---|---|---|---|---|
| `starred` | `Triple<String,String,String>?` | `SyncPreferences.getStarredCourse(context)` | The pinned/starred course (identity, name, timetable_type_id) | `onStarToggle` callback (TimetableScreen) | All; determines initial screen; `goToStarredOrExit`; `goBackFromTimetable` |
| `currentScreen` | `String` | `if (starred != null) "TIMETABLE" else "SEARCH"` | Active screen: `"SEARCH"`, `"TIMETABLE"`, `"SETTINGS"` | SearchScreen `onCourseSelected`, SettingsScreen `onSavedCourseSelected`, `goToStarredOrExit`, `goBackFromTimetable`, TimetableScreen `onSearchClick`/`onSettingsClick` | `when(currentScreen)` — all three branches |
| `selectedCourse` | `SearchResult?` | Starred → reconstructed `SearchResult` from `starred` triple; otherwise `null` | The course whose timetable is being viewed | `onCourseSelected` (SearchScreen), `onSavedCourseSelected` (SettingsScreen), `goToStarredOrExit`, `goBackFromTimetable` | TimetableScreen (as `selectedCourse`) |
| `preselectedGroup` | `String?` | `null` | A group filter to apply immediately when the timetable loads | `onCourseSelected` (SearchScreen), `onSavedCourseSelected` (SettingsScreen) | TimetableScreen (as `preselectedGroup`) |
| `searchQuery` | `String` | `""` | Current search query text | `onQueryChange` (SearchScreen) | SearchScreen (as `query`) |
| `searchResults` | `List<SearchResult>` | `emptyList()` | Current search results | `onStateChange` (SearchScreen) | SearchScreen (as `results`) |
| `searchIsLoading` | `Boolean` | `false` | Whether a search is in-flight | `onStateChange` (SearchScreen) | SearchScreen (as `isLoading`) |
| `searchError` | `String?` | `null` | Error message from last search | `onStateChange` (SearchScreen) | SearchScreen (as `errorMessage`) |
| `searchHasSearched` | `Boolean` | `false` | Whether at least one search has been performed | `onStateChange` (SearchScreen) | SearchScreen (as `hasSearched`) |
| `showUpdateDialog` | `Boolean` | `false` | Whether the "Update Available" AlertDialog is showing | `LaunchedEffect(Unit)`, dialog buttons | AlertDialog (update available prompt) |
| `updateResult` | `UpdateChecker.UpdateResult?` | `null` | Result of the GitLab release check | `LaunchedEffect(Unit)` | AlertDialog (for version string and `downloadUrl`) |
| `updateCheckDone` | `Boolean` | `false` | Guard flag — ensures the update check runs exactly once | `LaunchedEffect(Unit)` | `LaunchedEffect(Unit)` guard |

#### 1.2.2 `when(currentScreen)` Rendering Matrix

```
currentScreen == "SEARCH"   ──► SearchScreen(...)
currentScreen == "TIMETABLE" ──► selectedCourse?.let { TimetableScreen(...) }
currentScreen == "SETTINGS"  ──► SettingsScreen(...)
```

##### "SEARCH" Branch

```
SearchScreen(
    query = searchQuery,
    onQueryChange = { searchQuery = it },
    results = searchResults,
    isLoading = searchIsLoading,
    errorMessage = searchError,
    hasSearched = searchHasSearched,
    onStateChange = { q, r, l, e, h -> ... },
    onCourseSelected = { course, group -> selectedCourse = course; preselectedGroup = group; currentScreen = "TIMETABLE" },
    onSettingsClick = { currentScreen = "SETTINGS" }
)
```

**Callbacks:**
- `onQueryChange(String)` — replaces `searchQuery`
- `onStateChange(q, r, l, e, h)` — bulk-replaces all five search-related hoisted variables
- `onCourseSelected(SearchResult, String?)` — transitions to `"TIMETABLE"`
- `onSettingsClick` — transitions to `"SETTINGS"`

##### "TIMETABLE" Branch

```
TimetableScreen(
    selectedCourse = course,
    preselectedGroup = preselectedGroup,
    isStarred = viewingStarred,
    onStarToggle = { star -> ... SyncPreferences.setStarredCourse(...); starred = ... },
    onSearchClick = { currentScreen = "SEARCH"; selectedCourse = null; preselectedGroup = null },
    onSettingsClick = { currentScreen = "SETTINGS" },
    onBack = { goBackFromTimetable(viewingStarred) },
    showBackArrow = !viewingStarred
)
```

**Callbacks:**
- `onStarToggle(Boolean)` — writes/clears the starred course to `SyncPreferences` and updates the `starred` hoisted variable
- `onSearchClick` — navigates back to `"SEARCH"` and clears `selectedCourse`
- `onSettingsClick` — navigates to `"SETTINGS"`
- `onBack` — delegates to `goBackFromTimetable`
- `showBackArrow` — derived: hidden when viewing the starred course (pinned to home)

##### "SETTINGS" Branch

```
SettingsScreen(
    onBack = { goToStarredOrExit() },
    onSavedCourseSelected = { course, group -> selectedCourse = course; preselectedGroup = group; currentScreen = "TIMETABLE" }
)
```

**Callbacks:**
- `onBack` — delegates to `goToStarredOrExit()`
- `onSavedCourseSelected(SearchResult, String?)` — transitions to `"TIMETABLE"`

#### 1.2.3 `BackHandler` Routing Matrix

```
BackHandler {
    when (currentScreen) {
        "SEARCH"   -> goToStarredOrExit()
        "TIMETABLE" -> goBackFromTimetable(starred?.first == selectedCourse?.identity)
        "SETTINGS"  -> goToStarredOrExit()
    }
}
```

**`goToStarredOrExit()` logic:**
1. If `starred != null` → reconstruct `selectedCourse` from the `starred` triple, set `preselectedGroup = null`, set `currentScreen = "TIMETABLE"` (return to starred course).
2. Else → `(context as Activity).finish()` (exit the app).

**`goBackFromTimetable(isViewingStarred: Boolean)` logic:**
1. If `isViewingStarred` → `finish()` (exit).
2. Else if `starred != null` → `goToStarredOrExit()` (return to pinned course).
3. Else → clear `selectedCourse` and `preselectedGroup`, set `currentScreen = "SEARCH"`.

### 1.3 Self-Updating Entry Point

A `LaunchedEffect(Unit)` block guarded by `updateCheckDone` calls `UpdateChecker.checkForUpdate()` exactly once on first composition. If an update is available (`result.updateAvailable && result.downloadUrl != null`), it sets `showUpdateDialog = true`.

A `DisposableEffect(Unit)` registers and unregisters the `UpdateReceiver` BroadcastReceiver via `UpdateManager.registerReceiver`.

---

## Section 2: Component & Screen Directory

### 2.1 `SearchScreen.kt`

**Physical Path:** `app/src/main/java/com/example/timetablescraper/ui/screens/SearchScreen.kt`  
**Lines:** 399

#### State Dependencies (All Injected from `MainApp()`)

| Parameter | Type | Source |
|---|---|---|
| `query` | `String` | Hoisted from `MainApp().searchQuery` |
| `onQueryChange` | `(String) -> Unit` | Replaces `searchQuery` at root |
| `results` | `List<SearchResult>` | Hoisted from `MainApp().searchResults` |
| `isLoading` | `Boolean` | Hoisted from `MainApp().searchIsLoading` |
| `errorMessage` | `String?` | Hoisted from `MainApp().searchError` |
| `hasSearched` | `Boolean` | Hoisted from `MainApp().searchHasSearched` |
| `onStateChange` | `(String, List<SearchResult>, Boolean, String?, Boolean) -> Unit` | Bulk-set of all search state at root |
| `onCourseSelected` | `(SearchResult, String?) -> Unit` | Transitions to `"TIMETABLE"` |
| `onSettingsClick` | `() -> Unit` | Transitions to `"SETTINGS"` |

#### Sub-Components

| Composable | Visibility | Purpose |
|---|---|---|
| **TopAppBar** | Always | TU Dublin logo (Image from `R.drawable.ic_tud_logo`), History icon button, Settings icon button |
| **OutlinedTextField** | Always | Search bar with leading search icon, trailing clear (Close) icon, `singleLine`, `RoundedCornerShape(12.dp)` |
| **CircularProgressIndicator** | Only when `isLoading == true` | Loading spinner centered |
| **Error Card** | Only when `errorMessage != null` | `Card` styled with `errorContainer` colors |
| **"No results" Text** | Only when `hasSearched && !isLoading && results.isEmpty() && errorMessage == null` | Centered "No courses found" message |
| **Idle state** | Only when `!hasSearched && query.isBlank()` | Search icon + "Type a course code..." hint |
| **Results list (LazyColumn)** | Only when `results.isNotEmpty() && !isLoading` | Each item: `CourseResultCard` + expandable group-picker |
| **History AlertDialog** | Toggled via `showHistoryDialog` | Lists recent searches, each clickable to re-populate query |

#### Internal State

| Variable | Type | Default |
|---|---|---|
| `showHistoryDialog` | `Boolean` | `false` |
| `historyEntries` | `List<SearchHistoryEntity>` | `emptyList()` |
| `expandedIdentities` (inside results) | `MutableState<Set<String>>` | `emptySet()` (per-result expanded/fetched groups) |
| `fetchedGroups` (per result) | `List<String>` | `emptyList()` |
| `fetchingGroups` (per result) | `Boolean` | `false` |

#### Core Logic: Debounced Search (`LaunchedEffect(query)`)

1. If `query.isBlank()` → clear state immediately.
2. Capture `query` as `capturedQuery`.
3. `delay(300)` → debounce 300ms.
4. If `query != capturedQuery` → abort (stale request discarded).
5. Set loading state.
6. Call `TimetableApiService.DEFAULT.searchCourses(query)`.
7. Double-check `query == capturedQuery` → update results, record search in repository.
8. On exception → set error state (only if query still matches).

**Group expansion flow** (per result row):
1. User taps expand arrow → `expandedIdentities` is updated.
2. `fetchTimetable` called for current Monday; if empty, falls back to an October Monday.
3. Parses unique groups from committed response events.
4. Displays `FilterChip` rows for each group; tapping calls `onCourseSelected` with that group.

### 2.2 `TimetableScreen.kt`

**Physical Path:** `app/src/main/java/com/example/timetablescraper/ui/screens/TimetableScreen.kt`  
**Lines:** 1125 — the largest file in the codebase.

#### State Dependencies

| Parameter | Type | Source |
|---|---|---|
| `selectedCourse` | `SearchResult` | Hoisted from `MainApp().selectedCourse` |
| `onBack` | `() -> Unit` | `goBackFromTimetable` |
| `preselectedGroup` | `String?` | Hoisted from `MainApp().preselectedGroup` |
| `isStarred` | `Boolean` | Derived: `starred?.first == course.identity` |
| `onStarToggle` | `(Boolean) -> Unit` | Writes to SyncPreferences + `starred` at root |
| `onSearchClick` | `() -> Unit` | Transitions to `"SEARCH"` |
| `onSettingsClick` | `() -> Unit` | Transitions to `"SETTINGS"` |
| `showBackArrow` | `Boolean` | Derived: `!isStarred` |

#### Internal State (Persisted and Ephemeral)

| Variable | Type | Persisted? | Purpose |
|---|---|---|---|
| `currentMonday` | `LocalDate` | Via saved prefs | The Monday of the currently viewed week |
| `selectedDayIndex` | `Int` | Via saved prefs | The selected day tab (0=Mon…4=Fri) |
| `isLoading` | `Boolean` | — | Loading indicator |
| `events` | `List<TimetableEvent>` | — | The currently loaded timetable events |
| `errorMessage` | `String?` | — | Error message from last load |
| `cacheSource` | `CacheSource?` | — | Source: CACHE_FRESH / CACHE_STALE / NETWORK |
| `isSaved` | `Boolean` | — | Whether the course is bookmarked |
| `selectedGroup` | `String?` | Via `rememberSaveable` | Active subgroup filter |
| `groupInitialised` | `Boolean` | Via `rememberSaveable` | Guard for initial group selection |
| `weekMenuExpanded` | `Boolean` | — | Week dropdown state |
| `groupMenuExpanded` | `Boolean` | — | Group dropdown state |
| `emptyWeeks` | `Set<String>` | Via `rememberSaveable` | Weeks confirmed empty by scanner |
| `activeWeeks` | `Set<String>` | Via `rememberSaveable` | Weeks with events found |
| `failedWeeks` | `Set<String>` | — | Weeks where network failed during scan |
| `scanningWeeks` | `Boolean` | — | Whether background week scanner is running |
| `scannedCount` | `Int` | — | Progress counter for scanner |
| `totalToScan` | `Int` | — | Total weeks to scan |
| `userPickedWeek` | `Boolean` | — | Whether user manually picked a week |
| `refreshTrigger` | `Int` | — | Incremented to force-refresh |

#### Sub-Components

| Composable | Purpose |
|---|---|
| **TopAppBar** | Course name + code + programme_code; back arrow; Star toggle; Search; Bookmark; Settings |
| **CacheStatusBar** | Compact status line: green (CACHE_FRESH), amber (CACHE_STALE with explanation), blue (NETWORK) |
| **Week scanning LinearProgressIndicator** | Thin bar shown during background week scan |
| **Week ExposedDropdownMenu** | Week picker with semester tab logic |
| **Semester row (FilterChips)** | Semester 1 / Semester 2 tabs |
| **Group ExposedDropdownMenu** | Subgroup filter with star-pin for default group |
| **Day tab row (LazyRow)** | Mon–Fri with date + event-dot indicator |
| **Crossfade content area** | Animates between loading/error/events/empty states |
| **EventsContent** | LazyColumn of TimetableEventCard items |
| **DayTabItem** | Single day tab with date, selected highlight, event dot |
| **TimetableEventCard** | Individual event card with accent bar, module code badge, title, time, lecturer, room, group |

#### Core Logic: Timetable Loading (`LaunchedEffect(currentMonday, selectedCourse.identity, refreshTrigger)`)

**Two-phase stale-while-revalidate:**
1. **Phase 1 (zero-wait):** Reads raw `CachedEventEntity` from Room directly via `dao.getEvents()` and converts to UI events via `TimetableUtils.toUiEvent`. Updates `events` immediately if data exists.
2. **Phase 2 (full validation):** Calls `repository.loadTimetable()` which checks cache TTL and potentially fetches from network. Updates `events`, `cacheSource`, and `activeWeeks`.

#### Core Logic: Background Week Scanner (`LaunchedEffect(events.isNotEmpty(), selectedCourse.identity)`)

- Generates all academic weeks via `TimetableUtils.generateAcademicWeeks()`.
- Filters out already-known weeks.
- Iterates through remaining weeks with **2.1s delay** between requests (rate-limit compliance: ~0.5 req/s, well under 5 req/10s).
- Collects results into `batchEmpty`, `batchActive`, `batchFailed` sets.
- Commits all batches in a single state update (preventing 30+ recompositions).
- Auto-detects Semester 1 / Semester 2 boundaries by looking for ≥3 consecutive empty weeks followed by an active week.

### 2.3 `SettingsScreen.kt`

**Physical Path:** `app/src/main/java/com/example/timetablescraper/ui/screens/SettingsScreen.kt`  
**Lines:** 735

#### State Dependencies

| Parameter | Type | Source |
|---|---|---|
| `onBack` | `() -> Unit` | `goToStarredOrExit()` |
| `onSavedCourseSelected` | `(SearchResult, String?) -> Unit` | Transitions to `"TIMETABLE"` |

#### Internal State

| Variable | Type | Purpose |
|---|---|---|
| `autoSyncEnabled` | `Boolean` | Background sync toggle state |
| `syncStrategy` | `SyncStrategy` | Current strategy (Daily / Weekly / Custom) |
| `customIntervalValue` | `String` | Custom interval text input |
| `customIntervalUnit` | `String` | Custom interval unit (HOURS / MINUTES / DAYS) |
| `cacheEventCount` | `Int` | Total cached event rows |
| `cacheWeeksCount` | `Int` | Distinct weeks cached |
| `cacheCoursesCount` | `Int` | Distinct courses cached |
| `newestCacheTime` | `Long?` | Timestamp of newest cache entry |
| `showClearConfirm` | `Boolean` | Clear-cache confirmation dialog |
| `savedCourses` | `List<SavedCourseEntity>` | Bookmarked courses |
| `cachedCourseIds` | `List<String>` | Course IDs that have cached data |
| `cachedCourseNames` | `List<CourseNamePair>` | Course identity + name pairs |
| `firstWeekExpanded` | `Boolean` | Academic week dropdown state |
| `sem2WeekExpanded` | `Boolean` | Sem 2 week dropdown state |
| `showPermissionRationale` | `Boolean` | POST_NOTIFICATIONS rationale dialog |

#### Sub-Components

| Composable / UI Section | Purpose |
|---|---|
| **TopAppBar** | "Sync & Cache Settings" title + back arrow |
| **Auto-refresh Card** | Toggle switch; description text |
| **Sync Strategy section** | FilterChip row (Daily / Weekly / Custom); custom interval value/unit fields |
| **Manual Sync button** | Triggers `TimetableSyncWorker.syncNow()`; handles POST_NOTIFICATIONS permission on API 33+ |
| **Academic Week Config Card** | "First Academic Week" and "Semester 2 Start" dropdown pickers |
| **Cache Statistics Card** | Events stored, weeks saved, courses cached, newest cache timestamp |
| **Course Management Card** | Per-course cache with search filter: Open, Delete, per-course cache clear buttons |
| **Clear All button** | With confirmation AlertDialog |
| **StatRow** | Reusable label/value row composable |

---

## Section 3: Data Layer & Offline-First Storage

### 3.1 Local Database Engine — Room

**Database Class:** `TimetableDatabase`  
**Path:** `app/src/main/java/com/example/timetablescraper/api/cache/TimetableDatabase.kt`

```kotlin
@Database(
    entities = [CachedEventEntity::class, SavedCourseEntity::class, SearchHistoryEntity::class],
    version = 7,
    exportSchema = false
)
abstract class TimetableDatabase : RoomDatabase()
```

**Singleton pattern:** `getInstance(context)` — double-checked locking (`@Volatile` + `synchronized`). Database file: `"timetable_cache.db"`. Uses `fallbackToDestructiveMigration()` for schema upgrades.

**Owned by:** `TimetableApplication.database` (lazy singleton).

### 3.2 Entities (Room `@Entity`)

#### `CachedEventEntity`

**File:** `app/src/main/java/com/example/timetablescraper/api/cache/CachedEventEntity.kt`

| Field | Type | Key/Index |
|---|---|---|
| `id` | `Long` | `@PrimaryKey(autoGenerate = true)` |
| `courseIdentity` | `String` | Composite index `["courseIdentity", "weekStart"]` |
| `weekStart` | `String` (yyyy-MM-dd) | Same composite index |
| `fetchedAt` | `Long` | Standalone index (for `pruneOlderThan`) |
| `moduleCode` | `String` | |
| `title` | `String` | |
| `type` | `String` | |
| `lecturer` | `String` | |
| `room` | `String` | |
| `start` | `String` (ISO-8601) | |
| `end` | `String` (ISO-8601) | |
| `group` | `String` | |
| `courseName` | `String` | |

**Table:** `cached_events`

#### `SavedCourseEntity`

**File:** `app/src/main/java/com/example/timetablescraper/api/cache/SavedCourseEntity.kt`

| Field | Type | Key/Index |
|---|---|---|
| `identity` | `String` | `@PrimaryKey` |
| `name` | `String` | |
| `programmeCode` | `String` | |
| `timetableTypeId` | `String` | |
| `savedAt` | `Long` | |
| `group` | `String?` | |

**Table:** `saved_courses`

#### `SearchHistoryEntity`

**File:** `app/src/main/java/com/example/timetablescraper/api/cache/SearchHistoryEntity.kt`

| Field | Type | Key/Index |
|---|---|---|
| `query` | `String` | `@PrimaryKey` |
| `searchedAt` | `Long` | |

**Table:** `search_history`

### 3.3 DAO & Query Matrix

**File:** `app/src/main/java/com/example/timetablescraper/api/cache/TimetableDao.kt`

#### Cached Events Queries

| Method | Input | Return Type | Called By |
|---|---|---|---|
| `getEvents(courseIdentity, weekStart)` | String, String | `List<CachedEventEntity>` (suspend) | `TimetableRepository.loadTimetable()`, `TimetableScreen` direct DAO read |
| `observeEvents(courseIdentity, weekStart)` | String, String | `Flow<List<CachedEventEntity>>` | (Defined for reactive use) |
| `insertAll(events)` | `List<CachedEventEntity>` | Unit | `TimetableRepository.loadTimetable()`, `TimetableSyncWorker.doWork()` |
| `deleteForWeek(courseIdentity, weekStart)` | String, String | Unit | `TimetableRepository.loadTimetable()`, `TimetableSyncWorker.doWork()` |
| `getLastFetchedAt(courseIdentity, weekStart)` | String, String | `Long?` | `WeekCacheIndex.ageMillis()` |
| `pruneOlderThan(olderThan)` | Long | Unit | `TimetableRepository.loadTimetable()`, `TimetableRepository.clearAll()` |
| `deleteForCourse(courseIdentity)` | String | Unit | `TimetableRepository.clearCacheForCourse()`, SettingsScreen |
| `count()` | — | `Int` | SettingsScreen cache statistics |
| `getDistinctCourseIdentities()` | — | `List<String>` | `TimetableSyncWorker.doWork()`, SettingsScreen |
| `getDistinctCourseNames()` | — | `List<CourseNamePair>` | SettingsScreen |
| `getNewestFetchedAt()` | — | `Long?` | SettingsScreen |
| `getOldestFetchedAt()` | — | `Long?` | SettingsScreen |
| `countDistinctWeeks()` | — | `Int` | SettingsScreen |
| `countEvents(courseIdentity, weekStart)` | String, String | `Int` | `WeekCacheIndex.containsKey()` |
| `countWeeksForCourse(courseIdentity)` | String | `Int` | `WeekCacheIndex.weekCount()` |
| `getDistinctWeekStarts(courseIdentity)` | String | `List<String>` | `WeekCacheIndex.cachedWeeks()` |

#### Saved Courses Queries

| Method | Input | Return Type | Called By |
|---|---|---|---|
| `getSavedCourses()` | — | `List<SavedCourseEntity>` (suspend) | `TimetableRepository.getSavedCourses()`, SettingsScreen |
| `getSavedCourse(identity)` | String | `SavedCourseEntity?` | `TimetableSyncWorker` (name resolution) |
| `isCourseSaved(identity)` | String | `Boolean` | `TimetableRepository.isCourseSaved()`, `TimetableScreen` |
| `saveCourse(course)` | `SavedCourseEntity` | Unit | `TimetableRepository.saveCourse()`, `TimetableRepository.toggleSavedCourse()` |
| `removeCourse(identity)` | String | Unit | `TimetableRepository.removeCourse()`, `TimetableRepository.toggleSavedCourse()`, SettingsScreen |

#### Search History Queries

| Method | Input | Return Type | Called By |
|---|---|---|---|
| `getRecentSearches(limit)` | Int (default 10) | `List<SearchHistoryEntity>` (suspend) | `TimetableRepository.getRecentSearches()`, SearchScreen |
| `recordSearch(entry)` | `SearchHistoryEntity` | Unit | `TimetableRepository.recordSearch()`, SearchScreen |
| `clearSearchHistory()` | — | Unit | `TimetableRepository.clearSearchHistory()`, SearchScreen |

### 3.4 `WeekCacheIndex`

**File:** `app/src/main/java/com/example/timetablescraper/api/WeekCacheIndex.kt`

A lightweight index over the DAO that implements a two-level map: `courseIdentity → weekStart → cache presence`. Holds no mutable state — every method delegates to the DAO. Used for cache-awareness without raw SQL.

| Method | Delegates To |
|---|---|
| `containsKey(courseId, weekStart)` | `dao.countEvents()` > 0 |
| `weekCount(courseId)` | `dao.countDistinctWeeksForCourse()` |
| `cachedWeeks(courseId)` | `dao.getDistinctWeekStarts()` |
| `ageMillis(courseId, weekStart)` | `dao.getLastFetchedAt()` → `System.currentTimeMillis() - fetchedAt` |

### 3.5 Network Interface & Anti-Spam Interceptor

#### `TimetableApiService`

**File:** `app/src/main/java/com/example/timetablescraper/api/TimetableApiService.kt`

**Constructor:** Takes `InstitutionConfiguration` (defaults to `Institution.DEFAULT`).

**OkHttpClient configuration:**

```kotlin
private val client = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(15, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false)  // app-layer backoff control
    .addInterceptor(RateLimitInterceptor(maxRequests = 5, perSeconds = 10))
    .build()
```

**Request headers:**

```kotlin
"Authorization" to "Anonymous",
"Referer"       to config.referer,       // "https://timetables.tudublin.ie/"
"User-Agent"    to config.userAgent,     // "TimeTableApp/1.1 ..."
"Content-Type"  to "application/json",
"Accept"        to "application/json, text/plain, */*"
```

**API Methods:**

| Method | HTTP | Endpoint Pattern | Called By |
|---|---|---|---|
| `searchCourses(query)` | POST | `{apiBase}/CategoryTypes/{programmeTypeId}/Categories/FilterWithCache/{institutionId}?query={encoded}&pageNumber=1` | `SearchScreen` via `TimetableApiService.DEFAULT` directly |
| `fetchTimetable(categoryTypeId, identity, mondayDate)` | POST | `{apiBase}/CategoryTypes/Categories/Events/Filter/{institutionId}?startRange=...&endRange=...` | `TimetableRepository.loadTimetable()`, `TimetableSyncWorker`, `SearchScreen` group expand |

**Response parsing:**
- `parseSearchResponse`: Parses `JSONObject("Results")` array → `SearchResponse`.
- `parseTimetableResponse`: Parses `JSONObject("CategoryEvents[...].Results[...]")` → delegates each event to `TimetableParser.parseApiEvent` → `TimetableResponse`.
- **Defensive parsing:** Both parsers wrap JSON construction in try/catch; malformed HTML/XML responses throw `TimetableApiException(502)` instead of leaking `JSONException`.

**Static body optimizations:**
- `STATIC_VIEW_OPTIONS` is a pre-built `JSONObject` containing `Days` (Mon–Sat), `Weeks` (empty), `TimePeriods` (All Day 00:00–23:59). Cloned per request via `cloneViewOptions()` to avoid rebuilding the full structure each time.

**Debounce integration:** `fetchTimetable` wraps its network call via `debouncer.execute(debounceKey) { ... }`, deduplicating concurrent identical requests.

#### `RateLimitInterceptor`

**File:** `app/src/main/java/com/example/timetablescraper/api/RateLimitInterceptor.kt`

**Algorithm:** Token Bucket — one bucket for all requests through the client.

- **Default:** 5 requests per 10 seconds.
- **Window tracking:** `windowStartNanos` (`AtomicLong`) and `tokens` (`AtomicLong`).
- On intercept:
  1. Check elapsed time; if window expired (≥ `perSeconds` seconds), CAS-reset the window and refill tokens to `maxRequests`.
  2. `tokens.decrementAndGet()` — if result < 0, return synthetic HTTP 429 response with `Retry-After` header.
  3. Otherwise, proceed with `chain.proceed(chain.request())`.
- **Thread-safe:** Uses `AtomicLong` + CAS for lock-free window resets.

#### `RequestDebouncer`

**File:** `app/src/main/java/com/example/timetablescraper/api/RequestDebouncer.kt`

Singleton (`RequestDebouncer.instance`) that deduplicates concurrent network calls via `ConcurrentHashMap<String, CompletableDeferred<Result<*>>>`.

- **Fast path:** If key already `inFlight`, await existing `CompletableDeferred`.
- **Race winner:** Atomic `putIfAbsert` — only the first caller runs `block()`.
- **Cleanup:** `invokeOnCompletion` removes the key from the map after all awaiters observe completion.
- **Cancellation:** Propagates `CancellationException` to all waiters; cancelled `CompletableDeferred` prevents hanging.

**Debounce key format:** `"POST|timetable|${institutionId}|${categoryTypeId}|${identity}|${ISO_DATE}"`

### 3.6 Repository Layer

**File:** `app/src/main/java/com/example/timetablescraper/api/TimetableRepository.kt`

#### Public API

| Method | Inputs | Returns | Network? | Caching |
|---|---|---|---|---|
| `loadTimetable` | `courseIdentity, timetableTypeId, mondayDate, forceRefresh, context, courseName` | `CacheResult` | Short-circuits if cache fresh (TTL); fetches on stale/miss | Writes to Room; returns `CacheSource` enum |
| `clearAll` | — | Unit | No | Deletes all Room rows |
| `clearCacheForCourse` | `courseIdentity` | Unit | No | Deletes only that course's cache |
| `getSavedCourses` | — | `List<SavedCourseEntity>` | No | Room read |
| `isCourseSaved` | `identity` | `Boolean` | No | Room EXISTS query |
| `saveCourse` | `SearchResult, group?` | Unit | No | Room INSERT |
| `removeCourse` | `identity` | Unit | No | Room DELETE |
| `toggleSavedCourse` | `SearchResult, group?` | `Boolean` (new state) | No | Conditional INSERT/DELETE |
| `getRecentSearches` | — | `List<SearchHistoryEntity>` | No | Room read (limit 10) |
| `recordSearch` | `query` | Unit | No | Room INSERT/REPLACE |
| `clearSearchHistory` | — | Unit | No | Room DELETE all |

#### `loadTimetable` — The Request-Minimization Pipeline

1. **Resolve effective TTL** from `SyncPreferences.getSyncStrategy(context)` or the repository's injected `syncStrategy`.
2. **Cache check** (unless `forceRefresh`): Query `dao.getEvents(courseIdentity, weekStart)`. If non-empty and `age < effectiveTtl`, return immediately with `CacheSource.CACHE_FRESH` — **zero network calls**.
3. **Network fetch:** Call `apiService.fetchTimetable(...)`.
4. **Persist:** Deduplicate events via `TimetableUtils.deduplicateEvents()`, map to `CachedEventEntity` list, call `dao.deleteForWeek()` + `dao.insertAll()` in sequence. Auto-prune if `count() > 1000`.
5. **Fail-safe fallback:** On any exception (except `CancellationException`), try `dao.getEvents()` for stale cache. If found, return `CacheResult(source = CacheSource.CACHE_STALE, error = e.message)`. If no cache exists, rethrow.

### 3.7 Domain Models

**File:** `app/src/main/java/com/example/timetablescraper/api/Models.kt`

| Class | Role |
|---|---|
| `SearchRequest` | POST body for search (wraps `query`) |
| `SearchResult` | Single search result — `name`, `programme_code`, `identity`, `type`, `selection_id`, `timetable_type_id` |
| `SearchResponse` | Wraps `results: List<SearchResult>` + `count` |
| `TimetableRequest` | POST body for timetable (wraps `url`) |
| `ApiEvent` | Raw API event — `module_code`, `title`, `type`, `lecturer`, `room`, `start`, `end`, `group`, `id` |
| `TimetableResponse` | Wraps `events: List<ApiEvent>` + `source` + `count` |
| `TimetableEvent` | UI-ready event — adds computed `day`, `dayIndex`, `timeRange`, `weekStart` |
| `CacheResult` | Repository return type: `events: List<ApiEvent>`, `source: CacheSource`, `error: String?` |
| `CacheSource` | Enum: `CACHE_FRESH`, `CACHE_STALE`, `NETWORK` |

### 3.8 `NetworkResult<T>` — Typed Network Outcome

**File:** `app/src/main/java/com/example/timetablescraper/api/NetworkResult.kt`

Sealed class with four variants:

| Variant | Meaning | `isRetryable` |
|---|---|---|
| `Success<T>(data)` | 2xx response | `false` |
| `HttpError(code, body)` | Non-2xx (429, 4xx, 5xx) | `code >= 500 \|\| code == 429` |
| `TransportError(reason, exception)` | Timeout, DNS, SSL, connectivity loss | `true` |

Used via `fromException(e)` factory (classifies `SocketTimeoutException`, `UnknownHostException`, `ConnectException`, `SSLException`, plus message-based HTTP detection) or `fromHttpCode(code, body)` factory.

### 3.9 `TimetableApiException`

**File:** `app/src/main/java/com/example/timetablescraper/api/TimetableApiException.kt`

Custom exception carrying `httpCode` and `body`. Factory `fromResponse(code, body)` constructs it from raw response values. Thrown by `TimetableApiService` on any non-successful response, and by `parseSearchResponse`/`parseTimetableResponse` on JSON parse failures (with `httpCode = 502`).

### 3.10 `TimetableParser`

**File:** `app/src/main/java/com/example/timetablescraper/api/TimetableParser.kt`

Internal singleton object. Pre-compiled regexes: `CODE_REGEX` (module code extraction), `SEM_REGEX` ("Sem 1/2" detection), `BRACKET_REGEX`. Method `parseApiEvent(JSONObject): ApiEvent` splits the event `Name` by `/`, extracts module code, title, type, group, and reads `ExtraProperties` for `Staff`, `Module`, `Class Group`.

### 3.11 `TimetableUtils`

**File:** `app/src/main/java/com/example/timetablescraper/api/TimetableUtils.kt`

| Method | Purpose |
|---|---|
| `toUiEvent(event, weekStart)` | Converts `ApiEvent` → `TimetableEvent` with computed `day`, `dayIndex`, `timeRange` |
| `getCurrentMonday(today)` | Returns the Monday of the current week |
| `formatDayDate(monday, dayOffset)` | Formats "MMM d" for a given day offset |
| `formatWeekRange(monday)` | Formats "MMM d – MMM d, yyyy" for a week |
| `generateAcademicWeeks(today)` | Returns all Mondays from September to April of the current academic year |
| `deduplicateEvents(events)` | Sorts by `group.length + lecturer.length` descending, then `distinctBy` normalised `start\|title\|lecturer` |
| `safeFormat(date, formatter)` | Null-safe `date.format()` |

### 3.12 `SyncStrategy`

**File:** `app/src/main/java/com/example/timetablescraper/api/SyncStrategy.kt`

Sealed class with three variants:

| Variant | TTL | Token |
|---|---|---|
| `Daily` | 24 hours (86,400,000 ms) | `"DAILY"` |
| `Weekly` | 7 days (604,800,000 ms) | `"WEEKLY"` |
| `Custom(value, unit)` | `value` × `unit` (MINUTES/HOURS/DAYS) | `"CUSTOM:value:UNIT"` |

Companion `fromToken(token)` reconstructs from string with fallback to `Daily`.

**Used by:** `TimetableRepository` (constructor injection), `SyncPreferences` (read/write), `TimetableSyncWorker.schedule()` (interval calculation), `SettingsScreen` (UI selection).

### 3.13 `InstitutionConfiguration` — Dynamic API Injection

**File:** `app/src/main/java/com/example/timetablescraper/api/InstitutionConfiguration.kt`

Interface providing `name`, `apiBaseUrl`, `institutionId`, `programmeTypeId`, `referer`, `userAgent`.

Implementations:
- `DefaultInstitution` (internal data class) — TU Dublin values.
- `OverrideInstitution` (internal data class) — for partial overrides.
- Companion `DEFAULT` → `DefaultInstitution()`.
- Companion `overrides(...)` → `OverrideInstitution(...)` with unspecified fields falling back to `DEFAULT`.

**File:** `app/src/main/java/com/example/timetablescraper/api/Institution.kt`

Deprecated data class that also implements `InstitutionConfiguration`. Retains `TU_DUBLIN` constant and `defaultSearchUrl`/`defaultEventsBaseUrl` static helpers.

### 3.14 `SyncPreferences`

**File:** `app/src/main/java/com/example/timetablescraper/SyncPreferences.kt`

Object wrapper for `SharedPreferences` (`"timetable_sync_prefs"`).

| Key Prefix | Purpose | Methods |
|---|---|---|
| `sync_strategy_token` + custom fields | Sync strategy persistence | `getSyncStrategy`, `setSyncStrategy`, `getCustomIntervalValue`, `getCustomIntervalUnit` |
| `auto_sync_enabled` | Background sync toggle | `isAutoSyncEnabled`, `setAutoSyncEnabled` |
| `sync_interval_hours` | Legacy (deprecated) | `getSyncIntervalHours`, `setSyncIntervalHours` |
| `last_manual_sync` | Manual sync timestamp | `getLastManualSync`, `setLastManualSync` |
| `first_week_monday` | First academic week | `getFirstWeekMonday`, `setFirstWeekMonday` |
| `sem2_start_monday` | Semester 2 start | `getSem2StartMonday`, `setSem2StartMonday` |
| `starred_identity/name/type_id` | Pinned course | `getStarredCourse`, `setStarredCourse` |
| `last_group_{courseIdentity}` | Per-course group preference | `getLastGroup`, `setLastGroup` |
| `view_semester_{id}`, `view_week_{id}`, `view_day_{id}` | Per-course view state | `saveCourseViewState`, `getSavedSemester/Week/DayIndex`, `clearCourseViewState` |
| `cached_week_{courseIdentity\|weekStart}` | Week cache polling markers | `markWeekCached`, `isWeekCached` |

---

## Section 4: Automated Self-Updater Pipeline

### 4.1 Lifecycle Entry Point

Located in `MainActivity.kt`, lines 68–76:

```kotlin
LaunchedEffect(Unit) {
    if (updateCheckDone) return@LaunchedEffect
    updateCheckDone = true
    val result = UpdateChecker.checkForUpdate()
    updateResult = result
    if (result.updateAvailable && result.downloadUrl != null) {
        showUpdateDialog = true
    }
}
```

- Runs once on first composition.
- If an update is found, shows a Material 3 `AlertDialog` with "Update Now" and "Later" buttons.
- "Update Now" calls `UpdateManager.startDownload(context, downloadUrl)` in a coroutine.

### 4.2 `UpdateChecker`

**File:** `app/src/main/java/com/example/timetablescraper/update/UpdateChecker.kt`

#### Endpoint
```
GET https://gitlab.com/api/v4/projects/Izzeddin-Hammad%2FTUD-TimeTable-APP/releases
```

#### OkHttp Client
Lightweight client with 10s connect / 15s read timeouts — **no** `RateLimitInterceptor` (update checks are infrequent).

#### `checkForUpdate(): UpdateResult`

1. Executes `GET` on `RELEASES_URL`.
2. Parses response as `JSONArray`.
3. Takes the first (latest) release.
4. Reads `tag_name` (e.g. `"v1.4"`).
5. Calls `extractDownloadUrl(release: JSONObject)`:
   - Searches `assets.links[]` for a link whose `name` or `url` ends with `.apk`.
   - Falls back to the first link's URL if no `.apk` extension found.
6. Compares versions via `isNewerThan(remote, local)`:
   - Strips leading `v`/`V`.
   - Splits on `.` and compares each segment numerically.
   - Falls back to lexicographic comparison for non-numeric segments.
7. Returns `UpdateResult(updateAvailable, remoteVersion, downloadUrl, errorMessage?)`.

#### `UpdateResult` Data Class
```kotlin
data class UpdateResult(
    val updateAvailable: Boolean,
    val remoteVersion: String? = null,
    val downloadUrl: String? = null,
    val errorMessage: String? = null
)
```

### 4.3 `UpdateManager`

**File:** `app/src/main/java/com/example/timetablescraper/update/UpdateManager.kt`

#### Methods

| Method | Purpose |
|---|---|
| `startDownload(context, downloadUrl): Boolean` | Enqueues APK via `DownloadManager.Request`. Sets destination to `context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)/TimeTable-update.apk`. Persists `downloadId` to SharedPreferences (`"update_prefs"`). Returns `true` on success. |
| `installApk(context, apkFile): Boolean` | Generates a content URI via `FileProvider.getUriForFile` with authority `"${packageName}.fileprovider"`. Fires `Intent.ACTION_VIEW` with MIME `"application/vnd.android.package-archive"` and flags `FLAG_ACTIVITY_NEW_TASK \| FLAG_GRANT_READ_URI_PERMISSION`. |
| `getApkFile(context): File` | Resolves `externalFilesDir/Download/TimeTable-update.apk` |
| `getLastDownloadId(context): Long` | Reads persisted download ID from prefs |
| `registerReceiver(context, receiver)` | Registers `BroadcastReceiver` for `DownloadManager.ACTION_DOWNLOAD_COMPLETE` with `RECEIVER_NOT_EXPORTED` |

### 4.4 `UpdateReceiver`

**File:** `app/src/main/java/com/example/timetablescraper/update/UpdateReceiver.kt`

Extends `BroadcastReceiver`. Registered dynamically in `MainApp()` via `DisposableEffect`.

**`onReceive` flow:**
1. Filter for `ACTION_DOWNLOAD_COMPLETE`.
2. Extract `EXTRA_DOWNLOAD_ID` and compare against `UpdateManager.getLastDownloadId()`.
3. Query `DownloadManager` for the download's status:
   - `STATUS_SUCCESSFUL` → resolve APK file via `UpdateManager.getApkFile()`, call `UpdateManager.installApk()`.
   - `STATUS_FAILED` → log the failure reason.
   - Other → log "waiting".

### 4.5 Provider & Path Configuration

**Manifest** (`AndroidManifest.xml`):
```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/filepaths" />
</provider>
```

**Path file** (`res/xml/filepaths.xml`):
```xml
<paths>
    <external-files-path name="downloads" path="Download/" />
</paths>
```

---

## Section 5: The Global Component & Dependency Map

### 5.1 Complete File Index

Below is every file in `app/src/main/java/com/example/timetablescraper/` with its structural responsibility and dependents.

---

#### **`TimetableApplication.kt`**
- **Role:** Application-level singleton. Owns the lazy-initialized `database` (Room), `institutionConfig`, `apiService`, and `repository`. Creates the sync notification channel and schedules the background WorkManager worker in `onCreate()`.
- **Dependents:** `SearchScreen` (casts `context.applicationContext` to `TimetableApplication` and reads `app.repository`), `TimetableScreen` (same pattern), `SettingsScreen` (reads `app.database.timetableDao()` and `app.repository`).

#### **`MainActivity.kt`**
- **Role:** Single-activity host. Owns the `MainApp()` composable state machine, the `BackHandler` routing matrix, the self-update dialog lifecycle, and the update receiver registration.
- **Dependents:** Every screen file: `SearchScreen.kt`, `TimetableScreen.kt`, `SettingsScreen.kt`; the theme `TimetableScraperTheme`; the update layer files `UpdateChecker.kt`, `UpdateManager.kt`, `UpdateReceiver.kt`; the import of `SearchResult` from `Models.kt`.

#### **`SyncPreferences.kt`**
- **Role:** `SharedPreferences` object wrapper for all persisted settings: sync strategy, starred course, per-course view state, week cache markers, academic week config.
- **Dependents:** `MainActivity.kt` (starred course), `TimetableRepository.kt` (sync strategy for TTL), `TimetableScreen.kt` (view state, last group, first week, sem2), `SettingsScreen.kt` (all sync/strategy/calendar settings), `TimetableSyncWorker.kt` (auto-sync enabled, sync strategy), `TimetableApplication.kt` (reads sync strategy for repository injection).

---

#### **`api/InstitutionConfiguration.kt`**
- **Role:** Interface for dynamic API configuration injection. Provides `DefaultInstitution` and `OverrideInstitution` implementations.
- **Dependents:** `Institution.kt`, `TimetableApiService.kt` (constructor parameter), `TimetableApplication.kt` (injected as `institutionConfig`), `TimetableSyncWorker.kt` (uses `Institution.DEFAULT`).

#### **`api/Institution.kt`**
- **Role:** Deprecated data class that implements `InstitutionConfiguration`. Holds `TU_DUBLIN` constant and static URL builders.
- **Dependents:** `TimetableApplication.kt` (`Institution.DEFAULT`), `TimetableApiService.kt` (default constructor parameter), `TimetableSyncWorker.kt` (uses `Institution.DEFAULT`).

#### **`api/Models.kt`**
- **Role:** All data types: `SearchRequest`, `SearchResult`, `SearchResponse`, `TimetableRequest`, `ApiEvent`, `TimetableResponse`, `TimetableEvent`.
- **Dependents:** `MainActivity.kt` (`SearchResult`), `SearchScreen.kt` (`SearchResult`), `TimetableScreen.kt` (`ApiEvent`, `TimetableEvent`, `SearchResult`), `SettingsScreen.kt` (`SearchResult`, `SavedCourseEntity`), `TimetableApiService.kt` (all), `TimetableParser.kt` (`ApiEvent`), `TimetableRepository.kt` (`ApiEvent`, `CacheResult`, `CacheSource`), `TimetableUtils.kt` (`ApiEvent`, `TimetableEvent`), `TimetableSyncWorker.kt` (`CachedEventEntity`).

#### **`api/NetworkResult.kt`**
- **Role:** Sealed class classifying network outcomes into `Success`, `HttpError`, `TransportError` with `isRetryable`, `isConnectivityLoss`, `statusLabel` accessors.
- **Dependents:** (Defined but currently used as a utility; the app primarily uses `TimetableApiException` for error propagation.)

#### **`api/TimetableApiException.kt`**
- **Role:** Typed exception carrying `httpCode` and `body`. Factory `fromResponse(code, body)`.
- **Dependents:** `TimetableApiService.kt` (thrown on HTTP errors and JSON parse failures).

#### **`api/RateLimitInterceptor.kt`**
- **Role:** OkHttp `Interceptor` implementing Token Bucket rate limiting (5 req/10s default). Returns synthetic HTTP 429 when bucket is empty.
- **Dependents:** `TimetableApiService.kt` (added to the OkHttp client via `addInterceptor`).

#### **`api/RequestDebouncer.kt`**
- **Role:** Singleton deduplication engine using `ConcurrentHashMap<String, CompletableDeferred<Result<*>>>`. Consolidates concurrent identical requests.
- **Dependents:** `TimetableApiService.kt` (`debouncer.execute(key) { ... }`).

#### **`api/SyncStrategy.kt`**
- **Role:** Sealed class with `Daily`, `Weekly`, `Custom(value, unit)` variants. Each provides `ttlMillis()`, `displayName()`, `toToken()`, and companion `fromToken()`.
- **Dependents:** `TimetableRepository.kt` (injected and used for TTL), `SyncPreferences.kt` (persistence), `SettingsScreen.kt` (UI selection), `TimetableSyncWorker.kt` (interval calculation).

#### **`api/TimetableApiService.kt`**
- **Role:** Configurable Scientia API client. Provides `searchCourses(query)` and `fetchTimetable(...)`. Builds URLs, request bodies, and parses responses. Integrates `RateLimitInterceptor` and `RequestDebouncer`.
- **Dependents:** `TimetableRepository.kt` (calls `fetchTimetable`), `SearchScreen.kt` (calls `searchCourses` directly via `TimetableApiService.DEFAULT`), `TimetableSyncWorker.kt` (calls `fetchTimetable` directly), `TimetableApplication.kt` (constructs instance).

#### **`api/TimetableParser.kt`**
- **Role:** Internal singleton. Pre-compiled regexes for module code, semester detection, bracketed suffix removal. `parseApiEvent(JSONObject): ApiEvent`.
- **Dependents:** `TimetableApiService.kt` (called in `parseTimetableResponse`).

#### **`api/TimetableRepository.kt`**
- **Role:** Enterprise caching repository. Implements the request-minimization pipeline: cache-first, network-second, stale-cache fallback. Manages saved courses and search history. Defines `CacheSource` and `CacheResult`.
- **Dependents:** `TimetableApplication.kt` (constructs instance), `TimetableScreen.kt` (calls `loadTimetable`, `isCourseSaved`, `saveCourse`), `SettingsScreen.kt` (calls `clearAll`, `removeCourse`), `TimetableSyncWorker.kt` (uses DAO directly, not through repository).

#### **`api/TimetableUtils.kt`**
- **Role:** Utilities for `ApiEvent` → `TimetableEvent` conversion, date calculations, academic week generation, event deduplication.
- **Dependents:** `TimetableScreen.kt` (`TimetableUtils.toUiEvent`, `TimetableUtils.generateAcademicWeeks`, `TimetableUtils.getCurrentMonday`), `SettingsScreen.kt` (`TimetableUtils.formatWeekRange`), `TimetableSyncWorker.kt` (`TimetableUtils.deduplicateEvents`), `TimetableRepository.kt` (`TimetableUtils.deduplicateEvents`).

#### **`api/WeekCacheIndex.kt`**
- **Role:** Lightweight key-value index over the DAO for cache interrogation without raw SQL. Methods: `containsKey`, `weekCount`, `cachedWeeks`, `ageMillis`.
- **Dependents:** `TimetableRepository.kt` (exposed as `weekCacheIndex` property, `WeekCacheIndex(dao)`).

---

#### **`api/cache/TimetableDatabase.kt`**
- **Role:** Room database class. Entities: `CachedEventEntity`, `SavedCourseEntity`, `SearchHistoryEntity`. Version 7, destructive migration. Double-checked locking singleton.
- **Dependents:** `TimetableApplication.kt` (lazy `getInstance(this)`), `TimetableRepository.kt` (constructor parameter), `TimetableSyncWorker.kt` (`TimetableDatabase.getInstance(applicationContext)`).

#### **`api/cache/TimetableDao.kt`**
- **Role:** Room DAO with all queries for cached events, saved courses, search history, and week cache index.
- **Dependents:** `TimetableRepository.kt` (`database.timetableDao()`), `TimetableScreen.kt` (direct DAO access via `app.database.timetableDao()`), `SettingsScreen.kt` (direct DAO access), `TimetableSyncWorker.kt` (direct DAO access), `WeekCacheIndex.kt` (DAO reference).

#### **`api/cache/CachedEventEntity.kt`**
- **Role:** Room entity for `cached_events` table. Composite index on `(courseIdentity, weekStart)`. Standalone index on `fetchedAt`.
- **Dependents:** `TimetableDatabase.kt` (in entities list), `TimetableDao.kt` (all queries), `TimetableRepository.kt` (mapping to/from), `TimetableSyncWorker.kt` (constructing entities), `TimetableScreen.kt` (direct read).

#### **`api/cache/SavedCourseEntity.kt`**
- **Role:** Room entity for `saved_courses` table. Primary key: `identity`.
- **Dependents:** `TimetableDatabase.kt`, `TimetableDao.kt`, `TimetableRepository.kt`, `SettingsScreen.kt`, `TimetableSyncWorker.kt`.

#### **`api/cache/SearchHistoryEntity.kt`**
- **Role:** Room entity for `search_history` table. Primary key: `query`. Deduplicated by query text.
- **Dependents:** `TimetableDatabase.kt`, `TimetableDao.kt`, `TimetableRepository.kt`, `SearchScreen.kt`.

---

#### **`ui/screens/SearchScreen.kt`**
- **Role:** Course search screen with debounced search (300ms), results list with expandable group picker, search history dialog.
- **Internal sub-components:** `CourseResultCard` (private).
- **Dependents:** `MainActivity.kt` (invoked in `when(currentScreen)`), `TimetableApplication.kt` (for repository), `api/Models.kt`, `api/TimetableApiService.kt` (direct search call), `api/cache/SearchHistoryEntity.kt`, `R.drawable.ic_tud_logo`.

#### **`ui/screens/TimetableScreen.kt`**
- **Role:** Main timetable viewing screen. Week picker, semester tabs, subgroup filter, day tabs, event list with Crossfade animations, cache status bar, background week scanner.
- **Internal sub-components:** `CacheStatusBar`, `EventsContent`, `DayTabItem`, `TimetableEventCard` (all private).
- **Dependents:** `MainActivity.kt`, `TimetableApplication.kt`, `SyncPreferences.kt`, `api/Models.kt`, `api/TimetableUtils.kt`, `api/cache/TimetableDao.kt` (direct), `api/cache/CachedEventEntity.kt` (direct read), `worker/SyncNotificationManager.kt` (for `setForeground` info).

#### **`ui/screens/SettingsScreen.kt`**
- **Role:** Settings screen with auto-refresh toggle, sync strategy selector (Daily/Weekly/Custom), manual sync button (with POST_NOTIFICATIONS permission handling), academic week configuration, cache statistics, saved courses management with per-course open/delete, and cache clear.
- **Internal sub-components:** `StatRow` (private).
- **Dependents:** `MainActivity.kt`, `SyncPreferences.kt`, `TimetableApplication.kt`, `api/Models.kt` (`SearchResult`), `api/SyncStrategy.kt`, `api/TimetableUtils.kt`, `api/cache/TimetableDao.kt` (direct), `api/cache/SavedCourseEntity.kt`, `worker/TimetableSyncWorker.kt`.

---

#### **`ui/theme/Color.kt`**
- **Role:** Color constants for light and dark palettes (`Purple80`, `PurpleGrey80`, `Pink80`, `Purple40`, `PurpleGrey40`, `Pink40`).
- **Dependents:** `Theme.kt`.

#### **`ui/theme/Theme.kt`**
- **Role:** `TimetableScraperTheme` composable. Supports dynamic color on Android 12+ via `dynamicDarkColorScheme` / `dynamicLightColorScheme`, with fallback to `DarkColorScheme` / `LightColorScheme`.
- **Dependents:** `MainActivity.kt` (wraps `MainApp()`).

#### **`ui/theme/Type.kt`**
- **Role:** Material 3 `Typography` definition.
- **Dependents:** `Theme.kt`.

---

#### **`update/UpdateChecker.kt`**
- **Role:** GitLab Releases API client. `checkForUpdate()` returns `UpdateResult` with version comparison.
- **Dependents:** `MainActivity.kt` (calls `UpdateChecker.checkForUpdate()`), `BuildConfig` (for local `VERSION_NAME`).

#### **`update/UpdateManager.kt`**
- **Role:** Manages APK download via `DownloadManager` and install via `FileProvider` + `Intent.ACTION_VIEW`.
- **Dependents:** `MainActivity.kt` (calls `startDownload`, `registerReceiver`), `UpdateReceiver.kt` (calls `getLastDownloadId`, `getApkFile`, `installApk`).

#### **`update/UpdateReceiver.kt`**
- **Role:** `BroadcastReceiver` for `DownloadManager.ACTION_DOWNLOAD_COMPLETE`. Triggers install on successful download.
- **Dependents:** `MainActivity.kt` (registered via `UpdateManager.registerReceiver`).

---

#### **`worker/TimetableSyncWorker.kt`**
- **Role:** `CoroutineWorker` for periodic background sync. Iterates all cached course identities, fetches current week's timetable, and writes to Room. Strategy-aware scheduling via `schedule(context)` / `syncNow(context)` / `cancel(context)`. Posts completion notifications.
- **Dependents:** `TimetableApplication.kt` (scheduled in `onCreate()`), `SettingsScreen.kt` (triggered by sync button and strategy changes), `SyncPreferences.kt` (strategy and auto-sync toggle), `api/Institution.kt`, `api/TimetableApiService.kt`, `api/TimetableUtils.kt`, `api/cache/TimetableDatabase.kt`, `api/cache/CachedEventEntity.kt`, `SyncNotificationManager.kt`.

#### **`worker/SyncNotificationManager.kt`**
- **Role:** Creates notification channel (`CHANNEL_ID = "timetable_sync"`), posts foreground and completion notifications. Handles `POST_NOTIFICATIONS` permission on API 33+.
- **Dependents:** `TimetableApplication.kt` (channel creation), `TimetableSyncWorker.kt` (completion notification), `R.drawable.ic_notification_sync`.

---

### 5.2 Dependency Flow Summary

```
┌─────────────────────────────────────────────────────────────────┐
│                        MainActivity.kt                         │
│                                                                 │
│  LaunchedEffect(Unit) ──► UpdateChecker.checkForUpdate()        │
│  DisposableEffect ──► UpdateManager.registerReceiver            │
│                          └─► UpdateReceiver.onReceive()          │
│                                └─► UpdateManager.installApk()    │
│                                                                 │
│  when(currentScreen) {                                          │
│    "SEARCH"    ──► SearchScreen                                 │
│    "TIMETABLE" ──► TimetableScreen                              │
│    "SETTINGS"  ──► SettingsScreen                               │
│  }                                                              │
│  BackHandler ──► goToStarredOrExit / goBackFromTimetable        │
└──────────┬──────────────────────────────────────────────────────┘
           │
           ▼
┌─────────────────────────────────────────────────────────────────┐
│                      TimetableApplication.kt                    │
│                                                                 │
│  database ──► TimetableDatabase (Room, singleton)                │
│                   └─► TimetableDao                              │
│                        ├─► CachedEventEntity                    │
│                        ├─► SavedCourseEntity                    │
│                        └─► SearchHistoryEntity                  │
│                                                                 │
│  apiService ──► TimetableApiService                             │
│                   ├─► RateLimitInterceptor                      │
│                   ├─► RequestDebouncer                          │
│                   └─► TimetableParser                           │
│                                                                 │
│  repository ──► TimetableRepository                             │
│                   ├─► WeekCacheIndex                            │
│                   └─► (wraps DAO + ApiService)                  │
│                                                                 │
│  onCreate() ──► SyncNotificationManager.createChannel           │
│              ──► TimetableSyncWorker.schedule                   │
└─────────────────────────────────────────────────────────────────┘
```

### 5.3 Architectural Principles Enforced

1. **State Hoisting at Root:** All navigation state, search state, and cross-cutting state (starred course) lives in `MainApp()` inside `MainActivity.kt`. No screen owns mutable navigation state.

2. **Repository-Centric Data Flow:** UI screens never call the API service directly for timetable data (except `SearchScreen` which calls `TimetableApiService.DEFAULT.searchCourses()` directly, and group expansion which calls `fetchTimetable` directly). The `TimetableRepository` is the single gate for timetable loading, caching, and staleness logic.

3. **Offline-First with Stale-While-Revalidate:** `TimetableScreen` implements a two-phase load: immediate cache display (Phase 1), followed by a full repository load that validates freshness and falls back to stale cache on network failure (Phase 2).

4. **Request Minimization Triad:** (a) Cache TTL check blocks network calls entirely within the sync window; (b) `RequestDebouncer` deduplicates concurrent identical in-flight requests; (c) `RateLimitInterceptor` prevents exceeding 5 req/10s at the transport layer.

5. **Dynamic Configuration via Interface:** `InstitutionConfiguration` allows targeting any Scientia Publish instance by swapping UUIDs, base URL, and headers — zero hardcoded endpoint values in business logic.

6. **Separated Update Pipeline:** The self-updater (`UpdateChecker` → `UpdateManager` → `UpdateReceiver`) is fully decoupled from the timetable logic, wired in only at the `MainActivity` composable entry point.

7. **Persistent View State with SharedPreferences:** Per-course semester, week, day tab, and group selection survive process death via `SyncPreferences` save/restore methods — not via `SavedStateHandle` or Room.

---

*End of Specification Document.*  
*All file paths are relative to `app/src/main/java/com/example/timetablescraper/` unless otherwise noted.*  
*Total source files analyzed: 36 (production code) + 5 test files + XML resources.*
