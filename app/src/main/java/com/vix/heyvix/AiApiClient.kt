
package com.vix.heyvix

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Sends text messages to the AI endpoint configured in assets/ai_config.json.
 *
 * Network work runs on a background thread. Results are delivered on the
 * Android main thread so the UI can safely display them.
 */
class AiApiClient(context: Context) {

    data class Result(
        val successful: Boolean,
        val message: String
    )

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var closed = false

    /**
     * Sends a message to the configured AI service.
     */
    fun sendMessage(
        message: String,
        callback: (Result) -> Unit
    ) {
        val cleanMessage = message.trim()

        if (cleanMessage.isEmpty()) {
            callbackOnMain(
                callback,
                Result(false, "Please enter a message first.")
            )
            return
        }

        if (closed) {
            callbackOnMain(
                callback,
                Result(false, "The VIX AI connection is unavailable.")
            )
            return
        }

        executor.execute {
            val result = try {
                performRequest(cleanMessage)
            } catch (exception: Exception) {
                Result(
                    false,
                    "Couldn't connect to VIX AI: " +
                        (exception.message ?: "Unknown network error.")
                )
            }

            callbackOnMain(callback, result)
        }
    }

    private fun performRequest(message: String): Result {
        val configText = appContext.assets
            .open("ai_config.json")
            .bufferedReader()
            .use { it.readText() }

        val config = JSONObject(configText)

        if (!config.optBoolean("enabled", false)) {
            return Result(false, "The VIX AI connection is disabled.")
        }

        val baseUrl = config.optString("base_url").trimEnd('/')
        val endpoint = config.optString("endpoint")
        val timeoutSeconds = config.optInt("timeout_seconds", 90)
            .coerceIn(10, 180)

        if (
            baseUrl.isBlank() ||
            endpoint.isBlank() ||
            !baseUrl.startsWith("https://")
        ) {
            return Result(
                false,
                "The VIX AI API configuration is invalid. " +
                    "Check the base URL and endpoint."
            )
        }

        val requestFields = config.optJSONObject("request_fields")
            ?: JSONObject()

        val messageField = requestFields.optString(
            "message",
            "input_value"
        )

        val promptField = requestFields.optString(
            "system_prompt",
            "system_prompt_input_value"
        )

        val requestBody = JSONObject().apply {
            put(messageField, message)
            put(
                promptField,
                config.optString("system_prompt", "You are VIX AI.")
            )
        }

        val connection = (URL(baseUrl + endpoint)
            .openConnection() as HttpURLConnection)

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = timeoutSeconds * 1000
            connection.readTimeout = timeoutSeconds * 1000
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type",
                "application/json; charset=UTF-8"
            )
            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            OutputStreamWriter(
                connection.outputStream,
                StandardCharsets.UTF_8
            ).use { writer ->
                writer.write(requestBody.toString())
                writer.flush()
            }

            val statusCode = connection.responseCode
            val responseStream = if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val responseText = responseStream?.let { stream ->
                BufferedReader(
                    InputStreamReader(stream, StandardCharsets.UTF_8)
                ).use { reader ->
                    reader.readText()
                }
            }.orEmpty()

            if (statusCode !in 200..299) {
                return Result(
                    false,
                    "VIX AI server returned HTTP $statusCode. " +
                        explainHttpError(statusCode, responseText)
                )
            }

            val answer = extractAnswer(responseText, config)

            return if (answer.isBlank()) {
                Result(
                    false,
                    "The VIX AI server responded, but no answer was found. " +
                        "The API response format may need to be adjusted."
                )
            } else {
                Result(true, answer)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun extractAnswer(
        responseText: String,
        config: JSONObject
    ): String {
        if (responseText.isBlank()) return ""

        val json = try {
            JSONObject(responseText)
        } catch (_: Exception) {
            null
        }

        if (json == null) {
            return responseText.trim()
        }

        val configuredFields = config.optJSONArray("response_fields")
            ?: JSONArray().apply {
                put("output")
                put("output_1")
            }

        // Prefer the response fields specified in the configuration.
        for (index in 0 until configuredFields.length()) {
            val field = configuredFields.optString(index)
            val value = findText(json, field)

            if (value.isNotBlank()) {
                return value
            }
        }

        // Handle common Gradio responses such as {"data":["answer"]}.
        val data = json.optJSONArray("data")
        if (data != null) {
            for (index in 0 until data.length()) {
                val value = data.opt(index)
                if (value is String && value.isNotBlank()) {
                    return value.trim()
                }
            }
        }

        // Handle common alternative response formats.
        for (field in listOf("response", "answer", "text", "message")) {
            val value = findText(json, field)
            if (value.isNotBlank()) {
                return value
            }
        }

        val detail = json.optString("detail")
        if (detail.isNotBlank()) {
            return ""
        }

        return ""
    }

    private fun findText(
        json: JSONObject,
        field: String
    ): String {
        val directValue = json.opt(field)

        if (directValue is String && directValue.isNotBlank()) {
            return directValue.trim()
        }

        if (directValue is JSONObject) {
            val nested = findText(
                directValue,
                "text"
            )
            if (nested.isNotBlank()) return nested

            val nestedOutput = findText(
                directValue,
                "output"
            )
            if (nestedOutput.isNotBlank()) return nestedOutput
        }

        return ""
    }

    private fun explainHttpError(
        statusCode: Int,
        responseText: String
    ): String {
        return when (statusCode) {
            401, 403 ->
                "The service may require authentication or may not allow this request."
            404 ->
                "The configured endpoint was not found. Verify the API route."
            429 ->
                "The service is rate-limiting requests. Try again later."
            500, 502, 503, 504 ->
                "The AI service may be starting, busy, or experiencing an error."
            else ->
                responseText.take(250).ifBlank {
                    "Check the API configuration and service status."
                }
        }
    }

    private fun callbackOnMain(
        callback: (Result) -> Unit,
        result: Result
    ) {
        mainHandler.post {
            callback(result)
        }
    }

    /**
     * Call when the owning screen no longer needs this client.
     */
    fun close() {
        closed = true
        executor.shutdownNow()
    }
}
