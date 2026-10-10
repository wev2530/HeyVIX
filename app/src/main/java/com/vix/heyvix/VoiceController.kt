package com.vix.heyvix

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Wraps Android SpeechRecognizer. Call from the main thread only. */
class VoiceController(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onListening()
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
    }

    private var recognizer: SpeechRecognizer? = null

    fun isAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (!isAvailable()) {
            listener.onError(
                "Speech recognition isn't available on this device. You can type instead."
            )
            return
        }

        stop()

        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r

        r.setRecognitionListener(object : RecognitionListener {

            override fun onReadyForSpeech(params: Bundle?) {
                if (recognizer === r) listener.onListening()
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                if (recognizer !== r) return

                val t = firstResult(partialResults)
                if (t.isNotBlank()) listener.onPartial(t)
            }

            override fun onResults(results: Bundle?) {
                if (recognizer !== r) return

                val t = firstResult(results)

                if (t.isBlank()) {
                    listener.onError(
                        "I didn't catch that. Tap the mic and try again."
                    )
                } else {
                    listener.onFinal(t)
                }
            }

            override fun onError(error: Int) {
                if (recognizer !== r) return
                listener.onError(messageFor(error))
            }
        })

        val intent = Intent(
            RecognizerIntent.ACTION_RECOGNIZE_SPEECH
        ).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(
                RecognizerIntent.EXTRA_CALLING_PACKAGE,
                context.packageName
            )
        }

        r.startListening(intent)
    }

    /** Cancels listening and frees the recognizer. Safe to call any time. */
    fun stop() {
        val r = recognizer ?: return
        recognizer = null

        try {
            r.cancel()
            r.destroy()
        } catch (e: Exception) {
            // Ignore: recognizer was already gone.
        }
    }

    fun destroy() = stop()

    private fun firstResult(b: Bundle?): String =
        b?.getStringArrayList(
            SpeechRecognizer.RESULTS_RECOGNITION
        )?.firstOrNull() ?: ""

    private fun messageFor(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            "I didn't catch that. Tap the mic and try again."

        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Speech recognition couldn't reach the network. Check your connection, or type instead."

        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "Microphone permission is missing. Allow it in phone Settings, or type instead."

        SpeechRecognizer.ERROR_AUDIO ->
            "There was a problem with the microphone."

        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            "The speech service is busy. Try again in a moment."

        SpeechRecognizer.ERROR_SERVER ->
            "The speech service had a problem. Try again."

        else -> "Speech recognition failed (code $code). Try again."
    }
}
