package com.vix.heyvix

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AiApiClient(context: Context) {

data class Result(
    val successful: Boolean,
    val message: String
)

private val appContext = context.applicationContext
private val mainHandler = Handler(Looper.getMainLooper())
private val executor: ExecutorService =
    Executors.newSingleThreadExecutor()

private val config: JSONObject by lazy {
    appContext.assets.open("ai_config.json")
        .bufferedReader()
        .use { JSONObject(it.readText()) }
}

fun sendMessage(
    message: String,
    callback: (Result) -> Unit
) {
    executor.execute {
        val result = try {
            sendQueuedRequest(message)
        } catch (e: Exception) {
            Result(
                false,
                "VIX AI connection error: " +
                    (e.message ?: "Unknown error")
            )
        }

        mainHandler.post {
            callback(result)
        }
    }
}

private fun sendQueuedRequest(message: String): Result {
    val baseUrl = config.getString("base_url").trimEnd('/')
    val timeout = config.optInt("timeout_seconds", 90) * 1000
    val systemPrompt = config.optString(
        "system_prompt",
        "You are VIX AI, a helpful AI assistant."
    )

    // Discover the API function index from Gradio's config.
    val appConfig = getJson("$baseUrl/config", 20000)
    val dependencies = appConfig.optJSONArray("dependencies")
        ?: throw Exception(
            "Gradio configuration has no dependencies list."
        )

    var functionIndex = -1

    for (i in 0 until dependencies.length()) {
        val dependency = dependencies.optJSONObject(i)
            ?: continue

        val apiName = dependency.optString("api_name")
        val targets = dependency.optJSONArray("targets")
        var matches = apiName == "generate_code"

        if (!matches && targets != null) {
            for (j in 0 until targets.length()) {
                if (
                    targets.optJSONArray(j)?.optString(1) ==
                    "generate_code"
                ) {
                    matches = true
                    break
                }
            }
        }

        if (matches) {
            functionIndex = dependency.optInt("id", i)
            break
        }
    }

    if (functionIndex < 0) {
        throw Exception(
            "Could not find generate_code in the Gradio config. " +
                "The Space may have changed its API."
        )
    }

    val sessionHash = UUID.randomUUID()
        .toString()
        .replace("-", "")
        .take(12)

    // The function uses a third input: conversation state.
    val state = JSONObject()
        .put("system_prompt", systemPrompt)
        .put("history", JSONArray())

    val data = JSONArray()
        .put(message)
        .put(systemPrompt)
        .put(state)

    val joinBody = JSONObject()
        .put("data", data)
        .put("fn_index", functionIndex)
        .put("session_hash", sessionHash)
        .put("event_data", JSONObject.NULL)

    val joinResponse = postJson(
        "$baseUrl/queue/join",
        joinBody,
        20000
    )

    val eventId = joinResponse.optString("event_id")

    if (eventId.isBlank()) {
        throw Exception(
            "Gradio queue did not return an event ID: " +
                joinResponse.toString()
        )
    }

    // Gradio returns queued events as server-sent events (SSE).
    val connection = URL(
        "$baseUrl/queue/data?session_hash=$sessionHash"
    ).openConnection() as HttpURLConnection

    try {
        connection.requestMethod = "GET"
        connection.connectTimeout = 20000
        connection.readTimeout = timeout
        connection.setRequestProperty(
            "Accept",
            "text/event-stream"
        )

        val status = connection.responseCode

        if (status !in 200..299) {
            val error = connection.errorStream?.bufferedReader()
                ?.use { it.readText() }.orEmpty()

            throw Exception(
                "Queue stream returned HTTP $status. $error"
            )
        }

        var eventType = ""
        val reader = BufferedReader(
            InputStreamReader(
                connection.inputStream,
                StandardCharsets.UTF_8
            )
        )

        reader.use { stream ->
            while (true) {
                val line = stream.readLine() ?: break

                if (line.startsWith("event:")) {
                    eventType = line.substringAfter(":").trim()
                } else if (line.startsWith("data:")) {
                    val raw = line.substringAfter(":").trim()
                    if (raw.isBlank()) continue

                    val event = try {
                        JSONObject(raw)
                    } catch (_: Exception) {
                        continue
                    }

                    val messageType = event.optString("msg")

                    if (
                        messageType == "process_completed" ||
                        eventType == "complete"
                    ) {
                        val output = event.optJSONObject("output")
                        val outputData = output?.optJSONArray("data")
                        val answer = outputData?.optString(0).orEmpty()

                        if (answer.isNotBlank() && answer != "null") {
                            return Result(true, answer)
                        }

                        throw Exception(
                            "The queued request completed without " +
                                "a text response: $raw"
                        )
                    }

                    if (
                        messageType == "process_failed" ||
                        eventType == "error"
                    ) {
                        throw Exception(
                            event.optString(
                                "message",
                                "The queued generation failed."
                            )
                        )
                    }
                }
            }
        }

        throw Exception(
            "The queue stream closed before VIX AI returned a result."
        )
    } finally {
        connection.disconnect()
    }
}

private fun getJson(
    url: String,
    timeout: Int
): JSONObject {
    val connection = URL(url)
        .openConnection() as HttpURLConnection

    try {
        connection.requestMethod = "GET"
        connection.connectTimeout = timeout
        connection.readTimeout = timeout
        connection.setRequestProperty(
            "Accept",
            "application/json"
        )

        val status = connection.responseCode
        val stream = if (status in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }

        val body = stream?.bufferedReader()
            ?.use { it.readText() }.orEmpty()

        if (status !in 200..299) {
            throw Exception("HTTP $status: $body")
        }

        return JSONObject(body)
    } finally {
        connection.disconnect()
    }
}

private fun postJson(
    url: String,
    body: JSONObject,
    timeout: Int
): JSONObject {
    val connection = URL(url)
        .openConnection() as HttpURLConnection

    try {
        connection.requestMethod = "POST"
        connection.connectTimeout = timeout
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

        val bytes = body.toString()
            .toByteArray(StandardCharsets.UTF_8)

        connection.outputStream.use { it.write(bytes) }

        val status = connection.responseCode
        val stream = if (status in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }

        val response = stream?.bufferedReader()
            ?.use { it.readText() }.orEmpty()

        if (status !in 200..299) {
            throw Exception("HTTP $status: $response")
        }

        return JSONObject(response)
    } finally {
        connection.disconnect()
    }
}

fun close() {
    executor.shutdownNow()
}

}