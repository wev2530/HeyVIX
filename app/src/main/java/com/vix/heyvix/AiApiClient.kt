package com.vix.heyvix

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Talks to the AI provider the user picked: Google Gemini (with Google Search
 * grounding for live info), OpenAI, Anthropic (Claude), OpenRouter, Groq,
 * DeepSeek, Mistral, or any other provider that copies the OpenAI chat API.
 *
 * Three ways a message can be answered:
 *
 * 1. NO own key saved: the app asks the Supabase function "gemini-chat", which
 *    holds the shared default Gemini key (secret GEMINI_API_KEY) on the server.
 *    That key never reaches the phone. The user must be logged in, and a daily
 *    message limit applies. Connections (tools) are NOT used here.
 *
 * 2. Own key saved, no Connections: the app calls the provider directly.
 *
 * 3. Own key saved AND at least one Connection (GitHub, Supabase, Webflow,
 *    Dropbox): the app asks the Supabase function "vix-agent", which lets the
 *    AI use those services as tools, with the user's own key for that one
 *    request. Anything that changes something asks the user first.
 *
 * Files attached on the phone (pictures, zip files, documents) only work in
 * way 3: they travel with the vix-agent request (field "attachments", each
 * with name, mime and b64), and the server uploads them as they are to GitHub
 * or Dropbox when the AI is asked to. The server numbers the files itself
 * (f1, f2, f3) and tells the AI about them, so this app adds nothing to the
 * message text. Limits (same as the server): 3 files, 3 MB each, 5 MB total.
 *
 * Users add their own key on the profile page (Keys, AI key) or type
 * "/key AIza..." in the chat. "/key openai sk-..." picks another provider.
 *
 * Optional settings live in assets/ai_config.json:
 *   model (Gemini default), system_prompt, timeout_seconds
 *
 * Web search is controlled by the switch in the VIX overlay (off by default).
 * With the shared key it runs on the server (Tavily). With the user's own
 * Gemini key it uses Google Search. A single call can also force search off
 * with allowSearch = false.
 */
class AiApiClient(context: Context) {

    data class Result(
        val successful: Boolean,
        val message: String
    )

    /**
     * A file the user attached on the phone. base64 holds the file as it is
     * (no changes), so a picture or zip file arrives on the server intact.
     */
    class Attachment(
        val id: String,
        val name: String,
        val mime: String,
        val size: Int,
        val base64: String
    )

    /**
     * kind is "gemini", "openai" (OpenAI-style chat API) or "anthropic".
     * baseUrl is empty for Gemini and for "custom" (the user types it).
     */
    data class Provider(
        val id: String,
        val label: String,
        val kind: String,
        val baseUrl: String,
        val defaultModel: String,
        val hint: String
    )

    companion object {
        private const val PREFS = "vix_private"

        // The name stays "gemini_key" so keys saved by older versions keep working.
        private const val KEY_API = "gemini_key"
        private const val KEY_PROVIDER = "ai_provider"
        private const val KEY_MODEL = "ai_model"
        private const val KEY_BASE = "ai_base"
        private const val KEY_SEARCH = "web_search"
        private const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
        private const val DEFAULT_SYSTEM =
            "Your name is Vix. You are part of Vix AI. When someone asks who you are, " +
                "simply say I am Vix, or I am Vix, a part of Vix AI. Never call yourself " +
                "Vixie, and never describe yourself as someone's Vix assistant or " +
                "personal assistant. Speak in a calm, mature, professional tone. " +
                "Your replies are read aloud, so answer in plain conversational text " +
                "with no markdown, no asterisks and no bullet symbols. " +
                "Keep answers short unless asked for detail."
        private const val MAX_HISTORY_MESSAGES = 20

        val PROVIDERS: List<Provider> = listOf(
            Provider(
                "gemini", "Google Gemini", "gemini", "", DEFAULT_MODEL,
                "Get a free key at aistudio.google.com"
            ),
            Provider(
                "openai", "OpenAI", "openai", "https://api.openai.com/v1", "gpt-4o-mini",
                "Get a key at platform.openai.com/api-keys"
            ),
            Provider(
                "anthropic", "Anthropic (Claude)", "anthropic",
                "https://api.anthropic.com/v1", "claude-haiku-5-5",
                "Get a key at console.anthropic.com"
            ),
            Provider(
                "openrouter", "OpenRouter", "openai", "https://openrouter.ai/api/v1",
                "openrouter/auto",
                "Get a key at openrouter.ai/keys. One key reaches many models."
            ),
            Provider(
                "groq", "Groq", "openai", "https://api.groq.com/openai/v1",
                "llama-3.3-70b-versatile",
                "Get a free key at console.groq.com/keys"
            ),
            Provider(
                "deepseek", "DeepSeek", "openai", "https://api.deepseek.com/v1",
                "deepseek-chat",
                "Get a key at platform.deepseek.com"
            ),
            Provider(
                "mistral", "Mistral", "openai", "https://api.mistral.ai/v1",
                "mistral-small-latest",
                "Get a key at console.mistral.ai"
            ),
            Provider(
                "custom", "Other (OpenAI-compatible)", "openai", "", "",
                "For any other provider that copies the OpenAI API. " +
                    "Enter its base URL and model name."
            )
        )

        /** Set to true when Google refuses web search for this key (free tier). */
        @Volatile
        private var searchRefused = false

        /** True when Google refused search for this key (free tier). */
        val searchBlocked: Boolean
            get() = searchRefused

        private fun prefs(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** Finds a provider by id or by a common nickname (claude, gpt, google...). */
        fun findProvider(word: String): Provider? {
            val w = word.trim().lowercase()
            val id = when (w) {
                "google" -> "gemini"
                "gpt", "chatgpt" -> "openai"
                "claude" -> "anthropic"
                "other" -> "custom"
                else -> w
            }
            return PROVIDERS.firstOrNull { it.id == id }
        }

        /** The provider the saved key belongs to. Gemini if none was chosen. */
        fun currentProvider(context: Context): Provider {
            val id = prefs(context).getString(KEY_PROVIDER, "gemini") ?: "gemini"
            return PROVIDERS.firstOrNull { it.id == id } ?: PROVIDERS[0]
        }

        /** The model the user typed. Empty means the provider's default. */
        fun currentModel(context: Context): String =
            prefs(context).getString(KEY_MODEL, "")?.trim().orEmpty()

        /** The base URL the user typed (only used by "Other" providers). */
        fun currentBase(context: Context): String =
            prefs(context).getString(KEY_BASE, "")?.trim().orEmpty()

        fun hasKey(context: Context): Boolean =
            !prefs(context).getString(KEY_API, "").isNullOrBlank()

        /** Web search switch. Off by default. */
        fun isWebSearchOn(context: Context): Boolean =
            prefs(context).getBoolean(KEY_SEARCH, false)

        fun setWebSearch(context: Context, on: Boolean) {
            prefs(context)
                .edit()
                .putBoolean(KEY_SEARCH, on)
                .apply()
            if (on) searchRefused = false // try search again
        }

        /** Saves the key for the provider that is currently chosen. */
        fun saveKey(context: Context, key: String) {
            prefs(context)
                .edit()
                .putString(KEY_API, key.trim())
                .apply()
            searchRefused = false // a new key may allow search
        }

        /**
         * Chooses the provider. When it changes, the saved model and base URL
         * are cleared, because model names belong to one provider.
         */
        fun saveProvider(context: Context, provider: Provider) {
            val saved = prefs(context)
            val changed = saved.getString(KEY_PROVIDER, "gemini") != provider.id
            val editor = saved.edit().putString(KEY_PROVIDER, provider.id)
            if (changed) {
                editor.remove(KEY_MODEL).remove(KEY_BASE)
            }
            editor.apply()
            searchRefused = false
        }

        /** Saves provider, key, model and base URL together. */
        fun saveSettings(
            context: Context,
            provider: Provider,
            key: String,
            model: String,
            baseUrl: String
        ) {
            prefs(context)
                .edit()
                .putString(KEY_PROVIDER, provider.id)
                .putString(KEY_API, key.trim())
                .putString(KEY_MODEL, model.trim())
                .putString(KEY_BASE, if (provider.id == "custom") baseUrl.trim() else "")
                .apply()
            searchRefused = false
        }

        /** Removes the saved key, model and base URL. */
        fun removeKey(context: Context) {
            prefs(context)
                .edit()
                .remove(KEY_API)
                .remove(KEY_MODEL)
                .remove(KEY_BASE)
                .apply()
        }
    }

    private class ApiException(val status: Int, message: String) : Exception(message)

    /** One action the AI wants to do that needs the user's approval. */
    private class Approval(val id: String, val name: String, val summary: String)

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    // The screen that created this client (if any). Approval questions are shown
    // on it. When there is none (for example the overlay), changes are declined.
    private val activityRef: WeakReference<Activity>? =
        (context as? Activity)?.let { WeakReference(it) }

    // Recent conversation so follow-up questions work: (role, text)
    // Roles here are "user" and "model"; each provider converts them as needed.
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
        prefs(appContext).getString(KEY_API, "") ?: ""

    /**
     * allowSearch = false forces web search off for this one call,
     * even if the web search switch is on.
     * attachments = files picked on the phone for this message (needs your own
     * key and a connection, otherwise a clear message is returned).
     */
    fun sendMessage(
        message: String,
        allowSearch: Boolean = true,
        attachments: List<Attachment> = emptyList(),
        callback: (Result) -> Unit
    ) {
        executor.execute {
            val result = try {
                ask(message, allowSearch, attachments)
            } catch (e: Exception) {
                Result(false, "VIX AI connection error: " + describe(e))
            }
            mainHandler.post { callback(result) }
        }
    }

    /**
     * Loads the earlier messages of a saved chat, so the AI continues from
     * them. Each pair is (role, text) with role "user" or "model".
     * Only the most recent messages are kept.
     */
    fun loadHistory(messages: List<Pair<String, String>>) {
        executor.execute {
            history.clear()

            for ((role, text) in messages) {
                if (text.isBlank()) continue

                val last = history.lastOrNull()
                if (last != null && last.first == role) {
                    // Two messages in a row from the same side: join them.
                    history[history.size - 1] = role to (last.second + "\n" + text)
                } else {
                    history.add(role to text)
                }
            }

            while (history.size > MAX_HISTORY_MESSAGES) {
                history.removeAt(0)
            }

            // The conversation must start with the user...
            while (history.isNotEmpty() && history[0].first != "user") {
                history.removeAt(0)
            }

            // ...and the next message is the user's, so the old part ends with the AI.
            if (history.isNotEmpty() && history[history.size - 1].first == "user") {
                history.removeAt(history.size - 1)
            }
        }
    }

    private fun ask(
        message: String,
        allowSearch: Boolean,
        attachments: List<Attachment>
    ): Result {
        val key = apiKey()
        val provider = currentProvider(appContext)
        val systemPrompt = config.optString("system_prompt", DEFAULT_SYSTEM).ifBlank { DEFAULT_SYSTEM }
        val timeoutMs = config.optInt("timeout_seconds", 60) * 1000

        val text = if (key.isBlank()) {
            if (attachments.isNotEmpty()) {
                throw Exception(
                    "To upload files I need your own AI key. Add it in Profile, Keys, " +
                        "and connect GitHub or Dropbox in Profile, Connections."
                )
            }

            // No own key: the server holds the shared default key (no tools).
            askViaServer(message, allowSearch, timeoutMs)
        } else {
            // Own key: tools from Connections are used when there are any.
            AgentClient.refreshIfStale(appContext)
            val viaAgent = if (AgentClient.hasConnections(appContext)) {
                askViaAgent(key, provider, message, timeoutMs, attachments)
            } else {
                null
            }

            if (viaAgent == null && attachments.isNotEmpty()) {
                throw Exception(
                    "To upload files, connect GitHub or Dropbox first. " +
                        "Open Profile, Connections."
                )
            }

            viaAgent ?: when (provider.kind) {
                "gemini" -> askGemini(key, message, allowSearch, systemPrompt, timeoutMs)
                "anthropic" -> askAnthropic(key, provider, message, systemPrompt, timeoutMs)
                else -> askOpenAi(key, provider, message, systemPrompt, timeoutMs)
            }
        }

        // Remember this exchange (only after success).
        history.add("user" to message)
        history.add("model" to text)
        while (history.size > MAX_HISTORY_MESSAGES) {
            history.removeAt(0)
        }

        return Result(true, text)
    }

    // ----------------------------------------------------------
    // The shared default key, kept on the server (Supabase)
    // ----------------------------------------------------------

    private fun askViaServer(
        message: String,
        allowSearch: Boolean,
        timeoutMs: Int
    ): String {
        val token = SupabaseAuth.accessToken(appContext)
            ?: throw Exception("Please log in again to use VIX AI.")

        val messages = JSONArray()
        for ((role, text) in history) {
            messages.put(JSONObject().put("role", role).put("text", text))
        }
        messages.put(JSONObject().put("role", "user").put("text", message))

        val body = JSONObject()
            .put("messages", messages)
            .put("search", allowSearch && isWebSearchOn(appContext))

        val response = try {
            postJson(
                SupabaseConfig.URL + "/functions/v1/gemini-chat",
                mapOf(
                    "Authorization" to "Bearer $token",
                    "apikey" to SupabaseConfig.PUBLISHABLE_KEY
                ),
                body,
                timeoutMs
            )
        } catch (e: ApiException) {
            throw Exception(serverMessage(e.message.orEmpty(), e.status) ?: describe(e))
        }

        val error = response.optString("error", "")
        if (error.isNotBlank()) {
            throw Exception(
                serverMessage(error, 200)
                    ?: ("VIX AI error ($error). " + response.optString("detail", ""))
            )
        }

        val text = response.optString("text", "").trim()
        if (text.isBlank()) throw Exception("VIX AI returned no answer. Try again.")
        return text
    }

    /** Turns the server's error codes into clear messages. Null if unknown. */
    private fun serverMessage(code: String, status: Int): String? = when {
        code == "daily_limit" ->
            "You've used today's messages on the shared key. Add your own API key " +
                "in Profile to keep chatting, or try again tomorrow."
        code == "no_default_key" ->
            "The free public quota has run out. Add your own API key in Profile " +
                "to continue using VIX AI."
        code == "not_signed_in" || status == 401 ->
            "Please log in again to use VIX AI."
        code == "gemini_busy" -> "Free limit reached. Wait a bit and try again."
        code == "blocked" -> "The provider blocked this request."
        code == "empty_answer" -> "VIX AI returned no answer. Try again."
        else -> null
    }

    // ----------------------------------------------------------
    // Tools from Connections (GitHub, Supabase, Webflow, Dropbox)
    // ----------------------------------------------------------

    /**
     * Returns the answer, or null when the server says there are no
     * connections (then the normal direct call is used instead).
     *
     * attachments are sent with the first request AND again with every
     * re-send after an approval, because the server keeps nothing between steps.
     */
    private fun askViaAgent(
        key: String,
        provider: Provider,
        message: String,
        timeoutMs: Int,
        attachments: List<Attachment>
    ): String? {
        val messages = JSONArray()
        for ((role, text) in history) {
            messages.put(JSONObject().put("role", role).put("text", text))
        }
        messages.put(JSONObject().put("role", "user").put("text", message))

        // The files themselves. The server numbers them f1, f2, f3 and tells the
        // AI about them, so nothing is added to the message text here.
        val files = JSONArray()
        for (a in attachments) {
            files.put(
                JSONObject()
                    .put("name", a.name)
                    .put("mime", a.mime)
                    .put("b64", a.base64)
            )
        }

        // The same fields go with every step of this one request.
        fun common(): JSONObject {
            val o = JSONObject()
                .put("action", "chat")
                .put("provider", provider.id)
                .put("key", key)
                .put("model", currentModel(appContext))
                .put("base", if (provider.id == "custom") currentBase(appContext) else "")
            if (files.length() > 0) o.put("attachments", files)
            return o
        }

        var body = common().put("messages", messages)
        val readMs = maxOf(timeoutMs, 130000)

        for (round in 0 until 5) {
            val response = AgentClient.post(appContext, body, readMs).json

            val error = response.optString("error", "")
            if (error.isNotBlank()) {
                if (error == "no_connections") {
                    AgentClient.clearCache(appContext)
                    return null
                }
                throw Exception(agentMessage(error, response))
            }

            val confirm = response.optJSONArray("confirm")
            if (confirm != null && confirm.length() > 0) {
                val items = ArrayList<Approval>()
                for (i in 0 until confirm.length()) {
                    val c = confirm.optJSONObject(i) ?: continue
                    items.add(
                        Approval(
                            c.optString("id", ""),
                            c.optString("name", ""),
                            c.optString("summary", "")
                        )
                    )
                }

                val decided = askApproval(items)
                val decisions = JSONObject()
                for ((id, allowed) in decided) decisions.put(id, allowed)

                body = common()
                    .put("state", response.optJSONArray("state") ?: JSONArray())
                    .put("decisions", decisions)
                continue
            }

            val text = response.optString("text", "").trim()
            if (text.isBlank()) throw Exception("VIX AI returned no answer. Try again.")
            return text
        }

        throw Exception("That needed too many approval steps. Try a simpler request.")
    }

    /**
     * Asks the user to allow or deny the changes the AI wants to make.
     * Waits for the answer (up to 3 minutes). Blocking: worker thread only.
     * Without a screen to ask on, everything is declined.
     */
    private fun askApproval(items: List<Approval>): Map<String, Boolean> {
        val activity = activityRef?.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            throw Exception(
                "VIX needs your approval to make changes, and it can only ask in the " +
                    "main VIX chat screen. Open the VIX app and ask there."
            )
        }

        val latch = CountDownLatch(1)
        val allowed = HashMap<String, Boolean>()
        val failed = AtomicBoolean(false)

        mainHandler.post {
            val text = items.joinToString("\n\n") {
                "• " + it.summary.ifBlank { it.name }
            }

            try {
                AlertDialog.Builder(activity)
                    .setTitle("Allow VIX to do this?")
                    .setMessage(text)
                    .setCancelable(false)
                    .setPositiveButton("Allow") { _, _ ->
                        for (item in items) allowed[item.id] = true
                        latch.countDown()
                    }
                    .setNegativeButton("Deny") { _, _ ->
                        for (item in items) allowed[item.id] = false
                        latch.countDown()
                    }
                    .show()
            } catch (e: Exception) {
                failed.set(true)
                latch.countDown()
            }
        }

        latch.await(180, TimeUnit.SECONDS)
        if (failed.get()) {
            throw Exception(
                "VIX couldn't show the approval question. Open the main VIX chat screen " +
                    "and try again."
            )
        }
        return items.associate { it.id to (allowed[it.id] == true) }
    }

    private fun agentMessage(code: String, response: JSONObject): String = when (code) {
        "not_configured" -> "Connections aren't set up on the server yet."
        "not_signed_in", "http_401" -> "Please log in again to use VIX AI."
        "provider_error" ->
            "The AI provider returned an error (HTTP ${response.optInt("status")}). " +
                response.optString("detail", "").take(160)
        "provider_setup" ->
            "Your provider settings are incomplete. Open Profile, Keys, " +
                "and check the base URL and model name."
        "too_many_steps" -> "I used too many steps. Try a simpler request."
        "timeout" -> "That took too long. Try a simpler request."
        "empty_answer" -> "VIX AI returned no answer. Try again."
        "agent_failed" -> "Something went wrong: " + response.optString("detail", "").take(160)
        "attachments_invalid" ->
            "I couldn't use the attached files: " + response.optString("detail", "").take(160)
        "http_413" -> "Those files are too big to send. Try smaller files."
        else -> "VIX AI error ($code)."
    }

    // ----------------------------------------------------------
    // Google Gemini
    // ----------------------------------------------------------

    private fun askGemini(
        key: String,
        message: String,
        allowSearch: Boolean,
        systemPrompt: String,
        timeoutMs: Int
    ): String {
        val model = currentModel(appContext).ifBlank {
            config.optString("model", DEFAULT_MODEL).ifBlank { DEFAULT_MODEL }
        }

        val contents = JSONArray()
        for ((role, text) in history) {
            contents.put(contentOf(role, text))
        }
        contents.put(contentOf("user", message))

        // Builds the request, with or without the Google Search tool.
        fun bodyFor(withSearch: Boolean): JSONObject {
            val body = JSONObject()
                .put(
                    "system_instruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", systemPrompt))
                    )
                )
                .put("contents", contents)

            if (withSearch) {
                body.put(
                    "tools",
                    JSONArray().put(JSONObject().put("google_search", JSONObject()))
                )
            }
            return body
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        val headers = mapOf("x-goog-api-key" to key)

        // Search only when this call allows it, the switch is on,
        // AND Google has not refused it for this key.
        val searchNow = allowSearch && isWebSearchOn(appContext) && !searchRefused

        val response = try {
            postJson(url, headers, bodyFor(searchNow), timeoutMs)
        } catch (e: ApiException) {
            if (searchNow && looksLikeSearchRefusal(e)) {
                // Free keys can be refused search: answer without it instead of failing.
                searchRefused = true
                postJson(url, headers, bodyFor(false), timeoutMs)
            } else {
                throw e
            }
        }

        return extractGeminiText(response)
    }

    private fun looksLikeSearchRefusal(e: ApiException): Boolean {
        if (e.status != 400 && e.status != 403 && e.status != 429) return false
        val text = (e.message ?: "").lowercase()
        return text.contains("search") ||
            text.contains("grounding") ||
            text.contains("tool")
    }

    private fun contentOf(role: String, text: String): JSONObject =
        JSONObject()
            .put("role", role)
            .put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun extractGeminiText(response: JSONObject): String {
        val blocked = response.optJSONObject("promptFeedback")?.optString("blockReason", "")
        if (!blocked.isNullOrBlank()) {
            throw Exception("Google blocked this request ($blocked).")
        }

        val candidate = response.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw Exception("The AI returned no answer.")

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
            throw Exception("The AI returned no text (reason: $reason).")
        }
        return text
    }

    // ----------------------------------------------------------
    // OpenAI and every provider that copies its chat API
    // ----------------------------------------------------------

    private fun askOpenAi(
        key: String,
        provider: Provider,
        message: String,
        systemPrompt: String,
        timeoutMs: Int
    ): String {
        val base = (if (provider.id == "custom") currentBase(appContext) else provider.baseUrl)
            .trim()
            .trimEnd('/')
        val model = currentModel(appContext).ifBlank { provider.defaultModel }

        if (base.isBlank() || model.isBlank()) {
            throw Exception(
                "Your provider settings are incomplete. Open Profile, Keys, " +
                    "and check the base URL and model name."
            )
        }

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
        for ((role, text) in history) {
            messages.put(chatMessageOf(role, text))
        }
        messages.put(chatMessageOf("user", message))

        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)

        val response = postJson(
            "$base/chat/completions",
            mapOf("Authorization" to "Bearer $key"),
            body,
            timeoutMs
        )

        val reply = response.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?: throw Exception("The AI returned no answer.")

        val content = reply.opt("content")
        val text = when (content) {
            is String -> content
            is JSONArray -> buildString {
                for (i in 0 until content.length()) {
                    append(content.optJSONObject(i)?.optString("text", "").orEmpty())
                }
            }
            else -> ""
        }.trim()

        if (text.isBlank()) throw Exception("The AI returned no text.")
        return text
    }

    // ----------------------------------------------------------
    // Anthropic (Claude)
    // ----------------------------------------------------------

    private fun askAnthropic(
        key: String,
        provider: Provider,
        message: String,
        systemPrompt: String,
        timeoutMs: Int
    ): String {
        val model = currentModel(appContext).ifBlank { provider.defaultModel }

        val messages = JSONArray()
        for ((role, text) in history) {
            messages.put(chatMessageOf(role, text))
        }
        messages.put(chatMessageOf("user", message))

        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 2048)
            .put("system", systemPrompt)
            .put("messages", messages)

        val response = postJson(
            provider.baseUrl.trimEnd('/') + "/messages",
            mapOf(
                "x-api-key" to key,
                "anthropic-version" to "2023-06-01"
            ),
            body,
            timeoutMs
        )

        val blocks = response.optJSONArray("content")
        val sb = StringBuilder()
        if (blocks != null) {
            for (i in 0 until blocks.length()) {
                val block = blocks.optJSONObject(i) ?: continue
                if (block.optString("type") == "text") {
                    sb.append(block.optString("text", ""))
                }
            }
        }

        val text = sb.toString().trim()
        if (text.isBlank()) throw Exception("The AI returned no text.")
        return text
    }

    /** One message in the OpenAI / Anthropic shape: role user or assistant. */
    private fun chatMessageOf(role: String, text: String): JSONObject =
        JSONObject()
            .put("role", if (role == "model") "assistant" else "user")
            .put("content", text)

    // ----------------------------------------------------------
    // Network
    // ----------------------------------------------------------

    private fun postJson(
        url: String,
        headers: Map<String, String>,
        body: JSONObject,
        readMs: Int
    ): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20000
            connection.readTimeout = readMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            for ((name, value) in headers) {
                connection.setRequestProperty(name, value)
            }
            connection.outputStream.use {
                it.write(body.toString().toByteArray(StandardCharsets.UTF_8))
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val msg = try {
                    val error = JSONObject(text).opt("error")
                    when (error) {
                        is JSONObject -> error.optString("message", text)
                        is String -> error
                        else -> text
                    }
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
                "The provider rejected the key. Add a valid key in Profile, " +
                    "or type /key followed by the key."
            e.status == 400 -> "Bad request (HTTP 400): ${e.message?.take(160)}"
            e.status == 401 || e.status == 403 ->
                "The provider rejected the key or this model isn't allowed (HTTP ${e.status}). " +
                    "Check your key in Profile. ${e.message?.take(120)}"
            e.status == 404 ->
                "Model or address not found (HTTP 404). Check the model name in Profile."
            e.status == 429 ->
                "Limit reached (HTTP 429). Wait a bit and try again."
            e.status in 500..599 ->
                "The provider's server is busy (HTTP ${e.status}). Try again in a moment."
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