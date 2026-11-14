package com.example.webbuilder

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class GeneratedWebsiteProject(
    val id: String,
    val title: String,
    val prompt: String,
    val htmlContent: String,
    val createdAtMs: Long
)

/**
 * Persists user-created HTML/CSS/JS websites generated inside MYRAA AI's built-in Web Studio
 * using app-private storage (zero broad storage permissions needed).
 */
class WebsiteBuilderManager(private val context: Context) {

    private val projectsDir: File by lazy {
        File(context.applicationContext.filesDir, "myraa_websites").apply { mkdirs() }
    }
    private val indexFile: File by lazy { File(projectsDir, "projects_index.json") }

    suspend fun getSavedProjects(): List<GeneratedWebsiteProject> = withContext(Dispatchers.IO) {
        if (!indexFile.exists()) return@withContext emptyList()
        return@withContext try {
            val arr = JSONArray(indexFile.readText(Charsets.UTF_8))
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "")
                    val htmlFile = File(projectsDir, "$id.html")
                    val html = if (htmlFile.exists()) htmlFile.readText(Charsets.UTF_8) else ""
                    if (id.isNotBlank() && html.isNotBlank()) {
                        add(
                            GeneratedWebsiteProject(
                                id = id,
                                title = obj.optString("title", "Custom Website"),
                                prompt = obj.optString("prompt", ""),
                                htmlContent = html,
                                createdAtMs = obj.optLong("createdAtMs", 0L)
                            )
                        )
                    }
                }
            }.sortedByDescending { it.createdAtMs }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun saveProject(title: String, prompt: String, htmlContent: String): GeneratedWebsiteProject =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString().take(12)
            val created = System.currentTimeMillis()
            val cleanTitle = title.trim().ifBlank { deriveTitleFromPrompt(prompt) }
            File(projectsDir, "$id.html").writeText(htmlContent, Charsets.UTF_8)

            val existing = getSavedProjects().filterNot { it.id == id }
            val updated = listOf(
                GeneratedWebsiteProject(
                    id = id,
                    title = cleanTitle,
                    prompt = prompt,
                    htmlContent = htmlContent,
                    createdAtMs = created
                )
            ) + existing.take(19)

            val arr = JSONArray()
            updated.forEach { p ->
                arr.put(JSONObject().apply {
                    put("id", p.id)
                    put("title", p.title)
                    put("prompt", p.prompt)
                    put("createdAtMs", p.createdAtMs)
                })
            }
            indexFile.writeText(arr.toString(), Charsets.UTF_8)
            updated.first()
        }

    suspend fun deleteProject(id: String) = withContext(Dispatchers.IO) {
        try { File(projectsDir, "$id.html").delete() } catch (_: Exception) {}
        val remaining = getSavedProjects().filterNot { it.id == id }
        val arr = JSONArray()
        remaining.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("title", p.title)
                put("prompt", p.prompt)
                put("createdAtMs", p.createdAtMs)
            })
        }
        indexFile.writeText(arr.toString(), Charsets.UTF_8)
    }

    companion object {
        fun deriveTitleFromPrompt(prompt: String): String {
            val clean = prompt.trim()
                .replace(Regex("^(build|create|make|design|generate|banao)\\s+(a|an|ek)?\\s*", RegexOption.IGNORE_CASE), "")
                .trim()
            return clean.take(36).replaceFirstChar { it.uppercase() }.ifBlank { "MYRAA AI Site" }
        }

        fun stripCodeFences(raw: String): String {
            var s = raw.trim()
            if (s.startsWith("```html", ignoreCase = true)) {
                s = s.removePrefix("```html").removePrefix("```HTML")
            } else if (s.startsWith("```")) {
                s = s.removePrefix("```")
            }
            if (s.endsWith("```")) {
                s = s.removeSuffix("```")
            }
            val docStart = s.indexOf("<!DOCTYPE", ignoreCase = true)
            val htmlStart = s.indexOf("<html", ignoreCase = true)
            val start = when {
                docStart >= 0 -> docStart
                htmlStart >= 0 -> htmlStart
                else -> 0
            }
            return s.substring(start).trim()
        }

        fun buildStarterTemplate(prompt: String): String {
            val safeTitle = deriveTitleFromPrompt(prompt)
                .replace("<", "&lt;").replace(">", "&gt;")
            val safeDesc = prompt.trim()
                .replace("<", "&lt;").replace(">", "&gt;")
            return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                  <title>$safeTitle</title>
                  <style>
                    :root { --bg: #0f172a; --card: #1e293b; --accent: #38bdf8; --text: #f8fafc; --muted: #94a3b8; }
                    * { box-sizing: border-box; margin: 0; padding: 0; font-family: system-ui, -apple-system, sans-serif; }
                    body { background: var(--bg); color: var(--text); line-height: 1.6; padding: 24px 16px; }
                    .container { max-width: 880px; margin: 0 auto; }
                    .hero { background: linear-gradient(135deg, #0ea5e9 0%, #6366f1 100%); border-radius: 24px; padding: 40px 28px; margin-bottom: 24px; box-shadow: 0 18px 40px rgba(14,165,233,0.22); }
                    .hero h1 { font-size: 2rem; margin-bottom: 10px; }
                    .hero p { opacity: 0.92; margin-bottom: 20px; }
                    .btn { display: inline-block; background: #fff; color: #0f172a; font-weight: 700; padding: 12px 22px; border-radius: 999px; border: none; cursor: pointer; }
                    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 16px; }
                    .card { background: var(--card); border-radius: 20px; padding: 22px; border: 1px solid rgba(255,255,255,0.08); }
                    .card h3 { color: var(--accent); margin-bottom: 8px; }
                    .card p { color: var(--muted); font-size: 0.95rem; }
                  </style>
                </head>
                <body>
                  <div class="container">
                    <section class="hero">
                      <h1>$safeTitle</h1>
                      <p>$safeDesc</p>
                      <button class="btn" onclick="alert('Welcome to $safeTitle!')">Explore Now</button>
                    </section>
                    <section class="grid">
                      <div class="card"><h3>Fast Responsive Design</h3><p>Built cleanly inside MYRAA Web Studio for mobile and desktop viewports.</p></div>
                      <div class="card"><h3>Interactive Actions</h3><p>Customize styling, sections, or export as standalone HTML anytime.</p></div>
                      <div class="card"><h3>AI-Powered Studio</h3><p>Enter your Gemini key in Settings for full custom AI web code generation.</p></div>
                    </section>
                  </div>
                </body>
                </html>
            """.trimIndent()
        }
    }
}
