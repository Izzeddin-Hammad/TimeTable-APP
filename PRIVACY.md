# Privacy Policy — TimeTable

**Last updated:** 23 June 2026  
**App version:** 1.15  
**Developer:** Izzeddin Hammad  
**Repository:** [Izzeddin-Hammad/TUD-TimeTable-APP](https://gitlab.com/Izzeddin-Hammad/TUD-TimeTable-APP)

---

## Zero Data Collection

**TimeTable collects no personal data, usage analytics, crash reports, or any other information from its users.**

The app has no telemetry, no analytics SDK, no advertising, and no third-party tracking of any kind. It operates entirely on-device and communicates only with the TU Dublin Scientia Publish API to fetch publicly available timetable information.

---

## Data Flow

```
TU Dublin Scientia Publish API
        │
        │  HTTPS (no third party)
        ▼
┌─────────────────────────┐
│     TimeTable App       │
│                         │
│  ┌───────────────────┐  │
│  │  Room Database     │  │  ← cached timetables
│  │  (on-device only)  │  │
│  └───────────────────┘  │
│                         │
│  ┌───────────────────┐  │
│  │  SharedPreferences │  │  ← user settings + preferences
│  │  (on-device only)  │  │
│  └───────────────────┘  │
└─────────────────────────┘
        │
        ▼
  GitLab Releases API
  (only for update checks,
   no personal data sent)
```

### What data is stored on your device

| Data type | Where | Purpose | Can you delete it? |
|---|---|---|---|
| Cached timetable events (module code, title, lecturer, room, dates, group) | Room DB (`timetable_cache.db`) | Offline access to your schedule | Yes — Settings → Clear Cache |
| Saved/bookmarked courses | Room DB | Quick access to frequent courses | Yes — Settings → Remove |
| Search history | Room DB | Quick re-search | Yes — Settings → Clear History |
| Sync preferences | SharedPreferences | Your sync strategy, starred course, UI state | Yes — Clear app data |
| Update download ID | SharedPreferences | APK auto-update tracking | Yes — Clear app data |

### What data is sent over the network

| Destination | Data sent | Purpose |
|---|---|---|
| `scientia-eu-v4-api-d4-01.azurewebsites.net` (TU Dublin) | Course search query, course identity | Fetch public timetable data |
| `gitlab.com` | None (public release API) | Check for app updates |

All network requests are made over **HTTPS**. No data is sent to any analytics server, advertising network, or third-party service.

---

## Your Rights Under GDPR (Articles 15–21)

Since the app stores **zero personal data** and performs **zero data collection**, there is nothing to access, rectify, erase, restrict, or port. If you wish to clear all locally stored data:

1. Open the app → **Settings**
2. Tap **Clear Cache** (removes cached timetables)
3. Tap **Clear Search History** (removes recent searches)
4. Unstar the pinned course (removes star preference)
5. Or go to Android **Settings → Apps → TimeTable → Storage → Clear data** (removes everything)

---

## Third-Party Services

| Service | Role | Privacy notice |
|---|---|---|
| TU Dublin Scientia API | Timetable data provider | Governed by TU Dublin's privacy policy |
| GitLab.com | Release hosting for app updates | [GitLab Privacy Policy](https://about.gitlab.com/privacy/) |
| GitHub (APK mirror) | Release hosting for app updates | [GitHub Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-privacy-statement) |

---

## Contact

For privacy-related questions, open an issue on the [repository](https://gitlab.com/Izzeddin-Hammad/TUD-TimeTable-APP/-/issues) or contact the developer via GitLab.

---

## Changes to This Policy

If this policy changes, the new version will be posted here and the "Last updated" date will be revised. Since the app collects no data, material changes are unlikely.
