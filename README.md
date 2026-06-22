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
- **Smart Semester Detection** — Auto-detects Semester 1 & 2 boundaries by scanning all academic weeks with rate-limit-aware serial scanning (2.1s delay between requests)
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
- **Subgroup UI Improvements** — 48dp touch target for dropdown arrows; gold star tint (⭐) on pinned/default subgroups for clear visual feedback
- **Week 1 Default** — New/uncached courses default to `visibleWeeks.first()` (the first non-empty week); eliminates the Week 4 regression at the root
- **Empty Weeks Permanently Hidden** — Week dropdown only shows weeks not in `emptyWeeks` (confirmed by scanner); empty weeks progressively disappear as the scanner processes them
- **Subgroup Expand Unlimited** — Removed artificial caps on subgroup expansion in search results; any number of courses can be expanded simultaneously to compare groups
- **In-App Self-Updating** — Queries GitLab Releases API on launch; prompts with an update dialog when a newer version is detected; downloads APK via Android DownloadManager and launches the system package installer
- **Fail-Safe Fallback** — HTTP 429/500 and network errors fall back to stale cache with an "⚠️ Offline / Cached Mode" banner
- **Background Sync** — WorkManager periodically refreshes cached timetables with configurable strategy-aware scheduling
- **Bookmark Courses** — Save courses for quick access from Settings
- **Search History** — Quick re-access to recent searches
- **Material 3 UI** — Jetpack Compose with dynamic color support and smooth crossfade animations
- **Defensive JSON Parsing** — All API response parsers wrapped in try/catch; malformed HTML/XML responses emit clean `TimetableApiException(502)` instead of leaking raw `JSONException` text to the UI
- **Reactive Background Sync** — UI polls Room every 2 minutes; if WorkManager updated cache, the timetable refreshes automatically without manual pull-to-refresh
- **Performance Optimized** — `derivedStateOf` on group filtering prevents recomposition churn; serial scanner with 2.1s delay respects rate limiter, preventing cascading 429 failures
- **Unified Rate Limiter** — Both UI and WorkManager share a single `TimetableApiService.DEFAULT` singleton with one OkHttp client and one token-bucket rate limiter
- **Instant Cache Load** — Stale-while-revalidate: cached data renders immediately (~5ms), then refreshes silently in background; `toUiEvent` date parsing offloaded to `Dispatchers.Default`
- **Compose Stability** — `@Immutable` annotations on `TimetableEvent`, `SearchResult`, `ApiEvent`, `CacheResult` let Compose skip unchanged-item recomposition checks
- **Room Performance** — Composite index on `[courseIdentity, weekStart]` + `fetchedAt` index for cache pruning; DB v7 schema

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
2. Selecting a course loads the timetable via two-phase stale-while-revalidate:
   - **Phase 1 (instant)**: Room cache read + `toUiEvent` parsing on `Dispatchers.Default` → UI renders in ~5ms
   - **Phase 2 (background)**: `TimetableRepository.loadTimetable()` — fresh cache short-circuits; stale cache triggers network fetch silently
3. Events are parsed (pre-compiled regexes), deduplicated (O(n log n) single pass), and cached
4. A rate-limit-aware serial background scanner (2.1s delay between requests) discovers which weeks have classes
5. Semester boundaries are auto-detected from 3+ consecutive empty-week gaps in the schedule
6. The week dropdown shows only non-empty weeks, numbered per semester; empty weeks are permanently hidden
7. View state (semester, week, day, group) is persisted per course across app restarts
8. WorkManager refreshes cached data per the user's chosen SyncStrategy
9. A 2-minute background poll detects WorkManager cache updates and refreshes the UI automatically
10. Sync completions post notifications with success/fail status and timestamp

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
