package com.example.stepcounterdemo

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class StepCounterViewModel(application: Application) : AndroidViewModel(application) {

    enum class SensorMode { STEP_COUNTER, ACCELEROMETER, NONE }

    private val sensorManager: SensorManager =
        application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepCounterSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val accelerometerSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // Which sensors are physically present on this device
    val stepCounterAvailable: Boolean = stepCounterSensor != null
    val accelerometerAvailable: Boolean = accelerometerSensor != null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _stepCount = MutableStateFlow(0)
    val stepCount: StateFlow<Int> = _stepCount

    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds

    private val _activeSensorMode = MutableStateFlow(SensorMode.NONE)
    val activeSensorMode: StateFlow<SensorMode> = _activeSensorMode

    // User-selected preferred source (changeable only while stopped)
    private val _preferredMode = MutableStateFlow(
        if (stepCounterSensor != null) SensorMode.STEP_COUNTER else SensorMode.ACCELEROMETER
    )
    val preferredMode: StateFlow<SensorMode> = _preferredMode

    fun setPreferredMode(mode: SensorMode) {
        if (!_isRunning.value) _preferredMode.value = mode
    }

    // --- Step counter sensor state ---
    // Long to avoid float→int precision loss for large cumulative counts
    private var initialStepCount = -1L

    // --- Accelerometer state ---
    // Gravity estimate (low-pass filtered raw acceleration)
    private val gravity = FloatArray(3)
    // Smoothed magnitude of linear (gravity-free) acceleration
    private var smoothedMag = 0f
    // Hysteresis flag: true while magnitude is above the upper threshold
    private var stepPhase = false
    private var lastStepTime = 0L
    // How many initial accelerometer samples to skip while gravity filter warms up
    private var warmupSamplesLeft = 0

    private var timerJob: Job? = null

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!_isRunning.value) return
            when (event.sensor.type) {
                Sensor.TYPE_STEP_COUNTER -> handleStepCounter(event)
                Sensor.TYPE_ACCELEROMETER -> handleAccelerometer(event)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    /**
     * Start a new counting session.
     *
     * @param hasActivityRecognitionPermission  true when the runtime ACTIVITY_RECOGNITION
     *   permission has been granted (required on API 29+); false to force the
     *   accelerometer fallback.
     */
    fun start(hasActivityRecognitionPermission: Boolean) {
        if (_isRunning.value) return

        // Respect the user's preferred source; fall back gracefully if unavailable
        val mode = when (_preferredMode.value) {
            SensorMode.STEP_COUNTER ->
                if (hasActivityRecognitionPermission && stepCounterSensor != null) SensorMode.STEP_COUNTER
                else if (accelerometerSensor != null) SensorMode.ACCELEROMETER
                else SensorMode.NONE
            SensorMode.ACCELEROMETER ->
                if (accelerometerSensor != null) SensorMode.ACCELEROMETER
                else if (hasActivityRecognitionPermission && stepCounterSensor != null) SensorMode.STEP_COUNTER
                else SensorMode.NONE
            SensorMode.NONE -> SensorMode.NONE
        }
        _activeSensorMode.value = mode
        _isRunning.value = true
        _stepCount.value = 0
        _elapsedSeconds.value = 0L

        // Reset per-session state
        initialStepCount = -1
        gravity.fill(0f)
        smoothedMag = 0f
        stepPhase = false
        lastStepTime = 0L
        // Ignore the first ACCEL_WARMUP_SAMPLES events so the gravity filter can settle
        // (gravity starts at [0,0,0] and needs ~20 samples at SENSOR_DELAY_GAME to reach ~99%)
        warmupSamplesLeft = ACCEL_WARMUP_SAMPLES

        val sensor = when (mode) {
            SensorMode.STEP_COUNTER -> stepCounterSensor
            SensorMode.ACCELEROMETER -> accelerometerSensor
            SensorMode.NONE -> null
        }
        sensor?.let {
            if (mode == SensorMode.ACCELEROMETER) {
                // maxReportLatencyUs = 0 disables hardware batching → events delivered immediately
                sensorManager.registerListener(
                    sensorListener, it, SensorManager.SENSOR_DELAY_GAME, 0
                )
            } else {
                // TYPE_STEP_COUNTER is event-driven; some chipsets silently reject the
                // 4-arg overload for this sensor type, so use the simpler 3-arg version.
                sensorManager.registerListener(
                    sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL
                )
            }
        }

        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000L)
                _elapsedSeconds.value++
            }
        }
    }

    fun stop() {
        if (!_isRunning.value) return
        _isRunning.value = false
        sensorManager.unregisterListener(sensorListener)
        timerJob?.cancel()
        timerJob = null
    }

    // -------------------------------------------------------------------------
    // Sensor handlers
    // -------------------------------------------------------------------------

    private fun handleStepCounter(event: SensorEvent) {
        // Use Long to preserve precision for large cumulative counts (Float only has ~7 digits)
        val total = event.values[0].toLong()
        if (initialStepCount < 0L) {
            // First delivery: record baseline so we count relative steps only.
            initialStepCount = total
        }
        _stepCount.value = (total - initialStepCount).toInt()
    }

    /**
     * Lightweight accelerometer-based step detector.
     *
     * Algorithm:
     *  1. Separate gravity with a recursive low-pass filter (alpha = 0.8).
     *  2. Compute the magnitude of the linear (gravity-free) acceleration.
     *  3. Smooth it further to suppress high-frequency noise.
     *  4. Use hysteresis thresholds to detect one step per oscillation cycle,
     *     with a minimum inter-step interval guard to prevent double counting.
     */
    private fun handleAccelerometer(event: SensorEvent) {
        // Low-pass filter to isolate gravity (alpha = 0.8 → ~5 time-constants to settle)
        val alpha = 0.8f
        gravity[0] = alpha * gravity[0] + (1f - alpha) * event.values[0]
        gravity[1] = alpha * gravity[1] + (1f - alpha) * event.values[1]
        gravity[2] = alpha * gravity[2] + (1f - alpha) * event.values[2]

        // Discard early samples while the gravity estimate is still converging.
        // Without this, the large initial error produces a false spike that
        // blocks real first steps via the MIN_STEP_INTERVAL guard.
        if (warmupSamplesLeft > 0) {
            warmupSamplesLeft--
            return
        }

        val lx = event.values[0] - gravity[0]
        val ly = event.values[1] - gravity[1]
        val lz = event.values[2] - gravity[2]

        val mag = sqrt((lx * lx + ly * ly + lz * lz).toDouble()).toFloat()

        // Light smoothing: enough to remove sensor jitter, not enough to kill real peaks.
        // alpha = 0.2 means the current sample has 80% weight → very responsive.
        smoothedMag = 0.2f * smoothedMag + 0.8f * mag

        val now = SystemClock.elapsedRealtime()

        if (!stepPhase && smoothedMag > STEP_UPPER_THRESHOLD) {
            // Rising edge — count as a step if enough time has elapsed
            stepPhase = true
            if (now - lastStepTime >= MIN_STEP_INTERVAL_MS) {
                _stepCount.value++
                lastStepTime = now
            }
        } else if (stepPhase && smoothedMag < STEP_LOWER_THRESHOLD) {
            // Falling edge — reset so the next peak can be detected
            stepPhase = false
        }
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    companion object {
        // Thresholds in m/s² for the lightly-smoothed linear-acceleration magnitude.
        // Upper lowered from 2.0 to 1.5 to catch lighter footfalls.
        private const val STEP_UPPER_THRESHOLD = 1.5f
        private const val STEP_LOWER_THRESHOLD = 0.6f
        // Minimum time between two consecutive steps (guards against double count)
        private const val MIN_STEP_INTERVAL_MS = 250L
        // Samples to discard at startup while the gravity low-pass filter settles.
        // At SENSOR_DELAY_GAME (~50 Hz), 25 samples ≈ 500 ms → >99% gravity accuracy.
        private const val ACCEL_WARMUP_SAMPLES = 25
    }
}
