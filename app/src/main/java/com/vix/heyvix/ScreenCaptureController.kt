package com.vix.heyvix

/**
 * Recognizes screenshot and screen-recording commands.
 * MainActivity asks this before sending a message to VIX AI.
 */
object ScreenCaptureController {

    enum class Action {
        SCREENSHOT,
        START_RECORDING,
        STOP_RECORDING
    }

    /** Returns null when the text is not a screen-capture command. */
    fun parse(rawText: String): Action? {
        val text = rawText.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .replace(Regex("^(hey\\s+)?vix\\s*"), "")
            .replace(Regex("^please\\s+"), "")
            .trim()

        // Order matters: "stop screen recording" also contains "screen record".
        if (containsAny(
                text,
                "stop recording",
                "stop screen recording",
                "stop screen record",
                "stop the recording",
                "stop the screen recording",
                "end recording",
                "end screen recording",
                "finish recording"
            )
        ) {
            return Action.STOP_RECORDING
        }

        if (containsAny(
                text,
                "screen record",
                "record my screen",
                "record the screen",
                "record screen",
                "start recording",
                "start screen recording"
            )
        ) {
            return Action.START_RECORDING
        }

        if (containsAny(
                text,
                "screenshot",
                "screen shot",
                "capture my screen",
                "capture the screen",
                "capture screen"
            )
        ) {
            return Action.SCREENSHOT
        }

        return null
    }

    private fun containsAny(text: String, vararg phrases: String): Boolean =
        phrases.any { text.contains(it) }
}
