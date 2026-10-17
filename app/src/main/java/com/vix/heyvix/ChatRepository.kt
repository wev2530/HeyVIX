package com.vix.heyvix

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/** Two-letter avatar text made from an email, like "EG". */
fun vixInitials(email: String): String {
    val local = email.substringBefore("@")
    val parts = local.split(Regex("[^A-Za-z]+")).filter { it.isNotEmpty() }
    val text = when {
        parts.size >= 2 -> "${parts[0][0]}${parts[1][0]}"
        parts.size == 1 -> parts[0].take(2)
        else -> "V"
    }
    return text.uppercase()
}

/**
 * Saves and loads the chat history in Supabase (tables chats and messages).
 * Row Level Security makes sure each user only sees their own rows.
 * All callbacks run on the main thread.
 */
class ChatRepository(context: Context) {

    data class Chat(val id: String, val title: String)
    data class Msg(val role: String, val content: String, val quote: String?)

    /** value = result when it worked; error = text to show when it did not. */
    class Outcome<T>(
        val value: T?,
        val error: String? = null,
        val authExpired: Boolean = false
    )

    private class Reply(
        val ok: Boolean,
        val body: String,
        val error: String?,
        val authExpired: Boolean
    )

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    // ---------- chats ----------

    fun listChats(callback: (Outcome<List<Chat>>) -> Unit) {
        runAsync(callback) {
            val r = request(
                "GET",
                "chats?select=id,title&order=updated_at.desc&limit=200",
                null,
                null
            )
            if (!r.ok) {
                return@runAsync Outcome<List<Chat>>(null, r.error, r.authExpired)
            }
            val array = JSONArray(r.body)
            val list = ArrayList<Chat>()
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                list.add(Chat(o.getString("id"), o.optString("title", "New chat")))
            }
            Outcome<List<Chat>>(list)
        }
    }

    /** Creates an empty chat and returns its id. */
    fun createChat(title: String, callback: (Outcome<String>) -> Unit) {
        runAsync(callback) {
            val r = request(
                "POST",
                "chats",
                JSONObject().put("title", title).toString(),
                "return=representation"
            )
            if (!r.ok) {
                return@runAsync Outcome<String>(null, r.error, r.authExpired)
            }
            val id = JSONArray(r.body).getJSONObject(0).getString("id")
            Outcome<String>(id)
        }
    }

    fun renameChat(chatId: String, title: String, callback: (Outcome<Boolean>) -> Unit) {
        runAsync(callback) {
            // An upsert changes only the title of the existing row.
            val r = request(
                "POST",
                "chats?on_conflict=id",
                JSONObject().put("id", chatId).put("title", title).toString(),
                "resolution=merge-duplicates,return=minimal"
            )
            if (r.ok) Outcome<Boolean>(true) else Outcome<Boolean>(null, r.error, r.authExpired)
        }
    }

    fun deleteChat(chatId: String, callback: (Outcome<Boolean>) -> Unit = {}) {
        runAsync(callback) {
            val r = request("DELETE", "chats?id=eq.$chatId", null, null)
            if (r.ok) Outcome<Boolean>(true) else Outcome<Boolean>(null, r.error, r.authExpired)
        }
    }

    fun deleteAllChats(callback: (Outcome<Boolean>) -> Unit) {
        val userId = SupabaseAuth.userId(app)
        runAsync(callback) {
            if (userId.isBlank()) {
                return@runAsync Outcome<Boolean>(null, "Please log in again.", true)
            }
            val r = request("DELETE", "chats?user_id=eq.$userId", null, null)
            if (r.ok) Outcome<Boolean>(true) else Outcome<Boolean>(null, r.error, r.authExpired)
        }
    }

    // ---------- messages ----------

    fun loadMessages(chatId: String, callback: (Outcome<List<Msg>>) -> Unit) {
        runAsync(callback) {
            val r = request(
                "GET",
                "messages?chat_id=eq.$chatId&select=role,content,quote&order=created_at.asc&limit=1000",
                null,
                null
            )
            if (!r.ok) {
                return@runAsync Outcome<List<Msg>>(null, r.error, r.authExpired)
            }
            val array = JSONArray(r.body)
            val list = ArrayList<Msg>()
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val quote = if (o.isNull("quote")) null else o.getString("quote")
                list.add(Msg(o.getString("role"), o.getString("content"), quote))
            }
            Outcome<List<Msg>>(list)
        }
    }

    /** Saves one message. Fire and forget; messages are saved in the order sent. */
    fun addMessage(chatId: String, role: String, content: String, quote: String?) {
        executor.execute {
            try {
                val body = JSONObject()
                    .put("chat_id", chatId)
                    .put("role", role)
                    .put("content", content)
                if (!quote.isNullOrBlank()) {
                    body.put("quote", quote)
                }
                request("POST", "messages", body.toString(), "return=minimal")
            } catch (_: Exception) {
                // A failed save must never break the chat.
            }
        }
    }

    fun close() {
        executor.shutdown()
    }

    // ---------- helpers ----------

    private fun <T> runAsync(callback: (Outcome<T>) -> Unit, work: () -> Outcome<T>) {
        executor.execute {
            val outcome = try {
                work()
            } catch (e: UnknownHostException) {
                Outcome<T>(null, "No internet connection.")
            } catch (e: Exception) {
                Outcome<T>(null, e.message ?: "Something went wrong.")
            }
            main.post { callback(outcome) }
        }
    }

    private fun request(
        method: String,
        path: String,
        body: String?,
        prefer: String?
    ): Reply {
        val token = SupabaseAuth.accessToken(app)
            ?: return Reply(false, "", "Please log in again.", true)

        val c = URL(SupabaseConfig.URL + "/rest/v1/" + path)
            .openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 20000
            c.readTimeout = 30000
            c.setRequestProperty("apikey", SupabaseConfig.PUBLISHABLE_KEY)
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json")
            if (prefer != null) {
                c.setRequestProperty("Prefer", prefer)
            }
            if (body != null) {
                c.doOutput = true
                c.outputStream.use {
                    it.write(body.toByteArray(StandardCharsets.UTF_8))
                }
            }

            val status = c.responseCode
            val stream = if (status in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (status in 200..299) {
                return Reply(true, text, null, false)
            }

            val message = try {
                JSONObject(text).optString("message", "")
            } catch (e: Exception) {
                ""
            }
            return Reply(
                false,
                text,
                if (message.isNotBlank()) message else "Server error (HTTP $status).",
                status == 401
            )
        } finally {
            c.disconnect()
        }
    }
}