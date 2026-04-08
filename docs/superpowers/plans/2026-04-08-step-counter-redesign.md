# Step Counter Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove accelerometer mode, add a Foreground Service for background step counting with a persistent notification, persist hourly step history in Room, and display it as a custom Canvas bar chart.

**Architecture:** An Application-scoped `StepRepository` singleton is the single source of truth, shared between `StepCounterService` (writes sensor data + DB rows) and `StepCounterViewModel` (reads StateFlows for UI). The ViewModel delegates start/stop to the Foreground Service via `startForegroundService`/`stopService`.

**Tech Stack:** Kotlin, Jetpack Compose, AndroidViewModel, Room 2.6.1 (kapt), StateFlow, Foreground Service, Compose Canvas

---

## File Map

| File | Action | Responsibility |
|------|--------|----------------|
| `gradle/libs.versions.toml` | Modify | Add Room 2.6.1 version + library entries |
| `app/build.gradle.kts` | Modify | Apply kapt plugin, add Room deps |
| `app/src/main/java/…/data/HourlyStepEntity.kt` | Create | Room entity: one row per hour |
| `app/src/main/java/…/data/HourlyStepDao.kt` | Create | DAO: atomic upsert + 24h query |
| `app/src/main/java/…/data/StepDatabase.kt` | Create | Room database singleton |
| `app/src/main/java/…/StepRepository.kt` | Create | Shared state + DB bridge |
| `app/src/main/java/…/StepCounterApplication.kt` | Create | Custom Application; owns DB + Repository |
| `app/src/main/java/…/service/StepCounterService.kt` | Create | Foreground Service; owns sensor + timer |
| `app/src/main/java/…/StepCounterViewModel.kt` | Replace | Thin bridge: reads repo, starts/stops service |
| `app/src/main/java/…/ui/HourlyStepChart.kt` | Create | Canvas bar chart composable |
| `app/src/main/java/…/MainActivity.kt` | Replace | Remove accel UI, add switch + graph button |
| `app/src/main/AndroidManifest.xml` | Replace | Permissions + service + application name |
| `app/src/test/java/…/StepRepositoryTest.kt` | Create | Unit tests for repository logic |

---

## Task 1: Add Room Dependencies

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add Room to the version catalog**

In `gradle/libs.versions.toml`, add `room = "2.6.1"` to `[versions]` and three library entries to `[libraries]`:

```toml
[versions]
agp = "9.1.0"
coreKtx = "1.10.1"
junit = "4.13.2"
junitVersion = "1.1.5"
espressoCore = "3.5.1"
lifecycleRuntimeKtx = "2.6.1"
activityCompose = "1.8.0"
kotlin = "2.2.10"
composeBom = "2024.09.00"
room = "2.6.1"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycleRuntimeKtx" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
androidx-junit = { group = "androidx.test.ext", name = "junit", version.ref = "junitVersion" }
androidx-espresso-core = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espressoCore" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycleRuntimeKtx" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-compose-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
androidx-compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
androidx-compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-compose-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }
androidx-compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
androidx-room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
androidx-room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

- [ ] **Step 2: Apply kapt and add Room to app/build.gradle.kts**

Replace `app/build.gradle.kts` with:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.example.stepcounterdemo"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.stepcounterdemo"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
```

- [ ] **Step 3: Sync Gradle**

In Android Studio: File → Sync Project with Gradle Files (or run `./gradlew assembleDebug` from terminal).  
Expected: BUILD SUCCESSFUL with no errors.

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "build: add Room 2.6.1 dependency with kapt"
```

---

## Task 2: Create Data Layer

**Files:**
- Create: `app/src/main/java/com/example/stepcounterdemo/data/HourlyStepEntity.kt`
- Create: `app/src/main/java/com/example/stepcounterdemo/data/HourlyStepDao.kt`
- Create: `app/src/main/java/com/example/stepcounterdemo/data/StepDatabase.kt`

- [ ] **Step 1: Create HourlyStepEntity**

```kotlin
// app/src/main/java/com/example/stepcounterdemo/data/HourlyStepEntity.kt
package com.example.stepcounterdemo.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "hourly_steps")
data class HourlyStepEntity(
    @PrimaryKey val hourKey: Long,
    val stepCount: Int
)
```

`hourKey` is `System.currentTimeMillis() / 3_600_000L` — the epoch hour index. One row per calendar hour.

- [ ] **Step 2: Create HourlyStepDao**

```kotlin
// app/src/main/java/com/example/stepcounterdemo/data/HourlyStepDao.kt
package com.example.stepcounterdemo.data

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HourlyStepDao {

    /**
     * Atomically add [delta] steps to the row for [hourKey], creating it if absent.
     * Uses a single INSERT OR REPLACE with a sub-select so no separate read is needed.
     */
    @Query(
        "INSERT OR REPLACE INTO hourly_steps (hourKey, stepCount) VALUES " +
        "(:hourKey, COALESCE((SELECT stepCount FROM hourly_steps WHERE hourKey = :hourKey), 0) + :delta)"
    )
    suspend fun addSteps(hourKey: Long, delta: Int)

    /**
     * Returns all rows where hourKey >= [fromHour], ordered ascending.
     * Observed as a Flow so the chart updates live.
     */
    @Query("SELECT * FROM hourly_steps WHERE hourKey >= :fromHour ORDER BY hourKey ASC")
    fun getLast24Hours(fromHour: Long): Flow<List<HourlyStepEntity>>
}
```

- [ ] **Step 3: Create StepDatabase**

```kotlin
// app/src/main/java/com/example/stepcounterdemo/data/StepDatabase.kt
package com.example.stepcounterdemo.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [HourlyStepEntity::class], version = 1, exportSchema = false)
abstract class StepDatabase : RoomDatabase() {

    abstract fun hourlyStepDao(): HourlyStepDao

    companion object {
        @Volatile private var INSTANCE: StepDatabase? = null

        fun getInstance(context: Context): StepDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    StepDatabase::class.java,
                    "step_db"
                ).build().also { INSTANCE = it }
            }
    }
}
```

- [ ] **Step 4: Verify the data layer compiles**

```bash
./gradlew compileDebugKotlin
```

Expected: BUILD SUCCESSFUL. Room annotation processor generates `StepDatabase_Impl` and `HourlyStepDao_Impl`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/stepcounterdemo/data/
git commit -m "feat: add Room data layer (HourlyStepEntity, DAO, StepDatabase)"
```

---

## Task 3: Create StepRepository + Unit Tests

**Files:**
- Create: `app/src/main/java/com/example/stepcounterdemo/StepRepository.kt`
- Create: `app/src/test/java/com/example/stepcounterdemo/StepRepositoryTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
// app/src/test/java/com/example/stepcounterdemo/StepRepositoryTest.kt
package com.example.stepcounterdemo

import com.example.stepcounterdemo.data.HourlyStepDao
import com.example.stepcounterdemo.data.HourlyStepEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class StepRepositoryTest {

    // Minimal fake DAO — only the in-memory map matters for unit tests
    private class FakeDao : HourlyStepDao {
        val data = mutableMapOf<Long, Int>()
        override fun getLast24Hours(fromHour: Long): Flow<List<HourlyStepEntity>> =
            flowOf(data.entries.filter { it.key >= fromHour }
                .map { HourlyStepEntity(it.key, it.value) }
                .sortedBy { it.hourKey })
        override suspend fun addSteps(hourKey: Long, delta: Int) {
            data[hourKey] = (data[hourKey] ?: 0) + delta
        }
    }

    private lateinit var dao: FakeDao
    private lateinit var repository: StepRepository

    @Before
    fun setUp() {
        dao = FakeDao()
        repository = StepRepository(dao)
    }

    @Test
    fun `initial state is zeroed and not running`() {
        assertEquals(0, repository.stepCount.value)
        assertEquals(0L, repository.elapsedSeconds.value)
        assertFalse(repository.isRunning.value)
    }

    @Test
    fun `addSteps increments stepCount`() {
        repository.addSteps(5)
        assertEquals(5, repository.stepCount.value)
        repository.addSteps(3)
        assertEquals(8, repository.stepCount.value)
    }

    @Test
    fun `resetSession zeroes stepCount and elapsedSeconds`() {
        repository.addSteps(10)
        repository.incrementElapsed()
        repository.incrementElapsed()

        repository.resetSession()

        assertEquals(0, repository.stepCount.value)
        assertEquals(0L, repository.elapsedSeconds.value)
    }

    @Test
    fun `incrementElapsed adds one second`() {
        repository.incrementElapsed()
        repository.incrementElapsed()
        assertEquals(2L, repository.elapsedSeconds.value)
    }

    @Test
    fun `setRunning reflects in isRunning`() {
        repository.setRunning(true)
        assertTrue(repository.isRunning.value)
        repository.setRunning(false)
        assertFalse(repository.isRunning.value)
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail (StepRepository doesn't exist yet)**

```bash
./gradlew testDebugUnitTest --tests "com.example.stepcounterdemo.StepRepositoryTest"
```

Expected: FAILED — `error: unresolved reference: StepRepository`

- [ ] **Step 3: Create StepRepository**

```kotlin
// app/src/main/java/com/example/stepcounterdemo/StepRepository.kt
package com.example.stepcounterdemo

import com.example.stepcounterdemo.data.HourlyStepDao
import com.example.stepcounterdemo.data.HourlyStepEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class StepRepository(private val dao: HourlyStepDao) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _stepCount = MutableStateFlow(0)
    val stepCount: StateFlow<Int> = _stepCount

    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    /** Called by the service for each step delta. Updates in-memory count and persists to DB. */
    fun addSteps(delta: Int) {
        _stepCount.value += delta
        val hourKey = System.currentTimeMillis() / 3_600_000L
        scope.launch { dao.addSteps(hourKey, delta) }
    }

    fun incrementElapsed() {
        _elapsedSeconds.value++
    }

    fun setRunning(running: Boolean) {
        _isRunning.value = running
    }

    fun resetSession() {
        _stepCount.value = 0
        _elapsedSeconds.value = 0L
    }

    /** Returns a Flow of hourly step rows covering the last 24 hours. */
    fun last24HoursFlow(): Flow<List<HourlyStepEntity>> {
        val fromHour = System.currentTimeMillis() / 3_600_000L - 23L
        return dao.getLast24Hours(fromHour)
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
./gradlew testDebugUnitTest --tests "com.example.stepcounterdemo.StepRepositoryTest"
```

Expected: 5 tests PASSED.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/stepcounterdemo/StepRepository.kt \
        app/src/test/java/com/example/stepcounterdemo/StepRepositoryTest.kt
git commit -m "feat: add StepRepository with unit tests"
```

---

## Task 4: Create StepCounterApplication

**Files:**
- Create: `app/src/main/java/com/example/stepcounterdemo/StepCounterApplication.kt`

- [ ] **Step 1: Create the Application class**

```kotlin
// app/src/main/java/com/example/stepcounterdemo/StepCounterApplication.kt
package com.example.stepcounterdemo

import android.app.Application
import com.example.stepcounterdemo.data.StepDatabase

class StepCounterApplication : Application() {
    val database: StepDatabase by lazy { StepDatabase.getInstance(this) }
    val repository: StepRepository by lazy { StepRepository(database.hourlyStepDao()) }
}
```

`lazy` ensures the database and repository are created on first access, not at `Application.onCreate()`, which avoids blocking the main thread.

- [ ] **Step 2: Verify compilation**

```bash
./gradlew compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/stepcounterdemo/StepCounterApplication.kt
git commit -m "feat: add StepCounterApplication to own DB and Repository singletons"
```

---

## Task 5: Create StepCounterService

**Files:**
- Create: `app/src/main/java/com/example/stepcounterdemo/service/StepCounterService.kt`

- [ ] **Step 1: Create the Foreground Service**

```kotlin
// app/src/main/java/com/example/stepcounterdemo/service/StepCounterService.kt
package com.example.stepcounterdemo.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.stepcounterdemo.MainActivity
import com.example.stepcounterdemo.StepCounterApplication
import com.example.stepcounterdemo.StepRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class StepCounterService : Service() {

    private lateinit var repository: StepRepository
    private lateinit var sensorManager: SensorManager
    private var lastTotalSteps = -1L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var timerJob: Job? = null

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val total = event.values[0].toLong()
            if (lastTotalSteps < 0L) {
                // First event: set baseline, do not count yet
                lastTotalSteps = total
                return
            }
            val delta = (total - lastTotalSteps).toInt()
            if (delta > 0) {
                repository.addSteps(delta)
                lastTotalSteps = total
                updateNotification(repository.stepCount.value)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    override fun onCreate() {
        super.onCreate()
        repository = (application as StepCounterApplication).repository
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        repository.resetSession()
        repository.setRunning(true)
        lastTotalSteps = -1L

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(0))

        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        sensor?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        timerJob = scope.launch {
            while (isActive) {
                delay(1_000L)
                repository.incrementElapsed()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(sensorListener)
        timerJob?.cancel()
        scope.cancel()
        repository.setRunning(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Step Counter",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Shows step count while the service is running" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(steps: Int): Notification {
        val stopPendingIntent = PendingIntent.getService(
            this, 0,
            Intent(this, StepCounterService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Step Counter")
            .setContentText("$steps steps")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openPendingIntent)
            .addAction(0, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(steps: Int) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(steps))
    }

    companion object {
        const val CHANNEL_ID = "step_counter_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.example.stepcounterdemo.ACTION_STOP"
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/stepcounterdemo/service/
git commit -m "feat: add StepCounterService foreground service with notification"
```

---

## Task 6: Refactor StepCounterViewModel

**Files:**
- Modify: `app/src/main/java/com/example/stepcounterdemo/StepCounterViewModel.kt`

- [ ] **Step 1: Replace StepCounterViewModel.kt**

The old ViewModel owned the sensor directly. The new one is a thin bridge to the repository and service.

```kotlin
// app/src/main/java/com/example/stepcounterdemo/StepCounterViewModel.kt
package com.example.stepcounterdemo

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import com.example.stepcounterdemo.data.HourlyStepEntity
import com.example.stepcounterdemo.service.StepCounterService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class StepCounterViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as StepCounterApplication).repository

    val isRunning: StateFlow<Boolean> = repository.isRunning
    val stepCount: StateFlow<Int> = repository.stepCount
    val elapsedSeconds: StateFlow<Long> = repository.elapsedSeconds

    private val _runInBackground = MutableStateFlow(false)
    val runInBackground: StateFlow<Boolean> = _runInBackground

    fun setRunInBackground(value: Boolean) {
        _runInBackground.value = value
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

    fun last24HoursFlow(): Flow<List<HourlyStepEntity>> = repository.last24HoursFlow()
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/stepcounterdemo/StepCounterViewModel.kt
git commit -m "refactor: replace ViewModel with thin service/repo bridge, remove accelerometer code"
```

---

## Task 7: Create HourlyStepChart Composable

**Files:**
- Create: `app/src/main/java/com/example/stepcounterdemo/ui/HourlyStepChart.kt`

- [ ] **Step 1: Create HourlyStepChart.kt**

```kotlin
// app/src/main/java/com/example/stepcounterdemo/ui/HourlyStepChart.kt
package com.example.stepcounterdemo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stepcounterdemo.data.HourlyStepEntity

/**
 * Full-screen bar chart showing step counts for the 24 hours ending at [currentHour].
 * Each bar represents one hour; the current hour is highlighted in the primary colour.
 */
@Composable
fun HourlyStepChart(
    entries: List<HourlyStepEntity>,
    currentHour: Long,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fromHour = currentHour - 23L
    val hourRange = fromHour..currentHour
    val hourMap = entries.associate { it.hourKey to it.stepCount }
    val counts = hourRange.map { hourMap[it] ?: 0 }
    val maxCount = counts.maxOrNull()?.takeIf { it > 0 } ?: 1

    val textMeasurer = rememberTextMeasurer()
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val onSurface = MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                text = "Last 24 Hours",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(16.dp))

            Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                val labelAreaHeight = 28.dp.toPx()
                val barAreaHeight = canvasHeight - labelAreaHeight
                val slotWidth = canvasWidth / 24f
                val barWidth = slotWidth * 0.65f

                // X-axis baseline
                drawLine(
                    color = onSurface,
                    start = Offset(0f, barAreaHeight),
                    end = Offset(canvasWidth, barAreaHeight),
                    strokeWidth = 1.dp.toPx()
                )

                hourRange.forEachIndexed { index, hourKey ->
                    val count = counts[index]
                    val slotLeft = index * slotWidth
                    val barLeft = slotLeft + (slotWidth - barWidth) / 2f
                    val barHeight = (count.toFloat() / maxCount) * (barAreaHeight - 12.dp.toPx())
                    val color = if (hourKey == currentHour) primaryColor else secondaryColor

                    // Bar
                    if (barHeight > 0f) {
                        drawRect(
                            color = color,
                            topLeft = Offset(barLeft, barAreaHeight - barHeight),
                            size = Size(barWidth, barHeight)
                        )
                    }

                    // Hour label below bar
                    val hourLabel = (hourKey % 24L).toString()
                    val labelResult = textMeasurer.measure(
                        AnnotatedString(hourLabel),
                        style = TextStyle(fontSize = 8.sp, color = onSurface)
                    )
                    drawText(
                        textLayoutResult = labelResult,
                        topLeft = Offset(
                            slotLeft + slotWidth / 2f - labelResult.size.width / 2f,
                            barAreaHeight + 6.dp.toPx()
                        )
                    )

                    // Step count above bar (omit if zero)
                    if (count > 0) {
                        val countResult = textMeasurer.measure(
                            AnnotatedString(count.toString()),
                            style = TextStyle(fontSize = 7.sp, color = onSurface)
                        )
                        drawText(
                            textLayoutResult = countResult,
                            topLeft = Offset(
                                slotLeft + slotWidth / 2f - countResult.size.width / 2f,
                                barAreaHeight - barHeight - countResult.size.height - 2.dp.toPx()
                            )
                        )
                    }
                }
            }
        }

        // Close button top-right
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
        ) {
            Text("✕", style = MaterialTheme.typography.titleMedium)
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/stepcounterdemo/ui/HourlyStepChart.kt
git commit -m "feat: add HourlyStepChart Canvas composable"
```

---

## Task 8: Refactor MainActivity

**Files:**
- Modify: `app/src/main/java/com/example/stepcounterdemo/MainActivity.kt`

- [ ] **Step 1: Replace MainActivity.kt**

Key changes: remove sensor-mode selector, add background switch, add graph button, handle lifecycle to stop service when app goes to background (if switch is off).

```kotlin
// app/src/main/java/com/example/stepcounterdemo/MainActivity.kt
package com.example.stepcounterdemo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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

    // Permissions: ACTIVITY_RECOGNITION (API 29+) and POST_NOTIFICATIONS (API 33+)
    var hasActivityPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
            else true
        )
    }

    val permissionsToRequest = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasActivityPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            results[Manifest.permission.ACTIVITY_RECOGNITION] ?: false
        else true
    }

    // Request all needed permissions on first composition
    DisposableEffect(Unit) {
        if (permissionsToRequest.isNotEmpty()) permissionLauncher.launch(permissionsToRequest)
        onDispose {}
    }

    val isRunning by viewModel.isRunning.collectAsState()
    val stepCount by viewModel.stepCount.collectAsState()
    val elapsedSeconds by viewModel.elapsedSeconds.collectAsState()
    val runInBackground by viewModel.runInBackground.collectAsState()
    val last24Hours by viewModel.last24HoursFlow().collectAsState(initial = emptyList())
    val currentHour = System.currentTimeMillis() / 3_600_000L

    var showGraph by remember { mutableStateOf(false) }

    // Stop the service when the app goes to background, unless "run in background" is on
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !runInBackground && isRunning) {
                viewModel.stop()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Full-screen graph overlay
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

    Column(
        modifier = modifier.fillMaxSize(),
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
            text = "$stepCount steps",
            style = MaterialTheme.typography.displayMedium
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Background toggle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Run in background",
                style = MaterialTheme.typography.bodyMedium
            )
            Switch(
                checked = runInBackground,
                onCheckedChange = { viewModel.setRunInBackground(it) },
                enabled = !isRunning
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Start / Stop
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Button(
                onClick = { viewModel.start() },
                enabled = !isRunning && hasActivityPermission
            ) {
                Text("Start")
            }
            Button(
                onClick = { viewModel.stop() },
                enabled = isRunning
            ) {
                Text("Stop")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(onClick = { showGraph = true }) {
            Text("Last 24h")
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/stepcounterdemo/MainActivity.kt
git commit -m "feat: redesign MainActivity — background switch, graph button, remove accelerometer UI"
```

---

## Task 9: Update AndroidManifest.xml

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Replace AndroidManifest.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- Required on Android 10+ (API 29+) to read the hardware step counter -->
    <uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
    <!-- Required to start a Foreground Service -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <!-- Required on Android 14+ (API 34+) for health-type foreground services -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_HEALTH" />
    <!-- Required on Android 13+ (API 33+) to post notifications -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:name=".StepCounterApplication"
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.StepCounterDemo">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:label="@string/app_name"
            android:theme="@style/Theme.StepCounterDemo">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".service.StepCounterService"
            android:foregroundServiceType="health"
            android:exported="false" />

    </application>

</manifest>
```

- [ ] **Step 2: Full debug build**

```bash
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL. APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 3: Run all unit tests**

```bash
./gradlew testDebugUnitTest
```

Expected: All 5 `StepRepositoryTest` tests PASSED, `ExampleUnitTest` PASSED.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "feat: update manifest — add service, permissions, application class"
```

---

## Task 10: Manual Smoke Test

Install and verify on a physical Android device (step counter sensor requires real hardware).

- [ ] **Step 1: Install on device**

```bash
./gradlew installDebug
```

- [ ] **Step 2: Verify main screen**
  - Timer shows `00:00`, steps show `0 steps`
  - "Run in background" switch is visible and toggleable
  - Start / Stop / Last 24h buttons are present
  - No "Step source" / accelerometer selector visible

- [ ] **Step 3: Verify step counting (foreground)**
  - Tap Start; walk a few steps
  - Timer counts up; step count increases
  - Tap Stop; timer and count freeze

- [ ] **Step 4: Verify background mode**
  - Toggle "Run in background" ON
  - Tap Start
  - Press Home to go to background
  - Verify notification appears: "Step Counter — N steps" with a Stop action
  - Walk; notification step count updates
  - Tap Stop in the notification; service stops

- [ ] **Step 5: Verify graph**
  - After counting some steps, tap "Last 24h"
  - Full-screen bar chart appears with a bar for the current hour
  - Hour labels 0–23 appear on X-axis
  - Close button (✕) dismisses the chart

- [ ] **Step 6: Final commit**

```bash
git add .
git commit -m "chore: complete step counter redesign — background service, Room history, Canvas chart"
```
