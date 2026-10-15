package com.vix.heyvix

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Decides whether a request needs live information from the web,
 * and what to search for.
 */
object LiveInfo {

    data class Request(
        val query: String,
        val isNews: Boolean
    )

    private val notLive = listOf(
        "open ", "launch ", "start ", "turn ", "take ", "record ",
        "stop ", "switch ", "call ", "play "
    )

    private val searchPrefixes = listOf(
        "search the web for ",
        "search online for ",
        "search for ",
        "web search ",
        "look up ",
        "google ",
        "search "
    )

    private val liveWords = listOf(
        "latest news", "news about", "news on", "breaking news", "headlines",
        "score of", "scores of", "score for", "live score", "final score",
        "who won", "who is winning",
        "price of", "stock price", "exchange rate", "current price",
        "weather in", "weather for", "weather today", "weather forecast",
        "the weather", "temperature in",
        "latest"
    )

    private val newsWords = listOf("news", "headlines", "breaking")

    /** Returns null when this is not a live-info request. */
    fun parse(raw: String): Request? {
        var t = raw.lowercase().trim()
        t = t.replace(Regex("[^a-z0-9'\\s]"), " ")
        t = t.replace(Regex("\\s+"), " ").trim()
        t = t.replace(Regex("^(hey|hi|hello)\\s+vix\\s*"), "")
        t = t.replace(Regex("^vix\\s*"), "")
        t = t.replace(Regex("^please\\s+"), "").trim()

        if (t.isBlank()) return null
        if (notLive.any { t.startsWith(it) }) return null

        for (prefix in searchPrefixes) {
            if (t.startsWith(prefix)) {
                val q = t.removePrefix(prefix).trim()
                if (q.isNotBlank()) {
                    return Request(q, newsWords.any { q.contains(it) })
                }
            }
        }

        if (liveWords.any { t.contains(it) }) {
            return Request(t, newsWords.any { t.contains(it) })
        }

        return null
    }
}

/**
 * Live web search for the personal assistant, using the Tavily Search API.
 * It does not use Gemini.
 *
 * The key is never stored in the repo. Type "/tavilykey tvly-..." in the
 * main app chat to save it privately on this phone.
 *
 * The reply is built only from what Tavily returned. If there is no key,
 * no result, or an error, the reply says so. Nothing is invented.
 */
class TavilyClient(context: Context) {

    data class Result(
        val successful: Boolean,
        val message: String
    )

    private data class Item(
        val title: String,
        val url: String,
        val content: String,
        val score: Double
    )

    private class ApiException(val status: Int, message: String) : Exception(message)

    companion object {
        private const val PREFS = "vix_private"
        private const val KEY_TAVILY = "tavily_key"
        private const val ENDPOINT = "https://api.tavily.com/search"
        private const val MAX_SPOKEN_CHARS = 450
        private const val MIN_GOOD_SCORE = 0.3

        fun saveKey(context: Context, key: String) {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TAVILY, key.trim())
                .apply()
        }

        fun hasKey(context: Context): Boolean =
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_TAVILY, "")
                .orEmpty()
                .isNotBlank()
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private fun apiKey(): String =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TAVILY, "").orEmpty()

    fun search(request: LiveInfo.Request, callback: (Result) -> Unit) {
        executor.execute {
            val result = try {
                doSearch(request)
            } catch (e: Exception) {
                Result(false, describe(e))
            }
            mainHandler.post { callback(result) }
        }
    }

    private fun doSearch(request: LiveInfo.Request): Result {
        val key = apiKey()
        if (key.isBlank()) {
            return Result(
                false,
                "No Tavily key saved yet. Type /tavilykey followed by your key."
            )
        }

        val body = JSONObject()
            .put("query", request.query)
            .put("search_depth", "basic")
            .put("max_results", 5)
            .put("include_answer", true)
            .put("topic", if (request.isNews) "news" else "general")

        val json = postJson(key, body)
        return compose(json)
    }

    /** Builds a short, plain reply using only what Tavily returned. */
    private fun compose(json: JSONObject): Result {
        val rawAnswer = json.optString("answer", "")
        val answer = if (rawAnswer == "null") "" else clean(rawAnswer)

        val items = ArrayList<Item>()
        val results = json.optJSONArray("results")
        if (results != null) {
            for (i in 0 until results.length()) {
                val r = results.optJSONObject(i) ?: continue
                items.add(
                    Item(
                        title = clean(r.optString("title", "")),
                        url = r.optString("url", ""),
                        content = clean(r.optString("content", "")),
                        score = r.optDouble("score", 0.0)
                    )
                )
            }
        }

        val best = items.maxByOrNull { it.score }

        if (answer.isNotBlank()) {
            val source = best?.let { hostOf(it.url) }
            return Result(true, shorten(answer) + sourceNote(source))
        }

        if (best != null && best.content.isNotBlank() && best.score >= MIN_GOOD_SCORE) {
            return Result(
                true,
                shorten(best.content) + sourceNote(hostOf(best.url))
            )
        }

        if (best != null) {
            val host = hostOf(best.url)
            val where = if (host != null) " from $host" else ""
            val about = if (best.title.isNotBlank()) ": ${best.title}" else ""
            return Result(
                true,
                "I searched the web but didn't find a clear answer. " +
                    "The closest result was$where$about."
            )
        }

        return Result(true, "I searched the web but found nothing for that.")
    }

    private fun sourceNote(host: String?): String =
        if (host.isNullOrBlank()) "" else " Source: $host."

    private fun hostOf(url: String): String? =
        try {
            URL(url).host.removePrefix("www.").ifBlank { null }
        } catch (_: Exception) {
            null
        }

    private fun clean(text: String): String =
        text
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("[*#_`>|]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun shorten(text: String): String {
        if (text.length <= MAX_SPOKEN_CHARS) return text
        val cut = text.substring(0, MAX_SPOKEN_CHARS)
        val end = maxOf(
            cut.lastIndexOf(". "),
            cut.lastIndexOf("! "),
            cut.lastIndexOf("? ")
        )
        return if (end > 120) {
            cut.substring(0, end + 1)
        } else {
            cut.trimEnd() + "…"
        }
    }

    private fun postJson(key: String, body: JSONObject): JSONObject {
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20000
            connection.readTimeout = 25000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.outputStream.use {
                it.write(body.toString().toByteArray(StandardCharsets.UTF_8))
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (status !in 200..299) {
                throw ApiException(status, text.take(160))
            }
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is ApiException -> when (e.status) {
            401 ->
                "Tavily rejected the key. Type /tavilykey followed by a valid key."
            429, 432, 433 ->
                "Tavily says the usage limit was reached. Try again later."
            in 500..599 ->
                "Tavily's server is busy (HTTP ${e.status}). Try again in a moment."
            else ->
                "Web search failed (HTTP ${e.status}): ${e.message}"
        }
        is SocketTimeoutException -> "The web search took too long to answer."
        is UnknownHostException -> "No internet connection."
        else -> "Web search failed: ${e.message ?: "unknown error"}"
    }

    fun close() {
        executor.shutdownNow()
    }
}