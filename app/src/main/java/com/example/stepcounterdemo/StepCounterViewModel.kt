package com.example.stepcounterdemo

import android.app.Application
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

    val isRunning: StateFlow<Boolean> = repository.isRunning
    val stepCount: StateFlow<Int> = repository.stepCount
    val elapsedSeconds: StateFlow<Long> = repository.elapsedSeconds
    val last24Hours: StateFlow<List<HourlyStepEntity>> = repository.last24Hours

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

    /** Reloads 24h history from the DB into the StateFlow (call before opening the chart). */
    fun refreshChart() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.refreshLast24Hours()
        }
    }
}
