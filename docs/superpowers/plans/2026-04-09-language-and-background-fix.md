# Language Switching + Background Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Czech/English switching via emoji flag buttons and fix `runInBackground` to persist across app restarts and stop the foreground service when toggled off.

**Architecture:** `LocaleManager` singleton reads/writes locale preference to SharedPreferences and wraps a `Context` via `createConfigurationContext`; `MainActivity.attachBaseContext` applies the stored locale so all string resources resolve correctly. `StepCounterViewModel` reads `runInBackground` from SharedPreferences on creation, persists it on change, and calls `stop()` immediately when toggled off while running.

**Tech Stack:** Kotlin, Jetpack Compose, Android SharedPreferences, `createConfigurationContext`, `Activity.recreate()`

---

## File Map

| File | Action | Responsibility |
|------|--------|----------------|
| `app/src/main/res/values/strings.xml` | Modify | Czech (default) string resources |
| `app/src/main/res/values-en/strings.xml` | Create | English string resources |
| `app/src/main/java/com/example/stepcounterdemo/LocaleManager.kt` | Create | Read/write/apply locale preference |
| `app/src/main/java/com/example/stepcounterdemo/MainActivity.kt` | Modify | `attachBaseContext`, flag buttons, `stringResource` calls, switch enabled fix |
| `app/src/main/java/com/example/stepcounterdemo/StepCounterViewModel.kt` | Modify | Persist `runInBackground`, stop-on-disable logic |
| `app/src/main/java/com/example/stepcounterdemo/service/StepCounterService.kt` | Modify | Notification strings from resources |
| `app/src/main/java/com/example/stepcounterdemo/ui/HourlyStepChart.kt` | Modify | Chart title from string resource |

---

### Task 1: String resources — Czech (default) and English

No unit test applies (resource files only). Verified by build success.

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/values-en/strings.xml`

- [ ] **Step 1: Replace `values/strings.xml` with full Czech strings**

Replace the entire file content with:

```xml
<resources>
    <string name="app_name">Krokoměr</string>
    <string name="run_in_background">Běžet na pozadí</string>
    <string name="start">Spustit</string>
    <string name="stop">Zastavit</string>
    <string name="last_24h">Posl. 24 h</string>
    <string name="last_24_hours_title">Posledních 24 hodin</string>
    <string name="steps">kroků</string>
    <string name="notification_title">Krokoměr</string>
    <string name="notification_stop_action">Zastavit</string>
</resources>
```

- [ ] **Step 2: Create `values-en/strings.xml`**

Create the directory `app/src/main/res/values-en/` and the file with:

```xml
<resources>
    <string name="app_name">Step Counter</string>
    <string name="run_in_background">Run in background</string>
    <string name="start">Start</string>
    <string name="stop">Stop</string>
    <string name="last_24h">Last 24h</string>
    <string name="last_24_hours_title">Last 24 hours</string>
    <string name="steps">steps</string>
    <string name="notification_title">Step Counter</string>
    <string name="notification_stop_action">Stop</string>
</resources>
```

- [ ] **Step 3: Verify build succeeds**

Run:
```
./gradlew assembleDebug
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-en/strings.xml
git commit -m "feat: add Czech and English string resources"
```

---

### Task 2: LocaleManager utility

No unit test applies — `SharedPreferences` and `Context` require Android runtime; no Robolectric in this project.

**Files:**
- Create: `app/src/main/java/com/example/stepcounterdemo/LocaleManager.kt`

- [ ] **Step 1: Create `LocaleManager.kt`**

```kotlin
package com.example.stepcounterdemo

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleManager {
    private const val PREFS_FILE = "app_prefs"
    private const val KEY_LOCALE = "app_locale"
    private const val DEFAULT_LOCALE = "cs"

    fun getLocale(context: Context): String =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .getString(KEY_LOCALE, DEFAULT_LOCALE) ?: DEFAULT_LOCALE

    fun setLocale(context: Context, localeCode: String) {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LOCALE, localeCode)
            .apply()
    }

    fun applyLocale(context: Context): Context {
        val localeCode = getLocale(context)
        val locale = Locale(localeCode)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
```

- [ ] **Step 2: Verify build succeeds**

```
./gradlew assembleDebug
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/stepcounterdemo/LocaleManager.kt
git commit -m "feat: add LocaleManager for SharedPreferences-backed locale switching"
```

---

### Task 3: ViewModel — persist runInBackground, stop-on-disable

No unit test applies — `AndroidViewModel` requires `Application`; no Robolectric.

**Files:**
- Modify: `app/src/main/java/com/example/stepcounterdemo/StepCounterViewModel.kt`

- [ ] **Step 1: Rewrite `StepCounterViewModel.kt`**

Replace the entire file with:

```kotlin
package com.example.stepcounterdemo

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.stepcounterdemo.data.HourlyStepEntity
import com.example.stepcounterdemo.service.StepCounterService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class StepCounterViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as StepCounterApplication).repository
    private val prefs = application.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    val isRunning: StateFlow<Boolean> = repository.isRunning
    val stepCount: StateFlow<Int> = repository.stepCount
    val elapsedSeconds: StateFlow<Long> = repository.elapsedSeconds
    val last24Hours: StateFlow<List<HourlyStepEntity>> = repository.last24Hours

    private val _runInBackground = MutableStateFlow(
        prefs.getBoolean("run_in_background", false)
    )
    val runInBackground: StateFlow<Boolean> = _runInBackground

    fun setRunInBackground(value: Boolean) {
        prefs.edit().putBoolean("run_in_background", value).apply()
        _runInBackground.value = value
        if (!value && isRunning.value) {
            stop()
        }
    }

    fun start() {
        if (repository.isRunning.value) return
        val intent = Intent(getApplication(), StepCounterService::class.java)
        getApplication<Application>().startForegroundService(intent)
    }

    fun stop() {
        val intent = Intent(getApplication(), StepCounterService::class.java)
        getApplication<Application>().stopService(intent)
    }

    /** Reloads 24h history from the DB into the StateFlow (call before opening the chart). */
    fun refreshChart() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.refreshLast24Hours()
        }
    }
}
```

- [ ] **Step 2: Verify build succeeds and existing tests still pass**

```
./gradlew test
```
Expected: `BUILD SUCCESSFUL`, all 5 existing `StepRepositoryTest` tests pass.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/stepcounterdemo/StepCounterViewModel.kt
git commit -m "fix: persist runInBackground to SharedPreferences; stop service when toggled off"
```

---

### Task 4: MainActivity — locale bootstrap, flag buttons, string resources, switch fix

**Files:**
- Modify: `app/src/main/java/com/example/stepcounterdemo/MainActivity.kt`

- [ ] **Step 1: Rewrite `MainActivity.kt`**

Replace the entire file with:

```kotlin
package com.example.stepcounterdemo

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stepcounterdemo.ui.HourlyStepChart
import com.example.stepcounterdemo.ui.theme.StepCounterDemoTheme
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StepCounterDemoTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    StepCounterScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun StepCounterScreen(
    modifier: Modifier = Modifier,
    viewModel: StepCounterViewModel = viewModel()
) {
    val context = LocalContext.current

    var hasActivityPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACTIVITY_RECOGNITION
                ) == PackageManager.PERMISSION_GRANTED
            else true
        )
    }

    val permissionsToRequest = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasActivityPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            results[Manifest.permission.ACTIVITY_RECOGNITION] ?: false
        else true
    }

    DisposableEffect(Unit) {
        if (permissionsToRequest.isNotEmpty()) permissionLauncher.launch(permissionsToRequest)
        onDispose {}
    }

    val isRunning by viewModel.isRunning.collectAsState()
    val stepCount by viewModel.stepCount.collectAsState()
    val elapsedSeconds by viewModel.elapsedSeconds.collectAsState()
    val runInBackground by viewModel.runInBackground.collectAsState()
    val last24Hours by viewModel.last24Hours.collectAsState()
    val currentHour = System.currentTimeMillis() / 3_600_000L

    var showGraph by remember { mutableStateOf(false) }

    // Stop service on app background if "run in background" is off
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP
                && !viewModel.runInBackground.value
                && viewModel.isRunning.value) {
                viewModel.stop()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showGraph) {
        HourlyStepChart(
            entries = last24Hours,
            currentHour = currentHour,
            onClose = { showGraph = false },
            modifier = modifier
        )
        return
    }

    val minutes = elapsedSeconds / 60
    val seconds = elapsedSeconds % 60
    val timeString = String.format(Locale.US, "%02d:%02d", minutes, seconds)

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = timeString,
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "$stepCount ${stringResource(R.string.steps)}",
                style = MaterialTheme.typography.displayMedium
            )

            Spacer(modifier = Modifier.height(32.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.run_in_background),
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(
                    checked = runInBackground,
                    onCheckedChange = { viewModel.setRunInBackground(it) },
                    enabled = !isRunning || runInBackground
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Button(
                    onClick = { viewModel.start() },
                    enabled = !isRunning && hasActivityPermission
                ) {
                    Text(stringResource(R.string.start))
                }
                Button(
                    onClick = { viewModel.stop() },
                    enabled = isRunning
                ) {
                    Text(stringResource(R.string.stop))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(onClick = {
                viewModel.refreshChart()
                showGraph = true
            }) {
                Text(stringResource(R.string.last_24h))
            }
        }

        // Language flag buttons — top-right corner
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            val activity = context as Activity
            TextButton(onClick = {
                LocaleManager.setLocale(context, "cs")
                activity.recreate()
            }) {
                Text("🇨🇿")
            }
            TextButton(onClick = {
                LocaleManager.setLocale(context, "en")
                activity.recreate()
            }) {
                Text("🇬🇧")
            }
        }
    }
}
```

- [ ] **Step 2: Verify build succeeds**

```
./gradlew assembleDebug
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/stepcounterdemo/MainActivity.kt
git commit -m "feat: add locale bootstrap, Czech/English flag buttons, string resources, fix switch enabled rule"
```

---

### Task 5: StepCounterService — notification strings from resources

**Files:**
- Modify: `app/src/main/java/com/example/stepcounterdemo/service/StepCounterService.kt`

- [ ] **Step 1: Replace hardcoded notification strings**

In `buildNotification()`, change:

```kotlin
return NotificationCompat.Builder(this, CHANNEL_ID)
    .setContentTitle("Step Counter")
    .setContentText("$steps steps")
    .setSmallIcon(R.drawable.ic_launcher_foreground)
    .setContentIntent(openPendingIntent)
    .addAction(0, "Stop", stopPendingIntent)
    .setOngoing(true)
    .build()
```

To:

```kotlin
return NotificationCompat.Builder(this, CHANNEL_ID)
    .setContentTitle(getString(R.string.notification_title))
    .setContentText("$steps ${getString(R.string.steps)}")
    .setSmallIcon(R.drawable.ic_launcher_foreground)
    .setContentIntent(openPendingIntent)
    .addAction(0, getString(R.string.notification_stop_action), stopPendingIntent)
    .setOngoing(true)
    .build()
```

Also change the channel description in `createNotificationChannel()` from:

```kotlin
).apply { description = "Shows step count while the service is running" }
```

To (leave as-is — service description is not user-visible in the notification, no need to localize):

```kotlin
).apply { description = "Shows step count while the service is running" }
```

Add the `R` import at the top of the file (it resolves from the package automatically in this project, but verify the file compiles):

```kotlin
import com.example.stepcounterdemo.R
```

- [ ] **Step 2: Verify build succeeds**

```
./gradlew assembleDebug
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/stepcounterdemo/service/StepCounterService.kt
git commit -m "feat: use string resources for notification title and stop action"
```

---

### Task 6: HourlyStepChart — string resource for title

**Files:**
- Modify: `app/src/main/java/com/example/stepcounterdemo/ui/HourlyStepChart.kt`

- [ ] **Step 1: Replace hardcoded chart title**

Add import at the top of the file:

```kotlin
import androidx.compose.ui.res.stringResource
import com.example.stepcounterdemo.R
```

Change line 62–64 from:

```kotlin
Text(
    text = "Last 24 Hours",
    style = MaterialTheme.typography.titleLarge,
    fontWeight = FontWeight.Bold
)
```

To:

```kotlin
Text(
    text = stringResource(R.string.last_24_hours_title),
    style = MaterialTheme.typography.titleLarge,
    fontWeight = FontWeight.Bold
)
```

- [ ] **Step 2: Verify build succeeds and existing tests pass**

```
./gradlew test
```
Expected: `BUILD SUCCESSFUL`, all 5 `StepRepositoryTest` tests pass.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/stepcounterdemo/ui/HourlyStepChart.kt
git commit -m "feat: use string resource for chart title to support locale switching"
```

---

### Task 7: Manual smoke test

Install the debug APK on a physical Android device (emulator step counter sensor is unreliable).

```bash
./gradlew installDebug
```

**Checklist:**

- [ ] App launches in Czech by default — timer shows `00:00`, button labels are `Spustit` / `Zastavit`, background switch label is `Běžet na pozadí`
- [ ] Tap 🇬🇧 — activity recreates instantly, all labels switch to English (`Start` / `Stop`, `Run in background`, `Last 24h`)
- [ ] Tap 🇨🇿 — switches back to Czech; preference survives app kill and relaunch
- [ ] With language set to English: tap `Start` → foreground notification appears with title "Step Counter" and "Stop" action button
- [ ] Walk a few steps — notification updates step count; main screen updates step count
- [ ] Enable "Run in background" switch → put app in background → notification stays, service keeps counting
- [ ] Open app → tap "Last 24h" → bar chart shows current hour highlighted; close with ✕
- [ ] With service running and "Run in background" ON: toggle switch OFF → service stops immediately, notification disappears
- [ ] Relaunch app → "Run in background" switch is OFF (persisted correctly)
- [ ] Enable "Run in background", kill app → relaunch → switch is ON (persisted correctly)
- [ ] Start service with Czech locale → notification shows "Krokoměr" / "Zastavit"; switch to English mid-session (recreate) → existing notification still in Czech (acceptable — service inherits locale at start time)
