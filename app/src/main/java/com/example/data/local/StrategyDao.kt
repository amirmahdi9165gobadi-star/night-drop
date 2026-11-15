package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface StrategyDao {
    @Query("SELECT * FROM strategy_rankings ORDER BY goal ASC, successScore DESC, successCount DESC")
    fun observeAllStrategies(): Flow<List<StrategyEntity>>

    @Query("SELECT * FROM strategy_rankings WHERE goal = :goal ORDER BY successScore DESC, successCount DESC")
    suspend fun getStrategiesForGoal(goal: String): List<StrategyEntity>

    @Query("SELECT * FROM strategy_rankings WHERE id = :id LIMIT 1")
    suspend fun getStrategyById(id: String): StrategyEntity?

    @Query("SELECT COUNT(*) FROM strategy_rankings")
    suspend fun countStrategies(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(strategies: List<StrategyEntity>)

    @Update
    suspend fun updateStrategy(strategy: StrategyEntity)
}
