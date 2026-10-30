package com.vix.heyvix

/**
 * Answers "list commands" style questions locally.
 * No AI call or internet connection is needed.
 *
 * IMPORTANT: when a command is added to or removed from the app,
 * update message() below so this list stays accurate.
 */
object CommandList {

    private val exactPhrases = setOf(
        "help",
        "commands",
        "command list",
        "list commands",
        "show commands",
        "what can you do",
        "what can you do for me",
        "what can vix do"
    )

    private val phrases = listOf(
        "list commands",
        "list all commands",
        "list your commands",
        "list the commands",
        "list all your commands",
        "list all the commands",
        "list of commands",
        "list of your commands",
        "show commands",
        "show all commands",
        "show me commands",
        "show me all commands",
        "show me your commands",
        "show your commands",
        "give me the commands",
        "give me your commands",
        "tell me your commands",
        "tell me the commands",
        "what are your commands",
        "what are the commands",
        "what are all your commands",
        "what commands do you",
        "what commands can you",
        "what commands are",
        "what commands does vix",
        "which commands",
        "commands do you have",
        "commands you have",
        "commands you support",
        "commands can i use",
        "commands i can use",
        "commands can i say",
        "vixs commands",
        "available commands",
        "supported commands",
        "working commands",
        "command list",
        "what can you do",
        "what can vix do",
        "what are you able to do",
        "what are you capable of",
        "your capabilities",
        "your features",
        "what do you support",
        "how do i use you",
        "how do i use vix",
        "what can i say",
        "what should i say"
    )

    // If the question is clearly about something else, let the AI answer it.
    private val otherTopics = listOf(
        "linux", "git", "docker", "terminal", "python", "bash",
        "windows", "cmd", "npm", "sql", "kotlin", "adb", "java"
    )

    /** True when the text is asking for the list of VIX commands. */
    fun isAsking(raw: String): Boolean {
        val t = normalize(raw)
        if (t.isBlank()) return false
        if (t in exactPhrases) return true

        val padded = " $t "
        if (otherTopics.any { padded.contains(" $it ") }) return false

        val wordCount = t.split(" ").size
        return wordCount <= 8 && phrases.any { t.contains(it) }
    }

    private fun normalize(raw: String): String {
        var t = raw.lowercase()
            .replace("'", "")
            .replace("’", "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        t = t.replace(Regex("^(hey|hi|hello)\\s+vix\\b\\s*"), "")
        t = t.replace(Regex("^vix\\b\\s*"), "")
        t = t.replace(Regex("^please\\s+"), "")
        t = t.replace(Regex("\\s+please$"), "")
        return t.trim()
    }

    /** The full list shown on screen. */
    fun message(): String = """
VIX commands that work right now

PHONE COMMANDS (VIX AI on or off)
• Turn on flashlight / Turn off flashlight (also: torch on, torch off)
• Open camera (also: take a picture, take a photo, snap a picture)
• Open [app name] (also: launch or start), for example: Open WhatsApp
• Take a screenshot (also: capture my screen)
• Start screen recording (also: record my screen)
• Stop screen recording (also: stop recording)

LIVE WEB INFO (needs a Tavily key)
• Search for [topic] (also: look up, google)
• Latest news on [topic]
• Weather in [city]
• Score of [match], price of [item], exchange rate

ABOUT VIX
• Who made you / Who is your developer: shows the developer card
• List commands / What can you do: shows this list

CHAT TOOLS
• /key [API key]: saves your AI key on this phone, or /key openai [key] to pick a provider (main app chat only)
• /tavilykey [key]: saves your web search key (main app chat only)
• Swipe a message right to reply to it (main app chat only)
• Say "Hey VIX" to wake me, when it is turned on in Profile

Anything else is sent to VIX AI when it is on. When VIX AI is off, I ask you first before forwarding it.
""".trim()

    /** Short version read aloud, so VIX does not read the whole list. */
    fun spokenMessage(): String =
        "I can control your flashlight and camera, open apps, take " +
            "screenshots, record your screen, search the web, and answer " +
            "your questions. The full list of commands is on your screen."
}