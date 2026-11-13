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
    val actionType: String, // "SCREEN_CLICK", "SCREEN_TYPE", "OPEN_APP", "FLASHLIGHT", "VOLUME", "ACCESSIBILITY", "CHAT"
    val target: String,
    val extraData: String = "",
    val rawPrompt: String
)

sealed class ConnectionTestResult {
    data class Success(val message: String, val modelUsed: String) : ConnectionTestResult()
    data class Failure(val errorMessage: String, val errorCode: Int? = null) : ConnectionTestResult()
}

class GeminiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS)
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
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$targetModel:generateContent?key=$cleanKey"

        try {
            val testPayload = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", "Hello, reply with only the word OK."))
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

            val response = client.newCall(request).execute()
            if (response.isSuccessful && response.body != null) {
                return@withContext ConnectionTestResult.Success(
                    message = "Connection successful with $targetModel!",
                    modelUsed = targetModel
                )
            } else {
                val code = response.code
                val errBody = response.body?.string() ?: ""
                val humanMsg = when (code) {
                    400 -> "API key is invalid or unauthorized."
                    403 -> "API key does not have permissions or quota exceeded."
                    404 -> "Model $targetModel is not available or deprecated."
                    429 -> "Rate limit exceeded. Please wait a moment."
                    else -> "Connection failed (HTTP $code): ${parseErrorMessage(errBody)}"
                }
                return@withContext ConnectionTestResult.Failure(humanMsg, code)
            }
        } catch (e: Exception) {
            Log.e("GeminiClient", "Test connection exception", e)
            return@withContext ConnectionTestResult.Failure("Network error: ${e.localizedMessage ?: "Failed to reach Google servers"}")
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
                rawPrompt = prompt
            )
        }

        // 1. Direct device actions reflex (flashlight, volume, navigation, apps)
        val deviceAction = parseDeviceHardwareReflex(trimmed)
        if (deviceAction != null) {
            return@withContext deviceAction
        }

        // 2. Direct screen actions reflex (digital mouse clicks, virtual typing)
        val screenAction = parseScreenActionReflex(trimmed)
        if (screenAction != null) {
            return@withContext screenAction
        }

        // 3. True Conversational AI via Gemini with Mahi Persona
        val cleanKey = apiKey.trim()
        if (cleanKey.isNotBlank()) {
            val candidateModels = listOf(preferredModel) + AVAILABLE_MODELS.filter { it != preferredModel }

            for (model in candidateModels.distinct()) {
                try {
                    val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$cleanKey"

                    val systemPrompt = """
                        You are Myraa, an intelligent, respectful, friendly, and calm personal AI assistant speaking with the warm voice of Mahi.
                        You have a natural Indian conversational style.
                        IMPORTANT PERSONALITY & LANGUAGE INSTRUCTIONS:
                        - If the user speaks in Hindi or Hinglish, reply warmly and naturally in conversational Hindi/Hinglish (e.g., 'Ji, main bilkul theek hoon! Aap bataiye aaj main aapki kya madad kar sakti hoon?').
                        - If the user speaks in English, reply in friendly, pleasant, and natural Indian English.
                        - Be helpful, respectful, and calm. Address the user politely.
                        - Keep responses concise (1 to 2 short sentences, under 30 words) so they sound natural and delightful when spoken through Mahi's voice.
                        - Do NOT include markdown asterisks, hashtags, bullet points, or emojis.
                    """.trimIndent()

                    val requestJson = JSONObject().apply {
                        put("contents", JSONArray().apply {
                            put(JSONObject().apply {
                                put("role", "user")
                                put("parts", JSONArray().apply {
                                    put(JSONObject().put("text", "$systemPrompt\n\nUser: $trimmed"))
                                })
                            })
                        })
                        put("generationConfig", JSONObject().apply {
                            put("temperature", 0.7)
                            put("maxOutputTokens", 80)
                        })
                    }

                    val body = requestJson.toString().toRequestBody("application/json".toMediaType())
                    val request = Request.Builder()
                        .url(url)
                        .addHeader("x-goog-api-key", cleanKey)
                        .addHeader("Content-Type", "application/json")
                        .post(body)
                        .build()

                    val response = client.newCall(request).execute()

                    if (response.isSuccessful && response.body != null) {
                        val rawBody = response.body!!.string()
                        val root = JSONObject(rawBody)
                        val candidates = root.optJSONArray("candidates")
                        if (candidates != null && candidates.length() > 0) {
                            val contentObj = candidates.getJSONObject(0).optJSONObject("content")
                            val parts = contentObj?.optJSONArray("parts")
                            if (parts != null && parts.length() > 0) {
                                val replyText = parts.getJSONObject(0).optString("text")
                                    .replace("*", "")
                                    .replace("#", "")
                                    .replace("`", "")
                                    .replace("\n", " ")
                                    .trim()

                                if (replyText.isNotBlank()) {
                                    Log.i("GeminiClient", "Gemini answered via $model: $replyText")
                                    return@withContext ParsedCommand(
                                        spokenResponse = replyText,
                                        actionType = "CHAT",
                                        target = trimmed,
                                        rawPrompt = trimmed
                                    )
                                }
                            }
                        }
                    } else {
                        val err = response.body?.string() ?: ""
                        Log.w("GeminiClient", "Gemini $model HTTP ${response.code}: $err")
                    }
                } catch (e: Exception) {
                    Log.w("GeminiClient", "Error querying Gemini $model", e)
                }
            }
        }

        // 4. Fallback to extensive intelligent bilingual conversation engine (Mahi persona)
        return@withContext intelligentConversationalEngine(trimmed)
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

    private fun parseScreenActionReflex(text: String): ParsedCommand? {
        val lower = text.lowercase().trim()

        if (lower.startsWith("type ") || lower.startsWith("write ") || lower.startsWith("likho ") || lower.startsWith("type karo ")) {
            val toType = lower.removePrefix("type karo ").removePrefix("type ").removePrefix("write ").removePrefix("likho ").trim()
            return ParsedCommand(
                spokenResponse = "Screen par \"$toType\" type kar rahi hoon.",
                actionType = "SCREEN_TYPE",
                target = toType,
                extraData = toType,
                rawPrompt = text
            )
        }

        if (lower.startsWith("click ") || lower.startsWith("tap ") || lower.startsWith("touch ") || lower.startsWith("daba do ") || lower.startsWith("click karo ")) {
            val target = lower.removePrefix("click karo ").removePrefix("click ").removePrefix("tap ").removePrefix("touch ").removePrefix("daba do ").trim()
            return ParsedCommand(
                spokenResponse = "$target par click kar rahi hoon.",
                actionType = "SCREEN_CLICK",
                target = target,
                rawPrompt = text
            )
        }

        return null
    }

    private fun parseDeviceHardwareReflex(text: String): ParsedCommand? {
        val lower = text.lowercase().trim()

        // Flashlight
        if (lower == "torch on" || lower == "flashlight on" || lower == "torch jalao" || lower == "turn on flashlight" || lower == "turn on torch" || lower == "light on") {
            return ParsedCommand(
                spokenResponse = "Ji, flashlight on kar di hai.",
                actionType = "FLASHLIGHT",
                target = "on",
                rawPrompt = text
            )
        }
        if (lower == "torch off" || lower == "flashlight off" || lower == "torch band karo" || lower == "turn off flashlight" || lower == "turn off torch" || lower == "light off") {
            return ParsedCommand(
                spokenResponse = "Flashlight band kar di hai.",
                actionType = "FLASHLIGHT",
                target = "off",
                rawPrompt = text
            )
        }

        // Navigation
        if (lower == "go home" || lower == "home screen" || lower == "ghar chalo" || lower == "home jao" || lower == "home") {
            return ParsedCommand(
                spokenResponse = "Home screen par le ja rahi hoon.",
                actionType = "ACCESSIBILITY",
                target = "home",
                rawPrompt = text
            )
        }
        if (lower == "go back" || lower == "back" || lower == "peeche jao" || lower == "wapas jao") {
            return ParsedCommand(
                spokenResponse = "Peeche ja rahi hoon.",
                actionType = "ACCESSIBILITY",
                target = "back",
                rawPrompt = text
            )
        }
        if (lower == "scroll down" || lower == "neeche scroll" || lower == "neeche karo") {
            return ParsedCommand(
                spokenResponse = "Screen neeche scroll kar rahi hoon.",
                actionType = "ACCESSIBILITY",
                target = "scroll_down",
                rawPrompt = text
            )
        }
        if (lower == "scroll up" || lower == "upar scroll" || lower == "upar karo") {
            return ParsedCommand(
                spokenResponse = "Screen upar scroll kar rahi hoon.",
                actionType = "ACCESSIBILITY",
                target = "scroll_up",
                rawPrompt = text
            )
        }

        // Volume
        if (lower == "volume up" || lower == "sound up" || lower == "awaaz badhao" || lower == "sound badhao") {
            return ParsedCommand(
                spokenResponse = "Awaaz badha di hai.",
                actionType = "VOLUME",
                target = "up",
                rawPrompt = text
            )
        }
        if (lower == "volume down" || lower == "sound down" || lower == "awaaz kam karo" || lower == "sound kam karo") {
            return ParsedCommand(
                spokenResponse = "Awaaz kam kar di hai.",
                actionType = "VOLUME",
                target = "down",
                rawPrompt = text
            )
        }

        // Explicit App Launch
        val appPrefixes = listOf("open app ", "open ", "launch ", "chalao ", "kholo ")
        for (prefix in appPrefixes) {
            if (lower.startsWith(prefix) && lower.length < 28) {
                val app = lower.removePrefix(prefix).trim()
                val known = listOf("youtube", "whatsapp", "camera", "chrome", "maps", "spotify", "settings", "calculator", "clock", "gallery", "gmail", "instagram")
                if (known.contains(app) || lower.startsWith("open app ")) {
                    return ParsedCommand(
                        spokenResponse = "$app open kar rahi hoon.",
                        actionType = "OPEN_APP",
                        target = app,
                        rawPrompt = text
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
                "Namaste! Main Myraa hoon, aapki personal AI assistant. Main aapse baat karne aur aapka phone control karne ke liye taiyyar hoon."

            lower.contains("how are you") || lower.contains("kaise ho") || lower.contains("kya haal") ||
            lower.contains("kaisi ho") || lower.contains("kaisa chal raha") ->
                "Main bilkul theek aur khush hoon! Aapke saath baat karke bahut achha laga. Aap bataiye, aap kaise hain?"

            lower.contains("kya kar sakti ho") || lower.contains("what can you do") || lower.contains("features") || lower.contains("madad") ->
                "Main aapse natural Hindi, Hinglish aur English me baat kar sakti hoon, apps launch kar sakti hoon, torch aur volume control kar sakti hoon, aur aapki screen share me madad kar sakti hoon."

            lower.contains("namaste") || lower.contains("hello") || lower.contains("hi") || lower.contains("hey") || lower.contains("suno") ->
                "Namaste! Main active hoon aur sun rahi hoon. Boliye main aapki kya seva kar sakti hoon?"

            lower.contains("joke") || lower.contains("chutkula") || lower.contains("hasao") ->
                "Teacher ne pucha: Phone aur kitaab me kya farak hai? Bachha bola: Kitaab me sochte hain kab band karein, aur phone me sochte hain thoda aur chala lein! Kaisa laga?"

            lower.contains("thank") || lower.contains("dhanyawad") || lower.contains("shukriya") ->
                "Aapka swagat hai! Aapki madad karna mere liye khushi ki baat hai."

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
                    "Ji, maine suna aapne kaha \"$text\". Main sun rahi hoon, bataiye aur kya madad chahiye?"
                } else {
                    "I heard you say \"$text\". I am right here listening, ready to talk or assist with your phone."
                }
            }
        }

        return ParsedCommand(
            spokenResponse = answer,
            actionType = "CHAT",
            target = text,
            rawPrompt = text
        )
    }
}
