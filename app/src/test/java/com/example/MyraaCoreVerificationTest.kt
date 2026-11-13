package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.agents.AgentFleetManager
import com.example.agents.AgentStatus
import com.example.agents.InitializationVerifier
import com.example.agents.PreflightSeverity
import com.example.automation.ActionRiskLevel
import com.example.automation.ActionSecurityPolicy
import com.example.automation.DeviceActionExecutor
import com.example.core.MyraaConstants
import com.example.core.NetworkRetryHelper
import com.example.data.api.ConnectionTestResult
import com.example.data.api.ElevenLabsClient
import com.example.data.api.GeminiClient
import com.example.data.api.ParsedCommand
import com.example.data.api.VoiceTestResult
import com.example.data.db.AppDatabase
import com.example.data.db.AutomationLogEntity
import com.example.data.pref.SecurePreferences
import com.example.permissions.PermissionManager
import com.example.permissions.PermissionState
import com.example.shizuku.ShizukuBridge
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MyraaCoreVerificationTest {

    private lateinit var context: Context
    private lateinit var prefs: SecurePreferences
    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("myraa_secure_vault_v2", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = SecurePreferences(context)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `first install defaults to setup incomplete and Mahi as default voice`() {
        assertFalse(prefs.isSetupCompleted())
        assertEquals(SecurePreferences.VOICE_MAHI, prefs.getSelectedVoiceId())
        assertEquals("MYRAA AI", MyraaConstants.APP_NAME)
        assertEquals("Your Voice. Your Intelligence. Your Control.", MyraaConstants.TAGLINE)
    }

    @Test
    fun `onboarding completion and separate credential deletion work properly`() {
        prefs.setSetupCompleted(true)
        assertTrue(prefs.isSetupCompleted())

        prefs.setGeminiApiKey("test-gemini-value-12345")
        prefs.setVoiceApiKey("test-voice-value-67890")
        prefs.setSelectedVoiceId("custom-mahi-id")
        assertEquals("custom-mahi-id", prefs.getSelectedVoiceId())

        prefs.deleteGeminiCredentials()
        prefs.deleteVoiceCredentials()
        assertEquals(SecurePreferences.VOICE_MAHI, prefs.getSelectedVoiceId())

        prefs.deleteAllCredentials()
        assertFalse(prefs.hasStoredVoiceKey())
    }

    @Test
    fun `empty Gemini API key and missing voice config return readable failures`() = runTest {
        val gemini = GeminiClient()
        val elevenLabs = ElevenLabsClient(context)

        val geminiResult = gemini.testConnection("", SecurePreferences.DEFAULT_GEMINI_MODEL)
        assertTrue(geminiResult is ConnectionTestResult.Failure)
        assertTrue((geminiResult as ConnectionTestResult.Failure).errorMessage.contains("empty", ignoreCase = true))

        val missingVoiceKeyResult = elevenLabs.testVoiceConnection("", SecurePreferences.VOICE_MAHI)
        assertTrue(missingVoiceKeyResult is VoiceTestResult.Failure)
        assertTrue((missingVoiceKeyResult as VoiceTestResult.Failure).isConfigError)

        val missingVoiceIdResult = elevenLabs.testVoiceConnection("valid-looking-key", "")
        assertTrue(missingVoiceIdResult is VoiceTestResult.Failure)
        assertTrue((missingVoiceIdResult as VoiceTestResult.Failure).errorMessage.contains("Mahi", ignoreCase = true))

        elevenLabs.shutdown()
    }

    @Test
    fun `natural Hindi Hinglish and English voice commands parse cleanly without code injection`() = runTest {
        val gemini = GeminiClient()

        val ytCmd = gemini.interpretCommand("Hey Myraa, open YouTube.", "")
        assertEquals("OPEN_APP", ytCmd.actionType)
        assertEquals("YouTube", ytCmd.target)

        val calcCmd = gemini.interpretCommand("Myraa, open calculator.", "")
        assertEquals("OPEN_CALCULATOR", calcCmd.actionType)

        val btCmd = gemini.interpretCommand("Open Bluetooth settings.", "")
        assertEquals("OPEN_SETTINGS", btCmd.actionType)
        assertEquals("bluetooth", btCmd.target)
        assertFalse(btCmd.spokenResponse.contains("Bluetooth on", ignoreCase = true))

        val camCmd = gemini.interpretCommand("Launch camera.", "")
        assertEquals("OPEN_CAMERA", camCmd.actionType)

        val pauseCmd = gemini.interpretCommand("Pause listening.", "")
        assertEquals("PAUSE_LISTENING", pauseCmd.actionType)

        val stopCmd = gemini.interpretCommand("Stop AI.", "")
        assertEquals("STOP_AI", stopCmd.actionType)
    }

    @Test
    fun `security allowlist blocks shell or code payloads and flags high risk confirmations`() {
        val codeInjection = ParsedCommand(
            spokenResponse = "Running",
            actionType = "OPEN_APP",
            target = "sh -c rm -rf /",
            rawPrompt = "hack phone"
        )
        val blockedResult = ActionSecurityPolicy.validate(codeInjection)
        assertFalse(blockedResult.allowed)
        assertEquals("BLOCKED", blockedResult.normalizedAction)

        val unallowlisted = ParsedCommand(
            spokenResponse = "Running",
            actionType = "EXEC_SHELL",
            target = "su",
            rawPrompt = "run shell"
        )
        assertFalse(ActionSecurityPolicy.validate(unallowlisted).allowed)

        val lockScreen = ParsedCommand(
            spokenResponse = "Locking",
            actionType = "ACCESSIBILITY",
            target = "lock",
            rawPrompt = "lock phone"
        )
        val lockValidation = ActionSecurityPolicy.validate(lockScreen)
        assertTrue(lockValidation.allowed)
        assertEquals(ActionRiskLevel.HIGH, lockValidation.riskLevel)
        assertTrue(lockValidation.requiresConfirmation)
    }

    @Test
    fun `sanitizeSensitiveText redacts keys and headers before local logging`() {
        val raw = "Using key AIzaSyD1234567890ABCDEFGHIJKLMNOP and xi-api-key: sk_live_secret_1234567890abcd"
        val sanitized = NetworkRetryHelper.sanitizeSensitiveText(raw)
        assertFalse(sanitized.contains("AIzaSyD"))
        assertFalse(sanitized.contains("sk_live_secret"))
        assertTrue(sanitized.contains("[REDACTED"))
    }

    @Test
    fun `all 11 agents report honest statuses and unavailable agents never become ACTIVE`() = runTest {
        val fleet = AgentFleetManager(context, prefs)
        val summary = fleet.initializeAndStartEligibleAgents()

        assertEquals(11, summary.totalAgents)
        val micReport = fleet.getAgentReport("voice_recognition")
        assertNotNull(micReport)
        // Under Robolectric without granted RECORD_AUDIO, VoiceRecognitionAgent MUST NOT become ACTIVE
        assertTrue(micReport!!.status != AgentStatus.ACTIVE)

        val shizukuStatus = ShizukuBridge.checkStatus(context)
        assertFalse(shizukuStatus.isInstalled)

        val shizukuAgent = fleet.getAgentReport("shizuku_integration")
        assertNotNull(shizukuAgent)
        assertEquals(AgentStatus.DISABLED, shizukuAgent!!.status)

        fleet.stopAllAgents()
    }

    @Test
    fun `11-point InitializationVerifier detects missing Mic permission and Shizuku unavailability`() = runTest {
        val fleet = AgentFleetManager(context, prefs)
        val verifier = InitializationVerifier(context, prefs, GeminiClient(), fleet)
        val report = verifier.verifyAll(checkLiveConnectivity = false)

        assertEquals(11, report.checks.size)
        assertFalse(report.canStartVoiceSession)
        assertTrue(report.blockingMessages.any { it.contains("Microphone permission required", ignoreCase = true) })
        val shizukuCheck = report.checks.first { it.id == "shizuku_status" }
        assertEquals(PreflightSeverity.WARNING, shizukuCheck.severity)
        assertTrue(shizukuCheck.message.contains("Shizuku service is unavailable", ignoreCase = true))
    }

    @Test
    fun `PermissionManager audits all core and optional permissions`() {
        val pm = PermissionManager(context)
        val items = pm.getAllPermissionItems()
        assertEquals(5, items.size)
        val micItem = items.first { it.id == "microphone" }
        assertTrue(micItem.isMandatory)
        assertEquals(PermissionState.DENIED, micItem.state)
    }

    @Test
    fun `missing application launch reports honest failure without crashing`() = runTest {
        val executor = DeviceActionExecutor(context)
        val cmd = ParsedCommand(
            spokenResponse = "Opening non-existent app",
            actionType = "OPEN_APP",
            target = "TotallyNonExistentMyraaTestApp999",
            rawPrompt = "open TotallyNonExistentMyraaTestApp999",
            intent = "open_app"
        )
        val result = executor.executeCommand(cmd)
        assertFalse(result.success)
        assertTrue(result.details.contains("not installed", ignoreCase = true))
    }

    @Test
    fun `Room activity history supports insert, query, delete single, and clear all`() = runTest {
        val dao = db.automationLogDao()
        val id1 = dao.insertLog(
            AutomationLogEntity(
                voicePrompt = "Open Calculator",
                recognizedIntent = "open_calculator",
                actionType = "OPEN_CALCULATOR",
                target = "Calculator",
                agentInvolved = "Device Action Agent",
                permissionStatus = "NOT_REQUIRED",
                confirmationStatus = "AUTO_APPROVED",
                status = "SUCCESS",
                aiResponse = "Calculator open kar diya hai.",
                executionDetails = "Launched Calculator",
                executionDurationMs = 14L
            )
        )
        val id2 = dao.insertLog(
            AutomationLogEntity(
                voicePrompt = "Lock phone",
                recognizedIntent = "accessibility_lock",
                actionType = "ACCESSIBILITY",
                target = "lock",
                agentInvolved = "Permission Manager Agent",
                permissionStatus = "GRANTED",
                confirmationStatus = "USER_CANCELLED",
                status = "CANCELLED",
                aiResponse = "Cancelled",
                executionDetails = "User declined confirmation",
                executionDurationMs = 2L
            )
        )

        assertEquals(2, dao.getLogCount().first())
        dao.deleteLogById(id1)
        val remaining = dao.getAllLogsOnce()
        assertEquals(1, remaining.size)
        assertEquals(id2, remaining.first().id)

        dao.clearAllLogs()
        assertEquals(0, dao.getLogCount().first())
    }

    @Test
    fun `VoiceRecognitionManager handles permission revocation and cleans up cleanly`() {
        val voiceManager = com.example.voice.VoiceRecognitionManager(
            context = context,
            onCommandReceived = {}
        )
        voiceManager.startContinuousListening()
        voiceManager.onError(android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)

        assertFalse(voiceManager.isSessionActive)
        assertEquals(com.example.voice.CompanionState.ERROR, voiceManager.companionState.value)
        assertNotNull(voiceManager.errorMessage.value)

        voiceManager.pauseListening()
        assertEquals(com.example.voice.CompanionState.IDLE, voiceManager.companionState.value)
        voiceManager.destroy()
    }

    @Test
    fun `GeminiClient uses real models and parses dial, search, and polite intents`() = runTest {
        assertFalse(GeminiClient.AVAILABLE_MODELS.contains("gemini-3.8-flash"))
        assertTrue(GeminiClient.AVAILABLE_MODELS.contains("gemini-2.5-flash"))

        prefs.setSelectedGeminiModel("gemini-3.8-flash")
        assertEquals("gemini-2.5-flash", prefs.getSelectedGeminiModel())

        val gemini = GeminiClient()
        val dialCmd = gemini.interpretCommand("Hey Myraa, dial 9876543210", "")
        assertEquals("DIAL", dialCmd.actionType)
        assertEquals("9876543210", dialCmd.target)
        assertTrue(ActionSecurityPolicy.validate(dialCmd).allowed)

        val searchCmd = gemini.interpretCommand("search android 15 features", "")
        assertEquals("WEB_SEARCH", searchCmd.actionType)
        assertEquals("android 15 features", searchCmd.target)

        val wifiDirect = gemini.interpretCommand("please open wifi", "")
        assertEquals("OPEN_SETTINGS", wifiDirect.actionType)
        assertEquals("wifi", wifiDirect.target)
    }
}
