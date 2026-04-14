package com.example.stepcounterdemo

import android.database.sqlite.SQLiteDatabase
import com.example.stepcounterdemo.data.HourlyStepDao
import com.example.stepcounterdemo.data.HourlyStepEntity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class StepRepositoryTest {

    /**
     * In-memory DAO that does not touch SQLite.
     * Supplies a fake [SQLiteDatabase] lambda that is never called,
     * and overrides the two public methods directly.
     */
    private class FakeDao : HourlyStepDao(db = { throw UnsupportedOperationException("no DB in unit tests") }) {
        val data = mutableMapOf<Long, Int>()

        override fun addSteps(hourKey: Long, delta: Int) {
            data[hourKey] = (data[hourKey] ?: 0) + delta
        }

        override fun getHoursSince(fromHour: Long): List<HourlyStepEntity> =
            data.entries
                .filter { it.key >= fromHour }
                .map { HourlyStepEntity(it.key, it.value) }
                .sortedBy { it.hourKey }
    }

    private lateinit var dao: FakeDao
    private lateinit var repository: StepRepository

    @Before
    fun setUp() {
        dao = FakeDao()
        repository = StepRepository(dao)
    }

    @Test
    fun `initial state is zeroed and not running`() {
        assertEquals(0, repository.stepCount.value)
        assertEquals(0L, repository.elapsedSeconds.value)
        assertFalse(repository.isRunning.value)
    }

    @Test
    fun `addSteps increments stepCount`() {
        repository.addSteps(5)
        assertEquals(5, repository.stepCount.value)
        repository.addSteps(3)
        assertEquals(8, repository.stepCount.value)
    }

    @Test
    fun `resetSession zeroes stepCount and elapsedSeconds`() {
        repository.addSteps(10)
        repository.incrementElapsed()
        repository.incrementElapsed()

        repository.resetSession()

        assertEquals(0, repository.stepCount.value)
        assertEquals(0L, repository.elapsedSeconds.value)
    }

    @Test
    fun `incrementElapsed adds one second per call`() {
        repository.incrementElapsed()
        repository.incrementElapsed()
        assertEquals(2L, repository.elapsedSeconds.value)
    }

    @Test
    fun `setRunning reflects in isRunning`() {
        repository.setRunning(true)
        assertTrue(repository.isRunning.value)
        repository.setRunning(false)
        assertFalse(repository.isRunning.value)
    }

    @Test
    fun `getHoursSince returns only rows at or after given hourKey`() {
        dao.addSteps(100L, 50)
        dao.addSteps(101L, 100)
        dao.addSteps(102L, 75)

        val result = repository.getHoursSince(101L)

        assertEquals(2, result.size)
        assertEquals(101L, result[0].hourKey)
        assertEquals(100, result[0].stepCount)
        assertEquals(102L, result[1].hourKey)
        assertEquals(75, result[1].stepCount)
    }

    @Test
    fun `getHoursSince returns empty list when no rows at or after hourKey`() {
        dao.addSteps(100L, 50)

        val result = repository.getHoursSince(101L)

        assertTrue(result.isEmpty())
    }
}
