package com.vix.heyvix

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AiApiClient(context: Context) {

data class Result(
    val successful: Boolean,
    val message: String
)

private val appContext = context.applicationContext
private val mainHandler = Handler(Looper.getMainLooper())
private val executor: ExecutorService = Executors.newSingleThreadExecutor()

private val config: JSONObject by lazy {
    appContext.assets.open("ai_config.json").bufferedReader().use {
        JSONObject(it.readText())
    }
}

fun sendMessage(
    message: String,
    callback: (Result) -> Unit
) {
    executor.execute {
        val result = try {
            sendRequest(message)
        } catch (e: Exception) {
            Result(
                false,
                "VIX AI connection error: ${e.message ?: "Unknown error"}"
            )
        }

        mainHandler.post {
            callback(result)
        }
    }
}

private fun sendRequest(message: String): Result {
    val baseUrl = config.getString("base_url").trimEnd('/')
    val endpoint = config.getString("endpoint")
    val timeout = config.optInt("timeout_seconds", 90) * 1000

    val systemPrompt = config.optString(
        "system_prompt",
        "You are VIX AI, a helpful AI assistant."
    )

    val url = URL("$baseUrl/${
        endpoint.trimStart('/')
    }")

    val connection = url.openConnection() as HttpURLConnection

    try {
        connection.requestMethod = "POST"
        connection.connectTimeout = 20000
        connection.readTimeout = timeout
        connection.doOutput = true
        connection.setRequestProperty(
            "Content-Type",
            "application/json"
        )
        connection.setRequestProperty(
            "Accept",
            "application/json"
        )

        /*
         * Gradio API expects the inputs inside a "data" array.
         * This supplies:
         * data[0] = user message
         * data[1] = system prompt
         */
        val inputs = JSONArray()
            .put(message)
            .put(systemPrompt)

        val requestBody = JSONObject()
            .put("data", inputs)

        val bytes = requestBody.toString()
            .toByteArray(StandardCharsets.UTF_8)

        connection.setFixedLengthStreamingMode(bytes.size)

        connection.outputStream.use { output: OutputStream ->
            output.write(bytes)
            output.flush()
        }

        val statusCode = connection.responseCode

        val responseStream = if (statusCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }

        val responseText = responseStream?.use { stream ->
            BufferedReader(
                InputStreamReader(stream, StandardCharsets.UTF_8)
            ).readText()
        }.orEmpty()

        if (statusCode !in 200..299) {
            return Result(
                false,
                "VIX AI server returned HTTP $statusCode. $responseText"
            )
        }

        val responseJson = JSONObject(responseText)

        val output = extractText(responseJson)

        if (output.isBlank()) {
            return Result(
                false,
                "VIX AI returned an empty response. Raw response: $responseText"
            )
        }

        return Result(true, output)
    } finally {
        connection.disconnect()
    }
}

private fun extractText(json: JSONObject): String {
    val directFields = listOf(
        "output",
        "output_1",
        "response",
        "answer",
        "text",
        "message"
    )

    for (field in directFields) {
        val value = json.opt(field)

        if (value is String && value.isNotBlank()) {
            return value
        }

        if (value is JSONObject) {
            val nested = extractText(value)
            if (nested.isNotBlank()) return nested
        }

        if (value is JSONArray) {
            val nested = extractArrayText(value)
            if (nested.isNotBlank()) return nested
        }
    }

    val data = json.optJSONArray("data")
    if (data != null) {
        val text = extractArrayText(data)
        if (text.isNotBlank()) return text
    }

    return ""
}

private fun extractArrayText(array: JSONArray): String {
    val pieces = mutableListOf<String>()

    for (index in 0 until array.length()) {
        when (val item = array.opt(index)) {
            is String -> {
                if (item.isNotBlank()) pieces.add(item)
            }

            is JSONObject -> {
                val nested = extractText(item)
                if (nested.isNotBlank()) pieces.add(nested)
            }

            is JSONArray -> {
                val nested = extractArrayText(item)
                if (nested.isNotBlank()) pieces.add(nested)
            }
        }
    }

    return pieces.joinToString("\n").trim()
}

fun close() {
    executor.shutdownNow()
}

}