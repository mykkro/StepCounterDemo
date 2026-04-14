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
            try {
                val deviceGuid = prefs.getString("device_guid", "") ?: ""
                if (deviceGuid.isBlank()) {
                    _testState.value = TestState.Failure("Device not initialized")
                    return@launch
                }
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
            } catch (e: Exception) {
                _testState.value = TestState.Failure(e.message ?: "Unexpected error")
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
