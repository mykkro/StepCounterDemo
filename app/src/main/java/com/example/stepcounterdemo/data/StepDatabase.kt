package com.example.stepcounterdemo.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class StepDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "step_db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(HourlyStepDao.CREATE_SQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS ${HourlyStepDao.TABLE}")
        onCreate(db)
    }

    fun hourlyStepDao(): HourlyStepDao = HourlyStepDao { writableDatabase }

    companion object {
        @Volatile private var INSTANCE: StepDatabase? = null

        fun getInstance(context: Context): StepDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: StepDatabase(context).also { INSTANCE = it }
            }
    }
}
