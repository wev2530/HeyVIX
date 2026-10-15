package com.vix.heyvix

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Answers questions about the VIX AI developer locally.
 * No AI call or internet connection is needed.
 */
object DeveloperInfo {

    const val NAME = "GCV Designs"
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
        " who developed vixie", " who designed vixie",
        " gcv designs"
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

    /** Short line shown above the developer card. */
    fun shortMessage(): String =
        "VIX AI was designed and developed by $NAME."

    /** Text version including the contact link. */
    fun message(): String =
        "VIX AI developer\n\n" +
            "$NAME\n" +
            "Designer and developer of VIX AI\n" +
            "Focus: upcoming apps and AI\n" +
            "WhatsApp: $WHATSAPP_LINK"

    /** Text used for spoken responses. */
    fun spokenMessage(): String =
        "VIX AI was designed and built by G C V Designs, who works on " +
            "upcoming apps and A I. You can message the developer on " +
            "WhatsApp. The contact card is on your screen."

    /** Opens WhatsApp. Returns false if the action fails. */
    fun openWhatsApp(context: Context): Boolean {
        return try {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(WHATSAPP_LINK)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}