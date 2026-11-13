package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AutomationLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: AutomationLogEntity): Long

    @Query("SELECT * FROM automation_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<AutomationLogEntity>>

    @Query("SELECT * FROM automation_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentLogs(limit: Int): Flow<List<AutomationLogEntity>>

    @Query("DELETE FROM automation_logs WHERE id = :logId")
    suspend fun deleteLogById(logId: Long)

    @Query("DELETE FROM automation_logs")
    suspend fun clearAllLogs()
}
