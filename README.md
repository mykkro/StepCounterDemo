# StepCounterDemo

A minimal Android app that counts your footsteps in real time using either the device's built-in hardware step counter or a custom accelerometer-based algorithm.

---

## Purpose

Demonstrates how to build a live step-counting app on Android using:
- The hardware `TYPE_STEP_COUNTER` sensor (accurate, battery-efficient)
- A software fallback using raw accelerometer data with a custom step-detection algorithm

The app tracks step count and elapsed session time, and lets the user choose the sensor source between sessions.

---

## Usage

1. Launch the app. On Android 10+ (API 29), grant the **Physical Activity** permission when prompted.
2. (Optional) Select a sensor source — **Hardware Step Counter** or **Accelerometer** — using the buttons shown while the counter is stopped.
3. Tap **Start** to begin counting. The screen shows the elapsed time (MM:SS) and running step count.
4. Tap **Stop** to end the session. The source selector reappears so you can switch modes before the next run.

> If neither sensor is available, the app will display a "No sensor available" message and disable the Start button.

---

## Architecture

The project follows **MVVM** with **Jetpack Compose** for the UI. There are no XML layouts.

```
┌─────────────────────────────────────────────────────┐
│                      View Layer                      │
│  MainActivity + StepCounterScreen (Jetpack Compose)  │
│  - Observes StateFlows from ViewModel                │
│  - Handles ACTIVITY_RECOGNITION permission request   │
└───────────────────────┬─────────────────────────────┘
                        │ StateFlows
┌───────────────────────▼─────────────────────────────┐
│                   ViewModel Layer                    │
│           StepCounterViewModel (AndroidViewModel)    │
│  - Manages SensorManager registration / teardown     │
│  - Exposes: isRunning, stepCount, elapsedSeconds,    │
│             activeSensorMode, preferredMode          │
│  - Runs a coroutine-based 1-second stopwatch         │
└───────────────────────┬─────────────────────────────┘
                        │ SensorEventListener
┌───────────────────────▼─────────────────────────────┐
│                   Sensor Layer (Android)             │
│  TYPE_STEP_COUNTER  │  TYPE_ACCELEROMETER            │
│  Baseline-relative  │  Low-pass gravity filter +     │
│  counting           │  magnitude hysteresis +        │
│                     │  250 ms inter-step guard       │
└─────────────────────────────────────────────────────┘
```

### Key files

| File | Role |
|---|---|
| [app/src/main/java/…/MainActivity.kt](app/src/main/java/com/example/stepcounterdemo/MainActivity.kt) | Single activity; hosts the Compose UI and permission flow |
| [app/src/main/java/…/StepCounterViewModel.kt](app/src/main/java/com/example/stepcounterdemo/StepCounterViewModel.kt) | All sensor logic, state management, and the session stopwatch |
| [app/src/main/java/…/ui/theme/](app/src/main/java/com/example/stepcounterdemo/ui/theme/) | Material3 theme with dynamic color (Android 12+) and light/dark support |
| [app/src/main/AndroidManifest.xml](app/src/main/AndroidManifest.xml) | Declares `ACTIVITY_RECOGNITION` permission and the single launcher activity |

### Tech stack

- **Language:** Kotlin
- **UI:** Jetpack Compose + Material3
- **Architecture:** MVVM (`AndroidViewModel`, `StateFlow`)
- **Async:** Kotlin Coroutines
- **Min SDK:** 24 (Android 7.0) — **Target SDK:** 36
