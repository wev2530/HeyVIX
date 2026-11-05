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
import java.util.concurrent.Executors

/**
 * Talks to the Supabase Edge Function "vix-agent".
 *
 * It manages the user's Connections (GitHub, Supabase, Webflow, Dropbox):
 * the tokens are sent to the server, which checks them and keeps them
 * encrypted. They are never saved on this phone.
 *
 * It also makes the chat call that lets the AI use those tools. That call is
 * made by AiApiClient, and only when the user has saved their OWN AI key.
 *
 * This phone only remembers WHICH services are connected (names, no tokens),
 * so the chat knows whether to use the tools.
 */
object AgentClient {

    data class Service(val id: String, val label: String, val hint: String)

    data class Resp(val status: Int, val json: JSONObject)

    /** A custom connector (MCP address) the user added. service looks like "mcp:my-name". */
    data class Custom(
        val service: String,
        val label: String,
        val host: String,
        val mode: String
    )

    /** modes maps service id to "read" or "write". custom lists the custom connectors. */
    data class Out(
        val ok: Boolean,
        val message: String,
        val modes: Map<String, String> = emptyMap(),
        val custom: List<Custom> = emptyList()
    )

    val SERVICES: List<Service> = listOf(
        Service(
            "github", "GitHub",
            "Create a personal access token at github.com/settings/tokens and give it " +
                "access to your repositories. To let VIX make changes, it needs write access."
        ),
        Service(
            "supabase", "Supabase",
            "Create an access token at supabase.com/dashboard/account/tokens."
        ),
        Service(
            "webflow", "Webflow",
            "Create a Site API token in Webflow: Site settings, Apps and integrations, " +
                "API access."
        ),
        Service(
            "dropbox", "Dropbox",
            "Create an access token in the Dropbox App Console (your app, Settings, " +
                "Generated access token). It stops working after about 4 hours."
        )
    )

    private const val PREFS = "vix_agent"
    private const val K_CONNECTED = "connected"
    private const val STALE_MS = 10 * 60 * 1000L

    @Volatile
    private var lastRefresh = 0L

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    // ---------- what is connected (names only, no tokens) ----------

    fun hasConnections(context: Context): Boolean =
        !prefs(context).getStringSet(K_CONNECTED, emptySet()).isNullOrEmpty()

    fun clearCache(context: Context) {
        setCache(context, emptySet())
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun setCache(context: Context, services: Set<String>) {
        prefs(context).edit().putStringSet(K_CONNECTED, HashSet(services)).apply()
    }

    /**
     * Updates the list of connected services if it has not been checked
     * for 10 minutes. Blocking: call it from a background thread only.
     */
    fun refreshIfStale(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastRefresh < STALE_MS) return
        lastRefresh = now

        try {
            val r = post(context, JSONObject().put("action", "list"), 20000)
            if (r.status in 200..299) {
                setCache(context, parseModes(r.json).keys)
            }
        } catch (_: Exception) {
            // Keep the old list. The chat still works without the tools.
        }
    }

    // ---------- actions for the profile page ----------

    fun list(context: Context, callback: (Out) -> Unit) {
        val app = context.applicationContext
        run(callback) { fetchList(app) }
    }

    fun save(
        context: Context,
        service: String,
        token: String,
        write: Boolean,
        callback: (Out) -> Unit
    ) {
        val app = context.applicationContext
        run(callback) {
            val r = post(
                app,
                JSONObject()
                    .put("action", "save")
                    .put("service", service)
                    .put("token", token)
                    .put("mode", if (write) "write" else "read"),
                45000
            )
            if (r.status in 200..299) fetchList(app) else Out(false, errorText(r.json))
        }
    }

    /**
     * Adds (or updates) a custom connector. The server looks for it at the
     * address, connects, and only saves it if it answers like an MCP connector.
     * The token is optional.
     */
    fun saveCustom(
        context: Context,
        name: String,
        url: String,
        token: String,
        write: Boolean,
        callback: (Out) -> Unit
    ) {
        val app = context.applicationContext
        run(callback) {
            val r = post(
                app,
                JSONObject()
                    .put("action", "save_custom")
                    .put("name", name)
                    .put("url", url)
                    .put("token", token)
                    .put("mode", if (write) "write" else "read"),
                60000
            )
            if (r.status in 200..299) fetchList(app) else Out(false, errorText(r.json))
        }
    }

    fun setMode(
        context: Context,
        service: String,
        write: Boolean,
        callback: (Out) -> Unit
    ) {
        val app = context.applicationContext
        run(callback) {
            val r = post(
                app,
                JSONObject()
                    .put("action", "set_mode")
                    .put("service", service)
                    .put("mode", if (write) "write" else "read"),
                30000
            )
            if (r.status in 200..299) fetchList(app) else Out(false, errorText(r.json))
        }
    }

    fun remove(context: Context, service: String, callback: (Out) -> Unit) {
        val app = context.applicationContext
        run(callback) {
            val r = post(
                app,
                JSONObject().put("action", "remove").put("service", service),
                30000
            )
            if (r.status in 200..299) fetchList(app) else Out(false, errorText(r.json))
        }
    }

    // ---------- network ----------

    /**
     * Sends one request to vix-agent with the logged-in user's session.
     * Never throws for a bad HTTP status: the answer always has an "error"
     * field then. Blocking: call it from a background thread only.
     */
    fun post(context: Context, body: JSONObject, readMs: Int): Resp {
        val token = SupabaseAuth.accessToken(context.applicationContext)
            ?: throw Exception("Please log in again to use VIX AI.")

        val c = URL(SupabaseConfig.URL + "/functions/v1/vix-agent")
            .openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 20000
            c.readTimeout = readMs
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("apikey", SupabaseConfig.PUBLISHABLE_KEY)
            c.setRequestProperty("Authorization", "Bearer $token")
            c.outputStream.use {
                it.write(body.toString().toByteArray(StandardCharsets.UTF_8))
            }

            val status = c.responseCode
            val stream = if (status in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = try {
                if (text.isBlank()) JSONObject() else JSONObject(text)
            } catch (e: Exception) {
                JSONObject()
            }
            if (status !in 200..299 && !json.has("error")) {
                json.put("error", "http_$status")
            }
            return Resp(status, json)
        } finally {
            c.disconnect()
        }
    }

    private fun fetchList(app: Context): Out {
        val r = post(app, JSONObject().put("action", "list"), 30000)
        if (r.status !in 200..299) return Out(false, errorText(r.json))

        val modes = parseModes(r.json)
        setCache(app, modes.keys)
        lastRefresh = System.currentTimeMillis()
        return Out(true, "", modes, parseCustom(r.json))
    }

    private fun parseCustom(json: JSONObject): List<Custom> {
        val out = ArrayList<Custom>()
        val arr = json.optJSONArray("connections") ?: return out
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val service = item.optString("service", "")
            if (!service.startsWith("mcp:")) continue
            out.add(
                Custom(
                    service,
                    item.optString("label", "").ifBlank { service.removePrefix("mcp:") },
                    item.optString("host", ""),
                    item.optString("mode", "read")
                )
            )
        }
        return out
    }

    private fun parseModes(json: JSONObject): Map<String, String> {
        val out = HashMap<String, String>()
        val arr = json.optJSONArray("connections") ?: return out
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val service = item.optString("service", "")
            if (service.isNotBlank()) out[service] = item.optString("mode", "read")
        }
        return out
    }

    private fun errorText(json: JSONObject): String {
        val code = json.optString("error", "")
        return when (code) {
            "not_configured" -> "Connections aren't set up on the server yet."
            "token_invalid" ->
                "That token was refused. Check it and try again. " +
                    json.optString("detail", "").take(120)
            "not_signed_in", "http_401" -> "Please log in again."
            "bad_request" -> "Something is missing. Check the token and try again."
            "connections_failed" -> "The server couldn't save your connections. Try again."
            "connector_failed" ->
                "I couldn't connect to that connector. " +
                    json.optString("detail", "").take(160)
            "too_many_connectors" -> "You can add up to 5 custom connectors."
            else -> "Something went wrong ($code)."
        }
    }

    private fun run(callback: (Out) -> Unit, work: () -> Out) {
        executor.execute {
            val out = try {
                work()
            } catch (e: UnknownHostException) {
                Out(false, "No internet connection.")
            } catch (e: SocketTimeoutException) {
                Out(false, "The server took too long. Please try again.")
            } catch (e: Exception) {
                Out(false, e.message ?: "Something went wrong.")
            }
            main.post { callback(out) }
        }
    }
}
