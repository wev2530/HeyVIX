package com.vix.heyvix

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Talks to Google's Gemini API (with Google Search grounding for live info).
 *
 * The API key is never stored in the repo. Type "/key AIza..." in the chat
 * to save it privately on this phone.
 *
 * Optional settings live in assets/ai_config.json:
 *   model, system_prompt, use_google_search, timeout_seconds
 */
class AiApiClient(context: Context) {

    data class Result(
        val successful: Boolean,
        val message: String
    )

    companion object {
        private const val PREFS = "vix_private"
        private const val KEY_API = "gemini_key"
        private const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
        private const val DEFAULT_SYSTEM =
            "You are Vixie, an AI assistant for VIX AI. Your replies are read aloud, " +
                "so answer in plain conversational text with no markdown, no asterisks " +
                "and no bullet symbols. Keep answers short unless asked for detail."
        private const val MAX_HISTORY_MESSAGES = 20

        fun saveKey(context: Context, key: String) {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_API, key.trim())
                .apply()
        }
    }

    private class ApiException(val status: Int, message: String) : Exception(message)

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    // Recent conversation so follow-up questions work: (role, text)
    private val history = ArrayList<Pair<String, String>>()

    private val config: JSONObject by lazy {
        try {
            appContext.assets.open("ai_config.json")
                .bufferedReader()
                .use { JSONObject(it.readText()) }
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun apiKey(): String =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_API, "") ?: ""

    fun sendMessage(message: String, callback: (Result) -> Unit) {
        executor.execute {
            val result = try {
                askGemini(message)
            } catch (e: Exception) {
                Result(false, "VIX AI connection error: " + describe(e))
            }
            mainHandler.post { callback(result) }
        }
    }

    private fun askGemini(message: String): Result {
        val key = apiKey()
        if (key.isBlank()) {
            return Result(
                false,
                "No Gemini key saved yet. Type /key followed by your key. " +
                    "You can get a free one at aistudio.google.com."
            )
        }

        val model = config.optString("model", DEFAULT_MODEL).ifBlank { DEFAULT_MODEL }
        val systemPrompt = config.optString("system_prompt", DEFAULT_SYSTEM).ifBlank { DEFAULT_SYSTEM }
        val useSearch = config.optBoolean("use_google_search", true)
        val timeoutMs = config.optInt("timeout_seconds", 60) * 1000

        val contents = JSONArray()
        for ((role, text) in history) {
            contents.put(contentOf(role, text))
        }
        contents.put(contentOf("user", message))

        val body = JSONObject()
            .put(
                "system_instruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", systemPrompt))
                )
            )
            .put("contents", contents)

        if (useSearch) {
            body.put(
                "tools",
                JSONArray().put(JSONObject().put("google_search", JSONObject()))
            )
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        val response = postJson(url, key, body, timeoutMs)
        val text = extractText(response)

        // Remember this exchange (only after success).
        history.add("user" to message)
        history.add("model" to text)
        while (history.size > MAX_HISTORY_MESSAGES) {
            history.removeAt(0)
        }

        return Result(true, text)
    }

    private fun contentOf(role: String, text: String): JSONObject =
        JSONObject()
            .put("role", role)
            .put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun extractText(response: JSONObject): String {
        val blocked = response.optJSONObject("promptFeedback")?.optString("blockReason", "")
        if (!blocked.isNullOrBlank()) {
            throw Exception("Google blocked this request ($blocked).")
        }

        val candidate = response.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw Exception("Gemini returned no answer.")

        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
        val sb = StringBuilder()
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val t = parts.optJSONObject(i)?.optString("text", "").orEmpty()
                if (t.isNotBlank()) sb.append(t)
            }
        }

        val text = sb.toString().trim()
        if (text.isBlank()) {
            val reason = candidate.optString("finishReason", "unknown")
            throw Exception("Gemini returned no text (reason: $reason).")
        }
        return text
    }

    private fun postJson(url: String, key: String, body: JSONObject, readMs: Int): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20000
            connection.readTimeout = readMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("x-goog-api-key", key)
            connection.outputStream.use {
                it.write(body.toString().toByteArray(StandardCharsets.UTF_8))
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val msg = try {
                    JSONObject(text).optJSONObject("error")?.optString("message", text) ?: text
                } catch (e: Exception) {
                    text
                }
                throw ApiException(status, msg)
            }
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is ApiException -> when {
            e.status == 400 && (e.message ?: "").contains("API key", ignoreCase = true) ->
                "Google rejected the key. Type /key followed by a valid Gemini key."
            e.status == 400 -> "Bad request (HTTP 400): ${e.message?.take(160)}"
            e.status == 401 || e.status == 403 ->
                "Google rejected the key or this model isn't allowed (HTTP ${e.status}). " +
                    "Check the key with /key. ${e.message?.take(120)}"
            e.status == 404 ->
                "Model not found (HTTP 404). Check the model name in ai_config.json."
            e.status == 429 ->
                "Free limit reached (HTTP 429). Wait a bit and try again."
            e.status in 500..599 ->
                "Google's server is busy (HTTP ${e.status}). Try again in a moment."
            else -> "HTTP ${e.status}: ${e.message?.take(160)}"
        }
        is SocketTimeoutException -> "VIX AI took too long to answer."
        is UnknownHostException -> "No internet connection."
        else -> e.message ?: "Unknown error"
    }

    fun close() {
        executor.shutdownNow()
    }
}
