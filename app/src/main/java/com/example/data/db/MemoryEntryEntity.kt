package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memory_core")
data class MemoryEntryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val category: String,
    val label: String,
    val content: String,
    val isPermanent: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
