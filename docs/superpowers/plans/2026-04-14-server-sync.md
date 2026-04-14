# Server Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add manual server sync to the StepCounter app — settings screen for credentials, a Sync button on the main screen, and OkHttp-based batch upload of hourly step records.

**Architecture:** `SyncRepository` is a pure HTTP client (OkHttp, no Android prefs dependency). `StepCounterViewModel` orchestrates sync: reads prefs, reads unsent records from `StepRepository`, calls `SyncRepository`, writes results back to prefs. `SettingsViewModel` handles the settings screen state and calls `SyncRepository.authenticate()` for the Test Connection button.

**Tech Stack:** OkHttp 4.12.0, navigation-compose 2.7.7, `org.json.JSONObject` (built-in), JUnit 4 + MockWebServer for unit tests.

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| `gradle/libs.versions.toml` | Modify | Add okhttp + navigation-compose version aliases |
| `app/build.gradle.kts` | Modify | Add impl + testImpl dependencies |
| `app/src/main/res/values/strings.xml` | Modify | Czech string resources for new UI |
| `app/src/main/res/values-en/strings.xml` | Modify | English string resources for new UI |
| `SyncRepository.kt` | Create | HTTP-only: `authenticate()` + `submitBatch()` |
| `SyncRepositoryTest.kt` | Create | Unit tests via MockWebServer |
| `StepRepository.kt` | Modify | Add `getHoursSince(fromHourKey)` |
| `StepRepositoryTest.kt` | Modify | Test `getHoursSince` |
| `StepCounterApplication.kt` | Modify | Generate `device_guid`, wire `SyncRepository` |
| `StepCounterViewModel.kt` | Modify | Add `syncState`, `serverConfigured`, `sync()` |
| `SettingsViewModel.kt` | Create | Field state + `testConnection()` + `save()` |
| `ui/SettingsScreen.kt` | Create | Settings composable with TopAppBar |
| `MainActivity.kt` | Modify | NavHost, SnackbarHost, gear + Sync buttons, hint text |

---

## Task 1: Add Dependencies

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add version aliases and library entries to `gradle/libs.versions.toml`**

In the `[versions]` section, add after the last version entry:
```toml
okhttp = "4.12.0"
navigationCompose = "2.7.7"
```

In the `[libraries]` section, add after the last library entry:
```toml
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
okhttp-mockwebserver = { group = "com.squareup.okhttp3", name = "mockwebserver", version.ref = "okhttp" }
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigationCompose" }
```

- [ ] **Step 2: Add dependencies to `app/build.gradle.kts`**

In the `dependencies { }` block, add after `implementation(libs.androidx.compose.material3)`:
```kotlin
    implementation(libs.okhttp)
    implementation(libs.androidx.navigation.compose)
    testImplementation(libs.okhttp.mockwebserver)
```

- [ ] **Step 3: Verify the build resolves**

Run: `cd app && ../gradlew dependencies --configuration releaseRuntimeClasspath 2>&1 | grep -E "okhttp|navigation-compose"`

Expected: lines showing `com.squareup.okhttp3:okhttp:4.12.0` and `androidx.navigation:navigation-compose:2.7.7`

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "build: add OkHttp and navigation-compose dependencies"
```

---

## Task 2: Add String Resources

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-en/strings.xml`

- [ ] **Step 1: Add Czech strings to `app/src/main/res/values/strings.xml`**

Add inside `<resources>` before the closing tag:
```xml
    <string name="sync">Synchronizovat</string>
    <string name="last_sync_failed">Poslední synchronizace selhala</string>
    <string name="last_sync_time">Poslední sync: %s</string>
    <string name="settings_title">Nastavení serveru</string>
    <string name="settings_host">URL serveru</string>
    <string name="settings_username">Uživatelské jméno</string>
    <string name="settings_password">Heslo</string>
    <string name="settings_test_connection">Test připojení</string>
    <string name="settings_connection_ok">Připojení úspěšné</string>
    <string name="save">Uložit</string>
```

- [ ] **Step 2: Add English strings to `app/src/main/res/values-en/strings.xml`**

Add inside `<resources>` before the closing tag:
```xml
    <string name="sync">Sync</string>
    <string name="last_sync_failed">Last sync failed</string>
    <string name="last_sync_time">Last sync: %s</string>
    <string name="settings_title">Server Settings</string>
    <string name="settings_host">Host URL</string>
    <string name="settings_username">Username</string>
    <string name="settings_password">Password</string>
    <string name="settings_test_connection">Test Connection</string>
    <string name="settings_connection_ok">Connection successful</string>
    <string name="save">Save</string>
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-en/strings.xml
git commit -m "feat: add string resources for sync and settings screens"
```

---

## Task 3: SyncRepository — authenticate()

**Files:**
- Create: `app/src/main/java/com/example/stepcounterdemo/SyncRepository.kt`
- Create: `app/src/test/java/com/example/stepcounterdemo/SyncRepositoryTest.kt`

- [ ] **Step 1: Write the failing authenticate tests**

Create `app/src/test/java/com/example/stepcounterdemo/SyncRepositoryTest.kt`:

```kotlin
package com.example.stepcounterdemo

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Base64

class SyncRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: SyncRepository

    /** JWT sub extractor that works in JVM unit tests (no android.util.Base64). */
    private val jvmJwtExtractor = SyncRepository.JwtSubExtractor { token ->
        val part = token.split(".").getOrNull(1)
            ?: throw IllegalArgumentException("Invalid JWT")
        val padded = part + "=".repeat((4 - part.length % 4) % 4)
        val bytes = Base64.getDecoder().decode(padded.replace('-', '+').replace('_', '/'))
        JSONObject(String(bytes, Charsets.UTF_8)).getString("sub")
    }

    /** Builds a fake JWT with the given sub claim. */
    private fun fakeJwt(sub: String): String {
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"sub":"$sub"}""".toByteArray())
        return "header.$payload.sig"
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repo = SyncRepository(jwtSubExtractor = jvmJwtExtractor)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `authenticate returns Success with token and humanId on HTTP 200`() {
        val jwt = fakeJwt("client-abc-123")
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"token":"$jwt"}"""))

        val baseUrl = server.url("/").toString().trimEnd('/')
        val result = repo.authenticate(baseUrl, "user", "pass", "device-guid-1")

        assertTrue(result is SyncRepository.AuthResult.Success)
        val success = result as SyncRepository.AuthResult.Success
        assertEquals(jwt, success.token)
        assertEquals("client-abc-123", success.humanId)
    }

    @Test
    fun `authenticate sends correct request body`() {
        val jwt = fakeJwt("any-sub")
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"token":"$jwt"}"""))

        val baseUrl = server.url("/").toString().trimEnd('/')
        repo.authenticate(baseUrl, "myuser", "mypass", "dev-guid-xyz")

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/devices/auth", recorded.path)
        val body = JSONObject(recorded.body.readUtf8())
        assertEquals("myuser", body.getString("username"))
        assertEquals("mypass", body.getString("password"))
        assertEquals("dev-guid-xyz", body.getString("device_guid"))
        assertEquals("stepcounter", body.getString("device_type"))
    }

    @Test
    fun `authenticate returns Failure on non-200 response`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Unauthorized"}"""))

        val baseUrl = server.url("/").toString().trimEnd('/')
        val result = repo.authenticate(baseUrl, "bad", "creds", "dev")

        assertTrue(result is SyncRepository.AuthResult.Failure)
        assertTrue((result as SyncRepository.AuthResult.Failure).message.contains("401"))
    }

    @Test
    fun `authenticate returns Failure on network error`() {
        server.shutdown() // force connection refused

        val result = repo.authenticate("http://localhost:1", "u", "p", "d")

        assertTrue(result is SyncRepository.AuthResult.Failure)
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

Run: `cd app && ../gradlew test --tests "com.example.stepcounterdemo.SyncRepositoryTest" 2>&1 | tail -20`

Expected: compilation error — `SyncRepository` does not exist yet.

- [ ] **Step 3: Create `SyncRepository.kt`**

Create `app/src/main/java/com/example/stepcounterdemo/SyncRepository.kt`:

```kotlin
package com.example.stepcounterdemo

import com.example.stepcounterdemo.data.HourlyStepEntity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class SyncRepository(
    internal val jwtSubExtractor: JwtSubExtractor = androidJwtSubExtractor
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun interface JwtSubExtractor {
        fun extract(token: String): String
    }

    sealed class AuthResult {
        data class Success(val token: String, val humanId: String) : AuthResult()
        data class Failure(val message: String) : AuthResult()
    }

    sealed class BatchResult {
        data class Success(val accepted: Int) : BatchResult()
        object Unauthorized : BatchResult()
        data class Failure(val message: String) : BatchResult()
    }

    companion object {
        val androidJwtSubExtractor = JwtSubExtractor { token ->
            val part = token.split(".").getOrNull(1)
                ?: throw IllegalArgumentException("Invalid JWT format")
            val bytes = android.util.Base64.decode(part, android.util.Base64.URL_SAFE)
            JSONObject(String(bytes, Charsets.UTF_8)).getString("sub")
        }
    }

    fun authenticate(
        host: String,
        username: String,
        password: String,
        deviceGuid: String
    ): AuthResult {
        val body = JSONObject().apply {
            put("username", username)
            put("password", password)
            put("device_guid", deviceGuid)
            put("device_type", "stepcounter")
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("${host.trimEnd('/')}/api/devices/auth")
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.code != 200) {
                    return AuthResult.Failure("HTTP ${response.code}")
                }
                val json = JSONObject(response.body?.string() ?: "{}")
                val token = json.getString("token")
                val humanId = jwtSubExtractor.extract(token)
                AuthResult.Success(token, humanId)
            }
        } catch (e: Exception) {
            AuthResult.Failure(e.message ?: "Network error")
        }
    }

    fun submitBatch(
        host: String,
        token: String,
        humanId: String,
        deviceGuid: String,
        records: List<HourlyStepEntity>
    ): BatchResult {
        val measurements = JSONArray()
        for (record in records) {
            val guid = UUID.nameUUIDFromBytes(
                "$deviceGuid:${record.hourKey}".toByteArray()
            ).toString()
            measurements.put(JSONObject().apply {
                put("guid", guid)
                put("humanId", humanId)
                put("startTime", record.hourKey * 3_600_000L)
                put("endTime", (record.hourKey + 1) * 3_600_000L)
                put("stepCount", record.stepCount)
            })
        }

        val body = JSONObject().put("measurements", measurements).toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("${host.trimEnd('/')}/api/devices/measurements/stepcounter")
            .addHeader("Authorization", "Bearer $token")
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val json = JSONObject(response.body?.string() ?: "{}")
                        BatchResult.Success(json.getJSONArray("accepted").length())
                    }
                    401 -> BatchResult.Unauthorized
                    else -> BatchResult.Failure("HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            BatchResult.Failure(e.message ?: "Network error")
        }
    }
}
```

- [ ] **Step 4: Run authenticate tests to confirm they pass**

Run: `cd app && ../gradlew test --tests "com.example.stepcounterdemo.SyncRepositoryTest.authenticate*" 2>&1 | tail -20`

Expected: 4 tests pass, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/stepcounterdemo/SyncRepository.kt \
        app/src/test/java/com/example/stepcounterdemo/SyncRepositoryTest.kt
git commit -m "feat: add SyncRepository with authenticate()"
```

---

## Task 4: SyncRepository — submitBatch() tests

**Files:**
- Modify: `app/src/test/java/com/example/stepcounterdemo/SyncRepositoryTest.kt`

- [ ] **Step 1: Write failing submitBatch tests**

Add the following tests to `SyncRepositoryTest` (inside the class, after the authenticate tests):

```kotlin
    @Test
    fun `submitBatch returns Success with accepted count on HTTP 200`() {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"accepted":["guid1","guid2"],"rejected":[]}"""))

        val baseUrl = server.url("/").toString().trimEnd('/')
        val records = listOf(
            com.example.stepcounterdemo.data.HourlyStepEntity(488000L, 120),
            com.example.stepcounterdemo.data.HourlyStepEntity(488001L, 95)
        )
        val result = repo.submitBatch(baseUrl, "tok", "human-1", "dev-1", records)

        assertTrue(result is SyncRepository.BatchResult.Success)
        assertEquals(2, (result as SyncRepository.BatchResult.Success).accepted)
    }

    @Test
    fun `submitBatch sends correct Authorization header and JSON body`() {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"accepted":["g1"],"rejected":[]}"""))

        val baseUrl = server.url("/").toString().trimEnd('/')
        val hourKey = 488000L
        val records = listOf(com.example.stepcounterdemo.data.HourlyStepEntity(hourKey, 50))
        repo.submitBatch(baseUrl, "my-token", "human-uuid", "device-uuid", records)

        val recorded = server.takeRequest()
        assertEquals("Bearer my-token", recorded.getHeader("Authorization"))
        assertEquals("/api/devices/measurements/stepcounter", recorded.path)
        val body = JSONObject(recorded.body.readUtf8())
        val m = body.getJSONArray("measurements").getJSONObject(0)
        assertEquals("human-uuid", m.getString("humanId"))
        assertEquals(hourKey * 3_600_000L, m.getLong("startTime"))
        assertEquals((hourKey + 1) * 3_600_000L, m.getLong("endTime"))
        assertEquals(50, m.getInt("stepCount"))
    }

    @Test
    fun `submitBatch produces deterministic guid for same deviceGuid and hourKey`() {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"accepted":["g"],"rejected":[]}"""))
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"accepted":["g"],"rejected":[]}"""))

        val baseUrl = server.url("/").toString().trimEnd('/')
        val records = listOf(com.example.stepcounterdemo.data.HourlyStepEntity(488000L, 42))
        repo.submitBatch(baseUrl, "tok", "human", "device-xyz", records)
        repo.submitBatch(baseUrl, "tok", "human", "device-xyz", records)

        val guid1 = JSONObject(server.takeRequest().body.readUtf8())
            .getJSONArray("measurements").getJSONObject(0).getString("guid")
        val guid2 = JSONObject(server.takeRequest().body.readUtf8())
            .getJSONArray("measurements").getJSONObject(0).getString("guid")
        assertEquals(guid1, guid2)
    }

    @Test
    fun `submitBatch returns Unauthorized on HTTP 401`() {
        server.enqueue(MockResponse().setResponseCode(401))

        val baseUrl = server.url("/").toString().trimEnd('/')
        val result = repo.submitBatch(baseUrl, "expired", "h", "d",
            listOf(com.example.stepcounterdemo.data.HourlyStepEntity(1L, 10)))

        assertEquals(SyncRepository.BatchResult.Unauthorized, result)
    }

    @Test
    fun `submitBatch returns Failure on server error`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val baseUrl = server.url("/").toString().trimEnd('/')
        val result = repo.submitBatch(baseUrl, "tok", "h", "d",
            listOf(com.example.stepcounterdemo.data.HourlyStepEntity(1L, 10)))

        assertTrue(result is SyncRepository.BatchResult.Failure)
        assertTrue((result as SyncRepository.BatchResult.Failure).message.contains("500"))
    }
```

- [ ] **Step 2: Run tests to confirm they pass**

Run: `cd app && ../gradlew test --tests "com.example.stepcounterdemo.SyncRepositoryTest" 2>&1 | tail -20`

Expected: all 9 tests pass.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/example/stepcounterdemo/SyncRepositoryTest.kt
git commit -m "test: add SyncRepository submitBatch() tests"
```

---

## Task 5: StepRepository.getHoursSince() + Application Wiring

**Files:**
- Modify: `app/src/main/java/com/example/stepcounterdemo/StepRepository.kt`
- Modify: `app/src/test/java/com/example/stepcounterdemo/StepRepositoryTest.kt`
- Modify: `app/src/main/java/com/example/stepcounterdemo/StepCounterApplication.kt`

- [ ] **Step 1: Write the failing test for getHoursSince()**

Add to `StepRepositoryTest` (inside the class, after existing tests):

```kotlin
    @Test
    fun `getHoursSince returns only rows at or after given hourKey`() {
        dao.addSteps(100L, 50)
        dao.addSteps(101L, 100)
        dao.addSteps(102L, 75)

        val result = repository.getHoursSince(101L)

        assertEquals(2, result.size)
        assertEquals(101L, result[0].hourKey)
        assertEquals(100, result[0].stepCount)
        assertEquals(102L, result[1].hourKey)
        assertEquals(75, result[1].stepCount)
    }

    @Test
    fun `getHoursSince returns empty list when no rows at or after hourKey`() {
        dao.addSteps(100L, 50)

        val result = repository.getHoursSince(101L)

        assertTrue(result.isEmpty())
    }
```

- [ ] **Step 2: Run to confirm the tests fail**

Run: `cd app && ../gradlew test --tests "com.example.stepcounterdemo.StepRepositoryTest.getHoursSince*" 2>&1 | tail -10`

Expected: compilation error — method does not exist yet.

- [ ] **Step 3: Add `getHoursSince()` to `StepRepository.kt`**

Add after `refreshLast24Hours()` in `StepRepository.kt`:

```kotlin
    /** Returns all hourly rows where hourKey >= [fromHourKey], ascending. */
    fun getHoursSince(fromHourKey: Long): List<HourlyStepEntity> =
        dao.getLast24Hours(fromHourKey)
```

- [ ] **Step 4: Run to confirm the tests pass**

Run: `cd app && ../gradlew test --tests "com.example.stepcounterdemo.StepRepositoryTest" 2>&1 | tail -10`

Expected: all tests pass.

- [ ] **Step 5: Update `StepCounterApplication.kt`**

Replace the entire file with:

```kotlin
package com.example.stepcounterdemo

import android.app.Application
import android.content.Context
import com.example.stepcounterdemo.data.StepDatabase
import java.util.UUID

class StepCounterApplication : Application() {
    val database: StepDatabase by lazy { StepDatabase.getInstance(this) }
    val repository: StepRepository by lazy { StepRepository(database.hourlyStepDao()) }
    val syncRepository: SyncRepository by lazy { SyncRepository() }

    override fun onCreate() {
        super.onCreate()
        ensureDeviceGuid()
    }

    private fun ensureDeviceGuid() {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        if (prefs.getString("device_guid", "").isNullOrEmpty()) {
            prefs.edit().putString("device_guid", UUID.randomUUID().toString()).apply()
        }
    }
}
```

- [ ] **Step 6: Run all unit tests to confirm nothing broke**

Run: `cd app && ../gradlew test 2>&1 | tail -20`

Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/example/stepcounterdemo/StepRepository.kt \
        app/src/test/java/com/example/stepcounterdemo/StepRepositoryTest.kt \
        app/src/main/java/com/example/stepcounterdemo/StepCounterApplication.kt
git commit -m "feat: add StepRepository.getHoursSince(), wire SyncRepository in Application"
```

---

## Task 6: StepCounterViewModel — Sync Additions

**Files:**
- Modify: `app/src/main/java/com/example/stepcounterdemo/StepCounterViewModel.kt`

- [ ] **Step 1: Replace `StepCounterViewModel.kt` with the updated version**

```kotlin
package com.example.stepcounterdemo

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.stepcounterdemo.data.HourlyStepEntity
import com.example.stepcounterdemo.service.StepCounterService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class SyncState {
    object Idle : SyncState()
    object InProgress : SyncState()
    data class Success(val count: Int) : SyncState()
    data class Failure(val message: String) : SyncState()
}

class StepCounterViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as StepCounterApplication).repository
    private val syncRepository = (application as StepCounterApplication).syncRepository
    private val prefs = application.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    val isRunning: StateFlow<Boolean> = repository.isRunning
    val stepCount: StateFlow<Int> = repository.stepCount
    val elapsedSeconds: StateFlow<Long> = repository.elapsedSeconds
    val last24Hours: StateFlow<List<HourlyStepEntity>> = repository.last24Hours

    private val _runInBackground = MutableStateFlow(
        prefs.getBoolean("run_in_background", false)
    )
    val runInBackground: StateFlow<Boolean> = _runInBackground

    private val _serverConfigured = MutableStateFlow(isServerConfigured())
    val serverConfigured: StateFlow<Boolean> = _serverConfigured

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState

    private val _lastSyncTime = MutableStateFlow(prefs.getLong("last_sync_time", 0L))
    val lastSyncTime: StateFlow<Long> = _lastSyncTime

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "server_host", "server_username", "server_password" ->
                _serverConfigured.value = isServerConfigured()
            "last_sync_time" ->
                _lastSyncTime.value = prefs.getLong("last_sync_time", 0L)
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onCleared() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onCleared()
    }

    private fun isServerConfigured(): Boolean {
        val host = prefs.getString("server_host", "") ?: ""
        val user = prefs.getString("server_username", "") ?: ""
        val pass = prefs.getString("server_password", "") ?: ""
        return host.isNotEmpty() && user.isNotEmpty() && pass.isNotEmpty()
    }

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

    fun refreshChart() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.refreshLast24Hours()
        }
    }

    fun resetSyncState() {
        _syncState.value = SyncState.Idle
    }

    fun sync() {
        if (_syncState.value is SyncState.InProgress) return
        viewModelScope.launch(Dispatchers.IO) {
            _syncState.value = SyncState.InProgress

            val host = prefs.getString("server_host", "") ?: ""
            val username = prefs.getString("server_username", "") ?: ""
            val password = prefs.getString("server_password", "") ?: ""
            val deviceGuid = prefs.getString("device_guid", "") ?: ""
            var humanId = prefs.getString("human_id", "") ?: ""
            var token = prefs.getString("jwt_token", "") ?: ""
            val lastSyncedHour = prefs.getLong("last_synced_hour", 0L)

            val records = repository.getHoursSince(lastSyncedHour + 1)

            if (records.isEmpty()) {
                val now = System.currentTimeMillis()
                prefs.edit().putLong("last_sync_time", now).apply()
                _syncState.value = SyncState.Success(0)
                return@launch
            }

            var totalAccepted = 0

            for (batch in records.chunked(100)) {
                var result = syncRepository.submitBatch(host, token, humanId, deviceGuid, batch)

                if (result is SyncRepository.BatchResult.Unauthorized) {
                    val authResult = syncRepository.authenticate(host, username, password, deviceGuid)
                    if (authResult is SyncRepository.AuthResult.Success) {
                        token = authResult.token
                        humanId = authResult.humanId
                        prefs.edit()
                            .putString("jwt_token", token)
                            .putString("human_id", humanId)
                            .apply()
                        result = syncRepository.submitBatch(host, token, humanId, deviceGuid, batch)
                    } else {
                        _syncState.value = SyncState.Failure("Auth failed")
                        return@launch
                    }
                }

                when (result) {
                    is SyncRepository.BatchResult.Success -> {
                        totalAccepted += result.accepted
                        val newWatermark = batch.last().hourKey
                        prefs.edit().putLong("last_synced_hour", newWatermark).apply()
                    }
                    is SyncRepository.BatchResult.Failure -> {
                        _syncState.value = SyncState.Failure(result.message)
                        return@launch
                    }
                    is SyncRepository.BatchResult.Unauthorized -> {
                        _syncState.value = SyncState.Failure("Auth failed")
                        return@launch
                    }
                }
            }

            val now = System.currentTimeMillis()
            prefs.edit().putLong("last_sync_time", now).apply()
            _syncState.value = SyncState.Success(totalAccepted)
        }
    }
}
```

- [ ] **Step 2: Run all unit tests**

Run: `cd app && ../gradlew test 2>&1 | tail -20`

Expected: all tests pass.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/stepcounterdemo/StepCounterViewModel.kt
git commit -m "feat: add sync state and sync() to StepCounterViewModel"
```

---

## Task 7: SettingsViewModel

**Files:**
- Create: `app/src/main/java/com/example/stepcounterdemo/SettingsViewModel.kt`

- [ ] **Step 1: Create `SettingsViewModel.kt`**

```kotlin
package com.example.stepcounterdemo

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class TestState {
    object Idle : TestState()
    object Loading : TestState()
    object Success : TestState()
    data class Failure(val message: String) : TestState()
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val syncRepository = (application as StepCounterApplication).syncRepository
    private val prefs = application.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    var host by mutableStateOf(prefs.getString("server_host", "") ?: "")
    var username by mutableStateOf(prefs.getString("server_username", "") ?: "")
    var password by mutableStateOf(prefs.getString("server_password", "") ?: "")

    private val _testState = MutableStateFlow<TestState>(TestState.Idle)
    val testState: StateFlow<TestState> = _testState

    fun testConnection() {
        viewModelScope.launch(Dispatchers.IO) {
            _testState.value = TestState.Loading
            val deviceGuid = prefs.getString("device_guid", "") ?: ""
            val result = syncRepository.authenticate(host.trim(), username.trim(), password, deviceGuid)
            when (result) {
                is SyncRepository.AuthResult.Success -> {
                    prefs.edit()
                        .putString("jwt_token", result.token)
                        .putString("human_id", result.humanId)
                        .apply()
                    _testState.value = TestState.Success
                }
                is SyncRepository.AuthResult.Failure -> {
                    _testState.value = TestState.Failure(result.message)
                }
            }
        }
    }

    fun save() {
        prefs.edit()
            .putString("server_host", host.trim())
            .putString("server_username", username.trim())
            .putString("server_password", password)
            .apply()
    }
}
```

- [ ] **Step 2: Run all unit tests**

Run: `cd app && ../gradlew test 2>&1 | tail -10`

Expected: all tests pass.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/stepcounterdemo/SettingsViewModel.kt
git commit -m "feat: add SettingsViewModel"
```

---

## Task 8: SettingsScreen Composable

**Files:**
- Create: `app/src/main/java/com/example/stepcounterdemo/ui/SettingsScreen.kt`

- [ ] **Step 1: Create `ui/SettingsScreen.kt`**

```kotlin
package com.example.stepcounterdemo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stepcounterdemo.R
import com.example.stepcounterdemo.SettingsViewModel
import com.example.stepcounterdemo.TestState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val testState by viewModel.testState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("←") }
                },
                actions = {
                    TextButton(onClick = { viewModel.save(); onBack() }) {
                        Text(stringResource(R.string.save))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = viewModel.host,
                onValueChange = { viewModel.host = it },
                label = { Text(stringResource(R.string.settings_host)) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true
            )
            OutlinedTextField(
                value = viewModel.username,
                onValueChange = { viewModel.username = it },
                label = { Text(stringResource(R.string.settings_username)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = viewModel.password,
                onValueChange = { viewModel.password = it },
                label = { Text(stringResource(R.string.settings_password)) },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true
            )

            Spacer(Modifier.height(4.dp))

            OutlinedButton(
                onClick = { viewModel.testConnection() },
                modifier = Modifier.fillMaxWidth(),
                enabled = testState !is TestState.Loading
            ) {
                if (testState is TestState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(stringResource(R.string.settings_test_connection))
                }
            }

            when (val s = testState) {
                is TestState.Success -> Text(
                    stringResource(R.string.settings_connection_ok),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
                is TestState.Failure -> Text(
                    s.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                else -> {}
            }
        }
    }
}
```

- [ ] **Step 2: Run all unit tests**

Run: `cd app && ../gradlew test 2>&1 | tail -10`

Expected: all tests pass (SettingsScreen has no unit tests — UI is verified manually).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/stepcounterdemo/ui/SettingsScreen.kt
git commit -m "feat: add SettingsScreen composable"
```

---

## Task 9: MainActivity — Navigation + Main Screen Changes

**Files:**
- Modify: `app/src/main/java/com/example/stepcounterdemo/MainActivity.kt`

- [ ] **Step 1: Replace `MainActivity.kt` with the updated version**

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
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.stepcounterdemo.ui.HourlyStepChart
import com.example.stepcounterdemo.ui.SettingsScreen
import com.example.stepcounterdemo.ui.theme.StepCounterDemoTheme
import java.text.SimpleDateFormat
import java.util.Date
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
                val navController = rememberNavController()
                val snackbarHostState = remember { SnackbarHostState() }
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "main",
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable("main") {
                            StepCounterScreen(
                                onNavigateToSettings = { navController.navigate("settings") },
                                snackbarHostState = snackbarHostState
                            )
                        }
                        composable("settings") {
                            SettingsScreen(onBack = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StepCounterScreen(
    onNavigateToSettings: () -> Unit,
    snackbarHostState: SnackbarHostState,
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
    val serverConfigured by viewModel.serverConfigured.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val lastSyncTime by viewModel.lastSyncTime.collectAsState()
    val currentHour = System.currentTimeMillis() / 3_600_000L

    var showGraph by remember { mutableStateOf(false) }

    // Show snackbar on sync completion, then reset state
    LaunchedEffect(syncState) {
        when (val s = syncState) {
            is SyncState.Success -> {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.sync_success, s.count)
                )
                viewModel.resetSyncState()
            }
            is SyncState.Failure -> {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.sync_failure, s.message)
                )
                viewModel.resetSyncState()
            }
            else -> {}
        }
    }

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
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer)  }
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

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = {
                    viewModel.refreshChart()
                    showGraph = true
                }) {
                    Text(stringResource(R.string.last_24h))
                }
                if (serverConfigured) {
                    Button(
                        onClick = { viewModel.sync() },
                        enabled = syncState !is SyncState.InProgress
                    ) {
                        if (syncState is SyncState.InProgress) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text(stringResource(R.string.sync))
                        }
                    }
                }
            }

            // Hint text: failure message while snackbar is visible, then last success time
            if (serverConfigured) {
                val hintText = when {
                    syncState is SyncState.Failure ->
                        stringResource(R.string.last_sync_failed)
                    lastSyncTime > 0L -> {
                        val formatted = SimpleDateFormat("HH:mm", Locale.getDefault())
                            .format(Date(lastSyncTime))
                        stringResource(R.string.last_sync_time, formatted)
                    }
                    else -> null
                }
                hintText?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }

        // Language flags + settings gear — top-right corner
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            val activity = context as? Activity ?: return@Row
            TextButton(onClick = {
                LocaleManager.setLocale(context, "cs")
                activity.recreate()
            }) {
                Text("🇨🇿", fontSize = 28.sp)
            }
            TextButton(onClick = {
                LocaleManager.setLocale(context, "en")
                activity.recreate()
            }) {
                Text("🇬🇧", fontSize = 28.sp)
            }
            TextButton(onClick = onNavigateToSettings) {
                Text("⚙", fontSize = 28.sp)
            }
        }
    }
}
```

- [ ] **Step 2: Add the two snackbar string resources**

These strings are referenced in the composable above (`R.string.sync_success`, `R.string.sync_failure`) but were not in the string resources added in Task 2. Add them now.

In `app/src/main/res/values/strings.xml`, add inside `<resources>`:
```xml
    <string name="sync_success">Synchronizováno %d záznamů</string>
    <string name="sync_failure">Synchronizace selhala: %s</string>
```

In `app/src/main/res/values-en/strings.xml`, add inside `<resources>`:
```xml
    <string name="sync_success">Synced %d records</string>
    <string name="sync_failure">Sync failed: %s</string>
```

- [ ] **Step 3: Run all unit tests**

Run: `cd app && ../gradlew test 2>&1 | tail -20`

Expected: all tests pass.

- [ ] **Step 4: Verify the app builds**

Run: `cd app && ../gradlew assembleDebug 2>&1 | tail -30`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/stepcounterdemo/MainActivity.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-en/strings.xml
git commit -m "feat: add NavHost, SnackbarHost, sync button and settings navigation to main screen"
```

---

## Manual Verification Checklist

After the build succeeds, install on device/emulator and verify:

- [ ] App launches normally, step counting works as before
- [ ] ⚙ gear icon visible top-right; tapping opens Server Settings screen
- [ ] Settings screen: fields are empty initially; back arrow discards, Save button writes
- [ ] Test Connection with invalid server returns error message inline
- [ ] Test Connection with valid server shows "Connection successful"
- [ ] After saving valid credentials, Sync button appears beside "Last 24h"
- [ ] Sync button shows spinner while syncing, updates hint text on success
- [ ] Snackbar appears after sync completes (success or failure)
- [ ] Sync button absent when no credentials are saved
- [ ] Language switching still works correctly from main screen
