package com.vix.heyvix

import android.content.Context
import android.content.SharedPreferences
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
 * Login with Supabase Auth over its REST API (no SDK).
 * The session is kept in a private preferences file on this phone.
 */
object SupabaseAuth {

    data class Result(
        val successful: Boolean,
        val message: String,
        val needsConfirmation: Boolean = false
    )

    private class HttpResult(val status: Int, val body: JSONObject)

    private const val PREFS = "vix_account"
    private const val K_ACCESS = "access_token"
    private const val K_REFRESH = "refresh_token"
    private const val K_EXPIRES = "expires_at"
    private const val K_USER_ID = "user_id"
    private const val K_EMAIL = "email"

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- session info ----------

    fun isLoggedIn(context: Context): Boolean =
        !prefs(context).getString(K_REFRESH, null).isNullOrBlank()

    fun email(context: Context): String =
        prefs(context).getString(K_EMAIL, "") ?: ""

    fun userId(context: Context): String =
        prefs(context).getString(K_USER_ID, "") ?: ""

    /**
     * A valid access token, refreshed if it is about to expire.
     * Blocking: call it from a background thread only.
     * Returns null when logged out or when the refresh failed.
     */
    @Synchronized
    fun accessToken(context: Context): String? {
        val p = prefs(context)
        val token = p.getString(K_ACCESS, null) ?: return null
        val expiresAt = p.getLong(K_EXPIRES, 0L)
        val now = System.currentTimeMillis() / 1000
        if (now < expiresAt - 60) return token

        val refresh = p.getString(K_REFRESH, null) ?: return null
        return try {
            val r = request(
                "POST",
                "/auth/v1/token?grant_type=refresh_token",
                JSONObject().put("refresh_token", refresh),
                null
            )
            if (r.status in 200..299 && saveSession(context, r.body)) {
                p.getString(K_ACCESS, null)
            } else {
                // The refresh token is no longer valid: the user must log in again.
                if (r.status == 400 || r.status == 401 || r.status == 403) {
                    p.edit().clear().apply()
                }
                null
            }
        } catch (e: Exception) {
            null // network problem: keep the session and try again later
        }
    }

    // ---------- actions ----------

    fun signUp(context: Context, email: String, password: String, callback: (Result) -> Unit) {
        val app = context.applicationContext
        runAsync(callback) {
            val r = request(
                "POST",
                "/auth/v1/signup",
                JSONObject().put("email", email.trim()).put("password", password),
                null
            )
            if (r.status !in 200..299) {
                return@runAsync Result(false, errorText(r))
            }
            if (saveSession(app, r.body)) {
                Result(true, "Account created.")
            } else {
                // Email confirmation is on: the user must confirm by email first.
                Result(true, "Account created. Check your email to confirm it.", true)
            }
        }
    }

    fun signIn(context: Context, email: String, password: String, callback: (Result) -> Unit) {
        val app = context.applicationContext
        runAsync(callback) {
            val r = request(
                "POST",
                "/auth/v1/token?grant_type=password",
                JSONObject().put("email", email.trim()).put("password", password),
                null
            )
            if (r.status !in 200..299) {
                return@runAsync Result(false, errorText(r))
            }
            if (saveSession(app, r.body)) {
                Result(true, "Logged in.")
            } else {
                Result(false, "Login failed. Please try again.")
            }
        }
    }

    fun sendReset(email: String, callback: (Result) -> Unit) {
        runAsync(callback) {
            val r = request(
                "POST",
                "/auth/v1/recover",
                JSONObject().put("email", email.trim()),
                null
            )
            if (r.status in 200..299) {
                Result(true, "If that email has an account, a reset link is on its way.")
            } else {
                Result(false, errorText(r))
            }
        }
    }

    /** Logs out on this phone right away, then tells Supabase (best effort). */
    fun signOut(context: Context, done: () -> Unit) {
        val app = context.applicationContext
        val token = prefs(app).getString(K_ACCESS, null)
        prefs(app).edit().clear().apply()
        executor.execute {
            if (token != null) {
                try {
                    request("POST", "/auth/v1/logout", JSONObject(), token)
                } catch (_: Exception) {
                }
            }
            main.post { done() }
        }
    }

    // ---------- helpers ----------

    private fun runAsync(callback: (Result) -> Unit, work: () -> Result) {
        executor.execute {
            val result = try {
                work()
            } catch (e: UnknownHostException) {
                Result(false, "No internet connection.")
            } catch (e: SocketTimeoutException) {
                Result(false, "The server took too long. Please try again.")
            } catch (e: Exception) {
                Result(false, e.message ?: "Something went wrong.")
            }
            main.post { callback(result) }
        }
    }

    private fun saveSession(context: Context, json: JSONObject): Boolean {
        val access = json.optString("access_token", "")
        val refresh = json.optString("refresh_token", "")
        if (access.isBlank() || refresh.isBlank()) return false

        val now = System.currentTimeMillis() / 1000
        val given = json.optLong("expires_at", 0L)
        val expiresAt = if (given > 0) given else now + json.optLong("expires_in", 3600L)

        val p = prefs(context)
        val user = json.optJSONObject("user")
        val userId = user?.optString("id", "")?.takeIf { it.isNotBlank() }
            ?: p.getString(K_USER_ID, "") ?: ""
        val email = user?.optString("email", "")?.takeIf { it.isNotBlank() }
            ?: p.getString(K_EMAIL, "") ?: ""

        p.edit()
            .putString(K_ACCESS, access)
            .putString(K_REFRESH, refresh)
            .putLong(K_EXPIRES, expiresAt)
            .putString(K_USER_ID, userId)
            .putString(K_EMAIL, email)
            .apply()
        return true
    }

    private fun request(
        method: String,
        path: String,
        body: JSONObject?,
        bearer: String?
    ): HttpResult {
        val c = URL(SupabaseConfig.URL + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 20000
            c.readTimeout = 30000
            c.setRequestProperty("apikey", SupabaseConfig.PUBLISHABLE_KEY)
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json")
            if (bearer != null) {
                c.setRequestProperty("Authorization", "Bearer $bearer")
            }
            if (body != null) {
                c.doOutput = true
                c.outputStream.use {
                    it.write(body.toString().toByteArray(StandardCharsets.UTF_8))
                }
            }

            val status = c.responseCode
            val stream = if (status in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = try {
                if (text.isBlank()) JSONObject() else JSONObject(text)
            } catch (e: Exception) {
                JSONObject()
            }
            return HttpResult(status, json)
        } finally {
            c.disconnect()
        }
    }

    private fun errorText(r: HttpResult): String {
        val code = r.body.optString("error_code", r.body.optString("code", ""))
        val msg = r.body.optString(
            "msg",
            r.body.optString("error_description", r.body.optString("message", ""))
        )
        return when {
            code == "invalid_credentials" -> "Wrong email or password."
            code == "email_not_confirmed" ->
                "Please confirm your email first. Check your inbox."
            code == "user_already_exists" || code == "email_exists" ->
                "An account with this email already exists. Try logging in."
            code == "weak_password" ->
                "That password is too weak. Try a longer one."
            code == "signup_disabled" -> "Sign ups are turned off right now."
            code == "over_email_send_rate_limit" || r.status == 429 ->
                "Too many tries. Wait a minute and try again."
            msg.isNotBlank() -> msg
            else -> "Something went wrong (HTTP ${r.status})."
        }
    }
}