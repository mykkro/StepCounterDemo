package com.example.stepcounterdemo

import android.app.Application
import com.example.stepcounterdemo.data.StepDatabase

class StepCounterApplication : Application() {
    val database: StepDatabase by lazy { StepDatabase.getInstance(this) }
    val repository: StepRepository by lazy { StepRepository(database.hourlyStepDao()) }
}
