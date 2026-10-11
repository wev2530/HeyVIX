package com.vix.heyvix

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Talks to a Gradio 6 Space.
 *   1. POST {base}{prefix}/call/v2/{api_name}  body {"message": ..., "system_prompt": ..., ...}
 *      (falls back to the older POST {base}{prefix}/call/{api_name} {"data": [...]})
 *      -> {"event_id": "..."}
 *   2. GET  {base}{prefix}/call/{api_name}/{event_id}  (server-sent events)
 *
 * Settings live in assets/ai_config.json. The Hugging Face token is never stored in
 * the repo: type "/token hf_xxx" in the chat to save it on this phone.
 */
class AiApiClient(context: Context) {

    data class Result(
        val successful: Boolean,
        val message: String
    )

    companion object {
        private const val PREFS = "vix_private"
        private const val KEY_TOKEN = "hf_token"

        fun saveToken(context: Context, token: String) {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TOKEN, token.trim())
                .apply()
        }
    }

    private class ApiException(val status: Int, message: String) : Exception(message)

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private val config: JSONObject by lazy {
        appContext.assets.open("ai_config.json")
            .bufferedReader()
            .use { JSONObject(it.readText()) }
    }

    private fun token(): String =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, "") ?: ""

    fun sendMessage(message: String, callback: (Result) -> Unit) {
        executor.execute {
            val result = try {
                askGradio(message)
            } catch (e: Exception) {
                Result(false, "VIX AI connection error: " + describe(e))
            }
            mainHandler.post { callback(result) }
        }
    }

    private fun askGradio(message: String): Result {
        val baseUrl = config.getString("base_url").trimEnd('/')
        var prefix = config.optString("api_prefix", "/gradio_api").trim().trimEnd('/')
        if (prefix.isNotEmpty() && !prefix.startsWith("/")) prefix = "/$prefix"
        val apiName = config.optString("api_name", "ask").trim('/', ' ')
        val readTimeoutMs = config.optInt("timeout_seconds", 150) * 1000
        val responseIndex = config.optInt("response_index", 0)
        val systemPrompt = config.optString("system_prompt", "You are VIX AI, a helpful assistant.")
        val messageField = config.optString("message_field", "message")
        val systemField = config.optString("system_prompt_field", "system_prompt")
        val extra = config.optJSONObject("extra_fields")

        // Newer format: named fields.
        val named = JSONObject().put(messageField, message).put(systemField, systemPrompt)
        // Older format: ordered list.
        val ordered = JSONArray().put(message).put(systemPrompt)
        if (extra != null) {
            val keys = extra.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                named.put(k, extra.get(k))
                ordered.put(extra.get(k))
            }
        }

        val callBase = "$baseUrl$prefix/call/$apiName"
        val joined = try {
            requestJson("POST", "$baseUrl$prefix/call/v2/$apiName", named, 20000, 30000)
        } catch (e: ApiException) {
            if (e.status == 404 || e.status == 405 || e.status == 422) {
                requestJson("POST", callBase, JSONObject().put("data", ordered), 20000, 30000)
            } else {
                throw e
            }
        }

        val eventId = joined.optString("event_id")
        if (eventId.isBlank()) {
            throw Exception("Gradio did not return an event_id: " + joined.toString().take(200))
        }
        return readResult("$callBase/$eventId", readTimeoutMs, responseIndex)
    }

    private fun readResult(url: String, readTimeoutMs: Int, responseIndex: Int): Result {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 20000
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Accept", "text/event-stream")
            addAuth(connection)

            val status = connection.responseCode
            if (status !in 200..299) {
                val err = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw ApiException(status, err)
            }

            var eventName = ""
            BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.startsWith("event:")) {
                        eventName = line.substringAfter(":").trim()
                    } else if (line.startsWith("data:")) {
                        val raw = line.substringAfter(":").trim()
                        when (eventName) {
                            "complete" -> return Result(true, extractText(raw, responseIndex))
                            "error" -> throw Exception(errorText(raw))
                        }
                    }
                }
            }
            throw Exception("The stream closed before VIX AI returned a result.")
        } finally {
            connection.disconnect()
        }
    }

    private fun addAuth(connection: HttpURLConnection) {
        val t = token()
        if (t.isNotBlank()) {
            connection.setRequestProperty("Authorization", "Bearer $t")
        }
    }

    private fun extractText(raw: String, index: Int): String {
        val outputs = try {
            JSONArray(raw)
        } catch (e: Exception) {
            throw Exception("Unexpected response format: " + raw.take(200))
        }
        val item = outputs.opt(index)
        val text = when (item) {
            null, JSONObject.NULL -> ""
            is String -> item
            is JSONObject -> item.optString("value", item.optString("text", ""))
            else -> item.toString()
        }
        if (text.isBlank() || text == "null") {
            throw Exception("The model returned no text (output #$index).")
        }
        return text
    }

    private fun errorText(raw: String): String {
        if (raw.isBlank() || raw == "null") {
            return "The Space reported an error. It may be out of GPU quota, overloaded, or still loading."
        }
        return try {
            JSONObject(raw).optString("message", raw)
        } catch (e: Exception) {
            raw.trim('"').take(300)
        }
    }

    private fun requestJson(
        method: String,
        url: String,
        body: JSONObject?,
        connectMs: Int,
        readMs: Int
    ): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectMs
            connection.readTimeout = readMs
            connection.setRequestProperty("Accept", "application/json")
            addAuth(connection)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use {
                    it.write(body.toString().toByteArray(StandardCharsets.UTF_8))
                }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw ApiException(status, text)
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is ApiException -> when (e.status) {
            401, 403 -> "Hugging Face rejected the request (HTTP ${e.status}). Type /token followed by your token to save it."
            404 -> "HTTP 404. The Space address or API name is wrong, or the Space isn't running. (${e.message?.take(120)})"
            422 -> "HTTP 422. The Space rejected the request format. (${e.message?.take(160)})"
            429 -> "Too many requests or out of GPU quota (HTTP 429). Try again later."
            502, 503, 504 -> "The Space is asleep or restarting (HTTP ${e.status}). Try again in a minute."
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