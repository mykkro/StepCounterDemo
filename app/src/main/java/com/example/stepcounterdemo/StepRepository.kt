package com.example.stepcounterdemo

import com.example.stepcounterdemo.data.HourlyStepDao
import com.example.stepcounterdemo.data.HourlyStepEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

class StepRepository(private val dao: HourlyStepDao) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // AtomicInteger backing field ensures thread-safe compound increment from sensor callbacks
    private val _stepCountAtomic = AtomicInteger(0)
    private val _stepCount = MutableStateFlow(0)
    val stepCount: StateFlow<Int> = _stepCount

    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    /** Snapshot of last-24h data; refreshed on each write and on demand. */
    private val _last24Hours = MutableStateFlow<List<HourlyStepEntity>>(emptyList())
    val last24Hours: StateFlow<List<HourlyStepEntity>> = _last24Hours

    /** Called by the service for each step delta. Updates in-memory count and persists to DB. */
    fun addSteps(delta: Int) {
        _stepCount.value = _stepCountAtomic.addAndGet(delta)
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
        _stepCountAtomic.set(0)
        _stepCount.value = 0
        _elapsedSeconds.value = 0L
    }

    /** Reloads the last-24-hours data from the DB into [last24Hours]. */
    fun refreshLast24Hours() {
        val fromHour = System.currentTimeMillis() / 3_600_000L - 23L
        _last24Hours.value = dao.getHoursSince(fromHour)
    }

    /** Returns all hourly rows where hourKey >= [fromHourKey], ascending. */
    fun getHoursSince(fromHourKey: Long): List<HourlyStepEntity> =
        dao.getHoursSince(fromHourKey)
}
