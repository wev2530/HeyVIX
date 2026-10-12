package com.vix.heyvix

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * These classes let you pick VIX as your phone's "Digital assistant app".
 * After that, the phone's assistant trigger (for example holding the
 * power button, if your phone is set that way) opens VIX and starts
 * listening.
 */

/** Android needs this service to list VIX as an assistant. */
class VixVoiceInteractionService : VoiceInteractionService()

/** Creates a session each time the assistant trigger is used. */
class VixVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        VixVoiceInteractionSession(this)
}

/** Opens VIX in listening mode when the assistant is triggered. */
class VixVoiceInteractionSession(context: Context) :
    VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)

        val intent = Intent(context, MainActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            .putExtra(WakeWordService.EXTRA_WAKE, true)

        try {
            startAssistantActivity(intent)
        } catch (_: Exception) {
            try {
                context.startActivity(intent)
            } catch (_: Exception) {
            }
        }
    }
}

/**
 * Android's assistant setup asks for a recognition service.
 * VIX does its own listening, so this one does nothing and is not
 * offered as a speech recognizer to other apps.
 */
class VixRecognitionService : RecognitionService() {
    override fun onStartListening(
        recognizerIntent: Intent?,
        listener: Callback?
    ) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) {}

    override fun onStopListening(listener: Callback?) {}
}
