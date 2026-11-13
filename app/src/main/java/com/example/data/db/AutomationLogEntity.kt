package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "automation_logs")
data class AutomationLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val voicePrompt: String,
    val recognizedIntent: String = "unknown",
    val actionType: String,
    val target: String,
    val agentInvolved: String = "System Health Agent",
    val permissionStatus: String = "GRANTED",
    val confirmationStatus: String = "AUTO_APPROVED",
    val status: String, // "SUCCESS", "FAILED", "CANCELLED", "BLOCKED"
    val aiResponse: String,
    val executionDetails: String,
    val errorInfo: String = "",
    val executionDurationMs: Long = 0L
)
