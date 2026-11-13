package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "automation_logs")
data class AutomationLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val voicePrompt: String,
    val actionType: String, // e.g. "OPEN_APP", "DEVICE_CONTROL", "ACCESSIBILITY_GESTURE", "SYSTEM_QUERY", "CONVERSATION"
    val target: String,      // e.g. "YouTube", "Flashlight ON", "Scroll Down", "Volume 80%"
    val status: String,      // "SUCCESS", "FAILED", "RUNNING", "CANCELLED"
    val aiResponse: String,  // Text spoken by Myraa
    val executionDetails: String // Specific technical trace for transparency
)
