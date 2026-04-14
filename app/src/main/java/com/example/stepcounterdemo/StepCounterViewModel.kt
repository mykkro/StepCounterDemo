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
