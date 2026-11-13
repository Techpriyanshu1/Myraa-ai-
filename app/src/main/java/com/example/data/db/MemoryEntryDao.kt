package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryEntryDao {

    @Query("SELECT * FROM memory_core ORDER BY isPermanent DESC, createdAt DESC")
    fun getAllMemoriesFlow(): Flow<List<MemoryEntryEntity>>

    @Query("SELECT * FROM memory_core ORDER BY isPermanent DESC, createdAt DESC")
    suspend fun getAllMemoriesOnce(): List<MemoryEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(entry: MemoryEntryEntity): Long

    @Query("SELECT COUNT(*) FROM memory_core WHERE isPermanent = 1")
    suspend fun getPermanentMemoryCount(): Int

    @Query("DELETE FROM memory_core WHERE id = :id AND isPermanent = 0")
    suspend fun deleteUserMemoryById(id: Long): Int

    @Query("DELETE FROM memory_core WHERE isPermanent = 0")
    suspend fun clearUserMemories(): Int
}
