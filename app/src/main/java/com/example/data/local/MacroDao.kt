package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MacroDao {
    @Query("SELECT * FROM macro_steps WHERE phase = :phase ORDER BY orderIndex ASC, id ASC")
    fun observeStepsForPhase(phase: String): Flow<List<MacroStepEntity>>

    @Query("SELECT * FROM macro_steps WHERE phase = :phase ORDER BY orderIndex ASC, id ASC")
    suspend fun getStepsForPhase(phase: String): List<MacroStepEntity>

    @Query("SELECT * FROM macro_steps WHERE id = :id LIMIT 1")
    suspend fun getStepById(id: Long): MacroStepEntity?

    @Query("SELECT * FROM macro_steps WHERE phase = :phase ORDER BY orderIndex DESC, id DESC LIMIT 1")
    suspend fun getLastStepForPhase(phase: String): MacroStepEntity?

    @Query("SELECT COALESCE(MAX(orderIndex), -1) + 1 FROM macro_steps WHERE phase = :phase")
    suspend fun getNextOrderIndex(phase: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStep(step: MacroStepEntity): Long

    @Update
    suspend fun updateStep(step: MacroStepEntity)

    @Query("UPDATE macro_steps SET delayAfterMs = :delayAfterMs WHERE id = :stepId")
    suspend fun updateStepDelayById(stepId: Long, delayAfterMs: Long)

    @Query("DELETE FROM macro_steps WHERE id = :id")
    suspend fun deleteStepById(id: Long)

    @Query("DELETE FROM macro_steps WHERE phase = :phase")
    suspend fun clearPhase(phase: String)
}
