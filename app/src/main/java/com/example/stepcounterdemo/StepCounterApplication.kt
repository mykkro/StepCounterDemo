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
