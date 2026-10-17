package com.vix.heyvix

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks GitHub for the newest release (tag v1.0.N, where N is the build number)
 * and compares N with the version number of the installed app.
 */
object UpdateChecker {

    const val APK_URL =
        "https://github.com/wev2530/HeyVIX/releases/latest/download/VIX.apk"

    private const val API_URL =
        "https://api.github.com/repos/wev2530/HeyVIX/releases/latest"

    data class Update(val latest: Long, val name: String)

    /** Installed build number (the versionCode set by build.gradle.kts). */
    fun installedVersion(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    /**
     * Runs in the background. The callback runs on the main thread with the
     * newer release, or null when the app is up to date or the check failed
     * (for example no internet). A failed check never blocks the app.
     */
    fun check(context: Context, callback: (Update?) -> Unit) {
        val appContext = context.applicationContext
        val mainHandler = Handler(Looper.getMainLooper())

        Thread {
            val result = try {
                fetchNewer(appContext)
            } catch (e: Exception) {
                null
            }
            mainHandler.post { callback(result) }
        }.start()
    }

    private fun fetchNewer(context: Context): Update? {
        val connection = URL(API_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "HeyVIX-Android")

            if (connection.responseCode !in 200..299) return null

            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)

            val tag = json.optString("tag_name", "")
            val latest = tag.substringAfterLast('.').toLongOrNull() ?: return null

            if (latest <= installedVersion(context)) return null

            val name = json.optString("name", "").ifBlank { tag }
            return Update(latest, name)
        } finally {
            connection.disconnect()
        }
    }
}