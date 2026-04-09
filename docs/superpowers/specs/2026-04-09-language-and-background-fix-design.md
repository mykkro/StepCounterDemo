# Language Switching + Background Fix — Design Spec

**Date:** 2026-04-09

---

## Goal

Add Czech/English language switching with emoji flag buttons, and fix `runInBackground` so it persists across app restarts and correctly stops the foreground service when toggled off.

---

## Section 1: Language Switching

### Approach

Manual locale wrapping via `attachBaseContext` + SharedPreferences. No third-party libraries. No Android per-app locale API (requires API 33+; minSdk is 24).

### New file: `LocaleManager.kt`

Utility object with two responsibilities:

- **Read/write** locale preference — SharedPreferences file `"app_prefs"`, key `"app_locale"`, values `"cs"` (default) or `"en"`
- **Apply locale** — wraps a `Context` via `createConfigurationContext(Configuration().apply { setLocale(Locale(code)) })` and returns the wrapped context

### `MainActivity` changes

- Override `attachBaseContext(newBase: Context)` to call `LocaleManager.applyLocale(newBase)` so all resource resolution uses the stored locale from the moment the activity is created
- Add two flag `Text` buttons (`🇨🇿` / `🇬🇧`) in a `Box` at `Alignment.TopEnd` overlaid on the main screen
- On tap: call `LocaleManager.setLocale(context, code)` then `(context as Activity).recreate()`

### String resources

All hardcoded UI strings extracted to resource files:

**`values/strings.xml`** (Czech — default):
| Key | Value |
|-----|-------|
| `app_name` | `Krokoměr` |
| `run_in_background` | `Běžet na pozadí` |
| `start` | `Spustit` |
| `stop` | `Zastavit` |
| `last_24h` | `Posl. 24 h` |
| `steps` | `kroků` |
| `notification_title` | `Krokoměr` |
| `notification_stop_action` | `Zastavit` |
| `last_24_hours_title` | `Posledních 24 hodin` |

**`values-en/strings.xml`** (English):
| Key | Value |
|-----|-------|
| `app_name` | `Step Counter` |
| `run_in_background` | `Run in background` |
| `start` | `Start` |
| `stop` | `Stop` |
| `last_24h` | `Last 24h` |
| `steps` | `steps` |
| `notification_title` | `Step Counter` |
| `notification_stop_action` | `Stop` |
| `last_24_hours_title` | `Last 24 hours` |

### Notification strings

`StepCounterService.buildNotification()` reads notification title and stop-action label from `getString(R.string.notification_title)` and `getString(R.string.notification_stop_action)`. The service uses the application context which inherits the locale set at activity level — strings will reflect whichever language was active when the service was started.

### Locale scope

`recreate()` restarts the activity. The locale is re-applied in `attachBaseContext` on each restart. No fragment or composable-level locale management is needed.

---

## Section 2: Background Fix

### Problem

`runInBackground` is currently an in-memory `MutableStateFlow(false)` in `StepCounterViewModel`. It resets to `false` on every app launch. Additionally, toggling it off while the service is running does not stop the service.

### Fix: `StepCounterViewModel` changes

**1. Restore on creation**

`init` block reads `"run_in_background"` (Boolean, default `false`) from SharedPreferences `"app_prefs"` and initializes `_runInBackground` with the stored value.

**2. Persist and act on change**

`setRunInBackground(value: Boolean)`:
- Writes `value` to SharedPreferences `"app_prefs"` / `"run_in_background"`
- Updates `_runInBackground.value = value`
- If `value == false` and `isRunning.value == true` → calls `stop()` immediately

**3. Switch enabled rule**

```
enabled = !isRunning || runInBackground
```

- Running + background ON → switch is enabled (user can turn it OFF to stop)
- Running + background OFF → switch is disabled (can't turn ON mid-session)
- Not running → switch is always enabled

### Stop path (unchanged)

`stop()` → `stopService()` → `StepCounterService.onDestroy()` → `repository.setRunning(false)` → notification removed automatically by the system.

### SharedPreferences file

Both language and background preference share the same file name `"app_prefs"` — two keys: `"app_locale"` and `"run_in_background"`.

---

## Files Touched

| File | Change |
|------|--------|
| `app/src/main/res/values/strings.xml` | Create — Czech default strings |
| `app/src/main/res/values-en/strings.xml` | Create — English strings |
| `LocaleManager.kt` | Create — locale read/write/apply utility |
| `MainActivity.kt` | Add `attachBaseContext`, flag buttons, use string resources |
| `StepCounterViewModel.kt` | Persist `runInBackground`, fix `setRunInBackground` logic, fix switch enabled |
| `service/StepCounterService.kt` | Use string resources for notification title + stop action |
| `ui/HourlyStepChart.kt` | Use string resource for chart title (if hardcoded) |

---

## Out of Scope

- RTL layout support
- Dynamic language switch without activity recreate
- Locale change affecting a running service's notification mid-session (language takes effect on next service start)
