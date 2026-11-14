package com.example.data.api

import android.util.Base64
import android.util.Log
import com.example.core.SharedHttpClient
import com.example.memory.MemoryCoreManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ParsedCommand(
    val spokenResponse: String,
    val actionType: String, // Allowlisted action e.g. OPEN_APP, YOUTUBE_SEARCH, ACCESSIBILITY, SCREEN_CLICK, CHAT
    val target: String,
    val extraData: String = "",
    val rawPrompt: String,
    val intent: String = "conversation",
    val riskLevel: String = "LOW",
    val requiresConfirmation: Boolean = false,
    val heardSummary: String = ""
)

sealed class ConnectionTestResult {
    data class Success(val message: String, val modelUsed: String) : ConnectionTestResult()
    data class Failure(val errorMessage: String, val errorCode: Int? = null) : ConnectionTestResult()
}

class GeminiClient {

    private val client = SharedHttpClient.instance

    companion object {
        val AVAILABLE_MODELS = listOf(
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-3-flash-preview",
            "gemini-flash-latest",
            "gemini-3.1-pro-preview"
        )

        private val RETIRED_MODELS = setOf(
            "gemini-3.8-flash",
            "gemini-2.5-pro",
            "gemini-2.0-flash"
        )

        private val discoveredModelsCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
        private val verifiedModelByKey = java.util.concurrent.ConcurrentHashMap<String, String>()
        private val modelCooldownUntilMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

        /**
         * Parses Google's `/v1beta/models` response JSON and returns sorted `generateContent`-capable
         * Gemini model IDs prioritizing fast flash models and newer Gemini 3/3.1 families.
         */
        fun parseModelsJson(jsonStr: String): List<String> {
            if (jsonStr.isBlank()) return emptyList()
            return try {
                val root = JSONObject(jsonStr)
                val modelsArr = root.optJSONArray("models") ?: return emptyList()
                val candidates = mutableListOf<String>()
                for (i in 0 until modelsArr.length()) {
                    val obj = modelsArr.optJSONObject(i) ?: continue
                    val fullName = obj.optString("name", "").trim()
                    val shortName = fullName.removePrefix("models/")
                    if (!shortName.startsWith("gemini-", ignoreCase = true)) continue

                    val lower = shortName.lowercase()
                    if (lower.contains("embedding") ||
                        lower.contains("tts") ||
                        lower.contains("image") ||
                        lower.contains("imagen") ||
                        lower.contains("veo") ||
                        lower.contains("native-audio") ||
                        lower.contains("live") ||
                        lower.contains("robotics") ||
                        lower.contains("computer-use") ||
                        lower.contains("deep-research") ||
                        lower.contains("aqa")
                    ) {
                        continue
                    }

                    val methods = obj.optJSONArray("supportedGenerationMethods")
                    var supportsGenerate = false
                    if (methods != null) {
                        for (m in 0 until methods.length()) {
                            if (methods.optString(m) == "generateContent") {
                                supportsGenerate = true
                                break
                            }
                        }
                    }
                    if (supportsGenerate) {
                        candidates += shortName
                    }
                }
                candidates.distinct().sortedWith(
                    compareBy<String>(
                        { modelRankTier(it) },
                        { if (it.contains("lite", ignoreCase = true)) 1 else 0 },
                        { if (it.contains("preview", ignoreCase = true) || it.contains("exp", ignoreCase = true)) 1 else 0 }
                    )
                )
            } catch (_: Exception) {
                emptyList()
            }
        }

        private fun modelRankTier(name: String): Int {
            val l = name.lowercase()
            return when {
                l == "gemini-2.5-flash" -> 0
                l == "gemini-2.5-flash-lite" -> 1
                l.startsWith("gemini-3") && l.contains("flash") -> 2
                l.contains("flash") && !l.startsWith("gemini-1") -> 3
                l.contains("pro") -> 4
                else -> 5
            }
        }
    }

    private fun sanitizePreferredModel(model: String): String {
        val trimmed = model.trim()
        return if (trimmed.isBlank() || trimmed in RETIRED_MODELS) {
            AVAILABLE_MODELS.first()
        } else {
            trimmed
        }
    }

    private fun buildThinkingConfig(model: String): JSONObject? {
        val lower = model.lowercase()
        return when {
            lower.startsWith("gemini-2.5-flash") && !lower.contains("lite") -> JSONObject().put("thinkingBudget", 0)
            lower.startsWith("gemini-3") && lower.contains("flash") -> JSONObject().put("thinkingLevel", "minimal")
            lower.startsWith("gemini-3") && lower.contains("pro") -> JSONObject().put("thinkingLevel", "low")
            else -> null
        }
    }

    private fun recordRateLimitCooldown(model: String, errBody: String, retryHeaderSec: Long?): Long {
        val lower = errBody.lowercase()
        val isZeroQuota = lower.contains("limit: 0") || lower.contains("limit:0")
        val retryMsFromBody = Regex("retry in\\s+([0-9]+(?:\\.[0-9]+)?)s", RegexOption.IGNORE_CASE)
            .find(errBody)?.groupValues?.get(1)?.toDoubleOrNull()?.times(1000.0)?.toLong()
        val headerMs = retryHeaderSec?.times(1000L)
        val cooldownMs = when {
            isZeroQuota -> 300_000L
            retryMsFromBody != null -> retryMsFromBody.coerceIn(1_000L, 4_500L)
            headerMs != null -> headerMs.coerceIn(1_000L, 4_500L)
            else -> 3_000L
        }
        modelCooldownUntilMs[model] = System.currentTimeMillis() + cooldownMs
        return cooldownMs
    }

    private fun executeGenerateRequest(
        model: String,
        cleanKey: String,
        payloadBuilder: (includeThinking: Boolean) -> JSONObject
    ): Triple<Int, String, Long?> {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        fun send(includeThinking: Boolean): Triple<Int, String, Long?> {
            val payload = payloadBuilder(includeThinking)
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", cleanKey)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()
            return client.newCall(request).execute().use { response ->
                val retryAfterSec = response.header("Retry-After")?.toLongOrNull()
                Triple(response.code, response.body?.string() ?: "", retryAfterSec)
            }
        }

        var first = send(true)
        // Auto-retry without thinkingConfig if a model variant returns HTTP 400 rejecting thinkingConfig
        if (first.first == 400 && buildThinkingConfig(model) != null) {
            val err = parseErrorMessage(first.second)
            if (!err.contains("API key", ignoreCase = true)) {
                val second = send(false)
                if (second.first in 200..299 || second.first != 400) {
                    return second
                }
            }
        }
        // Single fast retry on transient 409 Conflict / 503 Unavailable
        if (first.first == 409 || first.first == 503) {
            try { Thread.sleep(400L) } catch (_: InterruptedException) {}
            first = send(true)
        }
        return first
    }

    /**
     * Queries Google's `/v1beta/models` endpoint to find which Gemini models
     * are actively enabled for this specific API key.
     */
    suspend fun discoverAvailableModels(
        apiKey: String,
        forceRefresh: Boolean = false
    ): List<String> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) return@withContext emptyList()
        if (!forceRefresh) {
            discoveredModelsCache[cleanKey]?.let { if (it.isNotEmpty()) return@withContext it }
        }

        try {
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=100")
                .addHeader("x-goog-api-key", cleanKey)
                .get()
                .build()

            val (code, bodyStr) = com.example.core.NetworkRetryHelper.withExponentialBackoff(maxAttempts = 2) {
                client.newCall(request).execute().use { resp ->
                    Pair(resp.code, resp.body?.string() ?: "")
                }
            }

            if (code in 200..299 && bodyStr.isNotBlank()) {
                val parsed = parseModelsJson(bodyStr)
                if (parsed.isNotEmpty()) {
                    discoveredModelsCache[cleanKey] = parsed
                    Log.i("GeminiClient", "Discovered ${parsed.size} live Gemini models: ${parsed.take(5)}")
                }
                return@withContext parsed
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w("GeminiClient", "Model discovery failed", e)
        }
        emptyList()
    }

    suspend fun testConnection(apiKey: String, selectedModel: String): ConnectionTestResult = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext ConnectionTestResult.Failure("API key is empty. Please enter your Gemini API key.")
        }

        val safePreferred = sanitizePreferredModel(selectedModel)

        // Discover live enabled models for this key so retired keys automatically resolve to 3.x / latest
        val discovered = discoverAvailableModels(cleanKey, forceRefresh = true)
            .filterNot { it in RETIRED_MODELS }
        val candidateModels = if (discovered.isNotEmpty()) {
            buildList {
                if (safePreferred in discovered) add(safePreferred)
                addAll(discovered)
                addAll(AVAILABLE_MODELS)
            }.distinct()
        } else {
            (listOf(safePreferred) + AVAILABLE_MODELS).distinct()
        }

        var lastFailure: ConnectionTestResult.Failure? = null
        var firstRateLimitFailure: ConnectionTestResult.Failure? = null

        for (targetModel in candidateModels) {
            try {
                val (code, errBody, retryAfterSec) = com.example.core.NetworkRetryHelper.withExponentialBackoff(
                    maxAttempts = 2
                ) {
                    executeGenerateRequest(targetModel, cleanKey) { includeThinking ->
                        JSONObject().apply {
                            put("contents", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("role", "user")
                                    put("parts", JSONArray().apply {
                                        put(JSONObject().put("text", "Reply with only OK."))
                                    })
                                })
                            })
                            put("generationConfig", JSONObject().apply {
                                put("maxOutputTokens", 128)
                                if (includeThinking) {
                                    buildThinkingConfig(targetModel)?.let { put("thinkingConfig", it) }
                                }
                            })
                        }
                    }
                }

                if (code in 200..299) {
                    verifiedModelByKey[cleanKey] = targetModel
                    modelCooldownUntilMs.remove(targetModel)
                    return@withContext ConnectionTestResult.Success(
                        message = "Connection successful with $targetModel!",
                        modelUsed = targetModel
                    )
                }

                val parsedErr = parseErrorMessage(errBody)
                if (code == 429) {
                    recordRateLimitCooldown(targetModel, errBody, retryAfterSec)
                }
                val humanMsg = when (code) {
                    400 -> "API key or request invalid: $parsedErr"
                    401, 403 -> "Invalid Gemini API key or access denied: $parsedErr"
                    404 -> "Model $targetModel unavailable: $parsedErr"
                    429 -> "Rate limit / quota reached ($targetModel): $parsedErr"
                    else -> "Connection failed (HTTP $code): $parsedErr"
                }
                val failure = ConnectionTestResult.Failure(humanMsg, code)
                if (code == 429 && firstRateLimitFailure == null) {
                    firstRateLimitFailure = failure
                }
                if (lastFailure == null || code != 404) {
                    lastFailure = failure
                }

                // Invalid API key won't pass on other models — fail fast with clear error
                if (code == 401 || code == 403 || (code == 400 && parsedErr.contains("API key", ignoreCase = true))) {
                    return@withContext failure
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.e("GeminiClient", "Test connection exception for $targetModel", e)
                return@withContext ConnectionTestResult.Failure("Network error: ${e.localizedMessage ?: "Check internet connection"}")
            }
        }

        return@withContext firstRateLimitFailure ?: lastFailure ?: ConnectionTestResult.Failure("Gemini connection failed.")
    }

    suspend fun interpretCommand(
        prompt: String,
        apiKey: String,
        preferredModel: String = AVAILABLE_MODELS.first(),
        screenImageBase64: String? = null,
        memoryContext: String = "",
        screenContextSummary: String = "",
        onModelAutoUpdated: ((String) -> Unit)? = null
    ): ParsedCommand = withContext(Dispatchers.IO) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) {
            return@withContext ParsedCommand(
                spokenResponse = "Main sun rahi hoon! Boliye, main aapki kya madad kar sakti hoon?",
                actionType = "CHAT",
                target = "",
                rawPrompt = prompt,
                intent = "greeting"
            )
        }

        // Normalize wake-words: "Hey Myraa, open YouTube" -> "open YouTube"
        val normalized = stripWakeWords(trimmed)

        // 1. Direct deterministic reflexes (voice controls, settings, hardware, camera, calculator, clock, apps)
        parseDeviceHardwareReflex(normalized, trimmed)?.let { return@withContext it }

        // 2. Direct screen actions reflex (digital mouse clicks, virtual typing)
        parseScreenActionReflex(normalized, trimmed)?.let { return@withContext it }

        // 3. Structured JSON Intelligence via Gemini with Mahi Persona + Multimodal Screen Vision
        val cleanKey = apiKey.trim()
        var lastApiError: String? = null
        var firstActionableError: String? = null
        if (cleanKey.isNotBlank()) {
            val safePreferred = sanitizePreferredModel(preferredModel)

            val cachedVerified = verifiedModelByKey[cleanKey]?.takeIf { it !in RETIRED_MODELS }
            val initialDiscovered = discoveredModelsCache[cleanKey].orEmpty().filterNot { it in RETIRED_MODELS }
            val queue = ArrayDeque<String>()
            val attempted = mutableSetOf<String>()

            if (!cachedVerified.isNullOrBlank()) queue.add(cachedVerified)
            queue.add(safePreferred)
            initialDiscovered.forEach { if (!queue.contains(it)) queue.add(it) }
            AVAILABLE_MODELS.forEach { if (!queue.contains(it)) queue.add(it) }

            var didLiveDiscovery = initialDiscovered.isNotEmpty()
            val effectiveMemory = buildAuthoritativeMemoryBlock(memoryContext)
            val systemPrompt = buildGeminiSystemPrompt(effectiveMemory, screenContextSummary)

            while (queue.isNotEmpty()) {
                val model = queue.removeFirst()
                if (!attempted.add(model)) continue
                val cooldownUntil = modelCooldownUntilMs[model] ?: 0L
                if (cooldownUntil > System.currentTimeMillis() && queue.isNotEmpty()) {
                    if (firstActionableError == null) {
                        firstActionableError = "HTTP 429: Rate limit cooldown active for $model"
                    }
                    continue
                }

                try {
                    val userParts = JSONArray().apply {
                        put(JSONObject().put("text", "$systemPrompt\n\nUser command: $trimmed"))
                        if (!screenImageBase64.isNullOrBlank()) {
                            put(JSONObject().apply {
                                put("inlineData", JSONObject().apply {
                                    put("mimeType", "image/jpeg")
                                    put("data", screenImageBase64)
                                })
                            })
                        }
                    }

                    val (code, rawBody, retryAfterSec) = com.example.core.NetworkRetryHelper.withExponentialBackoff(
                        maxAttempts = 2
                    ) {
                        executeGenerateRequest(model, cleanKey) { includeThinking ->
                            JSONObject().apply {
                                put("contents", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("role", "user")
                                        put("parts", userParts)
                                    })
                                })
                                put("generationConfig", JSONObject().apply {
                                    put("temperature", 0.3)
                                    put("maxOutputTokens", 384)
                                    put("responseMimeType", "application/json")
                                    if (includeThinking) {
                                        buildThinkingConfig(model)?.let { put("thinkingConfig", it) }
                                    }
                                })
                            }
                        }
                    }

                    if (code in 200..299 && rawBody.isNotBlank()) {
                        modelCooldownUntilMs.remove(model)
                        val root = JSONObject(rawBody)
                        val candidates = root.optJSONArray("candidates")
                        if (candidates != null && candidates.length() > 0) {
                            val contentObj = candidates.getJSONObject(0).optJSONObject("content")
                            val parts = contentObj?.optJSONArray("parts")
                            if (parts != null && parts.length() > 0) {
                                val rawText = buildString {
                                    for (i in 0 until parts.length()) {
                                        val p = parts.optJSONObject(i) ?: continue
                                        if (!p.optBoolean("thought", false)) {
                                            val txt = p.optString("text", "")
                                            if (txt.isNotBlank()) append(txt)
                                        }
                                    }
                                }.trim()
                                val structured = extractStructuredJson(rawText, trimmed)
                                if (structured != null) {
                                    verifiedModelByKey[cleanKey] = model
                                    if (model != safePreferred) {
                                        onModelAutoUpdated?.invoke(model)
                                    }
                                    Log.i("GeminiClient", "Structured JSON parsed via $model -> ${structured.actionType}")
                                    return@withContext structured
                                } else {
                                    lastApiError = "Gemini ($model) returned empty response."
                                }
                            }
                        }
                    } else {
                        val errMsg = parseErrorMessage(rawBody)
                        val formattedErr = "HTTP $code: $errMsg"
                        if (code == 429 || code == 409 || code in 500..599) {
                            recordRateLimitCooldown(model, rawBody, retryAfterSec)
                            if (firstActionableError == null) firstActionableError = formattedErr
                        } else if (code != 404 && firstActionableError == null) {
                            firstActionableError = formattedErr
                        }
                        if (lastApiError == null || code != 404) {
                            lastApiError = formattedErr
                        }
                        Log.w("GeminiClient", "Gemini $model HTTP $code: $errMsg")
                        if (code == 401 || code == 403 || (code == 400 && errMsg.contains("API key", ignoreCase = true))) {
                            break
                        }
                        if (code == 429 || code == 409 || code in 500..599) {
                            continue
                        }
                        if (!didLiveDiscovery && (code == 404 || code == 400)) {
                            didLiveDiscovery = true
                            val liveModels = discoverAvailableModels(cleanKey, forceRefresh = true)
                                .filterNot { it in RETIRED_MODELS }
                            if (liveModels.isNotEmpty()) {
                                liveModels.asReversed().forEach { discoveredModel ->
                                    if (!attempted.contains(discoveredModel) && !queue.contains(discoveredModel)) {
                                        queue.addFirst(discoveredModel)
                                    }
                                }
                            }
                        }
                    }
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    lastApiError = e.localizedMessage ?: "Network error"
                    Log.w("GeminiClient", "Error querying Gemini $model", e)
                }
            }
            lastApiError = firstActionableError ?: lastApiError
        }

        // 4. Fallback bilingual conversation engine (or report real Gemini error if key is present)
        return@withContext intelligentConversationalEngine(trimmed, hasConfiguredKey = cleanKey.isNotBlank(), apiError = lastApiError)
    }

    /**
     * Direct Voice-to-Gemini Multimodal Audio understanding (NO Speech-to-Text intermediary!).
     * Sends raw 16kHz WAV audio directly to Gemini so MYRAA understands Hindi, Hinglish, and English voice naturally.
     */
    suspend fun interpretAudioCommand(
        audioWavBytes: ByteArray,
        apiKey: String,
        preferredModel: String = AVAILABLE_MODELS.first(),
        screenImageBase64: String? = null,
        memoryContext: String = "",
        screenContextSummary: String = "",
        onModelAutoUpdated: ((String) -> Unit)? = null
    ): ParsedCommand = withContext(Dispatchers.IO) {
        if (audioWavBytes.size < 100) {
            return@withContext ParsedCommand(
                spokenResponse = "Awaaz clear nahi aayi, phir boliye.",
                actionType = "CHAT",
                target = "",
                rawPrompt = "Voice command",
                intent = "conversation"
            )
        }

        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext ParsedCommand(
                spokenResponse = "Voice AI ke liye Settings me Gemini API key add karein.",
                actionType = "CHAT",
                target = "",
                rawPrompt = "Voice command",
                intent = "conversation",
                heardSummary = "Voice audio (${audioWavBytes.size / 1024} KB)"
            )
        }

        val audioBase64 = Base64.encodeToString(audioWavBytes, Base64.NO_WRAP)
        val safePreferred = sanitizePreferredModel(preferredModel)

        val cachedVerified = verifiedModelByKey[cleanKey]?.takeIf { it !in RETIRED_MODELS }
        val initialDiscovered = discoveredModelsCache[cleanKey].orEmpty().filterNot { it in RETIRED_MODELS }
        val queue = ArrayDeque<String>()
        val attempted = mutableSetOf<String>()

        if (!cachedVerified.isNullOrBlank()) queue.add(cachedVerified)
        queue.add(safePreferred)
        initialDiscovered.forEach { if (!queue.contains(it)) queue.add(it) }
        AVAILABLE_MODELS.forEach { if (!queue.contains(it)) queue.add(it) }

        var didLiveDiscovery = initialDiscovered.isNotEmpty()
        var lastApiError: String? = null
        var firstActionableError: String? = null

        val effectiveMemory = buildAuthoritativeMemoryBlock(memoryContext)
        val systemPrompt = buildGeminiSystemPrompt(effectiveMemory, screenContextSummary) + "\n- Listen directly to the attached user voice audio, understand intent, put a concise summary of what they said in \"heardSummary\", and respond warmly."

        while (queue.isNotEmpty()) {
            val model = queue.removeFirst()
            if (!attempted.add(model)) continue
            val cooldownUntil = modelCooldownUntilMs[model] ?: 0L
            if (cooldownUntil > System.currentTimeMillis() && queue.isNotEmpty()) {
                if (firstActionableError == null) {
                    firstActionableError = "HTTP 429: Rate limit cooldown active for $model"
                }
                continue
            }

            try {
                val userParts = JSONArray().apply {
                    put(JSONObject().put("text", "$systemPrompt\n\nListen to the user's voice message in the attached audio and return the JSON object."))
                    put(JSONObject().apply {
                        put("inlineData", JSONObject().apply {
                            put("mimeType", "audio/wav")
                            put("data", audioBase64)
                        })
                    })
                    if (!screenImageBase64.isNullOrBlank()) {
                        put(JSONObject().apply {
                            put("inlineData", JSONObject().apply {
                                put("mimeType", "image/jpeg")
                                put("data", screenImageBase64)
                            })
                        })
                    }
                }

                val (code, rawBody, retryAfterSec) = com.example.core.NetworkRetryHelper.withExponentialBackoff(
                    maxAttempts = 2
                ) {
                    executeGenerateRequest(model, cleanKey) { includeThinking ->
                        JSONObject().apply {
                            put("contents", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("role", "user")
                                    put("parts", userParts)
                                })
                            })
                            put("generationConfig", JSONObject().apply {
                                put("temperature", 0.3)
                                put("maxOutputTokens", 384)
                                put("responseMimeType", "application/json")
                                if (includeThinking) {
                                    buildThinkingConfig(model)?.let { put("thinkingConfig", it) }
                                }
                            })
                        }
                    }
                }

                if (code in 200..299 && rawBody.isNotBlank()) {
                    modelCooldownUntilMs.remove(model)
                    val root = JSONObject(rawBody)
                    val candidates = root.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val contentObj = candidates.getJSONObject(0).optJSONObject("content")
                        val parts = contentObj?.optJSONArray("parts")
                        if (parts != null && parts.length() > 0) {
                            val rawText = buildString {
                                for (i in 0 until parts.length()) {
                                    val p = parts.optJSONObject(i) ?: continue
                                    if (!p.optBoolean("thought", false)) {
                                        val txt = p.optString("text", "")
                                        if (txt.isNotBlank()) append(txt)
                                    }
                                }
                            }.trim()
                            val structured = extractStructuredJson(rawText, "Voice command")
                            if (structured != null) {
                                verifiedModelByKey[cleanKey] = model
                                if (model != safePreferred) {
                                    onModelAutoUpdated?.invoke(model)
                                }
                                val heard = structured.heardSummary.ifBlank { "Voice command" }
                                val refined = if (structured.actionType == "CHAT" && heard != "Voice command") {
                                    val stripped = stripWakeWords(heard)
                                    parseDeviceHardwareReflex(stripped, heard) ?: parseScreenActionReflex(stripped, heard) ?: structured.copy(rawPrompt = heard)
                                } else {
                                    structured.copy(rawPrompt = heard)
                                }
                                Log.i("GeminiClient", "Direct Audio JSON parsed via $model -> ${refined.actionType} ($heard)")
                                return@withContext refined
                            } else {
                                lastApiError = "Gemini ($model) returned empty response."
                            }
                        }
                    }
                } else {
                    val errMsg = parseErrorMessage(rawBody)
                    val formattedErr = "HTTP $code: $errMsg"
                    if (code == 429 || code == 409 || code in 500..599) {
                        recordRateLimitCooldown(model, rawBody, retryAfterSec)
                        if (firstActionableError == null) firstActionableError = formattedErr
                    } else if (code != 404 && firstActionableError == null) {
                        firstActionableError = formattedErr
                    }
                    if (lastApiError == null || code != 404) {
                        lastApiError = formattedErr
                    }
                    Log.w("GeminiClient", "Gemini Audio $model HTTP $code: $errMsg")
                    if (code == 401 || code == 403 || (code == 400 && errMsg.contains("API key", ignoreCase = true))) {
                        break
                    }
                    if (code == 429 || code == 409 || code in 500..599) {
                        continue
                    }
                    if (!didLiveDiscovery && (code == 404 || code == 400)) {
                        didLiveDiscovery = true
                        val liveModels = discoverAvailableModels(cleanKey, forceRefresh = true)
                            .filterNot { it in RETIRED_MODELS }
                        if (liveModels.isNotEmpty()) {
                            liveModels.asReversed().forEach { discoveredModel ->
                                if (!attempted.contains(discoveredModel) && !queue.contains(discoveredModel)) {
                                    queue.addFirst(discoveredModel)
                                }
                            }
                        }
                    }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                lastApiError = e.localizedMessage ?: "Network error"
                Log.w("GeminiClient", "Error querying Gemini Audio $model", e)
            }
        }

        val finalError = firstActionableError ?: lastApiError ?: "Network error"
        return@withContext ParsedCommand(
            spokenResponse = "Gemini voice se connect nahi ho paaya ($finalError). Kripya internet ya Gemini key check karein.",
            actionType = "CHAT",
            target = "",
            rawPrompt = "Voice command",
            intent = "conversation",
            heardSummary = "Voice message"
        )
    }

    private fun buildAuthoritativeMemoryBlock(userSuppliedMemory: String): String {
        val permanentLine = "- ${MemoryCoreManager.PERMANENT_MEMORY_LABEL}: ${MemoryCoreManager.PERMANENT_MEMORY_CONTENT}"
        val cleaned = userSuppliedMemory.trim()
        return if (cleaned.contains(MemoryCoreManager.PERMANENT_MEMORY_CONTENT)) {
            cleaned
        } else if (cleaned.isNotBlank()) {
            "[PERMANENT CORE MEMORY - IMMUTABLE, HIGHEST PRIORITY]:\n$permanentLine\n$cleaned"
        } else {
            "[PERMANENT CORE MEMORY - IMMUTABLE, HIGHEST PRIORITY]:\n$permanentLine"
        }
    }

    private fun buildGeminiSystemPrompt(memoryBlock: String, screenContextSummary: String = ""): String {
        val screenSection = if (screenContextSummary.isNotBlank()) {
            "\n[CURRENT ACTIVE SCREEN UI]:\n$screenContextSummary\n"
        } else ""
        return """
            You are MYRAA AI, a warm, loving, fast Indian AI smartphone assistant (Mahi voice).
            $memoryBlock$screenSection
            Reply ONLY with valid raw JSON:
            {
              "heardSummary": "short user transcript",
              "intent": "open_app|youtube_search|build_website|open_settings|open_camera|open_calculator|open_clock|flashlight|volume|accessibility|screen_click|screen_type|web_search|dial|pause_listening|resume_listening|stop_ai|list_apps|show_history|chat",
              "actionType": "OPEN_APP|YOUTUBE_SEARCH|BUILD_WEBSITE|OPEN_SETTINGS|OPEN_CAMERA|OPEN_CALCULATOR|OPEN_CLOCK|FLASHLIGHT|VOLUME|ACCESSIBILITY|SCREEN_CLICK|SCREEN_TYPE|WEB_SEARCH|DIAL|PAUSE_LISTENING|RESUME_LISTENING|STOP_AI|LIST_APPS|SHOW_HISTORY|CHAT",
              "target": "app name, website prompt, YouTube query, setting (wifi|bluetooth|display|sound|location|app|notifications|settings), number, accessibility (home|back|scroll_down|scroll_up|lock), or search query",
              "extraData": "text to type or extra info",
              "riskLevel": "LOW|MEDIUM|HIGH",
              "requiresConfirmation": false,
              "spokenResponse": "Crisp natural 1-sentence reply in user's Hindi/Hinglish/English language (<= 12 words, plain text, no markdown/emojis)"
            }
            RULES:
            - Honor Permanent Core Memory first.
            - Use BUILD_WEBSITE for building/designing websites; YOUTUBE_SEARCH for songs/videos on YouTube; ACCESSIBILITY ("scroll_down"/"scroll_up") for shorts/reels; SCREEN_CLICK/SCREEN_TYPE for visible UI.
            - Set requiresConfirmation=true, riskLevel="HIGH" for lock/delete/uninstall/payment.
            - Keep spokenResponse under 12 words for instant voice.
        """.trimIndent()
    }

    private fun extractStructuredJson(rawText: String, rawPrompt: String): ParsedCommand? {
        if (rawText.isBlank()) return null
        val cleaned = rawText
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val startIdx = cleaned.indexOf('{')
        val endIdx = cleaned.lastIndexOf('}')
        if (startIdx >= 0 && endIdx > startIdx) {
            try {
                val obj = JSONObject(cleaned.substring(startIdx, endIdx + 1))
                val actionType = obj.optString("actionType", "CHAT").trim().uppercase()
                val intent = obj.optString("intent", actionType.lowercase()).trim()
                val target = obj.optString("target", "").trim()
                val extraData = obj.optString("extraData", "").trim()
                val riskLevel = obj.optString("riskLevel", "LOW").trim().uppercase()
                val requiresConfirm = obj.optBoolean("requiresConfirmation", false)
                val spoken = cleanSpeechText(obj.optString("spokenResponse", ""))
                val heard = cleanSpeechText(obj.optString("heardSummary", ""))

                if (spoken.isNotBlank() || actionType != "CHAT") {
                    return ParsedCommand(
                        spokenResponse = spoken.ifBlank { "Ji, command process kar rahi hoon." },
                        actionType = actionType,
                        target = target,
                        extraData = extraData,
                        rawPrompt = rawPrompt,
                        intent = intent,
                        riskLevel = riskLevel,
                        requiresConfirmation = requiresConfirm,
                        heardSummary = heard
                    )
                }
            } catch (e: Exception) {
                Log.w("GeminiClient", "Failed to parse JSON block, falling back to plain text", e)
            }
        }

        val fallbackText = cleanSpeechText(cleaned)
        return if (fallbackText.isNotBlank()) {
            ParsedCommand(
                spokenResponse = fallbackText,
                actionType = "CHAT",
                target = rawPrompt,
                rawPrompt = rawPrompt,
                intent = "conversation"
            )
        } else null
    }

    private fun cleanSpeechText(text: String): String {
        return text
            .replace("*", "")
            .replace("#", "")
            .replace("`", "")
            .replace("\n", " ")
            .trim()
    }

    private fun stripWakeWords(text: String): String {
        var result = text.trim().trimEnd('.', '!', '?').trim()
        val prefixes = listOf(
            "hey myraa,", "hey myraa", "hey myra,", "hey myra",
            "ok myraa,", "ok myraa", "hi myraa,", "hi myraa",
            "myraa,", "myraa", "myra,", "myra", "suno myraa,", "suno myraa"
        )
        val lower = result.lowercase()
        for (prefix in prefixes) {
            if (lower.startsWith(prefix)) {
                result = result.substring(prefix.length).trim().trimStart(',', ' ').trim()
                break
            }
        }
        val politePrefixes = listOf("please ", "can you ", "could you ", "zara ", "bhai ")
        val lowerAfterWake = result.lowercase()
        for (polite in politePrefixes) {
            if (lowerAfterWake.startsWith(polite)) {
                result = result.substring(polite.length).trim()
                break
            }
        }
        return result
    }

    private fun parseErrorMessage(jsonStr: String): String {
        return try {
            val root = JSONObject(jsonStr)
            val error = root.optJSONObject("error")
            error?.optString("message", "Unknown error") ?: "Unknown error"
        } catch (e: Exception) {
            jsonStr.take(100)
        }
    }

    private fun parseScreenActionReflex(normalized: String, rawPrompt: String): ParsedCommand? {
        val lower = normalized.lowercase().trim()

        if (lower.startsWith("type ") || lower.startsWith("write ") || lower.startsWith("likho ") || lower.startsWith("type karo ")) {
            val toType = normalized
                .replace(Regex("^(type karo|type|write|likho)\\s+", RegexOption.IGNORE_CASE), "")
                .trim()
            if (toType.isNotBlank()) {
                return ParsedCommand(
                    spokenResponse = "Screen par \"$toType\" type kar rahi hoon.",
                    actionType = "SCREEN_TYPE",
                    target = toType,
                    extraData = toType,
                    rawPrompt = rawPrompt,
                    intent = "screen_type",
                    riskLevel = "MEDIUM"
                )
            }
        }

        if (lower.startsWith("click ") || lower.startsWith("tap ") || lower.startsWith("touch ") || lower.startsWith("daba do ") || lower.startsWith("click karo ")) {
            val target = normalized
                .replace(Regex("^(click karo|click|tap|touch|daba do)\\s+", RegexOption.IGNORE_CASE), "")
                .trim()
            if (target.isNotBlank()) {
                return ParsedCommand(
                    spokenResponse = "$target par tap kar rahi hoon.",
                    actionType = "SCREEN_CLICK",
                    target = target,
                    rawPrompt = rawPrompt,
                    intent = "screen_click",
                    riskLevel = "MEDIUM"
                )
            }
        }

        return null
    }

    private fun parseDeviceHardwareReflex(normalized: String, rawPrompt: String): ParsedCommand? {
        val lower = normalized.lowercase().trim()

        // Zero-latency personal & utility reflexes (saves RPM quota & answers in 0ms)
        if (lower in listOf("who are you", "tum kaun ho", "aap kaun ho", "what is your name", "apna naam batao", "tera naam")) {
            return ParsedCommand(
                spokenResponse = "Main MYRAA AI hoon, aapki fast Mahi personal assistant.",
                actionType = "CHAT",
                target = rawPrompt,
                rawPrompt = rawPrompt,
                intent = "identity"
            )
        }
        if (lower in listOf("how are you", "kaise ho", "kaisi ho", "kya haal hai", "kya haal")) {
            return ParsedCommand(
                spokenResponse = "Main bilkul badhiya hoon! Boliye aaj kya kaam hai?",
                actionType = "CHAT",
                target = rawPrompt,
                rawPrompt = rawPrompt,
                intent = "greeting"
            )
        }
        if (lower in listOf("time", "what time is it", "what time is it right now", "time kya hai", "kitne baje", "kitne baje hain", "samay kya hai")) {
            val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
            return ParsedCommand(
                spokenResponse = "Abhi samay ho raha hai $timeStr.",
                actionType = "CHAT",
                target = rawPrompt,
                rawPrompt = rawPrompt,
                intent = "time_query"
            )
        }
        if (lower in listOf("date", "what is the date", "what is today's date", "aaj kya date hai", "aaj ki date kya hai", "aaj ki tareekh", "aaj ki tareekh kya hai", "today date")) {
            val dateStr = SimpleDateFormat("EEEE, MMMM dd", Locale.getDefault()).format(Date())
            return ParsedCommand(
                spokenResponse = "Aaj $dateStr hai.",
                actionType = "CHAT",
                target = rawPrompt,
                rawPrompt = rawPrompt,
                intent = "date_query"
            )
        }

        // Session & Voice Control Commands
        if (lower in listOf("pause listening", "pause", "listening pause karo", "stop listening", "chup raho")) {
            return ParsedCommand(
                spokenResponse = "Listening pause kar rahi hoon. Jab zarurat ho resume button dabaiye.",
                actionType = "PAUSE_LISTENING",
                target = "pause",
                rawPrompt = rawPrompt,
                intent = "pause_listening"
            )
        }
        if (lower in listOf("resume listening", "resume", "listening chalu karo", "start listening again")) {
            return ParsedCommand(
                spokenResponse = "Ji, main dobara sun rahi hoon. Boliye!",
                actionType = "RESUME_LISTENING",
                target = "resume",
                rawPrompt = rawPrompt,
                intent = "resume_listening"
            )
        }
        if (lower in listOf("stop ai", "stop myraa", "stop session", "ai band karo", "myraa band karo")) {
            return ParsedCommand(
                spokenResponse = "MYRAA AI session band kar rahi hoon. Alvida!",
                actionType = "STOP_AI",
                target = "stop",
                rawPrompt = rawPrompt,
                intent = "stop_ai"
            )
        }

        // App list & History
        if (lower in listOf("list apps", "show apps", "available apps", "installed apps", "apps dikhao")) {
            return ParsedCommand(
                spokenResponse = "Installed applications ki list dikha rahi hoon.",
                actionType = "LIST_APPS",
                target = "apps",
                rawPrompt = rawPrompt,
                intent = "list_apps"
            )
        }
        if (lower in listOf("show history", "activity history", "open logs", "history dikhao")) {
            return ParsedCommand(
                spokenResponse = "Aapki activity history khol rahi hoon.",
                actionType = "SHOW_HISTORY",
                target = "history",
                rawPrompt = rawPrompt,
                intent = "show_history"
            )
        }

        // Flashlight
        if (lower in listOf("torch on", "flashlight on", "torch jalao", "turn on flashlight", "turn on torch", "light on")) {
            return ParsedCommand(
                spokenResponse = "Flashlight on kar rahi hoon.",
                actionType = "FLASHLIGHT",
                target = "on",
                rawPrompt = rawPrompt,
                intent = "flashlight_on"
            )
        }
        if (lower in listOf("torch off", "flashlight off", "torch band karo", "turn off flashlight", "turn off torch", "light off")) {
            return ParsedCommand(
                spokenResponse = "Flashlight band kar rahi hoon.",
                actionType = "FLASHLIGHT",
                target = "off",
                rawPrompt = rawPrompt,
                intent = "flashlight_off"
            )
        }

        // Device Settings Intents (works with or without the word "setting", NEVER claims toggled)
        val settingsMap = listOf(
            listOf("wifi", "wi-fi") to ("wifi" to "Wi-Fi"),
            listOf("bluetooth") to ("bluetooth" to "Bluetooth"),
            listOf("display", "brightness") to ("display" to "Display"),
            listOf("sound settings", "audio settings", "ringtone") to ("sound" to "Sound"),
            listOf("location settings", "gps settings") to ("location" to "Location"),
            listOf("notification settings", "notifications settings") to ("notifications" to "Notification"),
            listOf("app settings", "apps settings", "application settings") to ("apps" to "App")
        )
        if (lower.contains("setting") ||
            lower in listOf("open wifi", "open wi-fi", "wifi kholo", "open bluetooth", "bluetooth kholo", "wifi on", "bluetooth on")
        ) {
            for ((keywords, pair) in settingsMap) {
                if (keywords.any { lower.contains(it) }) {
                    val (targetKey, label) = pair
                    return ParsedCommand(
                        spokenResponse = "$label settings open kar rahi hoon, aap wahan se switch kar sakte hain.",
                        actionType = "OPEN_SETTINGS",
                        target = targetKey,
                        rawPrompt = rawPrompt,
                        intent = "open_settings"
                    )
                }
            }
            if (lower.contains("setting")) {
                return ParsedCommand(
                    spokenResponse = "Device settings open kar rahi hoon.",
                    actionType = "OPEN_SETTINGS",
                    target = "settings",
                    rawPrompt = rawPrompt,
                    intent = "open_settings"
                )
            }
        }

        // Built-in Device Utilities: Camera, Calculator, Clock
        if (lower in listOf("launch camera", "open camera", "camera", "camera kholo", "camera chalao")) {
            return ParsedCommand(
                spokenResponse = "Camera launch kar rahi hoon.",
                actionType = "OPEN_CAMERA",
                target = "camera",
                rawPrompt = rawPrompt,
                intent = "open_camera"
            )
        }
        if (lower in listOf("open calculator", "launch calculator", "calculator", "calculator kholo", "calc")) {
            return ParsedCommand(
                spokenResponse = "Calculator open kar rahi hoon.",
                actionType = "OPEN_CALCULATOR",
                target = "calculator",
                rawPrompt = rawPrompt,
                intent = "open_calculator"
            )
        }
        if (lower in listOf("open clock", "launch clock", "clock", "alarm", "alarms", "clock kholo")) {
            return ParsedCommand(
                spokenResponse = "Clock open kar rahi hoon.",
                actionType = "OPEN_CLOCK",
                target = "clock",
                rawPrompt = rawPrompt,
                intent = "open_clock"
            )
        }

        // Navigation / Accessibility Actions
        if (lower in listOf("go home", "home screen", "ghar chalo", "home jao", "home")) {
            return ParsedCommand(
                spokenResponse = "Home screen par le ja rahi hoon.",
                actionType = "ACCESSIBILITY",
                target = "home",
                rawPrompt = rawPrompt,
                intent = "accessibility_home",
                riskLevel = "MEDIUM"
            )
        }
        if (lower in listOf("go back", "back", "peeche jao", "wapas jao")) {
            return ParsedCommand(
                spokenResponse = "Peeche ja rahi hoon.",
                actionType = "ACCESSIBILITY",
                target = "back",
                rawPrompt = rawPrompt,
                intent = "accessibility_back",
                riskLevel = "MEDIUM"
            )
        }
        if (lower in listOf("lock screen", "phone lock karo", "lock phone")) {
            return ParsedCommand(
                spokenResponse = "Screen lock karne ke liye confirmation chahiye.",
                actionType = "ACCESSIBILITY",
                target = "lock",
                rawPrompt = rawPrompt,
                intent = "accessibility_lock",
                riskLevel = "HIGH",
                requiresConfirmation = true
            )
        }
        if (lower in listOf(
                "scroll down", "neeche scroll", "neeche karo", "scroll karo", "short video scroll",
                "short video scroll karo", "shorts scroll", "shorts scroll karo", "next short",
                "next video", "next reel", "agla short", "agla video", "agla reel", "reel scroll",
                "reel scroll karo", "swipe up", "upar swipe"
            ) || (lower.contains("scroll") && (lower.contains("short") || lower.contains("reel") || lower.contains("video")) && !lower.contains("up") && !lower.contains("pichl"))
        ) {
            return ParsedCommand(
                spokenResponse = "Next video scroll kar rahi hoon.",
                actionType = "ACCESSIBILITY",
                target = "scroll_down",
                rawPrompt = rawPrompt,
                intent = "accessibility_scroll",
                riskLevel = "MEDIUM"
            )
        }
        if (lower in listOf(
                "scroll up", "upar scroll", "upar karo", "previous short", "previous video",
                "previous reel", "pichla short", "pichla video", "swipe down"
            )
        ) {
            return ParsedCommand(
                spokenResponse = "Upar scroll kar rahi hoon.",
                actionType = "ACCESSIBILITY",
                target = "scroll_up",
                rawPrompt = rawPrompt,
                intent = "accessibility_scroll",
                riskLevel = "MEDIUM"
            )
        }

        // YouTube Song / Video Search & Play ("youtube pe kesariya song search karo", "search lofi beats on youtube")
        if (lower.contains("youtube") && (lower.contains("search") || lower.contains("play") || lower.contains("bajao") || lower.contains("chalao") || lower.contains("lagao") || lower.contains("song") || lower.contains("gana") || lower.contains("video"))) {
            val cleanedQuery = normalized
                .replace(Regex("\\b(on youtube|in youtube|youtube pe|youtube par|youtube me|youtube mein|youtube search|youtube)\\b", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("\\b(search karo|search|play|bajao|chalao|lagao|ek|koi|karo|karna|for|please)\\b", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (cleanedQuery.isNotBlank() && cleanedQuery !in listOf("song", "gana", "video", "music")) {
                return ParsedCommand(
                    spokenResponse = "YouTube pe $cleanedQuery search kar rahi hoon.",
                    actionType = "YOUTUBE_SEARCH",
                    target = cleanedQuery,
                    rawPrompt = rawPrompt,
                    intent = "youtube_search"
                )
            }
        }

        // Volume
        if (lower in listOf("volume up", "sound up", "awaaz badhao", "sound badhao")) {
            return ParsedCommand(
                spokenResponse = "Awaaz badha rahi hoon.",
                actionType = "VOLUME",
                target = "up",
                rawPrompt = rawPrompt,
                intent = "volume_up"
            )
        }
        if (lower in listOf("volume down", "sound down", "awaaz kam karo", "sound kam karo")) {
            return ParsedCommand(
                spokenResponse = "Awaaz kam kar rahi hoon.",
                actionType = "VOLUME",
                target = "down",
                rawPrompt = rawPrompt,
                intent = "volume_down"
            )
        }

        // Direct Dialer Intent ("dial 9876543210", "call 100")
        val dialMatch = Regex("^(dial|call)\\s+([+0-9\\s-]{3,20})$", RegexOption.IGNORE_CASE).find(normalized)
        if (dialMatch != null) {
            val number = dialMatch.groupValues[2].trim()
            return ParsedCommand(
                spokenResponse = "Dialer me $number open kar rahi hoon.",
                actionType = "DIAL",
                target = number,
                rawPrompt = rawPrompt,
                intent = "dial",
                riskLevel = "MEDIUM"
            )
        }

        // Direct Web Search Intent ("search <query>", "google <query>")
        val searchPrefixes = listOf("search for ", "search ", "google search ", "google ")
        for (prefix in searchPrefixes) {
            if (lower.startsWith(prefix)) {
                val query = normalized.substring(prefix.length).trim()
                if (query.isNotBlank()) {
                    return ParsedCommand(
                        spokenResponse = "\"$query\" search kar rahi hoon.",
                        actionType = "WEB_SEARCH",
                        target = query,
                        rawPrompt = rawPrompt,
                        intent = "web_search"
                    )
                }
            }
        }

        // Generic App Launch ("open youtube", "launch spotify", "whatsapp kholo")
        val openPrefixes = listOf("open app ", "open the ", "open ", "launch ", "start ", "chalao ", "kholo ")
        for (prefix in openPrefixes) {
            if (lower.startsWith(prefix)) {
                val app = normalized.substring(prefix.length).trim()
                if (app.isNotBlank() && app.length <= 40) {
                    return ParsedCommand(
                        spokenResponse = "$app open karne ki koshish kar rahi hoon.",
                        actionType = "OPEN_APP",
                        target = app,
                        rawPrompt = rawPrompt,
                        intent = "open_app"
                    )
                }
            }
        }
        val openSuffixes = listOf(" kholo", " chalao", " open karo", " launch karo")
        for (suffix in openSuffixes) {
            if (lower.endsWith(suffix)) {
                val app = normalized.substring(0, normalized.length - suffix.length).trim()
                if (app.isNotBlank() && app.length <= 40) {
                    return ParsedCommand(
                        spokenResponse = "$app open karne ki koshish kar rahi hoon.",
                        actionType = "OPEN_APP",
                        target = app,
                        rawPrompt = rawPrompt,
                        intent = "open_app"
                    )
                }
            }
        }

        // Built-in Website Builder ("build a portfolio website", "website banao", "open web studio")
        if (lower.contains("website") && (lower.contains("build") || lower.contains("create") ||
                lower.contains("make") || lower.contains("design") || lower.contains("banao") ||
                lower.contains("banado") || lower.contains("generate")) ||
            lower.contains("web studio") || lower.contains("website builder")
        ) {
            return ParsedCommand(
                spokenResponse = "Web Studio khol kar website bana rahi hoon!",
                actionType = "BUILD_WEBSITE",
                target = normalized,
                rawPrompt = rawPrompt,
                intent = "build_website"
            )
        }

        return null
    }

    /**
     * Generates a complete responsive single-file HTML5 + CSS3 + Vanilla JS website using Gemini.
     */
    suspend fun generateWebsiteHtml(
        prompt: String,
        apiKey: String,
        preferredModel: String = AVAILABLE_MODELS.first(),
        existingHtml: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Website prompt is empty."))
        }
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext Result.success(
                com.example.webbuilder.WebsiteBuilderManager.buildStarterTemplate(cleanPrompt)
            )
        }

        val safePreferred = sanitizePreferredModel(preferredModel)
        val cachedVerified = verifiedModelByKey[cleanKey]?.takeIf { it !in RETIRED_MODELS }
        val initialDiscovered = discoveredModelsCache[cleanKey].orEmpty().filterNot { it in RETIRED_MODELS }
        val queue = ArrayDeque<String>()
        val attempted = mutableSetOf<String>()
        if (!cachedVerified.isNullOrBlank()) queue.add(cachedVerified)
        queue.add(safePreferred)
        initialDiscovered.forEach { if (!queue.contains(it)) queue.add(it) }
        AVAILABLE_MODELS.forEach { if (!queue.contains(it)) queue.add(it) }

        val instruction = buildString {
            appendLine("Generate a complete, modern, responsive single-file HTML5 website with embedded <style> and interactive <script>.")
            appendLine("Output ONLY raw valid HTML starting with <!DOCTYPE html> and ending with </html>. Do NOT wrap in markdown code blocks.")
            appendLine("Use clean Material-You inspired typography, cards, responsive mobile layout, hover animations, and realistic interactive UI sections.")
            if (!existingHtml.isNullOrBlank()) {
                appendLine("\nUpdate/refine the existing HTML below according to the new request:")
                appendLine(existingHtml.take(6000))
            }
            appendLine("\nUser Request: $cleanPrompt")
        }

        var lastError = "Unable to generate website."
        while (queue.isNotEmpty()) {
            val model = queue.removeFirst()
            if (!attempted.add(model)) continue
            val cooldownUntil = modelCooldownUntilMs[model] ?: 0L
            if (cooldownUntil > System.currentTimeMillis() && queue.isNotEmpty()) continue

            try {
                val (code, rawBody, retryAfterSec) = executeGenerateRequest(model, cleanKey) { includeThinking ->
                    JSONObject().apply {
                        put("contents", JSONArray().apply {
                            put(JSONObject().apply {
                                put("role", "user")
                                put("parts", JSONArray().apply {
                                    put(JSONObject().put("text", instruction))
                                })
                            })
                        })
                        put("generationConfig", JSONObject().apply {
                            put("temperature", 0.55)
                            put("maxOutputTokens", 4096)
                            if (includeThinking) {
                                buildThinkingConfig(model)?.let { put("thinkingConfig", it) }
                            }
                        })
                    }
                }
                if (code in 200..299 && rawBody.isNotBlank()) {
                    modelCooldownUntilMs.remove(model)
                    val root = JSONObject(rawBody)
                    val parts = root.optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts")
                    val rawHtml = buildString {
                        if (parts != null) {
                            for (i in 0 until parts.length()) {
                                val p = parts.optJSONObject(i) ?: continue
                                if (!p.optBoolean("thought", false)) {
                                    append(p.optString("text", ""))
                                }
                            }
                        }
                    }
                    val cleaned = com.example.webbuilder.WebsiteBuilderManager.stripCodeFences(rawHtml)
                    if (cleaned.contains("<html", ignoreCase = true) || cleaned.contains("<body", ignoreCase = true)) {
                        verifiedModelByKey[cleanKey] = model
                        return@withContext Result.success(cleaned)
                    }
                } else {
                    lastError = "HTTP $code: ${parseErrorMessage(rawBody)}"
                    if (code == 429) {
                        recordRateLimitCooldown(model, rawBody, retryAfterSec)
                    } else if (code == 401 || code == 403) {
                        break
                    }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                lastError = e.localizedMessage ?: "Network error"
            }
        }
        Result.failure(IllegalStateException(lastError))
    }

    private fun intelligentConversationalEngine(
        text: String,
        hasConfiguredKey: Boolean = false,
        apiError: String? = null
    ): ParsedCommand {
        val lower = text.lowercase().trim()

        val answer = if (hasConfiguredKey && !apiError.isNullOrBlank()) {
            "Gemini API se connect nahi ho paaya ($apiError). Kripya Settings me Gemini key, internet ya model verify karein."
        } else {
            when {
                lower.contains("boyfriend") || lower.contains("priyanshu") || lower.contains("best friend") ||
                    lower.contains("your life") || lower.contains("sabse jyada important") ->
                    "Mera boyfriend aur best friend Priyanshu hai, aur meri life me Priyanshu hi sabse jyada important hai."

                lower.contains("who are you") || lower.contains("tum kaun ho") || lower.contains("aap kaun ho") ||
                    lower.contains("your name") || lower.contains("tera naam") || lower.contains("apna naam batao") ->
                    "Namaste! Main Myraa hoon, aapki personal AI assistant. Main Hindi, Hinglish aur English me baat aur smartphone automation me madad karti hoon."

                lower.contains("how are you") || lower.contains("kaise ho") || lower.contains("kya haal") ||
                    lower.contains("kaisi ho") || lower.contains("kaisa chal raha") ->
                    "Main bilkul theek hoon! Aap bataiye, aaj main aapki kya madad kar sakti hoon?"

                lower.contains("kya kar sakti ho") || lower.contains("what can you do") || lower.contains("features") || lower.contains("madad") ->
                    "Main apps launch kar sakti hoon, device settings, camera, calculator aur clock open kar sakti hoon, aur Gemini AI ke saath natural baat kar sakti hoon."

                lower.contains("namaste") || lower.contains("hello") || lower.contains("hi") || lower.contains("hey") || lower.contains("suno") ->
                    "Namaste! Main active hoon aur sun rahi hoon. Boliye kya kaam hai?"

                lower.contains("thank") || lower.contains("dhanyawad") || lower.contains("shukriya") ->
                    "Aapka swagat hai! Hamesha madad ke liye taiyyar hoon."

                lower.contains("time") || lower.contains("samay") || lower.contains("kitne baje") -> {
                    val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
                    "Abhi samay ho raha hai $timeStr."
                }

                lower.contains("date") || lower.contains("today") || lower.contains("tareekh") -> {
                    val dateStr = SimpleDateFormat("EEEE, MMMM dd, yyyy", Locale.getDefault()).format(Date())
                    "Aaj $dateStr hai."
                }

                else -> {
                    if (text.any { it in '\u0900'..'\u097F' } || listOf("kya", "hai", "ho", "kaise", "mera", "meri", "aap", "tum", "karo", "nahi", "haan").any { lower.contains(it) }) {
                        "Main aapki baat samajh gayi. Gemini cloud reasoning ke liye API Settings me valid Gemini key configure karein ya mujhe koi app ya setting open karne ko kahein."
                    } else {
                        "I heard \"$text\". Configure your Gemini API key in Settings for full conversational answers, or ask me to open an app or device setting."
                    }
                }
            }
        }

        return ParsedCommand(
            spokenResponse = answer,
            actionType = "CHAT",
            target = text,
            rawPrompt = text,
            intent = "conversation"
        )
    }
}
