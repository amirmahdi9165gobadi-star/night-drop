package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ExecutionLogDao {
    @Query("SELECT * FROM execution_logs ORDER BY timestampMillis DESC, id DESC LIMIT 200")
    fun observeRecentLogs(): Flow<List<ExecutionLogEntity>>

    @Query("SELECT * FROM execution_logs WHERE timestampMillis >= :sinceMillis ORDER BY timestampMillis ASC, id ASC")
    suspend fun getLogsSince(sinceMillis: Long): List<ExecutionLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: ExecutionLogEntity): Long

    @Query("DELETE FROM execution_logs")
    suspend fun clearAllLogs()
}
