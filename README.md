# StepCounterDemo

An Android app that counts steps using the device's hardware `TYPE_STEP_COUNTER` sensor, persists hourly totals to a local database, and syncs them to a REST server.

---

## Features

- **Live step counting** via the hardware step counter sensor, running as a foreground service so it works with the screen off
- **Hourly persistence** — steps are accumulated per hour key in a Room database; data survives reboots and app restarts
- **24-hour chart** — bar chart of the last 24 hourly buckets, accessible from the main screen
- **Server sync** — uploads unsent hourly records to the server over HTTPS; supports token refresh and idempotent retry
- **Settings screen** — enter server URL, username and password; "Test Connection" verifies API compatibility and credentials before saving
- **Language switching** — Czech / English switchable at runtime from the main screen

---

## Usage

1. Launch the app. On Android 10+ grant **Physical Activity** permission when prompted.
2. Tap **Start** to begin counting. The foreground service starts and shows a persistent notification with a Stop action.
3. Tap **Stop** to end the session.
4. Tap **Last 24h** to view the hourly step chart.
5. Open **Settings** (⚙) to configure the server. Tap **Test Connection** to validate. Tap **Save** to persist credentials.
6. Tap **Sync** to upload unsent records. The button is disabled until server credentials are saved.

---

## Architecture

The project follows **MVVM** with **Jetpack Compose** for the UI.

```
┌───────────────────────────────────────────────────────────────┐
│                         UI Layer                              │
│  MainActivity — NavHost with two Compose destinations:        │
│    StepCounterScreen  (main screen, NavHost root)             │
│    SettingsScreen     (server settings)                       │
│  SnackbarHost for sync success / failure feedback             │
└───────────┬───────────────────────────┬───────────────────────┘
            │ StateFlow                 │ StateFlow
┌───────────▼─────────────┐ ┌──────────▼──────────────────────┐
│  StepCounterViewModel   │ │       SettingsViewModel          │
│  (AndroidViewModel)     │ │       (AndroidViewModel)         │
│  - starts/stops service │ │  - host / username / password    │
│  - triggers sync        │ │  - testConnection()              │
│  - exposes syncState,   │ │    → checkApiVersion + auth      │
│    lastSyncTime, etc.   │ │  - save()                        │
└───────────┬─────────────┘ └──────────────────────────────────┘
            │
┌───────────▼─────────────┐   ┌──────────────────────────────┐
│     StepRepository      │   │       SyncRepository         │
│  - in-memory StateFlows │   │  - checkApiVersion()         │
│  - writes to Room DB    │   │  - authenticate()            │
│    (addSteps, hourly    │   │  - submitBatch()             │
│     accumulation)       │   │  OkHttp, JSON, no 3rd-party  │
└───────────┬─────────────┘   │  serialisation library       │
            │                 └──────────────────────────────┘
┌───────────▼─────────────┐
│   StepCounterService    │
│   (ForegroundService)   │
│  - SensorEventListener  │
│    on TYPE_STEP_COUNTER  │
│  - 1 s coroutine timer  │
│  - persistent notif.    │
└─────────────────────────┘
```

### Key files

| File | Role |
|---|---|
| [MainActivity.kt](app/src/main/java/com/example/stepcounterdemo/MainActivity.kt) | Single activity; NavHost, permission requests, locale override |
| [StepCounterViewModel.kt](app/src/main/java/com/example/stepcounterdemo/StepCounterViewModel.kt) | Main-screen state: running, step count, elapsed time, sync flow |
| [SettingsViewModel.kt](app/src/main/java/com/example/stepcounterdemo/SettingsViewModel.kt) | Settings-screen state: credentials, test-connection flow |
| [StepCounterService.kt](app/src/main/java/com/example/stepcounterdemo/service/StepCounterService.kt) | Foreground service; sensor listener; 1 s elapsed timer |
| [StepRepository.kt](app/src/main/java/com/example/stepcounterdemo/StepRepository.kt) | In-memory StateFlows + Room write path; hourly accumulation |
| [SyncRepository.kt](app/src/main/java/com/example/stepcounterdemo/SyncRepository.kt) | All HTTP calls to the server API (OkHttp) |
| [data/StepDatabase.kt](app/src/main/java/com/example/stepcounterdemo/data/StepDatabase.kt) | Room database; single table `hourly_steps` |
| [data/HourlyStepEntity.kt](app/src/main/java/com/example/stepcounterdemo/data/HourlyStepEntity.kt) | `hourKey` (epoch hours) + `stepCount` |
| [ui/HourlyStepChart.kt](app/src/main/java/com/example/stepcounterdemo/ui/HourlyStepChart.kt) | Canvas-drawn 24-hour bar chart |
| [ui/SettingsScreen.kt](app/src/main/java/com/example/stepcounterdemo/ui/SettingsScreen.kt) | Settings Compose screen |
| [LocaleManager.kt](app/src/main/java/com/example/stepcounterdemo/LocaleManager.kt) | Runtime locale switching (CS / EN) |

### Tech stack

- **Language:** Kotlin
- **UI:** Jetpack Compose + Material 3
- **Architecture:** MVVM (`AndroidViewModel`, `StateFlow`)
- **Async:** Kotlin Coroutines
- **Database:** Room
- **Networking:** OkHttp (no Retrofit; JSON parsed with `org.json`)
- **Min SDK:** 24 (Android 7.0) — **Target SDK:** 36

---

## Server Communication

The app talks to the **Step Counter REST API v1.0**. All endpoints are under `<host>/api/devices/`.

### API version check

Before authenticating, `SettingsViewModel.testConnection()` calls:

```
GET /api/devices/stepcounter/version
```

The server returns:

```json
{ "api": "stepcounter", "version": "1.0", "released": "2026-04-15" }
```

The app requires **version ≥ 1.0**. If the server responds with an older version, or if the endpoint is unreachable, "Test Connection" fails with an explicit message before any credentials are sent.

### Authentication

```
POST /api/devices/auth
Content-Type: application/json

{
  "username":    "<username>",
  "password":    "<password>",
  "device_guid": "<UUID generated once on first install>",
  "device_type": "stepcounter"
}
```

On success the server returns a JWT (valid for 1 hour) and a `session_guid`:

```json
{
  "token":        "<JWT>",
  "session_guid": "<UUID>",
  "expires_in":   3600
}
```

The JWT `sub` claim carries the `client_guid` (the user's identity on the server). The app extracts it via Base64 decoding — no JWT library is used. Both `token` and `humanId` (= `sub`) are stored in SharedPreferences for subsequent sync calls.

### Batch upload

```
POST /api/devices/measurements/stepcounter
Authorization: Bearer <JWT>
Content-Type: application/json

{
  "measurements": [
    {
      "guid":      "<deterministic UUID: nameUUIDFromBytes(deviceGuid:hourKey)>",
      "humanId":   "<client_guid from JWT sub>",
      "startTime": <epoch ms — start of hour>,
      "endTime":   <epoch ms — end of hour>,
      "stepCount": <non-negative int>
    }
  ]
}
```

Each item represents one hourly bucket. The `guid` is derived deterministically from `deviceGuid + hourKey` so that retrying an entire batch never creates duplicate records on the server (idempotent by design).

The server responds with:

```json
{ "accepted": ["<compact-guid>", ...], "rejected": [...] }
```

The app reads `accepted.length` and advances the local watermark (`last_synced_hour` in SharedPreferences) after each successful batch.

### Sync flow (step by step)

```
StepCounterViewModel.sync()
  │
  ├─ Read hourly records from Room where hourKey > last_synced_hour
  │    (if none: mark success, return)
  │
  ├─ for each batch of ≤ 100 records:
  │    │
  │    ├─ submitBatch() → 200 OK
  │    │    └─ advance last_synced_hour watermark
  │    │
  │    ├─ submitBatch() → 401 Unauthorized
  │    │    └─ authenticate() → new token → retry submitBatch()
  │    │         ├─ success → continue
  │    │         └─ auth failure → abort, show error
  │    │
  │    ├─ submitBatch() → 403 Forbidden (DeviceRejected)
  │    │    └─ abort, show "Device rejected by server"
  │    │
  │    └─ submitBatch() → other error
  │         └─ abort, show HTTP error
  │
  └─ all batches done → update last_sync_time, show success snackbar
```

A `Mutex` prevents concurrent sync invocations.

### Error outcomes

| Server response | `BatchResult` | User-visible message |
|---|---|---|
| HTTP 200 | `Success(accepted)` | "Synced N records" (snackbar) |
| HTTP 401 | `Unauthorized` | triggers re-auth; if re-auth fails → "Authentication failed" |
| HTTP 403 | `DeviceRejected` | "Device rejected by server" |
| HTTP 4xx/5xx | `Failure(message)` | "Sync failed: HTTP NNN" |
| Network error | `Failure(message)` | "Sync failed: \<exception message\>" |

### SharedPreferences keys

| Key | Contents |
|---|---|
| `server_host` | Server base URL |
| `server_username` | Username |
| `server_password` | Password |
| `device_guid` | UUID generated on first launch; stable per install |
| `jwt_token` | Most recently issued JWT |
| `human_id` | `sub` claim extracted from last JWT |
| `last_synced_hour` | Watermark: highest hourKey successfully uploaded |
| `last_sync_time` | Epoch ms of last successful sync (for UI display) |

---

## Permissions

| Permission | Required on | Reason |
|---|---|---|
| `ACTIVITY_RECOGNITION` | Android 10+ (API 29+) | Access to `TYPE_STEP_COUNTER` sensor |
| `POST_NOTIFICATIONS` | Android 13+ (API 33+) | Show the foreground-service notification |
| `INTERNET` | All versions | Upload step data to the server |
| `FOREGROUND_SERVICE` | All versions | Run `StepCounterService` in the foreground |

---

## Server test client

The `server/1.0/test-client/` directory contains a Python test client that simulates the Android app:

```bash
cd server/1.0/test-client
pip install -r requirements.txt
python test_client.py --base-url http://localhost/... --username client1 --password secret
```

It authenticates, submits synthetic step events, exercises idempotency (sends the same batch twice), and tests malformed-record and client-mismatch rejection paths.
