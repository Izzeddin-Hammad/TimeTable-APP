# TimeTable — Prototype

> **⚠️ PROTOTYPE — TU Dublin only.** This app is a prototype/proof-of-concept that only supports **TU Dublin**. It connects to the TU Dublin Scientia Publish API. It is not intended for production use and may change significantly.

A prototype Android timetable app that fetches your TU Dublin university schedule directly from the Scientia Publish API — no manual entry needed.

## Features

- **Direct API Integration** — Connects to the TU Dublin Scientia timetable API with Anonymous auth
- **Course Search** — Search by course code or name with real-time debounced results; stale queries are discarded mid-flight
- **Sync Notification System** — Background/sync completions post notifications with success/fail status and timestamp
- **Configurable Sync Strategy** — Three-mode pattern:
  - **Daily** — 24-hour cache TTL
  - **Weekly** — 7-day cache TTL
  - **Custom (days)** — User-defined day interval
- **Settings Screen** — Access sync settings, academic week configuration, saved courses, cached data statistics
- **Smart Semester Detection** — Auto-detects Semester 1 & 2 boundaries by scanning all academic weeks in the background (parallelized, 4× faster)
- **Week Dropdown** — Numbered weeks (W1, W2, ...) with empty weeks permanently hidden
- **Semester Tabs** — Wide Semester 1 / Semester 2 tabs for quick semester switching
- **Subgroup Filtering** — Select specific class groups (A, B, G1, G2, etc.) via dropdown with ⭐ default group pinning; expand unlimited search results simultaneously to compare groups across courses
- **Pin to Home** — Star a course to make it your home screen; opens instantly on launch
- **Persistent View State** — Remembers your semester, week, day tab, and group per course across app restarts
- **Offline Cache** — Room database caches timetables; view your schedule even without internet
- **Request Minimization** — Singleton request debouncer deduplicates concurrent API calls to the same URL
- **Client-Side Rate Limiting** — Token Bucket OkHttp interceptor (5 req/10s); returns synthetic 429 to trigger fail-safe fallback when exceeded
- **Granular Cache Management** — Delete individual course caches from Settings without wiping everything; properly displays course names resolved from cache/saved courses
- **Corrected Notification Icon** — Custom vector drawable for sync notifications replaces system icons that rendered as solid white squares on some devices
- **Fixed "All" Toggle** — Correctly shows/hides empty academic weeks; default state strictly hides empty weeks; ALL toggle shows continuous block from Week 1
- **Subgroup UI Improvements** — 48dp touch target for dropdown arrows; gold star tint (⭐) on pinned/default subgroups for clear visual feedback
- **Default Week 1** — New/uncached courses now default to the first academic week instead of incorrectly jumping to Week 4
- **Removed "All" Toggle** — Empty academic weeks are now permanently hidden; the week dropdown only ever displays weeks with scheduled classes (no toggle UX clutter)
- **Subgroup Expand Unlimited** — Removed artificial caps on subgroup expansion in search results; any number of courses can be expanded simultaneously to compare groups
- **Week 1 Default (Deep Fix)** — New courses now jump to `visibleWeeks.first()` (the first non-empty week) instead of relying on fallback guesswork, eliminating the Week 4 regression at the root
- **Empty Weeks Permanently Hidden** — Week dropdown only shows weeks in `activeWeeks` (confirmed by the scanner); unscanned and empty weeks are excluded, and the initially loaded week is immediately added to `activeWeeks` so it appears right away
- **In-App Self-Updating** — Queries GitLab Releases API on launch; prompts with an update dialog when a newer version is detected; downloads APK via Android DownloadManager and launches the system package installer
- **Fail-Safe Fallback** — HTTP 429/500 and network errors fall back to stale cache with an "⚠️ Offline / Cached Mode" banner
- **Background Sync** — WorkManager periodically refreshes cached timetables with configurable strategy-aware scheduling
- **Bookmark Courses** — Save courses for quick access from Settings
- **Search History** — Quick re-access to recent searches
- **Material 3 UI** — Jetpack Compose with dynamic color support and smooth crossfade animations

## How It Works

### Architecture
```
UI (Jetpack Compose)
  ├─ UpdateChecker → OkHttp → GitLab Releases API (self-updating)
  ├─ UpdateManager → DownloadManager → FileProvider → System Installer
  └─ TimetableRepository (strategy-aware TTL, request debouncer, fail-safe)
       ├─ Room Database (per-week indexable key-value cache)
       ├─ TimetableApiService → OkHttp → Scientia Publish API (TU Dublin)
       │     ├─ RequestDebouncer (URL-keyed singleton deduplication)
       │     └─ RateLimitInterceptor (Token Bucket; 5 req/10s)
       └─ WorkManager (strategy-aware periodic sync)
            └─ SyncNotificationManager (progress/completion notifications)
```

### Data Flow
1. User searches for a course → API returns matching programmes (debounced, stale-guarded)
2. Selecting a course loads the timetable via `TimetableRepository.loadTimetable()`
3. Repository checks cache first: if fresh within the configured SyncStrategy TTL → zero network
4. If cache is stale or missing → API call via `RequestDebouncer` (concurrent duplicates consolidated)
5. Events are parsed (pre-compiled regexes), deduplicated (O(n log n) single pass), and cached
6. A parallelized background scanner discovers which weeks have classes and which are empty
7. Semester boundaries are auto-detected from gaps in the schedule
8. The week dropdown shows only active weeks, numbered per semester; "All" toggle overrides
9. View state (semester, week, day, group) is persisted per course across app restarts
10. WorkManager refreshes cached data per the user's chosen SyncStrategy
11. Sync completions post notifications with success/fail status and timestamp

### Sync Strategies
| Mode | TTL | Behavior |
|------|-----|----------|
| **Daily** | 24 hours | Cache valid for 1 day — network calls blocked within window |
| **Weekly** | 7 days | Cache valid for 7 days |
| **Custom** | X days | User-defined interval (e.g., 3 days) |

Network calls are completely blocked if the app is opened while the cache is still fresh.

### Request Minimization
- **Singleton Request Debouncer** — Concurrent calls to the same `(course, week)` pair share one in-flight request. Others await the result.
- **Per-Week Indexing** — Each `(courseIdentity, weekStart)` cached independently. Switching weeks triggers zero network if already fetched.
- **Stale Query Guard** — SearchScreen captures query at effect start; if user types more during debounce, stale searches are discarded.
- **No Automatic HTTP Retries** — OkHttp `retryOnConnectionFailure` disabled; backoff handled at app layer.
- **Response Body Cleanup** — All OkHttp `Response` objects wrapped in `.use{}` to prevent socket leaks.

### Fail-Safe Behavior
| Scenario | Behavior |
|----------|----------|
| Network timeout / DNS failure | Fall back to stale cache, show "⚠️ Offline / Cached Mode" banner |
| HTTP 429 (Rate Limited) | Fall back to stale cache |
| HTTP 500+ (Server Error) | Fall back to stale cache |
| No cache available + network fail | Show error message with "Retry" button |
| Coroutine cancelled (navigation) | `CancellationException` propagated, cache left intact |

## Download

[**Download latest APK (v1.4)**](https://github.com/Izzeddin-Hammad/TUD-TimeTable-APP/raw/main/releases/TimeTable-v1.4-debug.apk)

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
