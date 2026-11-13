package com.example.data.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class ParsedCommand(
    val spokenResponse: String,
    val actionType: String, // Allowlisted action e.g. OPEN_APP, OPEN_SETTINGS, OPEN_CAMERA, OPEN_CALCULATOR, OPEN_CLOCK, CHAT
    val target: String,
    val extraData: String = "",
    val rawPrompt: String,
    val intent: String = "conversation",
    val riskLevel: String = "LOW",
    val requiresConfirmation: Boolean = false
)

sealed class ConnectionTestResult {
    data class Success(val message: String, val modelUsed: String) : ConnectionTestResult()
    data class Failure(val errorMessage: String, val errorCode: Int? = null) : ConnectionTestResult()
}

class GeminiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    companion object {
        val AVAILABLE_MODELS = listOf(
            "gemini-flash-latest",
            "gemini-3.8-flash",
            "gemini-2.5-flash-lite",
            "gemini-pro-latest",
            "gemini-3.5-flash"
        )
    }

    suspend fun testConnection(apiKey: String, selectedModel: String): ConnectionTestResult = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext ConnectionTestResult.Failure("API key is empty. Please enter your Gemini API key.")
        }

        val targetModel = if (selectedModel.isNotBlank()) selectedModel.trim() else "gemini-flash-latest"
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$targetModel:generateContent"

        try {
            val testPayload = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", "Reply with only OK."))
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("maxOutputTokens", 10)
                })
            }

            val body = testPayload.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", cleanKey)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            val (code, errBody, isOk) = com.example.core.NetworkRetryHelper.withExponentialBackoff(
                maxAttempts = 2
            ) {
                client.newCall(request).execute().use { response ->
                    val responseBodyStr = response.body?.string() ?: ""
                    Triple(response.code, responseBodyStr, response.isSuccessful)
                }
            }

            if (isOk) {
                return@withContext ConnectionTestResult.Success(
                    message = "Connection successful with $targetModel!",
                    modelUsed = targetModel
                )
            } else {
                val humanMsg = when (code) {
                    400 -> "API key is invalid or unauthorized."
                    403 -> "API key does not have permissions or quota exceeded."
                    404 -> "Model $targetModel is not available or deprecated."
                    429 -> "Rate limit exceeded. Please wait a moment."
                    else -> "Connection failed (HTTP $code): ${parseErrorMessage(errBody)}"
                }
                return@withContext ConnectionTestResult.Failure(humanMsg, code)
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.e("GeminiClient", "Test connection exception", e)
            return@withContext ConnectionTestResult.Failure("Gemini connection failed: ${e.localizedMessage ?: "Network error"}")
        }
    }

    suspend fun interpretCommand(
        prompt: String,
        apiKey: String,
        preferredModel: String = "gemini-flash-latest"
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

        // 3. Structured JSON Intelligence via Gemini with Mahi Persona + Controlled Exponential Backoff
        val cleanKey = apiKey.trim()
        if (cleanKey.isNotBlank()) {
            val candidateModels = listOf(preferredModel) + AVAILABLE_MODELS.filter { it != preferredModel }

            for (model in candidateModels.distinct()) {
                try {
                    val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"

                    val systemPrompt = """
                        You are MYRAA AI, a warm, respectful Indian AI smartphone assistant speaking in Mahi's voice.
                        Respond ONLY with a valid single JSON object (no markdown fences, no prose outside JSON) using this exact schema:
                        {
                          "intent": "open_app|open_settings|open_camera|open_calculator|open_clock|flashlight|volume|accessibility|screen_click|screen_type|web_search|pause_listening|resume_listening|stop_ai|list_apps|show_history|chat",
                          "actionType": "OPEN_APP|OPEN_SETTINGS|OPEN_CAMERA|OPEN_CALCULATOR|OPEN_CLOCK|FLASHLIGHT|VOLUME|ACCESSIBILITY|SCREEN_CLICK|SCREEN_TYPE|WEB_SEARCH|PAUSE_LISTENING|RESUME_LISTENING|STOP_AI|LIST_APPS|SHOW_HISTORY|CHAT",
                          "target": "target app, setting (wifi|bluetooth|display|sound|location|app|notifications|settings), or parameter",
                          "extraData": "optional text to type or extra info",
                          "riskLevel": "LOW|MEDIUM|HIGH",
                          "requiresConfirmation": false,
                          "spokenResponse": "Natural 1-2 sentence reply in Hindi, Hinglish, or English matching user language, max 30 words, no markdown or emojis"
                        }
                        STRICT RULES:
                        - Never output executable code or shell commands.
                        - For OPEN_SETTINGS, NEVER claim the setting was toggled ON/OFF; say the settings screen is being opened so the user can change it.
                        - Set requiresConfirmation=true and riskLevel="HIGH" for irreversible or sensitive actions (like locking screen, deleting, uninstalling, sending/paying).
                    """.trimIndent()

                    val requestJson = JSONObject().apply {
                        put("contents", JSONArray().apply {
                            put(JSONObject().apply {
                                put("role", "user")
                                put("parts", JSONArray().apply {
                                    put(JSONObject().put("text", "$systemPrompt\n\nUser command: $trimmed"))
                                })
                            })
                        })
                        put("generationConfig", JSONObject().apply {
                            put("temperature", 0.35)
                            put("maxOutputTokens", 180)
                            put("responseMimeType", "application/json")
                        })
                    }

                    val body = requestJson.toString().toRequestBody("application/json".toMediaType())
                    val request = Request.Builder()
                        .url(url)
                        .addHeader("x-goog-api-key", cleanKey)
                        .addHeader("Content-Type", "application/json")
                        .post(body)
                        .build()

                    val (code, rawBody, isOk) = com.example.core.NetworkRetryHelper.withExponentialBackoff(
                        maxAttempts = 2
                    ) {
                        client.newCall(request).execute().use { response ->
                            Triple(response.code, response.body?.string() ?: "", response.isSuccessful)
                        }
                    }

                    if (isOk && rawBody.isNotBlank()) {
                        val root = JSONObject(rawBody)
                        val candidates = root.optJSONArray("candidates")
                        if (candidates != null && candidates.length() > 0) {
                            val contentObj = candidates.getJSONObject(0).optJSONObject("content")
                            val parts = contentObj?.optJSONArray("parts")
                            if (parts != null && parts.length() > 0) {
                                val rawText = parts.getJSONObject(0).optString("text").trim()
                                val structured = extractStructuredJson(rawText, trimmed)
                                if (structured != null) {
                                    Log.i("GeminiClient", "Structured JSON parsed via $model -> ${structured.actionType}")
                                    return@withContext structured
                                }
                            }
                        }
                    } else {
                        Log.w("GeminiClient", "Gemini $model HTTP $code: ${parseErrorMessage(rawBody)}")
                    }
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    Log.w("GeminiClient", "Error querying Gemini $model", e)
                }
            }
        }

        // 4. Fallback bilingual conversation engine (Mahi persona)
        return@withContext intelligentConversationalEngine(trimmed)
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

                if (spoken.isNotBlank() || actionType != "CHAT") {
                    return ParsedCommand(
                        spokenResponse = spoken.ifBlank { "Ji, command process kar rahi hoon." },
                        actionType = actionType,
                        target = target,
                        extraData = extraData,
                        rawPrompt = rawPrompt,
                        intent = intent,
                        riskLevel = riskLevel,
                        requiresConfirmation = requiresConfirm
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

        // Device Settings Intents (NEVER claim setting changed, only opened settings screen)
        val settingsMap = listOf(
            listOf("wifi setting", "wi-fi setting", "wifi", "wi-fi") to ("wifi" to "Wi-Fi"),
            listOf("bluetooth setting", "bluetooth") to ("bluetooth" to "Bluetooth"),
            listOf("display setting", "brightness setting", "screen setting", "display") to ("display" to "Display"),
            listOf("sound setting", "audio setting", "ringtone setting", "sound") to ("sound" to "Sound"),
            listOf("location setting", "gps setting", "location") to ("location" to "Location"),
            listOf("notification setting", "notifications setting", "notification") to ("notifications" to "Notification"),
            listOf("app setting", "apps setting", "application setting") to ("apps" to "App")
        )
        if (lower.contains("setting")) {
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
            return ParsedCommand(
                spokenResponse = "Device settings open kar rahi hoon.",
                actionType = "OPEN_SETTINGS",
                target = "settings",
                rawPrompt = rawPrompt,
                intent = "open_settings"
            )
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
        if (lower in listOf("scroll down", "neeche scroll", "neeche karo")) {
            return ParsedCommand(
                spokenResponse = "Screen neeche scroll kar rahi hoon.",
                actionType = "ACCESSIBILITY",
                target = "scroll_down",
                rawPrompt = rawPrompt,
                intent = "accessibility_scroll",
                riskLevel = "MEDIUM"
            )
        }
        if (lower in listOf("scroll up", "upar scroll", "upar karo")) {
            return ParsedCommand(
                spokenResponse = "Screen upar scroll kar rahi hoon.",
                actionType = "ACCESSIBILITY",
                target = "scroll_up",
                rawPrompt = rawPrompt,
                intent = "accessibility_scroll",
                riskLevel = "MEDIUM"
            )
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

        // Generic App Launch ("open youtube", "launch spotify", "whatsapp kholo")
        val openPrefixes = listOf("open app ", "open ", "launch ", "start ", "chalao ", "kholo ")
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

        return null
    }

    private fun intelligentConversationalEngine(text: String): ParsedCommand {
        val lower = text.lowercase().trim()

        val answer = when {
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

        return ParsedCommand(
            spokenResponse = answer,
            actionType = "CHAT",
            target = text,
            rawPrompt = text,
            intent = "conversation"
        )
    }
}
