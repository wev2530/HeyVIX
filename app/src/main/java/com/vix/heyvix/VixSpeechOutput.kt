package com.vix.heyvix

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Wraps Android TextToSpeech. All callbacks are delivered on the main thread.
 * The chosen voice and speed are saved on the phone and used everywhere
 * (main app and overlay).
 */
class VixSpeechOutput(
    context: Context,
    private val onSpeakingChanged: (Boolean) -> Unit,
    private val onProblem: (String) -> Unit
) {
    data class VoiceOption(
        val name: String,
        val label: String
    )

    companion object {
        private const val PREFS = "vix_voice"
        private const val KEY_VOICE = "voice_name"
        private const val KEY_RATE = "rate"

        private const val RATE_SLOW = 0.8f
        private const val RATE_NORMAL = 1.0f
        private const val RATE_FAST = 1.25f
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var pending: String? = null

    init {
        tts = TextToSpeech(appContext) { status ->
            main.post { onInit(status) }
        }
    }

    private fun onInit(status: Int) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            failed = true
            onProblem("Text-to-speech isn't available on this device, so I can only reply in text.")
            return
        }

        var result = engine.setLanguage(Locale.getDefault())
        if (result == TextToSpeech.LANG_MISSING_DATA ||
            result == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            result = engine.setLanguage(Locale.US)
        }

        if (result == TextToSpeech.LANG_MISSING_DATA ||
            result == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            failed = true
            onProblem("No voice is installed for text-to-speech, so I can only reply in text.")
            return
        }

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                main.post { onSpeakingChanged(true) }
            }

            override fun onDone(utteranceId: String?) {
                main.post { onSpeakingChanged(false) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post { onSpeakingChanged(false) }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                main.post { onSpeakingChanged(false) }
            }
        })

        applySavedSettings(engine)

        ready = true
        pending?.let { speak(it) }
        pending = null
    }

    /** Applies the voice and speed the user saved earlier. */
    private fun applySavedSettings(engine: TextToSpeech) {
        engine.setSpeechRate(currentRate())

        val savedName = prefs.getString(KEY_VOICE, null)
        if (!savedName.isNullOrBlank()) {
            try {
                val voice = engine.voices?.firstOrNull { it.name == savedName }
                if (voice != null) {
                    engine.setVoice(voice)
                }
            } catch (_: Exception) {
                // Keep the default voice if the saved one is gone.
            }
        }
    }

    private fun currentRate(): Float =
        prefs.getFloat(KEY_RATE, RATE_NORMAL)

    // ---------------- Voice picker ----------------

    /** Voices the user can pick from (empty until text-to-speech is ready). */
    fun voiceOptions(): List<VoiceOption> {
        val engine = tts
        if (!ready || engine == null) return emptyList()

        val all = try {
            engine.voices?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        val installed = all.filter { v ->
            !v.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        }

        val language = Locale.getDefault().language
        val sameLanguage = installed.filter { it.locale.language == language }
        val chosen = if (sameLanguage.isNotEmpty()) sameLanguage else installed

        val sorted = chosen.sortedWith(
            compareBy({ it.locale.toString() }, { it.name })
        )

        val counters = HashMap<String, Int>()
        return sorted.map { v ->
            val place = v.locale.getDisplayName(Locale.getDefault())
            val number = (counters[place] ?: 0) + 1
            counters[place] = number
            val online = if (v.isNetworkConnectionRequired) " · online" else ""
            VoiceOption(v.name, "$place · Voice $number$online")
        }
    }

    fun currentVoiceName(): String =
        try {
            tts?.voice?.name.orEmpty()
        } catch (_: Exception) {
            ""
        }

    /** Switches to the voice, saves it, and speaks a short sample. */
    fun chooseVoice(name: String) {
        val engine = tts ?: return
        if (!ready) return

        try {
            val voice = engine.voices?.firstOrNull { it.name == name } ?: return
            engine.setVoice(voice)
            prefs.edit().putString(KEY_VOICE, name).apply()
            speak("Hi, this is my new voice.")
        } catch (_: Exception) {
            onProblem("I couldn't switch to that voice.")
        }
    }

    // ---------------- Speed ----------------

    fun speedLabel(): String = when {
        currentRate() < 0.9f -> "Slow"
        currentRate() > 1.1f -> "Fast"
        else -> "Normal"
    }

    /** Slow -> Normal -> Fast -> Slow. Returns the new label. */
    fun cycleSpeed(): String {
        val next = when (speedLabel()) {
            "Slow" -> RATE_NORMAL
            "Normal" -> RATE_FAST
            else -> RATE_SLOW
        }

        prefs.edit().putFloat(KEY_RATE, next).apply()
        tts?.setSpeechRate(next)
        speak("This is my speed.")
        return speedLabel()
    }

    // ---------------- Speaking ----------------

    fun speak(text: String) {
        if (failed) {
            onSpeakingChanged(false)
            return
        }

        if (!ready) {
            pending = text
            return
        }

        val code = tts?.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "vix-utterance"
        )

        if (code != TextToSpeech.SUCCESS) {
            onSpeakingChanged(false)
            onProblem("I couldn't speak that reply out loud.")
        }
    }

    fun stop() {
        pending = null
        tts?.stop()
    }

    fun shutdown() {
        pending = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}