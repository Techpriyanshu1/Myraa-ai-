package com.example.agents

import kotlinx.coroutines.flow.StateFlow

/**
 * Lifecycle states of an AI Sub-Agent
 */
enum class AgentStatus {
    NOT_CONFIGURED,
    INITIALIZING,
    READY,
    ACTIVE,
    PERMISSION_REQUIRED,
    ERROR,
    DISABLED
}

/**
 * Health report returned by every sub-agent during monitoring
 */
data class AgentHealthReport(
    val agentId: String,
    val name: String,
    val role: String,
    val status: AgentStatus,
    val detailMessage: String,
    val requiresPermission: Boolean = false,
    val requiresCredential: Boolean = false,
    val lastActiveTimestamp: Long = System.currentTimeMillis()
)

/**
 * Standard contract for all modular sub-agents in MYRAA AI
 */
interface MyraaSubAgent {
    val agentId: String
    val name: String
    val role: String
    val statusFlow: StateFlow<AgentStatus>

    suspend fun initialize(): Boolean
    suspend fun start(): Boolean
    suspend fun stop(): Boolean
    fun checkHealth(): AgentHealthReport
}
