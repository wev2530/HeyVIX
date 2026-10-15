package com.vix.heyvix

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Answers "who is your developer / how do I contact the developer"
 * questions locally, in AI mode and in phone assistant mode alike.
 * No AI call and no internet are needed for this answer.
 */
object DeveloperInfo {

    const val WHATSAPP_LINK = "https://wa.me/2348126844814"

    private val strongPhrases = listOf(
        " your developer", " your creator", " your maker",
        " your programmer", " your designer", " your owner",
        " who made you", " who created you", " who built you",
        " who developed you", " who designed you", " who programmed you",
        " who owns you", " who is behind you",
        " who made vix", " who created vix", " who built vix",
        " who developed vix", " who designed vix",
        " who made vixie", " who created vixie", " who built vixie",
        " who developed vixie", " who designed vixie"
    )

    private val roleWords = listOf(
        "developer", "creator", "programmer", "designer"
    )

    private val contextPhrases = listOf(
        " vix ", " vixie ", "this app", "the app",
        "contact the developer", "contact developer",
        "developer contact", "reach the developer",
        "who is the developer", "whos the developer"
    )

    /** True when the text is a question about the developer. */
    fun isAbout(raw: String): Boolean {
        val t = " " + raw
            .lowercase()
            .replace("'", "")
            .replace("’", "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim() + " "

        if (strongPhrases.any { t.contains(it) }) return true

        val hasRole = roleWords.any { t.contains(it) }
        return hasRole && contextPhrases.any { t.contains(it) }
    }

    /** The formatted message shown on screen. */
    fun message(): String =
        "VIX AI developer details\n\n" +
            "Role: Designer and developer of VIX AI\n" +
            "Focus: Upcoming apps and AI\n" +
            "Contact: WhatsApp\n" +
            "$WHATSAPP_LINK\n\n" +
            "Tap the button below to message the developer."

    /** A shorter version for reading aloud (no link read out). */
    fun spokenMessage(): String =
        "I was designed and built by my developer, who works on upcoming " +
            "apps and AI. You can message the developer on WhatsApp. " +
            "The button is on your screen."

    /** Opens the WhatsApp chat. Returns false if nothing could open it. */
    fun openWhatsApp(context: Context): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(WHATSAPP_LINK))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}