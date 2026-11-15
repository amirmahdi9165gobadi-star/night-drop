package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadTaskDao {
    @Query("SELECT * FROM download_tasks ORDER BY queueOrder ASC, createdAtMillis ASC, id ASC")
    fun observeAllTasks(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE id = :id LIMIT 1")
    suspend fun getTaskById(id: Long): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE systemDownloadId = :systemDownloadId LIMIT 1")
    suspend fun getTaskBySystemDownloadId(systemDownloadId: Long): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE status = :status ORDER BY queueOrder ASC, createdAtMillis ASC, id ASC")
    suspend fun getTasksByStatus(status: String): List<DownloadTaskEntity>

    @Query(
        "SELECT * FROM download_tasks WHERE status IN ('QUEUED', 'SCHEDULED') " +
            "ORDER BY queueOrder ASC, createdAtMillis ASC, id ASC"
    )
    suspend fun getQueuedTasks(): List<DownloadTaskEntity>

    @Query(
        "SELECT * FROM download_tasks WHERE status IN ('QUEUED', 'SCHEDULED') " +
            "ORDER BY queueOrder ASC, createdAtMillis ASC, id ASC LIMIT 1"
    )
    suspend fun getNextQueuedTask(): DownloadTaskEntity?

    @Query(
        "SELECT * FROM download_tasks WHERE status IN " +
            "('QUEUED', 'SCHEDULED', 'PRE_ACTIONS_RUNNING', 'WAITING_NETWORK', 'DOWNLOADING', 'PAUSED') " +
            "ORDER BY queueOrder ASC, createdAtMillis ASC, id ASC"
    )
    suspend fun getPendingOrActiveTasks(): List<DownloadTaskEntity>

    @Query("SELECT COALESCE(MAX(queueOrder), -1) + 1 FROM download_tasks")
    suspend fun getNextQueueOrder(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: DownloadTaskEntity): Long

    @Update
    suspend fun updateTask(task: DownloadTaskEntity)

    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun deleteTaskById(id: Long)

    @Query("DELETE FROM download_tasks WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED', 'SKIPPED')")
    suspend fun clearFinishedTasks()
}
