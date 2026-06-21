# TimeTable

> **TU Dublin by default — supports any Scientia Publish institution.** TU Dublin is pre-configured. To add your university, add one entry to `Institution.ALL` in `Institution.kt` and select it in Settings.

A smart Android timetable app that fetches your university schedule directly from the Scientia Publish API — no manual entry needed.

## Features

- **Direct API Integration** — Connects to the TU Dublin Scientia timetable API with Anonymous auth
- **Course Search** — Search by course code or name with real-time debounced results
- **Smart Semester Detection** — Auto-detects Semester 1 & 2 boundaries by scanning all academic weeks in the background
- **Week Dropdown** — Numbered weeks (W1, W2, ...) with empty weeks automatically hidden
- **Subgroup Filtering** — Select specific class groups (A, B, G1, G2, etc.) via dropdown with ⭐ default group pinning
- **Pin to Home** — Star a course to make it your home screen; opens instantly on launch
- **Offline Cache** — Room database caches timetables for 4 hours; view your schedule even without internet
- **Background Sync** — WorkManager periodically refreshes cached timetables in the background
- **Bookmark Courses** — Save courses for quick access from Settings
- **Search History** — Quick re-access to recent searches
- **Material 3 UI** — Jetpack Compose with dynamic color support and smooth crossfade animations

## How It Works

### Architecture
```
UI (Jetpack Compose)
  └─ Repository (cache-first strategy)
       ├─ Room Database (local cache)
       └─ OkHttp → Scientia Publish API
            └─ WorkManager (background sync)
```

### Data Flow
1. User searches for a course → API returns matching programmes
2. Selecting a course fetches the timetable for the current week
3. Events are parsed, deduplicated, and cached locally
4. A background scanner discovers which weeks have classes and which are empty
5. Semester boundaries are auto-detected from gaps in the schedule
6. The week dropdown shows only active weeks, numbered per semester
7. WorkManager refreshes cached data every 6-24 hours (configurable)

### Tech Stack
- **Kotlin** + **Jetpack Compose** (Material 3)
- **Room** for local caching
- **OkHttp** for API calls
- **WorkManager** for background sync
- **Coroutines** for async operations
- **JUnit** + **Room Testing** for tests (113+ test scenarios)

## Download

[**Download latest APK (v1.0)**](https://github.com/Izzeddin-Hammad/TimeTable-APP/raw/main/releases/TimeTable-v1.0-debug.apk)

> Requires Android 8.0+ (API 26). Enable "Install from unknown sources" to sideload.

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
