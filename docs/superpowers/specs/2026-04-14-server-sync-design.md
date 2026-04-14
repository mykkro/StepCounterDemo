---
title: Server Sync Design
date: 2026-04-14
status: APPROVED
---

# Server Sync — Design Document

## 1. Overview

Add server sync capability to the StepCounter app. The user can configure server credentials (host URL, username, password) via a settings screen, test the connection, and manually trigger a sync that uploads all unsynced hourly step records to the server in batches.

The server API is defined in `server/DESIGN.md`. This document covers the Android client side only.

---

## 2. UI Changes

### 2.1 Main screen

- The existing top-right row (language flags) gains a **⚙ gear button** appended after the flags. Tapping it navigates to the Settings screen.
- A **Sync button** (filled/primary style, `Button`) is added beside the existing "Last 24h" outlined button.
  - Disabled when server credentials are not configured (host + username + password all non-empty).
  - Disabled while a sync is in progress (shows a small `CircularProgressIndicator` instead of the label).
- A **hint text line** is shown below the button row:
  - When `lastSyncTime > 0`: "Last sync: HH:mm"
  - When last sync failed: "Last sync failed"
  - Hidden when credentials are not configured.
- A `SnackbarHost` is added to the `Scaffold`. On sync completion (success or failure) a Snackbar is shown briefly:
  - Success: "Synced N records"
  - Failure: "Sync failed: {reason}"

### 2.2 Settings screen

A new `SettingsScreen` composable, reached via Compose Navigation (`androidx.navigation:navigation-compose`).

Fields:
- **Host URL** — `OutlinedTextField`, keyboard type URL
- **Username** — `OutlinedTextField`
- **Password** — `OutlinedTextField` with `PasswordVisualTransformation`

Actions:
- **Test Connection** button (outlined, full width) — calls authenticate, shows inline result below the button: spinner while in progress, green "Connection successful" or red error message.
- **Top bar**: title "Server Settings", back arrow (discards unsaved changes), **Save** text action (writes fields to SharedPreferences and pops back).

Save is always enabled regardless of field content — the Sync button on the main screen enforces the non-empty check.

---

## 3. Storage (SharedPreferences)

All keys stored in the existing `"app_prefs"` SharedPreferences file.

| Key | Type | Description |
|---|---|---|
| `server_host` | String | Base URL, e.g. `https://my-server.example.com` |
| `server_username` | String | Login username |
| `server_password` | String | Login password |
| `device_guid` | String | UUID auto-generated on first run; sent during auth |
| `human_id` | String | `client_guid` extracted from JWT `sub` after first successful login |
| `jwt_token` | String | Cached JWT; reused across syncs; refreshed on 401 |
| `last_synced_hour` | Long | `hourKey` of the last successfully synced record (0 = never) |
| `last_sync_time` | Long | Epoch ms of last successful sync (0 = never) |

`device_guid` is generated once on first app run (UUID.randomUUID()) and never changes. `human_id` and `jwt_token` are populated on the first successful Test Connection or Sync.

---

## 4. Networking — `SyncRepository`

A new `SyncRepository` class, owned by `StepCounterApplication`, constructed with `SharedPreferences` and `HourlyStepDao`.

HTTP client: **OkHttp** (single dependency). Timeouts: 15s connect, 30s read. JSON: `org.json.JSONObject` (ships with Android — no extra dependency).

### 4.1 `authenticate(host, username, password, deviceGuid): AuthResult`

- `POST {host}/api/devices/auth` with credentials JSON body.
- On HTTP 200: decodes the JWT (base64-split on `.`, parse middle segment as JSON), extracts `sub` as `client_guid`.
- Stores `human_id` and `jwt_token` in SharedPreferences.
- Returns `AuthResult.Success` or `AuthResult.Failure(message)`.

### 4.2 `syncSteps(): SyncResult`

1. Reads all `HourlyStepEntity` rows where `hourKey > last_synced_hour`, ordered ascending.
2. Converts each row to a measurement item:
   - `startTime = hourKey * 3_600_000`
   - `endTime = (hourKey + 1) * 3_600_000`
   - `guid` — deterministic UUID derived from `device_guid + hourKey` (UUID.nameUUIDFromBytes)
   - `humanId` — stored `human_id`
   - `stepCount` — row's stepCount
3. Sends in batches of up to 100 items: `POST {host}/api/devices/measurements/stepcounter` with `Authorization: Bearer {jwt_token}`.
4. On HTTP 401: re-authenticates once using stored credentials, retries the batch. If auth fails, returns `SyncResult.Failure("Auth failed")`.
5. After each successful batch: advances `last_synced_hour` to the highest hourKey in that batch and persists it immediately. This ensures that if a later batch fails, the next sync resumes from the correct position rather than re-sending already-accepted records.
6. After all batches complete: updates `last_sync_time` to current epoch ms.
7. Returns `SyncResult.Success(totalAccepted)` or `SyncResult.Failure(message)`.

---

## 5. ViewModels

### 5.1 `SettingsViewModel`

Holds field state (`host`, `username`, `password`) and `testState: TestState` (Idle / Loading / Success / Failure(message)). Calls `SyncRepository.authenticate()` on Test Connection.

### 5.2 `StepCounterViewModel` additions

| Addition | Description |
|---|---|
| `serverConfigured: StateFlow<Boolean>` | True when host + username + password are all non-empty |
| `syncState: StateFlow<SyncState>` | Idle / InProgress / Success(count, time) / Failure(message) |
| `lastSyncTime: StateFlow<Long>` | Loaded from SharedPreferences |
| `fun sync()` | Launches `SyncRepository.syncSteps()` in viewModelScope, updates syncState |

---

## 6. Navigation

`MainActivity` wraps content in a `NavHost` with two destinations:

| Route | Composable |
|---|---|
| `"main"` | `StepCounterScreen` (existing, adapted) |
| `"settings"` | `SettingsScreen` (new) |

Start destination: `"main"`. Gear icon calls `navController.navigate("settings")`. Settings back/save pops back to `"main"`.

---

## 7. New dependency

```
implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("androidx.navigation:navigation-compose:2.7.7")
```

---

## 8. New files

```
app/src/main/java/com/example/stepcounterdemo/
  SyncRepository.kt
  SettingsViewModel.kt
  ui/SettingsScreen.kt
```

## 9. Modified files

```
app/src/main/java/com/example/stepcounterdemo/
  StepCounterApplication.kt   — instantiate SyncRepository
  StepCounterViewModel.kt     — add sync state + serverConfigured
  MainActivity.kt             — add NavHost, SnackbarHost, gear button, Sync button, hint text
app/build.gradle.kts          — add OkHttp + navigation-compose dependencies
```

---

## 10. Out of scope

- Automatic background sync (sync is manual only — button tap)
- Sync on app start
- Conflict resolution (server is idempotent; already-accepted guids count as success)
- Exposing `device_guid` or `human_id` in the UI
