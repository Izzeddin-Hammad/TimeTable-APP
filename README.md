# TimeTable — Prototype

> **⚠️ PROTOTYPE — TU Dublin only.** This app is a prototype/proof-of-concept that only supports **TU Dublin**. It connects to the TU Dublin Scientia Publish API. It is not intended for production use and may change significantly.

A prototype Android timetable app that fetches your TU Dublin university schedule directly from the Scientia Publish API — no manual entry needed.

## Features

### Timetable
- **Direct API Integration** — Connects to the TU Dublin Scientia timetable API with Anonymous auth
- **Course Search** — Search by course code or name with real-time debounced results; stale queries are discarded mid-flight
- **Full-Year Week Classifier** — Single API request classifies all 30 academic weeks as active or empty instantly (replaces the old 60-second serial scanner)
- **Hide Empty Weeks** — Toggle in Settings to remove weeks with no classes from the dropdown (default: on)
- **Smart Semester Detection** — Auto-detects Semester 1 & 2 boundaries by finding a ≥21-day gap between active weeks after November
- **Week Dropdown** — Numbered weeks (W1, W2, …) with empty weeks optionally hidden
- **Semester Tabs** — Wide Semester 1 / Semester 2 tabs for quick semester switching
- **Subgroup Filtering** — Select specific class groups (A, B, G1, G2, etc.) via dropdown with ⭐ default group pinning; expand unlimited search results simultaneously to compare groups across courses
- **Pull to Refresh** — Swipe down on the timetable to force a fresh network fetch (limited to once per 24 hours)
- **Persistent View State** — Remembers your semester, week, day tab, and group per course across app restarts
- **Offline Cache** — Room database caches timetables per week; view your schedule even without internet
- **Stale-While-Revalidate** — Cache renders instantly (~5ms), background refresh updates silently

### Pinning & Bookmarks
- **Pin to Home** — Star a course to make it your home screen; opens instantly on launch. Only one course can be pinned at a time
- **Star = Auto-Save** — Starring a course automatically bookmarks it. Unstarring does NOT unsave
- **Bookmark Courses** — Save courses for quick access from Settings. Bookmarking auto-stars (with replacement confirmation)
- **Home Button in Search** — A home icon appears in the search bar when a course is pinned, for one-tap navigation back

### Sync & Cache
- **Configurable Sync Strategy** — Three-mode pattern:
  - **Daily** — 24-hour cache TTL
  - **Weekly** — 7-day cache TTL
  - **Custom (days)** — User-defined day interval
- **Background Sync** — WorkManager periodically refreshes cached timetables with strategy-aware scheduling
- **Reactive Background Sync** — UI polls Room every 2 minutes; if WorkManager updated cache, the timetable refreshes automatically
- **Granular Cache Management** — Delete individual course caches from Settings without wiping everything
- **Sync Notification System** — Background sync completions post notifications with success/fail status and timestamp
- **Client-Side Rate Limiting** — Token Bucket OkHttp interceptor (5 req/10s); returns synthetic 429 to trigger fail-safe fallback

### Resilience & Safety
- **Global Crash Handler** — Uncaught exceptions are persisted and recovered on next launch via a dedicated Fatal Error recovery screen
- **Fatal Error Screen** — Shows "Something went wrong" with "Clear Cache & Restart" and "Try Again" buttons
- **Coroutine Exception Handler** — Unhandled coroutine crashes are caught at the root scope and persisted for next-launch recovery
- **Fail-Safe Fallback** — HTTP 429/500 and network errors fall back to stale cache with an "⚠️ Offline / Cached Mode" banner
- **Request Minimization** — Singleton request debouncer deduplicates concurrent API calls to the same URL
- **Defensive JSON Parsing** — All API response parsers wrapped in try/catch; malformed HTML/XML responses emit clean `TimetableApiException(502)`

### Performance
- **Instant Week Classification Cache** — Full-year classification is cached in SharedPreferences per course; subsequent launches are instant
- **Compose Stability** — `@Immutable` annotations on `TimetableEvent`, `SearchResult`, `ApiEvent`, `CacheResult` let Compose skip unchanged-item recomposition checks
- **Room Performance** — Composite index on `[courseIdentity, weekStart]` + `fetchedAt` index for cache pruning; DB v7 schema
- **Resource Efficiency** — `DateTimeFormatter` instances cached globally; date parsing offloaded to `Dispatchers.Default`

### UI/UX
- **Material 3 UI** — Jetpack Compose with dynamic color support and smooth crossfade animations
- **In-App Self-Updating** — Queries GitLab Releases API on launch; prompts with an update dialog when a newer version is detected
- **Search History** — Quick re-access to recent searches
- **Subgroup UI** — 32dp expand arrow with "Tap to reveal course sub-groups" label; full subgroup path displayed in filter chips
- **Auto-Dismiss Cache Status** — "🌐 Updated from server" banner auto-dismisses after 4 seconds

## Privacy

**Zero data collection.** See [PRIVACY.md](PRIVACY.md) for full details.

## How It Works

### Architecture
```
┌─ Presentation Layer ─────────────────────────────────────────┐
│  Jetpack Compose UI (single-Activity, all state hoisted)     │
│  ┌─ SearchScreen ─┐  ┌─ TimetableScreen ─┐  ┌─ Settings ─┐  │
│  │ (course search) │  │ (week view, pull  │  │ (sync,     │  │
│  │                 │  │  star, bookmark,  │  │  cache,    │  │
│  │                 │  │  semester tabs)   │  │  updates)  │  │
│  └────────────────┘  └───────────────────┘  └────────────┘  │
│         ▲                    ▲                     ▲         │
│         │  onCourseSelected  │  onStarToggle       │         │
│         ▼                    ▼                     ▼         │
│  ┌─ MainActivity (MainApp) ────────────────────────────┐     │
│  │  State machine: SEARCH / TIMETABLE / SETTINGS       │     │
│  │  CoroutineScope + CoroutineExceptionHandler         │     │
│  └─────────────────────────────────────────────────────┘     │
├─ Safety Layer ───────────────────────────────────────────────┤
│  CrashHandler (Thread.setDefaultUncaughtExceptionHandler)     │
│    → persists crash → next launch shows FatalErrorScreen     │
├─ Data Layer ─────────────────────────────────────────────────┤
│  TimetableRepository (strategy-aware TTL, request debouncer) │
│    ├─ Room Database (per-week indexed key-value cache)       │
│    ├─ TimetableApiService → OkHttp → Scientia Publish API   │
│    │     ├─ RequestDebouncer (URL-keyed deduplication)       │
│    │     └─ RateLimitInterceptor (token bucket; 5 req/10s)  │
│    └─ WorkManager (strategy-aware periodic sync)             │
│         └─ SyncNotificationManager                           │
├─ Update Layer ───────────────────────────────────────────────┤
│  UpdateChecker → GitLab Releases API                         │
│  UpdateManager → DownloadManager → FileProvider → Installer  │
└──────────────────────────────────────────────────────────────┘
```

### Data Flow
1. User searches for a course → API returns matching programmes (debounced, stale-guarded)
2. Selecting a course loads the timetable via two-phase stale-while-revalidate:
   - **Phase 1 (instant)**: Room cache read + `toUiEvent` parsing on `Dispatchers.Default` → UI renders in ~5ms
   - **Phase 2 (background)**: `TimetableRepository.loadTimetable()` — fresh cache short-circuits; stale cache triggers network fetch silently
3. Events are parsed (pre-compiled regexes), deduplicated (O(n log n) single pass), and cached
4. A single full-year API request classifies all 30 academic weeks as active or empty instantly
5. Week classification is cached in SharedPreferences per course — subsequent launches are instant
6. Semester boundaries auto-detected from a ≥21-day gap between active weeks after November
7. The week dropdown shows only non-empty weeks when the "Hide empty weeks" toggle is on
8. View state (semester, week, day, group) is persisted per course across app restarts
9. WorkManager refreshes cached data per the user's chosen SyncStrategy
10. A 2-minute background poll detects WorkManager cache updates and refreshes the UI automatically
11. Pull-to-refresh fetches fresh data but is rate-limited to once every 24 hours

### Sync Strategies
| Mode | TTL | Behavior |
|------|-----|----------|
| **Daily** | 24 hours | Cache valid for 1 day — network calls blocked within window |
| **Weekly** | 7 days | Cache valid for 7 days |
| **Custom** | X days | User-defined interval (e.g., 3 days) |

Network calls are completely blocked if the app is opened while the cache is still fresh.

### Fail-Safe Behavior
| Scenario | Behavior |
|----------|----------|
| Network timeout / DNS failure | Fall back to stale cache, show "⚠️ Offline / Cached Mode" banner |
| HTTP 429 (Rate Limited) | Fall back to stale cache |
| HTTP 500+ (Server Error) | Fall back to stale cache |
| No cache available + network fail | Show error message with "Retry" button |
| Crash (uncaught exception) | Persisted to prefs → next launch shows FatalErrorScreen |
| Coroutine crash | Caught by root CoroutineExceptionHandler → persisted → FatalErrorScreen on next launch |
| Coroutine cancelled (navigation) | `CancellationException` propagated, cache left intact |

### Fault Tolerance (Chaos Engineering)
| Attack Scenario | Defense |
|----------------|---------|
| JSON key renamed by upstream | `optString()` with safe defaults — missing keys return `""`, never crash |
| Null values in API response | `optString` treats JSON null as missing; `.ifBlank { "TBA" }` guards downstream |
| Server returns HTML instead of JSON | `JSONObject(body)` wrapped in try/catch → clean `TimetableApiException(502)` |
| Server hangs (no response) | 15s connect timeout + 30s read timeout; no automatic retries |
| HTTP 429 / 500+ | Repository `catch(Exception)` triggers stale Room cache fallback |
| Firewall returns login page | `optJSONArray("Results") ?: JSONArray()` — graceful empty results, no crash |
| Worker updates Room while user is viewing | 2-minute polling `LaunchedEffect` detects changes, refreshes UI automatically |
| Timezone boundary (midnight) | All `LocalDate.now()` calls use `Europe/Dublin` with safe `ZoneId` fallback |

## Download

[**Download latest APK (v1.14)**](https://github.com/Izzeddin-Hammad/TUD-TimeTable-APP/raw/main/releases/TimeTable-v1.14-debug.apk)

> Requires Android 8.0+ (API 26). Tap the APK to install — the system will prompt you once per app.

## Setup (for developers)

1. Clone the repo
2. Open in Android Studio
3. Sync Gradle
4. Run on device/emulator (min SDK 26)

No API keys needed — the Scientia Publish API uses Anonymous authentication.

## Running Tests

```bash
# JVM unit tests (fast, no emulator)
./gradlew test

# Filter specific tests
./gradlew test -PtestFilter="com.example.timetablescraper.api.*"

# Instrumentation tests (emulator required)
./gradlew connectedAndroidTest
```

## License

MIT
