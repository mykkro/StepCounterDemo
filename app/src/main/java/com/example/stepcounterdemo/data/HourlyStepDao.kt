package com.example.stepcounterdemo.data

import android.database.sqlite.SQLiteDatabase

/**
 * Data-access object for hourly step history.
 * Uses raw SQLite — no annotation processing required.
 */
open class HourlyStepDao(private val db: () -> SQLiteDatabase) {

    /**
     * Atomically adds [delta] to the step count for [hourKey],
     * creating the row if it does not exist.
     */
    open fun addSteps(hourKey: Long, delta: Int) {
        db().execSQL(
            "INSERT OR REPLACE INTO $TABLE (hourKey, stepCount) VALUES " +
            "(?, COALESCE((SELECT stepCount FROM $TABLE WHERE hourKey = ?), 0) + ?)",
            arrayOf<Any>(hourKey, hourKey, delta)
        )
    }

    /** Returns all hourly rows where hourKey >= [fromHour], ascending. */
    open fun getLast24Hours(fromHour: Long): List<HourlyStepEntity> {
        val cursor = db().rawQuery(
            "SELECT hourKey, stepCount FROM $TABLE WHERE hourKey >= ? ORDER BY hourKey ASC",
            arrayOf(fromHour.toString())
        )
        return buildList {
            cursor.use {
                while (it.moveToNext()) {
                    add(HourlyStepEntity(it.getLong(0), it.getInt(1)))
                }
            }
        }
    }

    companion object {
        const val TABLE = "hourly_steps"
        const val CREATE_SQL =
            "CREATE TABLE IF NOT EXISTS $TABLE (hourKey INTEGER PRIMARY KEY, stepCount INTEGER NOT NULL DEFAULT 0)"
    }
}
