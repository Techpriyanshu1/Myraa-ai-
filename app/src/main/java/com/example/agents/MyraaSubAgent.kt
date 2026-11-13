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
 * Result of validating configuration or permissions for an agent
 */
data class AgentValidationResult(
    val isValid: Boolean,
    val statusOnFailure: AgentStatus = AgentStatus.ERROR,
    val reason: String = ""
)

/**
 * Health report returned by every sub-agent during monitoring
 */
data class AgentHealthReport(
    val agentId: String,
    val name: String,
    val role: String,
    val status: AgentStatus,
    val detailMessage: String,
    val isOptional: Boolean = false,
    val requiresPermission: Boolean = false,
    val requiresCredential: Boolean = false,
    val errorReason: String? = null,
    val dependencies: List<String> = emptyList(),
    val lastActiveTimestamp: Long = System.currentTimeMillis()
)

/**
 * Standard contract for all 11 modular sub-agents in MYRAA AI
 */
interface MyraaSubAgent {
    val agentId: String
    val name: String
    val role: String
    val isOptional: Boolean
    val dependencies: List<String>
    val statusFlow: StateFlow<AgentStatus>

    fun validateConfiguration(): AgentValidationResult
    fun validatePermissions(): AgentValidationResult
    suspend fun initialize(): Boolean
    suspend fun start(): Boolean
    suspend fun stop(): Boolean
    fun recordError(reason: String)
    fun clearError()
    fun checkHealth(): AgentHealthReport
}
