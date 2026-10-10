package com.vix.heyvix

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Wraps Android TextToSpeech. All callbacks are delivered on the main thread. */
class VixSpeechOutput(
    context: Context,
    private val onSpeakingChanged: (Boolean) -> Unit,
    private val onProblem: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var pending: String? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
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

        ready = true
        pending?.let { speak(it) }
        pending = null
    }

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
