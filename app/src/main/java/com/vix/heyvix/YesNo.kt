package com.vix.heyvix

/**
 * Understands a short yes / no answer, typed or spoken.
 * Returns true for yes, false for no, and null if it is neither
 * (then the text is treated as a normal new command).
 */
object YesNo {

    private val yes = setOf(
        "yes", "yeah", "yep", "yup", "sure", "ok", "okay",
        "go ahead", "do it", "send it", "forward it",
        "yes please", "please"
    )

    private val no = setOf(
        "no", "nope", "nah", "cancel", "stop", "never mind",
        "nevermind", "don't", "dont", "no thanks"
    )

    fun parse(text: String): Boolean? {
        val t = text
            .lowercase()
            .replace(Regex("[^a-z' ]"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

        return when {
            t in yes -> true
            t in no -> false
            else -> null
        }
    }
}