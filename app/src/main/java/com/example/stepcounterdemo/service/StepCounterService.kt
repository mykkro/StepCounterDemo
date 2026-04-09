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
import com.example.stepcounterdemo.R
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
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_FASTEST)
        }

        timerJob = scope.launch {
            while (isActive) {
                delay(1_000L)
                repository.incrementElapsed()
            }
        }

        return START_NOT_STICKY
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
            .setContentTitle(getString(R.string.notification_title))
            .setContentText("$steps ${getString(R.string.steps)}")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openPendingIntent)
            .addAction(0, getString(R.string.notification_stop_action), stopPendingIntent)
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
