package com.example.agents

import android.content.Context
import com.example.data.pref.SecurePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FleetStartupSummary(
    val activeAgents: List<AgentHealthReport>,
    val unavailableAgents: List<AgentHealthReport>,
    val totalAgents: Int
) {
    fun buildConciseSummary(): String {
        if (unavailableAgents.isEmpty()) {
            return "All $totalAgents agents are initialized and ACTIVE."
        }
        val reasons = unavailableAgents.joinToString("; ") { "${it.name}: ${it.errorReason ?: it.detailMessage}" }
        return "${activeAgents.size}/$totalAgents agents active. Unavailable: $reasons"
    }
}

/**
 * Central Orchestrator managing initialization, dependency verification,
 * startup/shutdown controls, and health monitoring across all 11 modular agents.
 */
class AgentFleetManager(
    private val context: Context,
    private val prefs: SecurePreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _agentReports = MutableStateFlow<List<AgentHealthReport>>(emptyList())
    val agentReports: StateFlow<List<AgentHealthReport>> = _agentReports.asStateFlow()

    private val _fleetOverallStatus = MutableStateFlow("Fleet Standing By")
    val fleetOverallStatus: StateFlow<String> = _fleetOverallStatus.asStateFlow()

    private val _unavailableReasons = MutableStateFlow<List<String>>(emptyList())
    val unavailableReasons: StateFlow<List<String>> = _unavailableReasons.asStateFlow()

    private lateinit var systemHealthAgent: SystemHealthAgent

    private val agents: List<MyraaSubAgent> by lazy {
        val coreAndSystem = listOf(
            GeminiIntelligenceAgent(prefs),
            VoiceRecognitionAgent(context),
            MahiVoiceOutputAgent(prefs),
            AppLauncherAgent(context),
            DeviceActionAgent(context),
            PermissionManagerAgent(context),
            AccessibilityAgent(),
            ShizukuIntegrationAgent(context),
            FloatingAssistantAgent(context, prefs),
            ActivityLoggerAgent(context)
        )
        systemHealthAgent = SystemHealthAgent { coreAndSystem.map { it.checkHealth() } }
        coreAndSystem + systemHealthAgent
    }

    init {
        refreshFleetStatus()
    }

    /**
     * Evaluates current configuration & permissions of all 11 agents without force-activating them.
     */
    fun refreshFleetStatus(clearErrors: Boolean = false) {
        if (clearErrors) {
            agents.forEach { it.clearError() }
        }
        val reports = agents.map { it.checkHealth() }
        publishReports(reports)
    }

    fun clearAgentError(agentId: String) {
        val agent = agents.firstOrNull { it.agentId == agentId } ?: return
        agent.clearError()
        publishReports(agents.map { it.checkHealth() })
    }

    /**
     * START AI Startup Logic:
     * 1. Initialize all configured agents
     * 2. Verify their dependencies
     * 3. Activate all eligible agents
     * 4. Keep optional unavailable agents disabled / permission-required
     * 5. Never mark a failed agent as ACTIVE; expose exact reasons
     */
    suspend fun initializeAndStartEligibleAgents(): FleetStartupSummary {
        val orderedByDependency = agents
        val startedStatusById = mutableMapOf<String, AgentStatus>()

        for (agent in orderedByDependency) {
            // Check dependency satisfaction first
            val failedDeps = agent.dependencies.filter { depId ->
                val depStatus = startedStatusById[depId]
                depStatus != AgentStatus.ACTIVE && depStatus != AgentStatus.READY
            }

            if (failedDeps.isNotEmpty()) {
                val depNames = failedDeps.joinToString(", ")
                agent.initialize()
                agent.recordError("Blocked by unavailable dependency: $depNames")
                startedStatusById[agent.agentId] = agent.statusFlow.value
                continue
            }

            val activated = agent.start()
            startedStatusById[agent.agentId] = if (activated) AgentStatus.ACTIVE else agent.statusFlow.value
        }

        val reports = agents.map { it.checkHealth() }
        publishReports(reports)

        val activeList = reports.filter { it.status == AgentStatus.ACTIVE }
        val unavailableList = reports.filter { it.status != AgentStatus.ACTIVE }
        return FleetStartupSummary(
            activeAgents = activeList,
            unavailableAgents = unavailableList,
            totalAgents = reports.size
        )
    }

    suspend fun stopAllAgents() {
        agents.forEach { it.stop() }
        refreshFleetStatus()
    }

    fun startAgentAsync(agentId: String) {
        scope.launch {
            val agent = agents.firstOrNull { it.agentId == agentId } ?: return@launch
            agent.start()
            publishReports(agents.map { it.checkHealth() })
        }
    }

    fun stopAgentAsync(agentId: String) {
        scope.launch {
            val agent = agents.firstOrNull { it.agentId == agentId } ?: return@launch
            agent.stop()
            publishReports(agents.map { it.checkHealth() })
        }
    }

    fun initializeAllAsync() {
        scope.launch {
            initializeAndStartEligibleAgents()
        }
    }

    fun stopAllAsync() {
        scope.launch {
            stopAllAgents()
        }
    }

    fun recordAgentError(agentId: String, reason: String) {
        val agent = agents.firstOrNull { it.agentId == agentId } ?: return
        agent.recordError(reason)
        publishReports(agents.map { it.checkHealth() })
    }

    fun getAgentReport(agentId: String): AgentHealthReport? {
        return _agentReports.value.firstOrNull { it.agentId == agentId }
    }

    private fun publishReports(reports: List<AgentHealthReport>) {
        _agentReports.value = reports
        val activeCount = reports.count { it.status == AgentStatus.ACTIVE }
        val readyCount = reports.count { it.status == AgentStatus.READY }
        val unavailable = reports.filter {
            it.status == AgentStatus.ERROR ||
                it.status == AgentStatus.DISABLED ||
                it.status == AgentStatus.PERMISSION_REQUIRED ||
                it.status == AgentStatus.NOT_CONFIGURED
        }
        _unavailableReasons.value = unavailable.map { "${it.name}: ${it.errorReason ?: it.detailMessage}" }
        _fleetOverallStatus.value = if (activeCount > 0) {
            "$activeCount ACTIVE • $readyCount READY • ${unavailable.size} Unavailable"
        } else {
            "$readyCount / ${reports.size} Ready • ${unavailable.size} Require Setup/Permission"
        }
    }

    companion object {
        @Volatile
        private var instance: AgentFleetManager? = null

        fun getInstance(context: Context, prefs: SecurePreferences): AgentFleetManager {
            return instance ?: synchronized(this) {
                instance ?: AgentFleetManager(context.applicationContext, prefs).also { instance = it }
            }
        }
    }
}
