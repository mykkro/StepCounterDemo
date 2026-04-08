# Step Counter Redesign — Design Spec

**Date:** 2026-04-08  
**Status:** Approved

## Overview

Modify the existing StepCounterDemo Android app to:
1. Remove accelerometer mode — use only the hardware step counter sensor
2. Add a "Run in background" option backed by an Android Foreground Service
3. Add an hourly step graph (last 24 hours) drawn with Compose Canvas, persisted in Room

## Architecture

**Pattern:** Application-scoped Repository  
A singleton `StepRepository` (held by `StepCounterApplication`) is the single source of truth shared between the `StepCounterService` and the `StepCounterViewModel`. No service binding needed.

```
StepCounterService  ──writes──▶  StepRepository  ◀──reads──  StepCounterViewModel
                                        │
                                   Room DB (hourly_steps)
```

## Data Layer

### Room Database — `StepDatabase`
Single table: `hourly_steps`

| Column     | Type | Notes                                              |
|------------|------|----------------------------------------------------|
| `hourKey`  | Long | Primary key. `System.currentTimeMillis() / 3_600_000` |
| `stepCount`| Int  | Steps accumulated during this hour                 |

### `HourlyStepDao`
- `upsert(hourKey, delta)` — reads existing row, adds delta, writes back (in a `@Transaction`)
- `getLast24Hours(): Flow<List<HourlyStepEntity>>` — queries rows where `hourKey >= currentHour - 23`

### `StepRepository` (Application-scoped singleton)
Exposes:
- `val stepCount: StateFlow<Int>` — live session step count
- `val elapsedSeconds: StateFlow<Long>` — live session elapsed time
- `val isRunning: StateFlow<Boolean>`
- `fun addSteps(delta: Int)` — increments `stepCount`, writes delta to current hour in DB
- `fun setRunning(running: Boolean)`
- `fun resetSession()` — zeroes stepCount and elapsedSeconds
- `fun last24HoursFlow(): Flow<List<HourlyStepEntity>>` — delegates to DAO

### `StepCounterApplication`
Custom `Application` subclass. Initialises `StepRepository` and `StepDatabase` on `onCreate()`. Declared in `AndroidManifest.xml`.

## Service Layer

### `StepCounterService` — Foreground Service
- Lifecycle: started via `startForegroundService(intent)`, stopped via `stopSelf()` or `stopService(intent)`
- On start: calls `repository.setRunning(true)`, `repository.resetSession()`, registers `TYPE_STEP_COUNTER` sensor listener, starts 1-second timer coroutine for `elapsedSeconds`, posts foreground notification
- On stop: unregisters sensor, cancels timer, calls `repository.setRunning(false)`
- **Step delta tracking:** stores `lastTotalSteps: Long = -1`. On each `TYPE_STEP_COUNTER` event, if `lastTotalSteps < 0` set baseline, else `delta = total - lastTotalSteps`; call `repository.addSteps(delta)`; update `lastTotalSteps = total`
- **Notification:** channel id `step_counter_channel`, title "Step Counter", content "N steps", updated on each step event. Has a **Stop** action (`PendingIntent` → `stopSelf()`).
- **Manifest requirements:** `FOREGROUND_SERVICE` permission, `POST_NOTIFICATIONS` permission (Android 13+), service declared with `android:foregroundServiceType="health"`

## UI Layer

### `StepCounterScreen` (simplified)
Removes sensor mode selector and all accelerometer UI. Layout (top to bottom):
1. Timer (`displayLarge`)
2. Step count (`displayMedium`)
3. "Run in background" `Switch` with label — disabled while running
4. Start / Stop buttons (row)
5. "Last 24h" `OutlinedButton` — always visible

### `StepCounterViewModel`
- Reads `isRunning`, `stepCount`, `elapsedSeconds` from `StepRepository` as `StateFlow`s
- Holds `_runInBackground: MutableStateFlow<Boolean>` (default `false`)
- `fun start()` — calls `startForegroundService(intent)` on app context
- `fun stop()` — calls `stopService(intent)` on app context
- `fun last24HoursFlow()` — delegates to `repository.last24HoursFlow()`
- Removes all accelerometer code (`SensorMode`, gravity arrays, `handleAccelerometer`, `warmupSamples`, etc.)

### Background toggle behaviour (in `MainActivity`)
- When **"Run in background" is OFF** (default): `onStop()` calls `viewModel.stop()` if running
- When **"Run in background" is ON**: `onStop()` does nothing; service keeps running until Stop is tapped in-app or via notification

### `HourlyStepChart` Composable
Shown as a full-screen overlay (`AnimatedVisibility` or `Box` over the main content) when the "Last 24h" button is tapped.

**Rendering (Canvas):**
- 24 bars representing hours `currentHour-23` through `currentHour`
- Bar width computed from canvas width / 24 with small horizontal gap
- Bar height proportional to `stepCount / maxStepCount` (if all zero, show empty bars)
- Hour label (0–23) drawn below each bar
- Step count drawn above each bar (omitted if 0)
- X-axis line drawn at bar base
- Current hour bar highlighted in primary colour; past hours in secondary colour
- A **Close** button (top-right) dismisses the overlay

## Permissions & Manifest Changes
- Add `android.permission.FOREGROUND_SERVICE`
- Add `android.permission.POST_NOTIFICATIONS` (runtime-requested on Android 13+)
- Add `android.permission.FOREGROUND_SERVICE_HEALTH` (Android 14+)
- Declare `StepCounterService` with `android:foregroundServiceType="health"`
- Declare `StepCounterApplication` as `android:name`

## Files Added / Modified

| File | Change |
|------|--------|
| `StepCounterApplication.kt` | **New** — custom Application, owns Repository + DB |
| `StepRepository.kt` | **New** — shared state + DB bridge |
| `StepDatabase.kt` | **New** — Room DB + entity + DAO |
| `StepCounterService.kt` | **New** — Foreground Service |
| `StepCounterViewModel.kt` | **Modified** — remove accel code, delegate to repository/service |
| `MainActivity.kt` | **Modified** — remove mode selector, add background switch + graph button |
| `HourlyStepChart.kt` | **New** — Canvas bar chart composable |
| `AndroidManifest.xml` | **Modified** — permissions, service, application name |
| `app/build.gradle.kts` | **Modified** — add Room dependencies |

## Dependencies Added
- `androidx.room:room-runtime`
- `androidx.room:room-ktx` (coroutines support)
- `androidx.room:room-compiler` (kapt or KSP annotation processor)

## Out of Scope
- Boot receiver (steps do not resume automatically after reboot)
- Multiple concurrent sessions
- Step goal / notifications beyond the running indicator
